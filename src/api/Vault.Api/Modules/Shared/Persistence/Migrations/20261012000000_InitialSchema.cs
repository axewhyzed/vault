using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Migrations;
using Vault.Api.Modules.Shared.Persistence;

#nullable disable

namespace Vault.Api.Modules.Shared.Persistence.Migrations;

[DbContext(typeof(VaultDbContext))]
[Migration("20261012000000_InitialSchema")]
public sealed class InitialSchema : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        // Slice 1 establishes the migration history on an empty database.
        // Domain tables are introduced with the slices that own their schemas.
    }

    protected override void Down(MigrationBuilder migrationBuilder)
    {
    }

    protected override void BuildTargetModel(ModelBuilder modelBuilder)
    {
        modelBuilder.HasAnnotation("ProductVersion", "10.0.0");
    }
}
