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

public readonly record struct RetentionPolicy(int StoredValue, int FingerprintHours, TimeSpan Duration)
{
    public static bool TryParse(int? retentionDays, int? retentionHours, out RetentionPolicy policy, out string? error)
    {
        policy = default;
        error = null;
        if (retentionHours is not null)
        {
            if (retentionHours < 1 || retentionHours > 168)
            {
                error = "Retention must be between 1 and 168 hours.";
                return false;
            }
            policy = new RetentionPolicy(-retentionHours.Value, retentionHours.Value, TimeSpan.FromHours(retentionHours.Value));
            return true;
        }
        if (retentionDays is < 1 or > 7)
        {
            error = "Retention must be between 1 and 7 days.";
            return false;
        }
        var days = retentionDays ?? 7;
        policy = new RetentionPolicy(days, days * 24, TimeSpan.FromDays(days));
        return true;
    }
}
