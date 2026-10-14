using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;
using Vault.Api.Modules.Shared.Persistence;

#nullable disable

namespace Vault.Api.Modules.Shared.Persistence.Migrations;

[DbContext(typeof(VaultDbContext))]
[Migration("20261015000000_FileUploadsSlice5")]
public sealed class FileUploadsSlice5 : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        migrationBuilder.CreateTable(
            name: "upload_sessions",
            columns: table => new
            {
                Id = table.Column<Guid>(type: "uuid", nullable: false),
                OwnerUserId = table.Column<Guid>(type: "uuid", nullable: false),
                CreatedByDeviceId = table.Column<Guid>(type: "uuid", nullable: false),
                IntentKey = table.Column<Guid>(type: "uuid", nullable: false),
                RequestFingerprint = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
                DisplayName = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                ContentType = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: false),
                ExpectedSizeBytes = table.Column<long>(type: "bigint", nullable: false),
                ExpectedSha256 = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: true),
                RetentionDays = table.Column<int>(type: "integer", nullable: false),
                State = table.Column<string>(type: "character varying(24)", maxLength: 24, nullable: false),
                StagingKey = table.Column<string>(type: "character varying(128)", maxLength: 128, nullable: false),
                StorageKey = table.Column<string>(type: "character varying(128)", maxLength: 128, nullable: false),
                SizeBytes = table.Column<long>(type: "bigint", nullable: true),
                Sha256 = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: true),
                ItemId = table.Column<Guid>(type: "uuid", nullable: true),
                CompleteResponseJson = table.Column<string>(type: "text", nullable: true),
                CreatedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
                ExpiresAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
                UpdatedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false)
            }, constraints: table =>
            {
                table.PrimaryKey("PK_upload_sessions", x => x.Id);
                table.ForeignKey("FK_upload_sessions_devices_CreatedByDeviceId", x => x.CreatedByDeviceId, "devices", "Id", onDelete: ReferentialAction.Restrict);
                table.ForeignKey("FK_upload_sessions_users_OwnerUserId", x => x.OwnerUserId, "users", "Id", onDelete: ReferentialAction.Cascade);
            });
        migrationBuilder.CreateIndex(name: "IX_upload_sessions_CreatedByDeviceId", table: "upload_sessions", column: "CreatedByDeviceId");
        migrationBuilder.CreateIndex(name: "IX_upload_sessions_ItemId", table: "upload_sessions", column: "ItemId");
        migrationBuilder.CreateIndex(name: "IX_upload_sessions_OwnerUserId_IntentKey", table: "upload_sessions", columns: new[] { "OwnerUserId", "IntentKey" }, unique: true);
        migrationBuilder.CreateIndex(name: "IX_upload_sessions_State_ExpiresAt", table: "upload_sessions", columns: new[] { "State", "ExpiresAt" });
    }

    protected override void Down(MigrationBuilder migrationBuilder) => migrationBuilder.DropTable(name: "upload_sessions");
}
