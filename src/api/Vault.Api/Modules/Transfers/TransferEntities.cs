namespace Vault.Api.Modules.Transfers;

public sealed class RelayItem
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid OwnerUserId { get; set; }
    public Guid CreatedByDeviceId { get; set; }
    public string Type { get; set; } = "text";
    public string State { get; set; } = "available";
    public string DisplayName { get; set; } = "";
    public string? TextContent { get; set; }
    public string ContentType { get; set; } = "text/plain";
    public long SizeBytes { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime AvailableAt { get; set; }
    public DateTime ExpiresAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public int Version { get; set; } = 1;
}

public sealed class ItemIdempotencyRecord
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid UserId { get; set; }
    public string Route { get; set; } = "";
    public Guid Key { get; set; }
    public string RequestFingerprint { get; set; } = "";
    public Guid ItemId { get; set; }
    public string ResponseJson { get; set; } = "";
    public int StatusCode { get; set; } = 201;
    public DateTime CreatedAt { get; set; }
}

public sealed class UploadSession
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid OwnerUserId { get; set; }
    public Guid CreatedByDeviceId { get; set; }
    public Guid IntentKey { get; set; }
    public string RequestFingerprint { get; set; } = "";
    public string DisplayName { get; set; } = "";
    public string ContentType { get; set; } = "application/octet-stream";
    public long ExpectedSizeBytes { get; set; }
    public string? ExpectedSha256 { get; set; }
    public int RetentionDays { get; set; } = 7;
    public string State { get; set; } = "initiated";
    public string StagingKey { get; set; } = "";
    public string StorageKey { get; set; } = "";
    public long? SizeBytes { get; set; }
    public string? Sha256 { get; set; }
    public Guid? ItemId { get; set; }
    public string? CompleteResponseJson { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime ExpiresAt { get; set; }
    public DateTime UpdatedAt { get; set; }
}
