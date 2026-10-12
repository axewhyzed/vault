using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Vault.Api.Modules.Shared.Persistence;

namespace Vault.Api.Modules.Identity;

public sealed class BootstrapAccountSeeder(VaultDbContext database, IPasswordHasher<VaultUser> hasher, IConfiguration configuration)
{
    public async Task SeedAsync(CancellationToken cancellationToken)
    {
        var email = configuration["Bootstrap:Email"]?.Trim();
        var password = configuration["Bootstrap:Password"];
        if (string.IsNullOrWhiteSpace(email) || string.IsNullOrEmpty(password))
            throw new InvalidOperationException("Bootstrap:Email and Bootstrap:Password must be configured when applying migrations.");
        if (password.Length < 14)
            throw new InvalidOperationException("Bootstrap:Password must be at least 14 characters.");

        var normalizedEmail = email.ToUpperInvariant();
        var bootstrap = await database.Users.SingleOrDefaultAsync(user => user.IsBootstrap, cancellationToken);
        if (bootstrap is not null)
        {
            if (!string.Equals(bootstrap.NormalizedEmail, normalizedEmail, StringComparison.Ordinal))
                throw new InvalidOperationException("The configured bootstrap email differs from the existing bootstrap account. Bootstrap email cannot be changed by reseeding.");
            return;
        }

        if (await database.Users.AnyAsync(user => user.NormalizedEmail == normalizedEmail, cancellationToken))
            throw new InvalidOperationException("The configured bootstrap email is already assigned to a non-bootstrap account.");

        var newUser = new VaultUser { Email = email, NormalizedEmail = normalizedEmail, IsBootstrap = true };
        newUser.PasswordHash = hasher.HashPassword(newUser, password);
        database.Users.Add(newUser);
        await database.SaveChangesAsync(cancellationToken);
    }
}
