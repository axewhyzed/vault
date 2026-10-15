using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;
using Vault.Api.Modules.Shared.Persistence;

#nullable disable

namespace Vault.Api.Modules.Shared.Persistence.Migrations;

[DbContext(typeof(VaultDbContext))]
[Migration("20261015180000_DevicePairingAndBearerSessionsSlice6")]
public sealed class DevicePairingAndBearerSessionsSlice6 : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.CreateTable("android_sessions", table => new
        {
            Id = table.Column<Guid>(type: "uuid", nullable: false),
            UserId = table.Column<Guid>(type: "uuid", nullable: false),
            DeviceId = table.Column<Guid>(type: "uuid", nullable: false),
            CreatedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
            RefreshExpiresAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
            RevokedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: true)
        }, constraints: table =>
        {
            table.PrimaryKey("PK_android_sessions", x => x.Id);
            table.ForeignKey("FK_android_sessions_devices_DeviceId", x => x.DeviceId, "devices", "Id", onDelete: ReferentialAction.Cascade);
            table.ForeignKey("FK_android_sessions_users_UserId", x => x.UserId, "users", "Id", onDelete: ReferentialAction.Cascade);
        });
        migrationBuilder.CreateTable("device_credentials", table => new
        {
            Id = table.Column<Guid>(type: "uuid", nullable: false),
            SessionId = table.Column<Guid>(type: "uuid", nullable: false),
            TokenType = table.Column<string>(type: "character varying(16)", maxLength: 16, nullable: false),
            TokenHash = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
            CreatedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
            ExpiresAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
            RevokedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: true)
        }, constraints: table =>
        {
            table.PrimaryKey("PK_device_credentials", x => x.Id);
            table.ForeignKey("FK_device_credentials_android_sessions_SessionId", x => x.SessionId, "android_sessions", "Id", onDelete: ReferentialAction.Cascade);
        });
        migrationBuilder.CreateTable("device_pairing_sessions", table => new
        {
            Id = table.Column<Guid>(type: "uuid", nullable: false),
            UserId = table.Column<Guid>(type: "uuid", nullable: false),
            SecretHash = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
            CreatedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
            ExpiresAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
            ConsumedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: true),
            RevokedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: true),
            PairingIdempotencyKey = table.Column<Guid>(type: "uuid", nullable: true),
            PairingRequestFingerprint = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: true)
        }, constraints: table =>
        {
            table.PrimaryKey("PK_device_pairing_sessions", x => x.Id);
            table.ForeignKey("FK_device_pairing_sessions_users_UserId", x => x.UserId, "users", "Id", onDelete: ReferentialAction.Cascade);
        });
        migrationBuilder.CreateIndex("IX_android_sessions_DeviceId", "android_sessions", "DeviceId");
        migrationBuilder.CreateIndex("IX_android_sessions_UserId_RevokedAt", "android_sessions", new[] { "UserId", "RevokedAt" });
        migrationBuilder.CreateIndex("IX_device_credentials_SessionId_TokenType_RevokedAt", "device_credentials", new[] { "SessionId", "TokenType", "RevokedAt" });
        migrationBuilder.CreateIndex("IX_device_credentials_TokenHash", "device_credentials", "TokenHash", unique: true);
        migrationBuilder.CreateIndex("IX_device_pairing_sessions_UserId_ExpiresAt", "device_pairing_sessions", new[] { "UserId", "ExpiresAt" });
    }

    protected override void Down(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.DropTable("device_credentials");
        migrationBuilder.DropTable("device_pairing_sessions");
        migrationBuilder.DropTable("android_sessions");
    }
}
