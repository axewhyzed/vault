using System.Net;
using System.Net.Http.Json;
using System.Security.Claims;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Options;
using Vault.Api.Modules.Identity;
using Vault.Api.Modules.Shared.Persistence;
using Vault.Api.Modules.Transfers;

namespace Vault.Api.Tests;

public sealed class TextItemTests
{
    private const string Origin = "https://localhost";
    private const string PasswordA = "Slice3TestPassword-A!";
    private const string PasswordB = "Slice3TestPassword-B!";

    [Fact]
    public async Task Text_byte_limit_metadata_idempotency_and_delete_replay_are_enforced()
    {
        using var host = await TestHost.CreateAsync();
        using var client = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        var text = new string('é', 131_072); // Exactly 262,144 UTF-8 bytes, but half as many UTF-16 code units.
        var key = Guid.NewGuid();

        using var created = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new
        {
            text,
            displayName = "  Boundary snippet  ",
            retentionDays = 3
        }, key);
        Assert.Equal(HttpStatusCode.Created, created.StatusCode);
        using var createJson = await ReadJsonAsync(created);
        var itemId = createJson.RootElement.GetProperty("id").GetGuid();
        Assert.Equal($"/api/v1/items/{itemId:D}", created.Headers.Location!.ToString());
        Assert.Equal("no-store", created.Headers.CacheControl?.ToString());
        Assert.Equal("text", createJson.RootElement.GetProperty("type").GetString());
        Assert.Equal(1, createJson.RootElement.GetProperty("version").GetInt32());
        Assert.Equal(3, (createJson.RootElement.GetProperty("expiresAt").GetDateTimeOffset() - createJson.RootElement.GetProperty("availableAt").GetDateTimeOffset()).Days);

        using var detail = await client.GetAsync($"/api/v1/items/{itemId:D}");
        Assert.Equal(HttpStatusCode.OK, detail.StatusCode);
        Assert.Equal("no-store", detail.Headers.CacheControl?.ToString());
        using var detailJson = await JsonDocument.ParseAsync(await detail.Content.ReadAsStreamAsync());
        Assert.Equal(text, detailJson.RootElement.GetProperty("text").GetString());
        Assert.Equal(262_144, detailJson.RootElement.GetProperty("sizeBytes").GetInt64());
        Assert.Equal("Boundary snippet", detailJson.RootElement.GetProperty("displayName").GetString());

        using var replay = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text, displayName = "Boundary snippet", retentionDays = 3 }, key);
        Assert.Equal(HttpStatusCode.Created, replay.StatusCode);
        using var replayJson = await JsonDocument.ParseAsync(await replay.Content.ReadAsStreamAsync());
        Assert.Equal(itemId, replayJson.RootElement.GetProperty("id").GetGuid());
        Assert.False(replayJson.RootElement.TryGetProperty("text", out _));

        using var conflict = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = "a different valid snippet", displayName = "Boundary snippet", retentionDays = 3 }, key);
        Assert.Equal(HttpStatusCode.Conflict, conflict.StatusCode);

        using var tooLarge = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = text + "x" }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, tooLarge.StatusCode);

        using var delete = await host.SendMutationAsync(client, HttpMethod.Delete, $"/api/v1/items/{itemId:D}", null, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.NoContent, delete.StatusCode);
        using var repeatDelete = await host.SendMutationAsync(client, HttpMethod.Delete, $"/api/v1/items/{itemId:D}", null, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.NoContent, repeatDelete.StatusCode);
        using var gone = await client.GetAsync($"/api/v1/items/{itemId:D}");
        Assert.Equal(HttpStatusCode.NotFound, gone.StatusCode);
        using var replayAfterDelete = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text, displayName = "Boundary snippet", retentionDays = 3 }, key);
        Assert.Equal(HttpStatusCode.Created, replayAfterDelete.StatusCode);
        using var replayAfterDeleteJson = await JsonDocument.ParseAsync(await replayAfterDelete.Content.ReadAsStreamAsync());
        Assert.Equal(itemId, replayAfterDeleteJson.RootElement.GetProperty("id").GetGuid());
        Assert.False(replayAfterDeleteJson.RootElement.TryGetProperty("text", out _));
    }

    [Fact]
    public async Task Url_normalization_validation_display_name_and_retention_are_bounded()
    {
        using var host = await TestHost.CreateAsync();
        using var client = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);

        using var normalized = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/url", new { url = "  https://example.test/path?q=1  " }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.Created, normalized.StatusCode);
        using var created = await JsonDocument.ParseAsync(await normalized.Content.ReadAsStreamAsync());
        Assert.Equal(7, (created.RootElement.GetProperty("expiresAt").GetDateTimeOffset() - created.RootElement.GetProperty("availableAt").GetDateTimeOffset()).Days);
        var id = created.RootElement.GetProperty("id").GetGuid();
        using var detail = await client.GetAsync($"/api/v1/items/{id:D}");
        using var detailJson = await JsonDocument.ParseAsync(await detail.Content.ReadAsStreamAsync());
        Assert.Equal("https://example.test/path?q=1", detailJson.RootElement.GetProperty("text").GetString());
        Assert.Equal("example.test", detailJson.RootElement.GetProperty("displayName").GetString());

        foreach (var invalidUrl in new[] { "javascript:alert(1)", "ftp://example.test/file", "//example.test/path", "https://user:secret@example.test/", "https://example.test/a b" })
        {
            using var invalid = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/url", new { url = invalidUrl }, Guid.NewGuid());
            Assert.Equal(HttpStatusCode.UnprocessableEntity, invalid.StatusCode);
        }

        using var tooLong = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/url", new { url = "https://e.test/" + new string('x', 8_200) }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, tooLong.StatusCode);
        const string urlPrefix = "https://e.test/";
        var exactUrl = urlPrefix + new string('x', 8_192 - urlPrefix.Length);
        using var exactUrlBoundary = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/url", new { url = exactUrl }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.Created, exactUrlBoundary.StatusCode);
        foreach (var days in new[] { 0, 8 })
        {
            using var invalidRetention = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = "synthetic", retentionDays = days }, Guid.NewGuid());
            Assert.Equal(HttpStatusCode.UnprocessableEntity, invalidRetention.StatusCode);
        }
        using var invalidName = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = "synthetic", displayName = new string('n', 201) }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.UnprocessableEntity, invalidName.StatusCode);
    }

    [Fact]
    public async Task Items_are_owner_scoped_and_expired_content_is_never_returned()
    {
        var clock = new TestClock(DateTimeOffset.Parse("2026-10-12T10:00:00Z"));
        using var host = await TestHost.CreateAsync(clock);
        using var owner = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        using var other = await host.AuthenticatedClientAsync(TestHost.EmailB, PasswordB);
        var idempotencyKey = Guid.NewGuid();
        using var created = await host.SendMutationAsync(owner, HttpMethod.Post, "/api/v1/items/text", new { text = "synthetic-private-payload" }, idempotencyKey);
        Assert.Equal(HttpStatusCode.Created, created.StatusCode);
        using var createdJson = await JsonDocument.ParseAsync(await created.Content.ReadAsStreamAsync());
        var id = createdJson.RootElement.GetProperty("id").GetGuid();

        using var ownerList = await owner.GetAsync("/api/v1/items");
        using var otherList = await other.GetAsync("/api/v1/items");
        Assert.Contains(id, await ListItemIdsAsync(ownerList));
        Assert.DoesNotContain(id, await ListItemIdsAsync(otherList));
        using var foreignDetail = await other.GetAsync($"/api/v1/items/{id:D}");
        Assert.Equal(HttpStatusCode.NotFound, foreignDetail.StatusCode);
        using var foreignDelete = await host.SendMutationAsync(other, HttpMethod.Delete, $"/api/v1/items/{id:D}", null, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.NoContent, foreignDelete.StatusCode);
        using var stillOwned = await owner.GetAsync($"/api/v1/items/{id:D}");
        Assert.Equal(HttpStatusCode.OK, stillOwned.StatusCode);

        await host.SetExpiryAsync(id, clock.GetUtcNow().UtcDateTime);
        using var atExpiry = await owner.GetAsync($"/api/v1/items/{id:D}");
        Assert.Equal(HttpStatusCode.Gone, atExpiry.StatusCode);
        Assert.DoesNotContain("synthetic-private-payload", await atExpiry.Content.ReadAsStringAsync(), StringComparison.Ordinal);
        using var listAfterExpiry = await owner.GetAsync("/api/v1/items");
        Assert.DoesNotContain(id, await ListItemIdsAsync(listAfterExpiry));
        using var replayAfterExpiry = await host.SendMutationAsync(owner, HttpMethod.Post, "/api/v1/items/text", new { text = "synthetic-private-payload" }, idempotencyKey);
        Assert.Equal(HttpStatusCode.Created, replayAfterExpiry.StatusCode);
        using var replayJson = await JsonDocument.ParseAsync(await replayAfterExpiry.Content.ReadAsStreamAsync());
        Assert.Equal(id, replayJson.RootElement.GetProperty("id").GetGuid());
        Assert.False(replayJson.RootElement.TryGetProperty("text", out _));

        var audit = await host.GetAuditDataAsync();
        Assert.Contains("item_created", audit);
        Assert.Contains("item_read", audit);
        Assert.DoesNotContain("synthetic-private-payload", audit, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Cursor_errors_limits_and_first_page_metadata_are_handled()
    {
        var clock = new TestClock(DateTimeOffset.Parse("2026-10-12T10:00:00Z"));
        using var host = await TestHost.CreateAsync(clock);
        using var client = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        foreach (var invalid in new[] { "!", "e30", Convert.ToBase64String(Encoding.UTF8.GetBytes("{}")) })
        {
            using var response = await client.GetAsync("/api/v1/items?cursor=" + Uri.EscapeDataString(invalid));
            Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        }
        using var invalidLimit = await client.GetAsync("/api/v1/items?limit=101");
        Assert.Equal(HttpStatusCode.UnprocessableEntity, invalidLimit.StatusCode);

        for (var i = 0; i < 4; i++)
        {
            clock.Set(clock.GetUtcNow().AddSeconds(1));
            using var created = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = $"cursor-synthetic-{i}" }, Guid.NewGuid());
            Assert.Equal(HttpStatusCode.Created, created.StatusCode);
        }
        using var first = await client.GetAsync("/api/v1/items?limit=2");
        Assert.Equal(HttpStatusCode.OK, first.StatusCode);
        using var firstJson = await JsonDocument.ParseAsync(await first.Content.ReadAsStreamAsync());
        var firstItems = firstJson.RootElement.GetProperty("items").EnumerateArray().Select(x => x.GetProperty("id").GetGuid()).ToArray();
        var cursor = firstJson.RootElement.GetProperty("nextCursor").GetString();
        Assert.False(string.IsNullOrEmpty(cursor));
        Assert.Equal(4, firstJson.RootElement.GetProperty("quota").GetProperty("activeItems").GetInt64());
        Assert.Equal(2, firstItems.Length);
    }

    [Fact]
    public async Task Quota_checks_are_owner_scoped_and_expired_rows_release_logical_usage()
    {
        using var host = await TestHost.CreateAsync();
        using var client = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        using var created = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = "x" }, Guid.NewGuid());
        using var json = await JsonDocument.ParseAsync(await created.Content.ReadAsStreamAsync());
        var id = json.RootElement.GetProperty("id").GetGuid();
        await host.SetSizeAsync(id, 1_999_999_998);
        using var exactBoundary = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = "yz" }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.Created, exactBoundary.StatusCode);
        using var overBoundary = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = "z" }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.Conflict, overBoundary.StatusCode);

        await host.ExpireAllActiveAsync(TestHost.EmailA);
        using var afterExpiry = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = "fresh" }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.Created, afterExpiry.StatusCode);

        await host.SeedAvailableItemsAsync(TestHost.EmailA, 9_998);
        using var atItemLimit = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = "limit-boundary" }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.Created, atItemLimit.StatusCode);
        using var overItemLimit = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = "over-limit" }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.Conflict, overItemLimit.StatusCode);
    }

    private static async Task<Guid[]> ListItemIdsAsync(HttpResponseMessage response)
    {
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        using var json = await JsonDocument.ParseAsync(await response.Content.ReadAsStreamAsync());
        return json.RootElement.GetProperty("items").EnumerateArray().Select(x => x.GetProperty("id").GetGuid()).ToArray();
    }

    private static Task<JsonDocument> ReadJsonAsync(HttpResponseMessage response) => JsonDocument.ParseAsync(response.Content.ReadAsStream());

    private sealed class TestClock(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset _now = now;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Set(DateTimeOffset value) => _now = value;
    }

    private sealed class TestHost : IDisposable
    {
        public const string EmailA = "slice3-owner-a@example.test";
        public const string EmailB = "slice3-owner-b@example.test";
        private readonly SqliteConnection _connection;
        private readonly WebApplicationFactory<Program> _factory;
        private readonly TestClock _clock;

        private TestHost(SqliteConnection connection, WebApplicationFactory<Program> factory, TestClock clock)
        {
            _connection = connection;
            _factory = factory;
            _clock = clock;
        }

        public static async Task<TestHost> CreateAsync(TestClock? clock = null)
        {
            clock ??= new TestClock(DateTimeOffset.UtcNow);
            var connection = new SqliteConnection("Data Source=:memory:");
            await connection.OpenAsync();
            var factory = new WebApplicationFactory<Program>().WithWebHostBuilder(builder =>
            {
                builder.ConfigureAppConfiguration((_, configuration) => configuration.AddInMemoryCollection(new Dictionary<string, string?>
                {
                    ["ConnectionStrings:Vault"] = "Data Source=:memory:",
                    ["Database:ApplyMigrations"] = "false",
                    ["TrustedProxyIp"] = "127.0.0.1",
                    ["Security:AllowedOrigins:0"] = Origin
                }));
                builder.ConfigureServices(services =>
                {
                    services.RemoveAll<VaultDbContext>();
                    services.RemoveAll<DbContextOptions<VaultDbContext>>();
                    services.RemoveAll<IDbContextOptionsConfiguration<VaultDbContext>>();
                    services.RemoveAll<TimeProvider>();
                    services.AddSingleton<TimeProvider>(clock);
                    services.AddDbContext<VaultDbContext>(options => options.UseSqlite(connection));
                    services.Configure<PasswordHasherOptions>(options => options.IterationCount = 10);
                });
            });
            using var scope = factory.Services.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            await db.Database.EnsureCreatedAsync();
            var hasher = new PasswordHasher<VaultUser>(Options.Create(new PasswordHasherOptions { IterationCount = 10 }));
            var first = new VaultUser { Email = EmailA, NormalizedEmail = EmailA.ToUpperInvariant(), IsBootstrap = true };
            var second = new VaultUser { Email = EmailB, NormalizedEmail = EmailB.ToUpperInvariant() };
            first.PasswordHash = hasher.HashPassword(first, PasswordA);
            second.PasswordHash = hasher.HashPassword(second, PasswordB);
            db.Users.AddRange(first, second);
            await db.SaveChangesAsync();
            return new TestHost(connection, factory, clock);
        }

        public async Task<HttpClient> AuthenticatedClientAsync(string email, string password)
        {
            var client = _factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri(Origin), HandleCookies = true, AllowAutoRedirect = false });
            using var csrfResponse = await client.GetAsync("/api/v1/auth/csrf");
            Assert.Equal(HttpStatusCode.OK, csrfResponse.StatusCode);
            using var csrfJson = await JsonDocument.ParseAsync(await csrfResponse.Content.ReadAsStreamAsync());
            var token = csrfJson.RootElement.GetProperty("token").GetString()!;
            using var login = new HttpRequestMessage(HttpMethod.Post, "/api/v1/auth/login") { Content = JsonContent.Create(new { email, password }) };
            login.Headers.Add("Origin", Origin);
            login.Headers.Add("X-CSRF-TOKEN", token);
            using var response = await client.SendAsync(login);
            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
            return client;
        }

        public async Task<HttpResponseMessage> SendMutationAsync(HttpClient client, HttpMethod method, string path, object? body, Guid key)
        {
            using var csrfResponse = await client.GetAsync("/api/v1/auth/csrf");
            Assert.Equal(HttpStatusCode.OK, csrfResponse.StatusCode);
            using var csrfJson = await JsonDocument.ParseAsync(await csrfResponse.Content.ReadAsStreamAsync());
            var token = csrfJson.RootElement.GetProperty("token").GetString()!;
            var request = new HttpRequestMessage(method, path);
            if (body is not null) request.Content = JsonContent.Create(body);
            request.Headers.Add("Origin", Origin);
            request.Headers.Add("X-CSRF-TOKEN", token);
            if (method == HttpMethod.Post && path is "/api/v1/items/text" or "/api/v1/items/url")
                request.Headers.Add("Idempotency-Key", key.ToString("D"));
            var response = await client.SendAsync(request);
            request.Dispose();
            return response;
        }

        public async Task SetExpiryAsync(Guid id, DateTime value)
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var row = await db.Items.SingleAsync(x => x.Id == id);
            row.ExpiresAt = value;
            await db.SaveChangesAsync();
        }

        public async Task SetSizeAsync(Guid id, long size)
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var row = await db.Items.SingleAsync(x => x.Id == id);
            row.SizeBytes = size;
            await db.SaveChangesAsync();
        }

        public async Task ExpireAllActiveAsync(string email)
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var userId = await db.Users.Where(x => x.Email == email).Select(x => x.Id).SingleAsync();
            var now = _clock.GetUtcNow().UtcDateTime;
            await db.Items.Where(x => x.OwnerUserId == userId && x.State == "available").ExecuteUpdateAsync(s => s.SetProperty(x => x.ExpiresAt, now));
        }

        public async Task SeedAvailableItemsAsync(string email, int count)
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var userId = await db.Users.Where(x => x.Email == email).Select(x => x.Id).SingleAsync();
            var deviceId = await db.Devices.Where(x => x.UserId == userId).Select(x => x.Id).FirstAsync();
            var now = _clock.GetUtcNow().UtcDateTime;
            var rows = Enumerable.Range(0, count).Select(i => new RelayItem
            {
                OwnerUserId = userId, CreatedByDeviceId = deviceId, Type = "text", State = "available", DisplayName = "Synthetic seed",
                TextContent = "x", ContentType = "text/plain", SizeBytes = 1, CreatedAt = now.AddTicks(i), AvailableAt = now,
                ExpiresAt = now.AddDays(7), UpdatedAt = now
            });
            db.Items.AddRange(rows);
            await db.SaveChangesAsync();
        }

        public async Task<string> GetAuditDataAsync()
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var rows = await db.AuditEvents.AsNoTracking().Select(x => new { x.EventType, x.ActorUserId, x.ActorDeviceId, x.ItemId, x.CorrelationId, x.OccurredAt }).ToListAsync();
            return JsonSerializer.Serialize(rows);
        }

        public void Dispose()
        {
            _factory.Dispose();
            _connection.Dispose();
        }
    }
}
