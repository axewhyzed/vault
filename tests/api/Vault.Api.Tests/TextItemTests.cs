using System.Net;
using System.Net.Http.Json;
using System.Security.Claims;
using System.Text;
using System.Text.Json;
using System.Security.Cryptography;
using Vault.Api.Modules.Storage;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Options;
using Vault.Api.Modules.Identity;
using Vault.Api.Modules.Shared.Persistence;
using Vault.Api.Modules.Transfers;

namespace Vault.Api.Tests;

public sealed class TextItemTests
{
    private const string Origin = "https://localhost";
    private const string PasswordA = "Slice3TestPassword-A!";
    private const string PasswordB = "Slice3TestPassword-B!";

    [Fact]
    public async Task Text_byte_limit_metadata_idempotency_and_delete_replay_are_enforced()
    {
        using var host = await TestHost.CreateAsync();
        using var client = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        var text = new string('é', 131_072); // Exactly 262,144 UTF-8 bytes, but half as many UTF-16 code units.
        var key = Guid.NewGuid();

        using var created = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new
        {
            text,
            displayName = "  Boundary snippet  ",
            retentionDays = 3
        }, key);
        Assert.Equal(HttpStatusCode.Created, created.StatusCode);
        using var createJson = await ReadJsonAsync(created);
        var itemId = createJson.RootElement.GetProperty("id").GetGuid();
        Assert.Equal($"/api/v1/items/{itemId:D}", created.Headers.Location!.ToString());
        Assert.Equal("no-store", created.Headers.CacheControl?.ToString());
        Assert.Equal("text", createJson.RootElement.GetProperty("type").GetString());
        Assert.Equal(1, createJson.RootElement.GetProperty("version").GetInt32());
        Assert.Equal(3, (createJson.RootElement.GetProperty("expiresAt").GetDateTimeOffset() - createJson.RootElement.GetProperty("availableAt").GetDateTimeOffset()).Days);

        using var detail = await client.GetAsync($"/api/v1/items/{itemId:D}");
        Assert.Equal(HttpStatusCode.OK, detail.StatusCode);
        Assert.Equal("no-store", detail.Headers.CacheControl?.ToString());
        using var detailJson = await JsonDocument.ParseAsync(await detail.Content.ReadAsStreamAsync());
        Assert.Equal(text, detailJson.RootElement.GetProperty("text").GetString());
        Assert.Equal(262_144, detailJson.RootElement.GetProperty("sizeBytes").GetInt64());
        Assert.Equal("Boundary snippet", detailJson.RootElement.GetProperty("displayName").GetString());

        using var replay = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text, displayName = "Boundary snippet", retentionDays = 3 }, key);
        Assert.Equal(HttpStatusCode.Created, replay.StatusCode);
        using var replayJson = await JsonDocument.ParseAsync(await replay.Content.ReadAsStreamAsync());
        Assert.Equal(itemId, replayJson.RootElement.GetProperty("id").GetGuid());
        Assert.False(replayJson.RootElement.TryGetProperty("text", out _));

        using var conflict = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = "a different valid snippet", displayName = "Boundary snippet", retentionDays = 3 }, key);
        Assert.Equal(HttpStatusCode.Conflict, conflict.StatusCode);

        using var tooLarge = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = text + "x" }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, tooLarge.StatusCode);

        using var delete = await host.SendMutationAsync(client, HttpMethod.Delete, $"/api/v1/items/{itemId:D}", null, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.NoContent, delete.StatusCode);
        using var repeatDelete = await host.SendMutationAsync(client, HttpMethod.Delete, $"/api/v1/items/{itemId:D}", null, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.NoContent, repeatDelete.StatusCode);
        using var gone = await client.GetAsync($"/api/v1/items/{itemId:D}");
        Assert.Equal(HttpStatusCode.NotFound, gone.StatusCode);
        using var replayAfterDelete = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text, displayName = "Boundary snippet", retentionDays = 3 }, key);
        Assert.Equal(HttpStatusCode.Created, replayAfterDelete.StatusCode);
        using var replayAfterDeleteJson = await JsonDocument.ParseAsync(await replayAfterDelete.Content.ReadAsStreamAsync());
        Assert.Equal(itemId, replayAfterDeleteJson.RootElement.GetProperty("id").GetGuid());
        Assert.False(replayAfterDeleteJson.RootElement.TryGetProperty("text", out _));
    }

    [Fact]
    public async Task Url_normalization_validation_display_name_and_retention_are_bounded()
    {
        using var host = await TestHost.CreateAsync();
        using var client = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);

        using var normalized = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/url", new { url = "  https://example.test/path?q=1  " }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.Created, normalized.StatusCode);
        using var created = await JsonDocument.ParseAsync(await normalized.Content.ReadAsStreamAsync());
        Assert.Equal(7, (created.RootElement.GetProperty("expiresAt").GetDateTimeOffset() - created.RootElement.GetProperty("availableAt").GetDateTimeOffset()).Days);
        var id = created.RootElement.GetProperty("id").GetGuid();
        using var detail = await client.GetAsync($"/api/v1/items/{id:D}");
        using var detailJson = await JsonDocument.ParseAsync(await detail.Content.ReadAsStreamAsync());
        Assert.Equal("https://example.test/path?q=1", detailJson.RootElement.GetProperty("text").GetString());
        Assert.Equal("example.test", detailJson.RootElement.GetProperty("displayName").GetString());

        foreach (var invalidUrl in new[] { "javascript:alert(1)", "ftp://example.test/file", "//example.test/path", "https://user:secret@example.test/", "https://example.test/a b" })
        {
            using var invalid = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/url", new { url = invalidUrl }, Guid.NewGuid());
            Assert.Equal(HttpStatusCode.UnprocessableEntity, invalid.StatusCode);
        }

        using var tooLong = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/url", new { url = "https://e.test/" + new string('x', 8_200) }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, tooLong.StatusCode);
        const string urlPrefix = "https://e.test/";
        var exactUrl = urlPrefix + new string('x', 8_192 - urlPrefix.Length);
        using var exactUrlBoundary = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/url", new { url = exactUrl }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.Created, exactUrlBoundary.StatusCode);
        foreach (var days in new[] { 0, 8 })
        {
            using var invalidRetention = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = "synthetic", retentionDays = days }, Guid.NewGuid());
            Assert.Equal(HttpStatusCode.UnprocessableEntity, invalidRetention.StatusCode);
        }
        using var invalidName = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = "synthetic", displayName = new string('n', 201) }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.UnprocessableEntity, invalidName.StatusCode);
    }

    [Fact]
    public async Task Items_are_owner_scoped_and_expired_content_is_never_returned()
    {
        var clock = new TestClock(DateTimeOffset.Parse("2026-10-12T10:00:00Z"));
        using var host = await TestHost.CreateAsync(clock);
        using var owner = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        using var other = await host.AuthenticatedClientAsync(TestHost.EmailB, PasswordB);
        var idempotencyKey = Guid.NewGuid();
        using var created = await host.SendMutationAsync(owner, HttpMethod.Post, "/api/v1/items/text", new { text = "synthetic-private-payload" }, idempotencyKey);
        Assert.Equal(HttpStatusCode.Created, created.StatusCode);
        using var createdJson = await JsonDocument.ParseAsync(await created.Content.ReadAsStreamAsync());
        var id = createdJson.RootElement.GetProperty("id").GetGuid();

        using var ownerList = await owner.GetAsync("/api/v1/items");
        using var otherList = await other.GetAsync("/api/v1/items");
        Assert.Contains(id, await ListItemIdsAsync(ownerList));
        Assert.DoesNotContain(id, await ListItemIdsAsync(otherList));
        using var foreignDetail = await other.GetAsync($"/api/v1/items/{id:D}");
        Assert.Equal(HttpStatusCode.NotFound, foreignDetail.StatusCode);
        using var foreignDelete = await host.SendMutationAsync(other, HttpMethod.Delete, $"/api/v1/items/{id:D}", null, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.NoContent, foreignDelete.StatusCode);
        using var stillOwned = await owner.GetAsync($"/api/v1/items/{id:D}");
        Assert.Equal(HttpStatusCode.OK, stillOwned.StatusCode);

        await host.SetExpiryAsync(id, clock.GetUtcNow().UtcDateTime);
        using var atExpiry = await owner.GetAsync($"/api/v1/items/{id:D}");
        Assert.Equal(HttpStatusCode.Gone, atExpiry.StatusCode);
        Assert.DoesNotContain("synthetic-private-payload", await atExpiry.Content.ReadAsStringAsync(), StringComparison.Ordinal);
        using var listAfterExpiry = await owner.GetAsync("/api/v1/items");
        Assert.DoesNotContain(id, await ListItemIdsAsync(listAfterExpiry));
        using var replayAfterExpiry = await host.SendMutationAsync(owner, HttpMethod.Post, "/api/v1/items/text", new { text = "synthetic-private-payload" }, idempotencyKey);
        Assert.Equal(HttpStatusCode.Created, replayAfterExpiry.StatusCode);
        using var replayJson = await JsonDocument.ParseAsync(await replayAfterExpiry.Content.ReadAsStreamAsync());
        Assert.Equal(id, replayJson.RootElement.GetProperty("id").GetGuid());
        Assert.False(replayJson.RootElement.TryGetProperty("text", out _));

        var audit = await host.GetAuditDataAsync();
        Assert.Contains("item_created", audit);
        Assert.Contains("item_read", audit);
        Assert.DoesNotContain("synthetic-private-payload", audit, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Cursor_errors_limits_and_first_page_metadata_are_handled()
    {
        var clock = new TestClock(DateTimeOffset.Parse("2026-10-12T10:00:00Z"));
        using var host = await TestHost.CreateAsync(clock);
        using var client = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        foreach (var invalid in new[] { "!", "e30", Convert.ToBase64String(Encoding.UTF8.GetBytes("{}")) })
        {
            using var response = await client.GetAsync("/api/v1/items?cursor=" + Uri.EscapeDataString(invalid));
            Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        }
        using var invalidLimit = await client.GetAsync("/api/v1/items?limit=101");
        Assert.Equal(HttpStatusCode.UnprocessableEntity, invalidLimit.StatusCode);

        for (var i = 0; i < 4; i++)
        {
            clock.Set(clock.GetUtcNow().AddSeconds(1));
            using var created = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = $"cursor-synthetic-{i}" }, Guid.NewGuid());
            Assert.Equal(HttpStatusCode.Created, created.StatusCode);
        }
        using var first = await client.GetAsync("/api/v1/items?limit=2");
        Assert.Equal(HttpStatusCode.OK, first.StatusCode);
        using var firstJson = await JsonDocument.ParseAsync(await first.Content.ReadAsStreamAsync());
        var firstItems = firstJson.RootElement.GetProperty("items").EnumerateArray().Select(x => x.GetProperty("id").GetGuid()).ToArray();
        var cursor = firstJson.RootElement.GetProperty("nextCursor").GetString();
        Assert.False(string.IsNullOrEmpty(cursor));
        Assert.Equal(4, firstJson.RootElement.GetProperty("quota").GetProperty("activeItems").GetInt64());
        Assert.Equal(2, firstItems.Length);
    }

    [Fact]
    public async Task Quota_checks_are_owner_scoped_and_expired_rows_release_logical_usage()
    {
        using var host = await TestHost.CreateAsync();
        using var client = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        using var created = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = "x" }, Guid.NewGuid());
        using var json = await JsonDocument.ParseAsync(await created.Content.ReadAsStreamAsync());
        var id = json.RootElement.GetProperty("id").GetGuid();
        await host.SetSizeAsync(id, 1_999_999_998);
        using var exactBoundary = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = "yz" }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.Created, exactBoundary.StatusCode);
        using var overBoundary = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = "z" }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.Conflict, overBoundary.StatusCode);

        await host.ExpireAllActiveAsync(TestHost.EmailA);
        using var afterExpiry = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = "fresh" }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.Created, afterExpiry.StatusCode);

        await host.SeedAvailableItemsAsync(TestHost.EmailA, 9_998);
        using var atItemLimit = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = "limit-boundary" }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.Created, atItemLimit.StatusCode);
        using var overItemLimit = await host.SendMutationAsync(client, HttpMethod.Post, "/api/v1/items/text", new { text = "over-limit" }, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.Conflict, overItemLimit.StatusCode);
    }

    [Fact]
    public async Task File_upload_is_streamed_checksummed_idempotent_and_attachment_only()
    {
        using var host = await TestHost.CreateAsync();
        using var owner = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        using var other = await host.AuthenticatedClientAsync(TestHost.EmailB, PasswordB);
        var bytes = Encoding.UTF8.GetBytes("synthetic file payload; never real user data");
        var sha256 = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        var key = Guid.NewGuid();
        using var initiated = await host.SendUploadInitiateAsync(owner, key, new { displayName = "synthetic.html", contentType = "text/html", sizeBytes = bytes.Length, sha256, retentionDays = 2 });
        Assert.Equal(HttpStatusCode.Created, initiated.StatusCode);
        using var initiateJson = await JsonDocument.ParseAsync(await initiated.Content.ReadAsStreamAsync());
        var uploadId = initiateJson.RootElement.GetProperty("uploadId").GetGuid();
        Assert.Equal($"/api/v1/uploads/{uploadId:D}/content", initiateJson.RootElement.GetProperty("uploadUrl").GetString());
        Assert.Equal(100_000_000, initiateJson.RootElement.GetProperty("maxAcceptedBytes").GetInt64());

        using var staged = await host.SendRawUploadAsync(owner, uploadId, key, bytes);
        Assert.Equal(HttpStatusCode.OK, staged.StatusCode);
        using var stagedJson = await JsonDocument.ParseAsync(await staged.Content.ReadAsStreamAsync());
        Assert.Equal("staged", stagedJson.RootElement.GetProperty("state").GetString());
        Assert.Equal(sha256, stagedJson.RootElement.GetProperty("sha256").GetString());
        using var stagedReplay = await host.SendRawUploadAsync(owner, uploadId, key, bytes);
        Assert.Equal(HttpStatusCode.OK, stagedReplay.StatusCode);

        using var completed = await host.SendUploadCompleteAsync(owner, uploadId, key);
        Assert.Equal(HttpStatusCode.Created, completed.StatusCode);
        using var completedJson = await JsonDocument.ParseAsync(await completed.Content.ReadAsStreamAsync());
        var itemId = completedJson.RootElement.GetProperty("id").GetGuid();
        Assert.Equal("file", completedJson.RootElement.GetProperty("type").GetString());
        using var replay = await host.SendUploadCompleteAsync(owner, uploadId, key);
        Assert.Equal(HttpStatusCode.Created, replay.StatusCode);
        using var replayJson = await JsonDocument.ParseAsync(await replay.Content.ReadAsStreamAsync());
        Assert.Equal(itemId, replayJson.RootElement.GetProperty("id").GetGuid());
        using var completeConflict = await host.SendUploadCompleteAsync(owner, uploadId, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.Conflict, completeConflict.StatusCode);

        using var detail = await owner.GetAsync($"/api/v1/items/{itemId:D}");
        Assert.Equal(HttpStatusCode.OK, detail.StatusCode);
        using var detailJson = await JsonDocument.ParseAsync(await detail.Content.ReadAsStreamAsync());
        Assert.True(detailJson.RootElement.GetProperty("text").ValueKind == JsonValueKind.Null);
        Assert.Equal("text/html", detailJson.RootElement.GetProperty("contentType").GetString());
        Assert.Equal(sha256, detailJson.RootElement.GetProperty("sha256").GetString());
        Assert.Equal($"/api/v1/items/{itemId:D}/download", detailJson.RootElement.GetProperty("downloadUrl").GetString());

        using var foreign = await other.GetAsync($"/api/v1/items/{itemId:D}/download");
        Assert.Equal(HttpStatusCode.NotFound, foreign.StatusCode);
        using var download = await owner.GetAsync($"/api/v1/items/{itemId:D}/download");
        Assert.Equal(HttpStatusCode.OK, download.StatusCode);
        Assert.Equal("nosniff", download.Headers.GetValues("X-Content-Type-Options").Single());
        Assert.Contains("attachment", download.Content.Headers.ContentDisposition!.DispositionType, StringComparison.OrdinalIgnoreCase);
        Assert.Equal("no-store, max-age=0", string.Join(", ", download.Headers.CacheControl!.ToString().Split(',').Select(x => x.Trim())));
        Assert.Equal(bytes, await download.Content.ReadAsByteArrayAsync());

        var emptyKey = Guid.NewGuid();
        using var emptyInitiated = await host.SendUploadInitiateAsync(owner, emptyKey, new { displayName = "empty.bin", contentType = "invalid mime", sizeBytes = 0 });
        Assert.Equal(HttpStatusCode.Created, emptyInitiated.StatusCode);
        using var emptyInitiateJson = await JsonDocument.ParseAsync(await emptyInitiated.Content.ReadAsStreamAsync());
        var emptyUploadId = emptyInitiateJson.RootElement.GetProperty("uploadId").GetGuid();
        using var emptyStaged = await host.SendRawUploadAsync(owner, emptyUploadId, emptyKey, []);
        Assert.Equal(HttpStatusCode.OK, emptyStaged.StatusCode);
        using var emptyComplete = await host.SendUploadCompleteAsync(owner, emptyUploadId, emptyKey);
        Assert.Equal(HttpStatusCode.Created, emptyComplete.StatusCode);
        using var emptyItemJson = await JsonDocument.ParseAsync(await emptyComplete.Content.ReadAsStreamAsync());
        using var emptyDetail = await owner.GetAsync($"/api/v1/items/{emptyItemJson.RootElement.GetProperty("id").GetGuid():D}");
        using var emptyDetailJson = await JsonDocument.ParseAsync(await emptyDetail.Content.ReadAsStreamAsync());
        Assert.Equal("application/octet-stream", emptyDetailJson.RootElement.GetProperty("contentType").GetString());
    }

    [Fact]
    public async Task Open_download_stream_finishes_after_delete_and_delete_first_hides_file()
    {
        using var host = await TestHost.CreateAsync();
        using var owner = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        var bytes = Encoding.UTF8.GetBytes("synthetic race payload that stays readable after unlink");

        var first = await host.CreateFileAsync(owner, bytes, "download-first.bin");
        var openFileHandle = host.GetLocalFileStore().OpenRead(first.StorageKey);
        Assert.NotNull(openFileHandle);
        using var download = await owner.GetAsync($"/api/v1/items/{first.ItemId:D}/download", HttpCompletionOption.ResponseHeadersRead);
        Assert.Equal(HttpStatusCode.OK, download.StatusCode);
        Assert.Equal("attachment", download.Content.Headers.ContentDisposition!.DispositionType);
        Assert.Equal("nosniff", download.Headers.GetValues("X-Content-Type-Options").Single());
        var responseStream = await download.Content.ReadAsStreamAsync();

        using var delete = await host.SendMutationAsync(owner, HttpMethod.Delete, $"/api/v1/items/{first.ItemId:D}", null, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.NoContent, delete.StatusCode);
        Assert.False(File.Exists(host.GetObjectPath(first.StorageKey)));
        using (openFileHandle)
        using (var handleBytes = new MemoryStream())
        {
            await openFileHandle.CopyToAsync(handleBytes);
            Assert.Equal(bytes, handleBytes.ToArray());
        }
        using (var responseBytes = new MemoryStream())
        {
            await responseStream.CopyToAsync(responseBytes);
            Assert.Equal(bytes, responseBytes.ToArray());
        }
        using var afterDelete = await owner.GetAsync($"/api/v1/items/{first.ItemId:D}/download");
        Assert.Equal(HttpStatusCode.NotFound, afterDelete.StatusCode);

        var second = await host.CreateFileAsync(owner, bytes, "delete-first.bin");
        using var deleteFirst = await host.SendMutationAsync(owner, HttpMethod.Delete, $"/api/v1/items/{second.ItemId:D}", null, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.NoContent, deleteFirst.StatusCode);
        using var neverOpened = await owner.GetAsync($"/api/v1/items/{second.ItemId:D}/download");
        Assert.Equal(HttpStatusCode.NotFound, neverOpened.StatusCode);
    }

    [Fact]
    public async Task Manual_sweep_clears_expired_text_and_retries_deleted_file_cleanup()
    {
        using var host = await TestHost.CreateAsync();
        using var owner = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        using var textCreated = await host.SendMutationAsync(owner, HttpMethod.Post, "/api/v1/items/text", new { text = "synthetic expired content" }, Guid.NewGuid());
        using var textJson = await JsonDocument.ParseAsync(await textCreated.Content.ReadAsStreamAsync());
        var textId = textJson.RootElement.GetProperty("id").GetGuid();
        await host.SetExpiryAsync(textId, DateTime.UtcNow.AddMinutes(-1));

        var key = Guid.NewGuid();
        var bytes = Encoding.UTF8.GetBytes("synthetic deleted file");
        using var initiate = await host.SendUploadInitiateAsync(owner, key, new { displayName = "deleted.bin", contentType = "application/octet-stream", sizeBytes = bytes.Length });
        using var initiateJson = await JsonDocument.ParseAsync(await initiate.Content.ReadAsStreamAsync());
        var uploadId = initiateJson.RootElement.GetProperty("uploadId").GetGuid();
        using var staged = await host.SendRawUploadAsync(owner, uploadId, key, bytes);
        Assert.Equal(HttpStatusCode.OK, staged.StatusCode);
        using var complete = await host.SendUploadCompleteAsync(owner, uploadId, key);
        using var completeJson = await JsonDocument.ParseAsync(await complete.Content.ReadAsStreamAsync());
        var fileItemId = completeJson.RootElement.GetProperty("id").GetGuid();
        var storageKey = await host.GetStorageKeyAsync(fileItemId);
        var objectPath = host.GetObjectPath(storageKey);
        Assert.True(File.Exists(objectPath));

        // Model a prior delete whose DB commit succeeded but physical removal did not.
        await host.SetItemStateAsync(fileItemId, "deleted");
        var reportOnly = await host.RunSweepAsync();
        Assert.Contains("mode=report-only", reportOnly, StringComparison.Ordinal);
        Assert.True(File.Exists(objectPath));
        var reportContent = await host.GetItemContentAsync(textId);
        Assert.Equal("available", reportContent.State);
        Assert.Equal("synthetic expired content", reportContent.Content);

        var firstSweep = await host.RunSweepAsync(applyChanges: true);
        Assert.Contains("mode=apply", firstSweep, StringComparison.Ordinal);
        Assert.True(firstSweep.Contains("expired_items=1", StringComparison.Ordinal));
        Assert.False(File.Exists(objectPath));
        var expiredText = await host.GetItemContentAsync(textId);
        Assert.Equal("expired", expiredText.State);
        Assert.Null(expiredText.Content);

        await host.RunSweepAsync(applyChanges: true);
        Assert.False(File.Exists(objectPath));

        var orphanPath = host.GetObjectPath("synthetic-unreferenced-orphan");
        Directory.CreateDirectory(Path.GetDirectoryName(orphanPath)!);
        await File.WriteAllTextAsync(orphanPath, "synthetic orphan bytes");
        await host.RunSweepAsync();
        Assert.True(File.Exists(orphanPath));
        File.SetLastWriteTimeUtc(orphanPath, DateTime.UtcNow.AddHours(-25));
        var orphanApply = await host.RunSweepAsync(applyChanges: true);
        Assert.Contains("aged_orphan_objects_24h=1", orphanApply, StringComparison.Ordinal);
        Assert.False(File.Exists(orphanPath));
    }

    [Fact]
    public async Task Upload_validation_rejects_unsafe_intents_and_checksum_mismatch()
    {
        using var host = await TestHost.CreateAsync();
        using var owner = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        using var tooLarge = await host.SendUploadInitiateAsync(owner, Guid.NewGuid(), new { displayName = "large.bin", sizeBytes = 100_000_001 });
        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, tooLarge.StatusCode);
        using var traversal = await host.SendUploadInitiateAsync(owner, Guid.NewGuid(), new { displayName = "../secret.txt", sizeBytes = 1 });
        Assert.Equal(HttpStatusCode.UnprocessableEntity, traversal.StatusCode);

        var key = Guid.NewGuid();
        using var initiated = await host.SendUploadInitiateAsync(owner, key, new { displayName = "synthetic.bin", contentType = "application/octet-stream", sizeBytes = 2, sha256 = new string('0', 64) });
        Assert.Equal(HttpStatusCode.Created, initiated.StatusCode);
        using var initiateJson = await JsonDocument.ParseAsync(await initiated.Content.ReadAsStreamAsync());
        var id = initiateJson.RootElement.GetProperty("uploadId").GetGuid();
        using var wrongKey = await host.SendRawUploadAsync(owner, id, Guid.NewGuid(), [1, 2]);
        Assert.Equal(HttpStatusCode.Conflict, wrongKey.StatusCode);
        using var wrongType = await host.SendRawUploadAsync(owner, id, key, [1, 2], "text/plain");
        Assert.Equal(HttpStatusCode.UnsupportedMediaType, wrongType.StatusCode);
        using var mismatch = await host.SendRawUploadAsync(owner, id, key, [1, 2]);
        Assert.Equal(HttpStatusCode.UnprocessableEntity, mismatch.StatusCode);
        using var complete = await host.SendUploadCompleteAsync(owner, id, key);
        Assert.Equal(HttpStatusCode.Conflict, complete.StatusCode);
    }

    [Fact]
    public async Task Interrupted_short_stream_can_retry_same_upload_intent()
    {
        using var host = await TestHost.CreateAsync();
        using var owner = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        var key = Guid.NewGuid();
        using var initiated = await host.SendUploadInitiateAsync(owner, key, new { displayName = "retry.bin", sizeBytes = 4 });
        using var initJson = await JsonDocument.ParseAsync(await initiated.Content.ReadAsStreamAsync());
        var id = initJson.RootElement.GetProperty("uploadId").GetGuid();
        using var partial = await host.SendRawUploadAsync(owner, id, key, [1, 2]);
        Assert.Equal(HttpStatusCode.UnprocessableEntity, partial.StatusCode);
        using var partialJson = await JsonDocument.ParseAsync(await partial.Content.ReadAsStreamAsync());
        Assert.Equal("upload_size_mismatch", partialJson.RootElement.GetProperty("code").GetString());

        using var retry = await host.SendRawUploadAsync(owner, id, key, [1, 2, 3, 4]);
        Assert.Equal(HttpStatusCode.OK, retry.StatusCode);
        using var complete = await host.SendUploadCompleteAsync(owner, id, key);
        Assert.Equal(HttpStatusCode.Created, complete.StatusCode);
    }

    [Fact]
    public async Task Aborted_upload_request_cleans_partial_staging_and_same_key_retries()
    {
        using var host = await TestHost.CreateAsync();
        using var owner = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        var payload = Enumerable.Range(0, 256_000).Select(i => (byte)(i % 251)).ToArray();
        var key = Guid.NewGuid();
        using var initiated = await host.SendUploadInitiateAsync(owner, key, new { displayName = "aborted.bin", sizeBytes = payload.Length });
        using var initJson = await JsonDocument.ParseAsync(await initiated.Content.ReadAsStreamAsync());
        var id = initJson.RootElement.GetProperty("uploadId").GetGuid();
        var stagingKey = await host.GetStagingKeyAsync(id);
        using var content = new BlockingUploadContent(payload[..64_000], payload.Length);
        using var request = await host.CreateRawUploadRequestAsync(owner, id, key, content);
        using var cancellation = new CancellationTokenSource();
        var sendTask = owner.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellation.Token);
        await content.PrefixWritten.Task.WaitAsync(TimeSpan.FromSeconds(5));
        await host.WaitForUploadStateAsync(id, "receiving");
        Assert.True(File.Exists(host.GetStagingPath(stagingKey)));

        cancellation.Cancel();
        content.Abort();
        HttpResponseMessage? interruptedResponse = null;
        var clientAborted = false;
        try { interruptedResponse = await sendTask.WaitAsync(TimeSpan.FromSeconds(5)); }
        catch (Exception ex) when (ex is OperationCanceledException or HttpRequestException or IOException) { clientAborted = true; }
        if (interruptedResponse is not null)
        {
            using (interruptedResponse)
            {
                Assert.Equal(HttpStatusCode.BadRequest, interruptedResponse.StatusCode);
            }
        }
        else Assert.True(clientAborted, "The upload must be aborted at the HTTP client transport.");

        await host.WaitForUploadStateAsync(id, "initiated");
        Assert.False(File.Exists(host.GetStagingPath(stagingKey)));
        Assert.Contains("upload_failed", await host.GetAuditDataAsync(), StringComparison.Ordinal);

        using var retry = await host.SendRawUploadAsync(owner, id, key, payload);
        Assert.Equal(HttpStatusCode.OK, retry.StatusCode);
        using var complete = await host.SendUploadCompleteAsync(owner, id, key);
        Assert.Equal(HttpStatusCode.Created, complete.StatusCode);
    }

    [Fact]
    public async Task Upload_quota_is_checked_at_initiation_and_again_at_completion()
    {
        using var host = await TestHost.CreateAsync();
        using var owner = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        await host.SeedAvailableItemsAsync(TestHost.EmailA, 10_000);
        using var full = await host.SendUploadInitiateAsync(owner, Guid.NewGuid(), new { displayName = "over-quota.bin", sizeBytes = 1 });
        Assert.Equal(HttpStatusCode.Conflict, full.StatusCode);
        using var fullJson = await JsonDocument.ParseAsync(await full.Content.ReadAsStreamAsync());
        Assert.Equal("quota_exceeded", fullJson.RootElement.GetProperty("code").GetString());

        await host.ExpireAllActiveAsync(TestHost.EmailA);
        var key = Guid.NewGuid();
        using var initiated = await host.SendUploadInitiateAsync(owner, key, new { displayName = "quota-race.bin", sizeBytes = 1 });
        Assert.Equal(HttpStatusCode.Created, initiated.StatusCode);
        using var initiateJson = await JsonDocument.ParseAsync(await initiated.Content.ReadAsStreamAsync());
        var id = initiateJson.RootElement.GetProperty("uploadId").GetGuid();
        using var staged = await host.SendRawUploadAsync(owner, id, key, [7]);
        Assert.Equal(HttpStatusCode.OK, staged.StatusCode);
        await host.SeedAvailableItemsAsync(TestHost.EmailA, 10_000);
        using var complete = await host.SendUploadCompleteAsync(owner, id, key);
        Assert.Equal(HttpStatusCode.Conflict, complete.StatusCode);
        using var quotaJson = await JsonDocument.ParseAsync(await complete.Content.ReadAsStreamAsync());
        Assert.Equal("quota_exceeded", quotaJson.RootElement.GetProperty("code").GetString());
        Assert.Equal(2, await host.CountAuditEventsAsync("upload_failed"));
    }

    [Fact]
    public async Task Completion_reserve_rejection_is_audited_and_same_session_can_retry()
    {
        var capacity = new TestCapacity(call => call == 3 ? (10_000, 1_999) : (10_000, 10_000));
        using var host = await TestHost.CreateAsync(capacity: capacity);
        using var owner = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        var key = Guid.NewGuid();
        using var initiated = await host.SendUploadInitiateAsync(owner, key, new { displayName = "reserve-complete.bin", sizeBytes = 1 });
        using var initJson = await JsonDocument.ParseAsync(await initiated.Content.ReadAsStreamAsync());
        var id = initJson.RootElement.GetProperty("uploadId").GetGuid();
        using var staged = await host.SendRawUploadAsync(owner, id, key, [8]);
        Assert.Equal(HttpStatusCode.OK, staged.StatusCode);
        using var rejected = await host.SendUploadCompleteAsync(owner, id, key);
        Assert.Equal((HttpStatusCode)507, rejected.StatusCode);
        Assert.Equal(1, await host.CountAuditEventsAsync("upload_failed"));

        using var retry = await host.SendUploadCompleteAsync(owner, id, key);
        Assert.Equal(HttpStatusCode.Created, retry.StatusCode);
        Assert.Equal(1, await host.CountAuditEventsAsync("upload_failed"));
    }

    [Fact]
    public async Task Missing_staged_file_completion_is_audited_without_creating_an_item()
    {
        using var host = await TestHost.CreateAsync();
        using var owner = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        var key = Guid.NewGuid();
        using var initiated = await host.SendUploadInitiateAsync(owner, key, new { displayName = "missing-stage.bin", sizeBytes = 1 });
        using var initJson = await JsonDocument.ParseAsync(await initiated.Content.ReadAsStreamAsync());
        var id = initJson.RootElement.GetProperty("uploadId").GetGuid();
        using var staged = await host.SendRawUploadAsync(owner, id, key, [9]);
        Assert.Equal(HttpStatusCode.OK, staged.StatusCode);
        var stagingKey = await host.GetStagingKeyAsync(id);
        File.Delete(host.GetStagingPath(stagingKey));

        using var rejected = await host.SendUploadCompleteAsync(owner, id, key);
        Assert.Equal(HttpStatusCode.Conflict, rejected.StatusCode);
        using var errorJson = await JsonDocument.ParseAsync(await rejected.Content.ReadAsStreamAsync());
        Assert.Equal("staged_file_missing", errorJson.RootElement.GetProperty("code").GetString());
        Assert.Equal(1, await host.CountAuditEventsAsync("upload_failed"));
        Assert.Equal(0, await host.CountItemsAsync());
    }

    [Fact]
    public async Task Upload_volume_reserve_accepts_exact_boundary_and_rejects_one_byte_under()
    {
        using var exactHost = await TestHost.CreateAsync(capacity: new TestCapacity(_ => (1_000, 201)));
        using var exactOwner = await exactHost.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        using var exact = await exactHost.SendUploadInitiateAsync(exactOwner, Guid.NewGuid(), new { displayName = "reserve-edge.bin", sizeBytes = 1 });
        Assert.Equal(HttpStatusCode.Created, exact.StatusCode);

        using var underHost = await TestHost.CreateAsync(capacity: new TestCapacity(_ => (1_000, 200)));
        using var underOwner = await underHost.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        using var under = await underHost.SendUploadInitiateAsync(underOwner, Guid.NewGuid(), new { displayName = "reserve-under.bin", sizeBytes = 1 });
        Assert.Equal((HttpStatusCode)507, under.StatusCode);
        var rejectionAudit = await underHost.GetAuditDataAsync();
        Assert.Contains("upload_failed", rejectionAudit, StringComparison.Ordinal);
        Assert.DoesNotContain("reserve-under.bin", rejectionAudit, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Streaming_rechecks_reserve_per_chunk_and_resets_for_retry()
    {
        var capacity = new TestCapacity(call => call == 3
            ? (10_000_000, 2_000_000) // exactly the reserve before the second chunk
            : (10_000_000, 10_000_000));
        using var host = await TestHost.CreateAsync(capacity: capacity);
        using var owner = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        var bytes = Enumerable.Range(0, 150_000).Select(i => (byte)(i % 239)).ToArray();
        var key = Guid.NewGuid();
        using var initiated = await host.SendUploadInitiateAsync(owner, key, new { displayName = "reserve-retry.bin", sizeBytes = bytes.Length });
        Assert.Equal(HttpStatusCode.Created, initiated.StatusCode);
        using var initJson = await JsonDocument.ParseAsync(await initiated.Content.ReadAsStreamAsync());
        var id = initJson.RootElement.GetProperty("uploadId").GetGuid();

        using var reserveFailure = await host.SendRawUploadAsync(owner, id, key, bytes);
        Assert.Equal((HttpStatusCode)507, reserveFailure.StatusCode);
        Assert.Equal("initiated", await host.GetUploadStateAsync(id));
        Assert.False(File.Exists(host.GetStagingPath(await host.GetStagingKeyAsync(id))));

        using var retry = await host.SendRawUploadAsync(owner, id, key, bytes);
        Assert.Equal(HttpStatusCode.OK, retry.StatusCode);
        using var complete = await host.SendUploadCompleteAsync(owner, id, key);
        Assert.Equal(HttpStatusCode.Created, complete.StatusCode);
    }

    [Fact]
    public async Task Missing_file_download_marks_item_unavailable_and_sweep_reports_promoted_orphan()
    {
        using var host = await TestHost.CreateAsync();
        using var owner = await host.AuthenticatedClientAsync(TestHost.EmailA, PasswordA);
        var bytes = Encoding.UTF8.GetBytes("synthetic missing file payload");
        var key = Guid.NewGuid();
        using var initiated = await host.SendUploadInitiateAsync(owner, key, new { displayName = "missing.bin", sizeBytes = bytes.Length });
        using var initJson = await JsonDocument.ParseAsync(await initiated.Content.ReadAsStreamAsync());
        var uploadId = initJson.RootElement.GetProperty("uploadId").GetGuid();
        using var staged = await host.SendRawUploadAsync(owner, uploadId, key, bytes);
        using var complete = await host.SendUploadCompleteAsync(owner, uploadId, key);
        using var completeJson = await JsonDocument.ParseAsync(await complete.Content.ReadAsStreamAsync());
        var itemId = completeJson.RootElement.GetProperty("id").GetGuid();
        var storageKey = await host.GetStorageKeyAsync(itemId);
        File.Delete(host.GetObjectPath(storageKey));
        using var missing = await owner.GetAsync($"/api/v1/items/{itemId:D}/download");
        Assert.Equal(HttpStatusCode.ServiceUnavailable, missing.StatusCode);
        Assert.Equal("unavailable", (await host.GetItemContentAsync(itemId)).State);

        var stagedKey = Guid.NewGuid();
        using var stagedInit = await host.SendUploadInitiateAsync(owner, stagedKey, new { displayName = "promoted-orphan.bin", sizeBytes = bytes.Length });
        using var stagedInitJson = await JsonDocument.ParseAsync(await stagedInit.Content.ReadAsStreamAsync());
        var stagedId = stagedInitJson.RootElement.GetProperty("uploadId").GetGuid();
        using var stagedResponse = await host.SendRawUploadAsync(owner, stagedId, stagedKey, bytes);
        Assert.Equal(HttpStatusCode.OK, stagedResponse.StatusCode);
        var promotedKey = await host.GetUploadStorageKeyAsync(stagedId);
        await host.PromoteUploadAsync(stagedId, promotedKey);
        var report = await host.RunSweepAsync();
        Assert.Contains("promoted_uncommitted=1", report, StringComparison.Ordinal);
        Assert.True(File.Exists(host.GetObjectPath(promotedKey)));
    }

    [Fact]
    public async Task Maintenance_gate_excludes_exclusive_backup_or_sweep_from_file_mutations()
    {
        using var host = await TestHost.CreateAsync();
        using var scope = host.Services.CreateScope();
        var gate = scope.ServiceProvider.GetRequiredService<StorageMaintenanceGate>();
        var shared = gate.EnterShared();
        using var started = new ManualResetEventSlim();
        var exclusive = Task.Run(() =>
        {
            started.Set();
            using var lease = gate.EnterExclusive();
            return true;
        });
        try
        {
            Assert.True(started.Wait(TimeSpan.FromSeconds(1)));
            Assert.NotSame(exclusive, await Task.WhenAny(exclusive, Task.Delay(100)));
        }
        finally { shared.Dispose(); }
        Assert.True(await exclusive.WaitAsync(TimeSpan.FromSeconds(2)));
    }

    private static async Task<Guid[]> ListItemIdsAsync(HttpResponseMessage response)
    {
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        using var json = await JsonDocument.ParseAsync(await response.Content.ReadAsStreamAsync());
        return json.RootElement.GetProperty("items").EnumerateArray().Select(x => x.GetProperty("id").GetGuid()).ToArray();
    }

    private static Task<JsonDocument> ReadJsonAsync(HttpResponseMessage response) => JsonDocument.ParseAsync(response.Content.ReadAsStream());

    private sealed class TestClock(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset _now = now;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Set(DateTimeOffset value) => _now = value;
    }

    private sealed class TestCapacity : IFileVolumeCapacity
    {
        private readonly Func<int, (long Total, long Available)> _capacityAtCall;
        private int _calls;

        public TestCapacity(Func<int, (long Total, long Available)>? capacityAtCall = null) =>
            _capacityAtCall = capacityAtCall ?? (_ => (5_000_000_000, 3_000_000_000));

        public bool TryGetCapacity(string path, out long totalBytes, out long availableBytes)
        {
            var sample = _capacityAtCall(Interlocked.Increment(ref _calls));
            totalBytes = sample.Total;
            availableBytes = sample.Available;
            return true;
        }
    }

    private sealed class BlockingUploadContent(byte[] prefix, long declaredLength) : HttpContent
    {
        private readonly TaskCompletionSource _continue = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource PrefixWritten { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public void Abort() => _continue.TrySetException(new OperationCanceledException("Synthetic client request abort."));

        protected override Task SerializeToStreamAsync(Stream stream, System.Net.TransportContext? context) =>
            SerializeCoreAsync(stream, CancellationToken.None);

        protected override Task SerializeToStreamAsync(Stream stream, System.Net.TransportContext? context, CancellationToken cancellationToken) =>
            SerializeCoreAsync(stream, cancellationToken);

        private async Task SerializeCoreAsync(Stream stream, CancellationToken cancellationToken)
        {
            await stream.WriteAsync(prefix, cancellationToken);
            PrefixWritten.TrySetResult();
            await _continue.Task.WaitAsync(cancellationToken);
        }

        protected override bool TryComputeLength(out long length)
        {
            length = declaredLength;
            return true;
        }
    }

    private sealed class TestHost : IDisposable
    {
        public const string EmailA = "slice3-owner-a@example.test";
        public const string EmailB = "slice3-owner-b@example.test";
        private readonly SqliteConnection _connection;
        private readonly WebApplicationFactory<Program> _factory;
        private readonly TestClock _clock;
        private readonly string _filesPath;

        private TestHost(SqliteConnection connection, WebApplicationFactory<Program> factory, TestClock clock, string filesPath)
        {
            _connection = connection;
            _factory = factory;
            _clock = clock;
            _filesPath = filesPath;
        }

        public static async Task<TestHost> CreateAsync(TestClock? clock = null, TestCapacity? capacity = null)
        {
            clock ??= new TestClock(DateTimeOffset.UtcNow);
            var filesPath = Path.Combine(Path.GetTempPath(), $"vault-api-tests-{Guid.NewGuid():N}");
            var connection = new SqliteConnection("Data Source=:memory:");
            await connection.OpenAsync();
            var factory = new WebApplicationFactory<Program>().WithWebHostBuilder(builder =>
            {
                builder.ConfigureAppConfiguration((_, configuration) => configuration.AddInMemoryCollection(new Dictionary<string, string?>
                {
                    ["ConnectionStrings:Vault"] = "Data Source=:memory:",
                    ["Database:ApplyMigrations"] = "false",
                    ["TrustedProxyIp"] = "127.0.0.1",
                    ["Security:AllowedOrigins:0"] = Origin,
                    ["Storage:FilesPath"] = filesPath
                }));
                builder.ConfigureServices(services =>
                {
                    services.RemoveAll<VaultDbContext>();
                    services.RemoveAll<DbContextOptions<VaultDbContext>>();
                    services.RemoveAll<IDbContextOptionsConfiguration<VaultDbContext>>();
                    services.RemoveAll<TimeProvider>();
                    services.RemoveAll<IFileVolumeCapacity>();
                    services.AddSingleton<IFileVolumeCapacity>(capacity ?? new TestCapacity());
                    services.AddSingleton<TimeProvider>(clock);
                    services.AddDbContext<VaultDbContext>(options => options.UseSqlite(connection));
                    services.Configure<PasswordHasherOptions>(options => options.IterationCount = 10);
                });
            });
            using var scope = factory.Services.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            await db.Database.EnsureCreatedAsync();
            var hasher = new PasswordHasher<VaultUser>(Options.Create(new PasswordHasherOptions { IterationCount = 10 }));
            var first = new VaultUser { Email = EmailA, NormalizedEmail = EmailA.ToUpperInvariant(), IsBootstrap = true };
            var second = new VaultUser { Email = EmailB, NormalizedEmail = EmailB.ToUpperInvariant() };
            first.PasswordHash = hasher.HashPassword(first, PasswordA);
            second.PasswordHash = hasher.HashPassword(second, PasswordB);
            db.Users.AddRange(first, second);
            await db.SaveChangesAsync();
            return new TestHost(connection, factory, clock, filesPath);
        }

        public async Task<HttpClient> AuthenticatedClientAsync(string email, string password)
        {
            var client = _factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri(Origin), HandleCookies = true, AllowAutoRedirect = false });
            using var csrfResponse = await client.GetAsync("/api/v1/auth/csrf");
            Assert.Equal(HttpStatusCode.OK, csrfResponse.StatusCode);
            using var csrfJson = await JsonDocument.ParseAsync(await csrfResponse.Content.ReadAsStreamAsync());
            var token = csrfJson.RootElement.GetProperty("token").GetString()!;
            using var login = new HttpRequestMessage(HttpMethod.Post, "/api/v1/auth/login") { Content = JsonContent.Create(new { email, password }) };
            login.Headers.Add("Origin", Origin);
            login.Headers.Add("X-CSRF-TOKEN", token);
            using var response = await client.SendAsync(login);
            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
            return client;
        }

        public IServiceProvider Services => _factory.Services;

        public async Task<HttpResponseMessage> SendMutationAsync(HttpClient client, HttpMethod method, string path, object? body, Guid key)
        {
            using var csrfResponse = await client.GetAsync("/api/v1/auth/csrf");
            Assert.Equal(HttpStatusCode.OK, csrfResponse.StatusCode);
            using var csrfJson = await JsonDocument.ParseAsync(await csrfResponse.Content.ReadAsStreamAsync());
            var token = csrfJson.RootElement.GetProperty("token").GetString()!;
            var request = new HttpRequestMessage(method, path);
            if (body is not null) request.Content = JsonContent.Create(body);
            request.Headers.Add("Origin", Origin);
            request.Headers.Add("X-CSRF-TOKEN", token);
            if (method == HttpMethod.Post && path is "/api/v1/items/text" or "/api/v1/items/url")
                request.Headers.Add("Idempotency-Key", key.ToString("D"));
            var response = await client.SendAsync(request);
            request.Dispose();
            return response;
        }

        public async Task<HttpResponseMessage> SendUploadInitiateAsync(HttpClient client, Guid key, object body)
        {
            using var csrfResponse = await client.GetAsync("/api/v1/auth/csrf");
            using var csrfJson = await JsonDocument.ParseAsync(await csrfResponse.Content.ReadAsStreamAsync());
            using var request = new HttpRequestMessage(HttpMethod.Post, "/api/v1/uploads/initiate") { Content = JsonContent.Create(body) };
            request.Headers.Add("Origin", Origin);
            request.Headers.Add("X-CSRF-TOKEN", csrfJson.RootElement.GetProperty("token").GetString());
            request.Headers.Add("Idempotency-Key", key.ToString("D"));
            return await client.SendAsync(request);
        }

        public async Task<HttpResponseMessage> SendRawUploadAsync(HttpClient client, Guid id, Guid key, byte[] body, string contentType = "application/octet-stream")
        {
            using var csrfResponse = await client.GetAsync("/api/v1/auth/csrf");
            using var csrfJson = await JsonDocument.ParseAsync(await csrfResponse.Content.ReadAsStreamAsync());
            using var request = new HttpRequestMessage(HttpMethod.Put, $"/api/v1/uploads/{id:D}/content") { Content = new ByteArrayContent(body) };
            request.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue(contentType);
            request.Headers.Add("Origin", Origin);
            request.Headers.Add("X-CSRF-TOKEN", csrfJson.RootElement.GetProperty("token").GetString());
            request.Headers.Add("Idempotency-Key", key.ToString("D"));
            return await client.SendAsync(request);
        }

        public async Task<HttpRequestMessage> CreateRawUploadRequestAsync(HttpClient client, Guid id, Guid key, HttpContent content)
        {
            using var csrfResponse = await client.GetAsync("/api/v1/auth/csrf");
            using var csrfJson = await JsonDocument.ParseAsync(await csrfResponse.Content.ReadAsStreamAsync());
            var request = new HttpRequestMessage(HttpMethod.Put, $"/api/v1/uploads/{id:D}/content") { Content = content };
            request.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/octet-stream");
            request.Headers.Add("Origin", Origin);
            request.Headers.Add("X-CSRF-TOKEN", csrfJson.RootElement.GetProperty("token").GetString());
            request.Headers.Add("Idempotency-Key", key.ToString("D"));
            return request;
        }

        public async Task<HttpResponseMessage> SendUploadCompleteAsync(HttpClient client, Guid id, Guid key)
        {
            using var csrfResponse = await client.GetAsync("/api/v1/auth/csrf");
            using var csrfJson = await JsonDocument.ParseAsync(await csrfResponse.Content.ReadAsStreamAsync());
            using var request = new HttpRequestMessage(HttpMethod.Post, $"/api/v1/uploads/{id:D}/complete") { Content = JsonContent.Create(new { }) };
            request.Headers.Add("Origin", Origin);
            request.Headers.Add("X-CSRF-TOKEN", csrfJson.RootElement.GetProperty("token").GetString());
            request.Headers.Add("Idempotency-Key", key.ToString("D"));
            return await client.SendAsync(request);
        }

        public async Task SetExpiryAsync(Guid id, DateTime value)
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var row = await db.Items.SingleAsync(x => x.Id == id);
            row.ExpiresAt = value;
            await db.SaveChangesAsync();
        }

        public async Task SetItemStateAsync(Guid id, string state)
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var row = await db.Items.SingleAsync(x => x.Id == id);
            row.State = state;
            await db.SaveChangesAsync();
        }

        public async Task<string> GetStorageKeyAsync(Guid itemId)
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            return await db.UploadSessions.Where(x => x.ItemId == itemId).Select(x => x.StorageKey).SingleAsync();
        }

        public async Task<string> GetUploadStorageKeyAsync(Guid uploadId)
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            return await db.UploadSessions.Where(x => x.Id == uploadId).Select(x => x.StorageKey).SingleAsync();
        }

        public async Task PromoteUploadAsync(Guid uploadId, string storageKey)
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var stagingKey = await db.UploadSessions.Where(x => x.Id == uploadId).Select(x => x.StagingKey).SingleAsync();
            scope.ServiceProvider.GetRequiredService<LocalFileStore>().Promote(stagingKey, storageKey);
        }

        public string GetObjectPath(string storageKey) => Path.Combine(_filesPath, "objects", storageKey);
        public string GetStagingPath(string stagingKey) => Path.Combine(_filesPath, "staging", stagingKey);
        public LocalFileStore GetLocalFileStore() => _factory.Services.GetRequiredService<LocalFileStore>();

        public async Task<(Guid ItemId, string StorageKey)> CreateFileAsync(HttpClient client, byte[] bytes, string displayName)
        {
            var key = Guid.NewGuid();
            using var initiated = await SendUploadInitiateAsync(client, key, new { displayName, contentType = "application/octet-stream", sizeBytes = bytes.Length });
            Assert.Equal(HttpStatusCode.Created, initiated.StatusCode);
            using var initiateJson = await JsonDocument.ParseAsync(await initiated.Content.ReadAsStreamAsync());
            var uploadId = initiateJson.RootElement.GetProperty("uploadId").GetGuid();
            using var staged = await SendRawUploadAsync(client, uploadId, key, bytes);
            Assert.Equal(HttpStatusCode.OK, staged.StatusCode);
            using var complete = await SendUploadCompleteAsync(client, uploadId, key);
            Assert.Equal(HttpStatusCode.Created, complete.StatusCode);
            using var completeJson = await JsonDocument.ParseAsync(await complete.Content.ReadAsStreamAsync());
            var itemId = completeJson.RootElement.GetProperty("id").GetGuid();
            return (itemId, await GetStorageKeyAsync(itemId));
        }

        public async Task<string> GetStagingKeyAsync(Guid uploadId)
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            return await db.UploadSessions.Where(x => x.Id == uploadId).Select(x => x.StagingKey).SingleAsync();
        }

        public async Task<string> GetUploadStateAsync(Guid uploadId)
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            return await db.UploadSessions.Where(x => x.Id == uploadId).Select(x => x.State).SingleAsync();
        }

        public async Task WaitForUploadStateAsync(Guid uploadId, string expected)
        {
            for (var attempt = 0; attempt < 250; attempt++)
            {
                try
                {
                    if (await GetUploadStateAsync(uploadId) == expected) return;
                }
                catch (SqliteException ex) when (ex.SqliteErrorCode is 5 or 6)
                {
                    // Polling can overlap the TestServer request's SQLite transaction; retry busy/locked only.
                }
                await Task.Delay(20);
            }
            Assert.Equal(expected, await GetUploadStateAsync(uploadId));
        }

        public async Task<int> CountAuditEventsAsync(string eventType)
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            return await db.AuditEvents.CountAsync(x => x.EventType == eventType);
        }

        public async Task<int> CountItemsAsync()
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            return await db.Items.CountAsync();
        }

        public async Task<(string State, string? Content)> GetItemContentAsync(Guid id)
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var row = await db.Items.AsNoTracking().Where(x => x.Id == id).Select(x => new { x.State, x.TextContent }).SingleAsync();
            return (row.State, row.TextContent);
        }

        public async Task<string> RunSweepAsync(bool applyChanges = false)
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            using var output = new StringWriter();
            await FileMaintenance.RunAsync(scope.ServiceProvider.GetRequiredService<VaultDbContext>(),
                scope.ServiceProvider.GetRequiredService<LocalFileStore>(), scope.ServiceProvider.GetRequiredService<StorageMaintenanceGate>(),
                _clock, output, CancellationToken.None, applyChanges);
            return output.ToString();
        }

        public async Task SetSizeAsync(Guid id, long size)
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var row = await db.Items.SingleAsync(x => x.Id == id);
            row.SizeBytes = size;
            await db.SaveChangesAsync();
        }

        public async Task ExpireAllActiveAsync(string email)
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var userId = await db.Users.Where(x => x.Email == email).Select(x => x.Id).SingleAsync();
            var now = _clock.GetUtcNow().UtcDateTime;
            await db.Items.Where(x => x.OwnerUserId == userId && x.State == "available").ExecuteUpdateAsync(s => s.SetProperty(x => x.ExpiresAt, now));
        }

        public async Task SeedAvailableItemsAsync(string email, int count)
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var userId = await db.Users.Where(x => x.Email == email).Select(x => x.Id).SingleAsync();
            var deviceId = await db.Devices.Where(x => x.UserId == userId).Select(x => x.Id).FirstAsync();
            var now = _clock.GetUtcNow().UtcDateTime;
            var rows = Enumerable.Range(0, count).Select(i => new RelayItem
            {
                OwnerUserId = userId, CreatedByDeviceId = deviceId, Type = "text", State = "available", DisplayName = "Synthetic seed",
                TextContent = "x", ContentType = "text/plain", SizeBytes = 1, CreatedAt = now.AddTicks(i), AvailableAt = now,
                ExpiresAt = now.AddDays(7), UpdatedAt = now
            });
            db.Items.AddRange(rows);
            await db.SaveChangesAsync();
        }

        public async Task<string> GetAuditDataAsync()
        {
            await using var scope = _factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var rows = await db.AuditEvents.AsNoTracking().Select(x => new { x.EventType, x.ActorUserId, x.ActorDeviceId, x.ItemId, x.CorrelationId, x.OccurredAt }).ToListAsync();
            return JsonSerializer.Serialize(rows);
        }

        public void Dispose()
        {
            _factory.Dispose();
            _connection.Dispose();
            if (Directory.Exists(_filesPath)) Directory.Delete(_filesPath, recursive: true);
        }
    }
}
