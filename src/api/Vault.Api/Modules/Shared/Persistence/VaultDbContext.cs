using Microsoft.EntityFrameworkCore;
using Vault.Api.Modules.Identity;
using Vault.Api.Modules.Transfers;

namespace Vault.Api.Modules.Shared.Persistence;

public sealed class VaultDbContext(DbContextOptions<VaultDbContext> options) : DbContext(options)
{
    public DbSet<VaultUser> Users => Set<VaultUser>();
    public DbSet<VaultDevice> Devices => Set<VaultDevice>();
    public DbSet<VaultAuditEvent> AuditEvents => Set<VaultAuditEvent>();
    public DbSet<RelayItem> Items => Set<RelayItem>();
    public DbSet<ItemIdempotencyRecord> ItemIdempotencyRecords => Set<ItemIdempotencyRecord>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<VaultUser>(entity =>
        {
            entity.ToTable("users");
            entity.HasKey(x => x.Id);
            entity.Property(x => x.Email).HasMaxLength(320).IsRequired();
            entity.Property(x => x.NormalizedEmail).HasMaxLength(320).IsRequired();
            entity.Property(x => x.PasswordHash).IsRequired();
            entity.Property(x => x.Status).HasMaxLength(32).IsRequired();
            entity.Property(x => x.IsBootstrap).HasColumnName("is_bootstrap");
            entity.HasIndex(x => x.NormalizedEmail).IsUnique();
            entity.HasIndex(x => x.IsBootstrap).IsUnique().HasFilter("is_bootstrap = TRUE");
        });

        modelBuilder.Entity<VaultDevice>(entity =>
        {
            entity.ToTable("devices");
            entity.HasKey(x => x.Id);
            entity.Property(x => x.Name).HasMaxLength(100).IsRequired();
            entity.Property(x => x.Platform).HasMaxLength(32).IsRequired();
            entity.HasOne<VaultUser>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
            entity.HasIndex(x => new { x.UserId, x.RevokedAt });
        });

        modelBuilder.Entity<VaultAuditEvent>(entity =>
        {
            entity.ToTable("audit_events");
            entity.HasKey(x => x.Id);
            entity.Property(x => x.EventType).HasMaxLength(40).IsRequired();
            entity.Property(x => x.CorrelationId).HasMaxLength(128).IsRequired();
            entity.HasIndex(x => new { x.OccurredAt, x.Id });
            entity.HasIndex(x => new { x.ActorUserId, x.OccurredAt, x.Id });
        });

        modelBuilder.Entity<RelayItem>(entity =>
        {
            entity.ToTable("relay_items");
            entity.HasKey(x => x.Id);
            entity.Property(x => x.Type).HasMaxLength(16).IsRequired();
            entity.Property(x => x.State).HasMaxLength(16).IsRequired();
            entity.Property(x => x.DisplayName).HasMaxLength(200).IsRequired();
            entity.Property(x => x.TextContent).HasColumnType("text");
            entity.Property(x => x.ContentType).HasMaxLength(100).IsRequired();
            entity.Property(x => x.SizeBytes).IsRequired();
            entity.Property(x => x.Version).IsConcurrencyToken();
            entity.HasOne<VaultUser>().WithMany().HasForeignKey(x => x.OwnerUserId).OnDelete(DeleteBehavior.Cascade);
            entity.HasOne<VaultDevice>().WithMany().HasForeignKey(x => x.CreatedByDeviceId).OnDelete(DeleteBehavior.Restrict);
            entity.HasIndex(x => new { x.OwnerUserId, x.State, x.CreatedAt, x.Id });
            entity.HasIndex(x => x.ExpiresAt);
        });

        modelBuilder.Entity<ItemIdempotencyRecord>(entity =>
        {
            entity.ToTable("item_idempotency_records");
            entity.HasKey(x => x.Id);
            entity.Property(x => x.Route).HasMaxLength(64).IsRequired();
            entity.Property(x => x.RequestFingerprint).HasMaxLength(64).IsRequired();
            entity.Property(x => x.ResponseJson).HasColumnType("text").IsRequired();
            entity.HasIndex(x => new { x.UserId, x.Route, x.Key }).IsUnique();
            entity.HasOne<VaultUser>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
            entity.HasOne<RelayItem>().WithMany().HasForeignKey(x => x.ItemId).OnDelete(DeleteBehavior.Restrict);
        });
    }
}
