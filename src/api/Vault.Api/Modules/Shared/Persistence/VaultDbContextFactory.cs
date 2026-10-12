using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace Vault.Api.Modules.Shared.Persistence;

public sealed class VaultDbContextFactory : IDesignTimeDbContextFactory<VaultDbContext>
{
    public VaultDbContext CreateDbContext(string[] args)
    {
        var connectionString = Environment.GetEnvironmentVariable("ConnectionStrings__Vault")
            ?? "Host=localhost;Port=5432;Database=vault;Username=vault;Password=development-only";
        var options = new DbContextOptionsBuilder<VaultDbContext>()
            .UseNpgsql(connectionString)
            .Options;

        return new VaultDbContext(options);
    }
}
