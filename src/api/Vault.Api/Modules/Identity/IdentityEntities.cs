namespace Vault.Api.Modules.Identity;

public sealed class VaultUser
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Email { get; set; } = "";
    public string NormalizedEmail { get; set; } = "";
    public string? Username { get; set; }
    public string? NormalizedUsername { get; set; }
    public string PasswordHash { get; set; } = "";
    public string Status { get; set; } = "active";
    public bool IsBootstrap { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
}

public sealed class VaultDevice
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid UserId { get; set; }
    public string Name { get; set; } = "Web browser";
    public string Platform { get; set; } = "web";
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime? RevokedAt { get; set; }
}

public sealed class VaultAuditEvent
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string EventType { get; set; } = "";
    public Guid? ActorUserId { get; set; }
    public Guid? ActorDeviceId { get; set; }
    public DateTime OccurredAt { get; set; } = DateTime.UtcNow;
    public string CorrelationId { get; set; } = "";
    public Guid? ItemId { get; set; }
}

public sealed class AndroidSession
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid UserId { get; set; }
    public Guid DeviceId { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime RefreshExpiresAt { get; set; }
    public DateTime? RevokedAt { get; set; }
}

public sealed class DeviceCredential
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid SessionId { get; set; }
    public string TokenType { get; set; } = "access";
    public string TokenHash { get; set; } = "";
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime ExpiresAt { get; set; }
    public DateTime? RevokedAt { get; set; }
}

public sealed class DevicePairingSession
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid UserId { get; set; }
    public string SecretHash { get; set; } = "";
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime ExpiresAt { get; set; }
    public DateTime? ConsumedAt { get; set; }
    public DateTime? RevokedAt { get; set; }
    public Guid? PairingIdempotencyKey { get; set; }
    public string? PairingRequestFingerprint { get; set; }
}
