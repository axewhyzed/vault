using System.Diagnostics;
using System.Text.RegularExpressions;
using System.Net;
using Microsoft.AspNetCore.Antiforgery;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.EntityFrameworkCore;
using Vault.Api.Modules.Identity;
using Vault.Api.Modules.Monitoring;
using Vault.Api.Modules.Transfers;
using Vault.Api.Modules.Shared.Persistence;
using Vault.Api;

const string updateBootstrapCredentialsArgument = "--update-bootstrap-credentials";
var updateBootstrapCredentials = args.Contains(updateBootstrapCredentialsArgument, StringComparer.Ordinal);
var builder = WebApplication.CreateBuilder(updateBootstrapCredentials ? Array.Empty<string>() : args);

builder.Logging.ClearProviders();
builder.Logging.AddJsonConsole(options =>
{
    options.IncludeScopes = true;
    options.TimestampFormat = "yyyy-MM-ddTHH:mm:ss.fffZ";
    options.UseUtcTimestamp = true;
});

var connectionString = builder.Configuration.GetConnectionString("Vault")
    ?? "Host=localhost;Port=5432;Database=vault;Username=vault;Password=development-only";

builder.Services.AddDbContext<VaultDbContext>(options => options.UseNpgsql(connectionString));
builder.Services.AddSingleton<TimeProvider>(TimeProvider.System);
builder.Services.AddHealthChecks();
builder.Services.AddOpenApi("v1", options => options.AddDocumentTransformer(OpenApiContract.TransformAsync));
var dataProtection = builder.Services.AddDataProtection()
    .SetApplicationName(builder.Configuration["DataProtection:ApplicationName"] ?? "Vault");
var dataProtectionPath = builder.Configuration["DataProtection:KeysPath"];
if (!string.IsNullOrWhiteSpace(dataProtectionPath))
{
    var keysDir = new DirectoryInfo(dataProtectionPath);
    var canWrite = false;
    try
    {
        if (!keysDir.Exists)
            keysDir.Create();
        var testFile = Path.Combine(keysDir.FullName, $".write-probe-{Guid.NewGuid():N}");
        File.WriteAllText(testFile, "probe");
        File.Delete(testFile);
        canWrite = true;
    }
    catch (Exception ex)
    {
        Console.Error.WriteLine($"Warning: DataProtection directory '{dataProtectionPath}' is not writable ({ex.Message}). Using user profile fallback.");
    }

    if (canWrite)
    {
        dataProtection.PersistKeysToFileSystem(keysDir);
    }
    else
    {
        var fallbackDir = new DirectoryInfo(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".aspnet", "DataProtection-Keys"));
        if (!fallbackDir.Exists)
            fallbackDir.Create();
        dataProtection.PersistKeysToFileSystem(fallbackDir);
    }
}
builder.Services.Configure<PasswordHasherOptions>(options => options.IterationCount = 210_000);
builder.Services.AddSingleton<IPasswordHasher<VaultUser>, PasswordHasher<VaultUser>>();
builder.Services.AddScoped<BootstrapAccountSeeder>();
builder.Services.AddSingleton<CredentialAttemptLimiter>();
builder.Services.AddAntiforgery(options =>
{
    options.HeaderName = "X-CSRF-TOKEN";
    options.Cookie.Name = "__Host-Vault.Csrf";
    options.Cookie.Path = "/";
    options.Cookie.HttpOnly = true;
    options.Cookie.SecurePolicy = CookieSecurePolicy.Always;
    options.Cookie.SameSite = SameSiteMode.Strict;
});
builder.Services.AddAuthentication(IdentityEndpoints.Scheme).AddCookie(IdentityEndpoints.Scheme, options =>
{
    options.Cookie.Name = IdentityEndpoints.CookieName;
    options.Cookie.Path = "/";
    options.Cookie.HttpOnly = true;
    options.Cookie.SecurePolicy = CookieSecurePolicy.Always;
    options.Cookie.SameSite = SameSiteMode.Strict;
    options.ExpireTimeSpan = TimeSpan.FromHours(8);
    options.SlidingExpiration = false;
    options.Events.OnRedirectToLogin = context =>
    {
        return IdentityErrors.Create(context.HttpContext, StatusCodes.Status401Unauthorized, "unauthorized", "Authentication is required.").ExecuteAsync(context.HttpContext);
    };
    options.Events.OnRedirectToAccessDenied = context =>
    {
        return IdentityErrors.Create(context.HttpContext, StatusCodes.Status403Forbidden, "forbidden", "The request is not allowed.").ExecuteAsync(context.HttpContext);
    };
    options.Events.OnValidatePrincipal = async context =>
    {
        var userId = IdentityEndpoints.GetGuid(context.Principal!, "userId");
        var deviceId = IdentityEndpoints.GetGuid(context.Principal!, "deviceId");
        var database = context.HttpContext.RequestServices.GetRequiredService<VaultDbContext>();
        var valid = userId != Guid.Empty && deviceId != Guid.Empty
            && await database.Devices.AsNoTracking().AnyAsync(device => device.Id == deviceId
                && device.UserId == userId && device.Platform == "web" && device.RevokedAt == null
                && database.Users.Any(user => user.Id == userId && user.Status == "active"));
        if (!valid) context.RejectPrincipal();
    };
});
builder.Services.AddAuthorizationBuilder().SetFallbackPolicy(new Microsoft.AspNetCore.Authorization.AuthorizationPolicyBuilder()
    .RequireAuthenticatedUser().Build());
var trustedProxy = builder.Configuration["TrustedProxyIp"];
IPAddress.TryParse(trustedProxy, out var trustedProxyAddress);
builder.Services.Configure<ForwardedHeadersOptions>(options =>
{
    options.ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto;
    options.KnownProxies.Clear();
    options.KnownIPNetworks.Clear();
    options.KnownIPNetworks.Add(new System.Net.IPNetwork(IPAddress.Parse("10.0.0.0"), 8));
    options.KnownIPNetworks.Add(new System.Net.IPNetwork(IPAddress.Parse("172.16.0.0"), 12));
    options.KnownIPNetworks.Add(new System.Net.IPNetwork(IPAddress.Parse("192.168.0.0"), 16));
    options.KnownIPNetworks.Add(new System.Net.IPNetwork(IPAddress.Loopback, 8));
    options.KnownIPNetworks.Add(new System.Net.IPNetwork(IPAddress.IPv6Loopback, 128));
    if (trustedProxyAddress is not null)
        options.KnownProxies.Add(trustedProxyAddress);
    options.ForwardLimit = null;
});

var app = builder.Build();

if (updateBootstrapCredentials)
{
    await using var scope = app.Services.CreateAsyncScope();
    await scope.ServiceProvider.GetRequiredService<BootstrapAccountSeeder>()
        .UpdateBootstrapCredentialsAsync(CancellationToken.None);
    app.Logger.LogInformation("Bootstrap credentials updated; all active account sessions were revoked.");
    return;
}

app.UseForwardedHeaders();
app.UseMiddleware<RequestCorrelationMiddleware>();
app.UseExceptionHandler(handler => handler.Run(async context =>
{
    var exception = context.Features.Get<Microsoft.AspNetCore.Diagnostics.IExceptionHandlerFeature>()?.Error;
    if (exception is not null)
    {
        var logger = context.RequestServices.GetRequiredService<ILogger<Program>>();
        logger.LogError(exception, "Unhandled exception on {RequestMethod} {RequestPath}", context.Request.Method, context.Request.Path);
    }
    var status = exception is AntiforgeryValidationException
        ? StatusCodes.Status403Forbidden : StatusCodes.Status500InternalServerError;
    await IdentityErrors.Create(context, status, status == StatusCodes.Status403Forbidden ? "csrf_validation_failed" : "internal_error",
        status == StatusCodes.Status403Forbidden ? "The CSRF token is missing or invalid." : "The request could not be completed.").ExecuteAsync(context);
}));
app.UseAuthentication();
app.UseMiddleware<BrowserRequestSecurityMiddleware>();
app.UseAuthorization();
app.MapOpenApi("/api/openapi/{documentName}.json").AllowAnonymous();
app.MapMonitoringEndpoints();
app.MapIdentityEndpoints();
app.MapTransferEndpoints();

var applyMigrations = app.Configuration.GetValue<bool>("Database:ApplyMigrations");
var migrationOnly = app.Configuration.GetValue<bool>("Database:MigrationOnly");
if (migrationOnly && !applyMigrations)
    throw new InvalidOperationException("Database:MigrationOnly requires Database:ApplyMigrations to be enabled.");

if (applyMigrations)
{
    await using var scope = app.Services.CreateAsyncScope();
    var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
    await db.Database.MigrateAsync();
    await scope.ServiceProvider.GetRequiredService<BootstrapAccountSeeder>().SeedAsync(CancellationToken.None);
}

if (migrationOnly)
    return;

app.Run();

public partial class Program { }

internal sealed class RequestCorrelationMiddleware(RequestDelegate next, ILogger<RequestCorrelationMiddleware> logger)
{
    private const string HeaderName = "X-Correlation-ID";
    private static readonly Regex ValidCorrelationId = new("^[A-Za-z0-9._-]{1,128}$", RegexOptions.Compiled | RegexOptions.CultureInvariant);

    public async Task InvokeAsync(HttpContext context)
    {
        var suppliedId = context.Request.Headers[HeaderName].ToString();
        var correlationId = ValidCorrelationId.IsMatch(suppliedId) ? suppliedId : Guid.NewGuid().ToString("N");
        context.TraceIdentifier = correlationId;
        context.Response.Headers[HeaderName] = correlationId;

        var stopwatch = Stopwatch.StartNew();
        using (logger.BeginScope(new Dictionary<string, object> { ["CorrelationId"] = correlationId }))
        {
            try
            {
                await next(context);
            }
            finally
            {
                stopwatch.Stop();
                logger.LogInformation(
                    "HTTP {RequestMethod} {RequestPath} responded {StatusCode} in {ElapsedMilliseconds} ms",
                    context.Request.Method,
                    context.Request.Path.Value,
                    context.Response.StatusCode,
                    stopwatch.Elapsed.TotalMilliseconds);
            }
        }
    }
}
