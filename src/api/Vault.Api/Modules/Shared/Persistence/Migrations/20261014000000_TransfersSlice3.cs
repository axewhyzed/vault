using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Vault.Api.Modules.Shared.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class TransfersSlice3 : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<Guid>(
                name: "ItemId",
                table: "audit_events",
                type: "uuid",
                nullable: true);

            migrationBuilder.CreateTable(
                name: "relay_items",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    OwnerUserId = table.Column<Guid>(type: "uuid", nullable: false),
                    CreatedByDeviceId = table.Column<Guid>(type: "uuid", nullable: false),
                    Type = table.Column<string>(type: "character varying(16)", maxLength: 16, nullable: false),
                    State = table.Column<string>(type: "character varying(16)", maxLength: 16, nullable: false),
                    DisplayName = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    TextContent = table.Column<string>(type: "text", nullable: true),
                    ContentType = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: false),
                    SizeBytes = table.Column<long>(type: "bigint", nullable: false),
                    CreatedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
                    AvailableAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
                    ExpiresAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
                    UpdatedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
                    Version = table.Column<int>(type: "integer", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_relay_items", x => x.Id);
                    table.ForeignKey(
                        name: "FK_relay_items_devices_CreatedByDeviceId",
                        column: x => x.CreatedByDeviceId,
                        principalTable: "devices",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_relay_items_users_OwnerUserId",
                        column: x => x.OwnerUserId,
                        principalTable: "users",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "item_idempotency_records",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    UserId = table.Column<Guid>(type: "uuid", nullable: false),
                    Route = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
                    Key = table.Column<Guid>(type: "uuid", nullable: false),
                    RequestFingerprint = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
                    ItemId = table.Column<Guid>(type: "uuid", nullable: false),
                    ResponseJson = table.Column<string>(type: "text", nullable: false),
                    StatusCode = table.Column<int>(type: "integer", nullable: false),
                    CreatedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_item_idempotency_records", x => x.Id);
                    table.ForeignKey(
                        name: "FK_item_idempotency_records_relay_items_ItemId",
                        column: x => x.ItemId,
                        principalTable: "relay_items",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_item_idempotency_records_users_UserId",
                        column: x => x.UserId,
                        principalTable: "users",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_audit_events_ActorUserId_OccurredAt_Id",
                table: "audit_events",
                columns: new[] { "ActorUserId", "OccurredAt", "Id" });

            migrationBuilder.CreateIndex(
                name: "IX_item_idempotency_records_ItemId",
                table: "item_idempotency_records",
                column: "ItemId");

            migrationBuilder.CreateIndex(
                name: "IX_item_idempotency_records_UserId_Route_Key",
                table: "item_idempotency_records",
                columns: new[] { "UserId", "Route", "Key" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_relay_items_CreatedByDeviceId",
                table: "relay_items",
                column: "CreatedByDeviceId");

            migrationBuilder.CreateIndex(
                name: "IX_relay_items_ExpiresAt",
                table: "relay_items",
                column: "ExpiresAt");

            migrationBuilder.CreateIndex(
                name: "IX_relay_items_OwnerUserId_State_CreatedAt_Id",
                table: "relay_items",
                columns: new[] { "OwnerUserId", "State", "CreatedAt", "Id" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "item_idempotency_records");

            migrationBuilder.DropTable(
                name: "relay_items");

            migrationBuilder.DropIndex(
                name: "IX_audit_events_ActorUserId_OccurredAt_Id",
                table: "audit_events");

            migrationBuilder.DropColumn(
                name: "ItemId",
                table: "audit_events");
        }
    }
}
