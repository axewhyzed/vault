using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Vault.Api.Modules.Shared.Persistence;

namespace Vault.Api.Modules.Identity;

public sealed class BootstrapAccountSeeder(VaultDbContext database, IPasswordHasher<VaultUser> hasher, IConfiguration configuration)
{
    private static readonly Regex ValidUsername = new("^[A-Za-z0-9._-]{3,32}$", RegexOptions.Compiled | RegexOptions.CultureInvariant);

    public async Task SeedAsync(CancellationToken cancellationToken)
    {
        var email = configuration["Bootstrap:Email"]?.Trim();
        var password = configuration["Bootstrap:Password"];
        var username = NormalizeUsername(configuration["Bootstrap:Username"], required: false);
        if (string.IsNullOrWhiteSpace(email) || string.IsNullOrEmpty(password))
            throw new InvalidOperationException("Bootstrap:Email and Bootstrap:Password must be configured when applying migrations.");
        ValidatePassword(password);

        var normalizedEmail = email.ToUpperInvariant();
        var bootstrap = await database.Users.SingleOrDefaultAsync(user => user.IsBootstrap, cancellationToken);
        if (bootstrap is not null)
        {
            if (!string.Equals(bootstrap.NormalizedEmail, normalizedEmail, StringComparison.Ordinal))
                throw new InvalidOperationException("The configured bootstrap email differs from the existing bootstrap account. Bootstrap email cannot be changed by reseeding.");

            var syncRequested = string.Equals(configuration["Bootstrap:SyncCredentials"], "true", StringComparison.OrdinalIgnoreCase);
            if (syncRequested)
            {
                var usernameTarget = username ?? bootstrap.Username;
                var normalizedTarget = usernameTarget?.ToUpperInvariant();
                var usernameMismatch = normalizedTarget is not null && !string.Equals(bootstrap.NormalizedUsername, normalizedTarget, StringComparison.Ordinal);
                var passwordMismatch = hasher.VerifyHashedPassword(bootstrap, bootstrap.PasswordHash, password) == PasswordVerificationResult.Failed;

                if (usernameMismatch || passwordMismatch)
                {
                    await UpdateBootstrapCredentialsAsync(cancellationToken);
                    return;
                }
            }

            if (username is null)
                return;

            var normalizedUsername = username.ToUpperInvariant();
            if (bootstrap.NormalizedUsername is null)
            {
                await EnsureUsernameAvailableAsync(normalizedUsername, bootstrap.Id, cancellationToken);
                bootstrap.Username = username;
                bootstrap.NormalizedUsername = normalizedUsername;
                await database.SaveChangesAsync(cancellationToken);
            }

            return;
        }

        if (await database.Users.AnyAsync(user => user.NormalizedEmail == normalizedEmail, cancellationToken))
            throw new InvalidOperationException("The configured bootstrap email is already assigned to a non-bootstrap account.");

        if (username is not null)
            await EnsureUsernameAvailableAsync(username.ToUpperInvariant(), null, cancellationToken);

        var newUser = new VaultUser
        {
            Email = email,
            NormalizedEmail = normalizedEmail,
            Username = username,
            NormalizedUsername = username?.ToUpperInvariant(),
            IsBootstrap = true
        };
        newUser.PasswordHash = hasher.HashPassword(newUser, password);
        database.Users.Add(newUser);
        await database.SaveChangesAsync(cancellationToken);
    }

    public async Task UpdateBootstrapCredentialsAsync(CancellationToken cancellationToken)
    {
        var email = configuration["Bootstrap:Email"]?.Trim();
        var configuredUsername = configuration["Bootstrap:Username"];
        var password = configuration["Bootstrap:Password"];
        if (string.IsNullOrWhiteSpace(email) || string.IsNullOrEmpty(password))
            throw new InvalidOperationException("Bootstrap:Email and Bootstrap:Password are required for the explicit credential update.");
        ValidatePassword(password);

        var normalizedEmail = email.ToUpperInvariant();
        var bootstrap = await database.Users.SingleOrDefaultAsync(user => user.IsBootstrap, cancellationToken)
            ?? throw new InvalidOperationException("No bootstrap account exists. Apply migrations and seed the account before updating credentials.");
        if (!string.Equals(bootstrap.NormalizedEmail, normalizedEmail, StringComparison.Ordinal))
            throw new InvalidOperationException("Bootstrap:Email does not match the existing bootstrap account.");

        var usernameToUse = !string.IsNullOrWhiteSpace(configuredUsername) ? configuredUsername : bootstrap.Username;
        var username = NormalizeUsername(usernameToUse, required: true)!;
        var normalizedUsername = username.ToUpperInvariant();

        await EnsureUsernameAvailableAsync(normalizedUsername, bootstrap.Id, cancellationToken);

        await using var transaction = await database.Database.BeginTransactionAsync(cancellationToken);
        bootstrap.Username = username;
        bootstrap.NormalizedUsername = normalizedUsername;
        bootstrap.PasswordHash = hasher.HashPassword(bootstrap, password);

        var activeDevices = await database.Devices
            .Where(device => device.UserId == bootstrap.Id && device.RevokedAt == null)
            .ToListAsync(cancellationToken);
        var revokedAt = DateTime.UtcNow;
        foreach (var device in activeDevices)
            device.RevokedAt = revokedAt;

        database.AuditEvents.Add(new VaultAuditEvent
        {
            EventType = "bootstrap_credentials_updated",
            ActorUserId = bootstrap.Id,
            CorrelationId = "bootstrap-admin-cli"
        });

        await database.SaveChangesAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);
    }

    private async Task EnsureUsernameAvailableAsync(string normalizedUsername, Guid? exceptUserId, CancellationToken cancellationToken)
    {
        if (await database.Users.AnyAsync(user => user.NormalizedUsername == normalizedUsername
            && (exceptUserId == null || user.Id != exceptUserId), cancellationToken))
            throw new InvalidOperationException("The configured bootstrap username is already assigned to another account.");
    }

    private static string? NormalizeUsername(string? username, bool required)
    {
        var value = username?.Trim();
        if (string.IsNullOrEmpty(value))
        {
            if (required)
                throw new InvalidOperationException("Bootstrap:Username is required for the explicit credential update.");
            return null;
        }

        if (!ValidUsername.IsMatch(value))
            throw new InvalidOperationException("Bootstrap:Username must be 3-32 characters and use only letters, numbers, dot, underscore, or hyphen.");
        return value;
    }

    private static void ValidatePassword(string password)
    {
        if (password.Length < 14)
            throw new InvalidOperationException("Bootstrap:Password must be at least 14 characters.");
    }
}
