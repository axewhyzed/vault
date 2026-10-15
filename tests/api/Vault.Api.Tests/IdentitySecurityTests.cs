using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Security.Cryptography;
using System.Text;
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

namespace Vault.Api.Tests;

public sealed class IdentitySecurityTests
{
    private const string Origin = "https://localhost";
    private const string Password1 = "FirstTestPassword_2026!";
    private const string Password2 = "SecondTestPassword_2026!";
    private const string UpdatedPassword = "UpdatedBootstrapPassword_2026!";

    [Fact]
    public async Task Health_and_openapi_are_public_while_authentication_routes_are_protected()
    {
        using var host = await TestApiHost.CreateAsync();
        using var client = host.CreateClient();

        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/health/live")).StatusCode);
        Assert.Equal(HttpStatusCode.ServiceUnavailable, (await client.GetAsync("/health/ready")).StatusCode);
        using var openApi = await client.GetAsync("/api/openapi/v1.json");
        Assert.Equal(HttpStatusCode.OK, openApi.StatusCode);
        using var document = JsonDocument.Parse(await openApi.Content.ReadAsStringAsync());
        Assert.Equal("3.1.1", document.RootElement.GetProperty("openapi").GetString());
        var paths = document.RootElement.GetProperty("paths");
        Assert.True(paths.TryGetProperty("/api/v1/auth/login", out _));
        Assert.True(paths.TryGetProperty("/api/v1/devices/pairing-sessions", out var pairingPath));
        Assert.True(paths.TryGetProperty("/api/v1/devices/pair", out var pairPath));
        Assert.True(paths.TryGetProperty("/api/v1/auth/refresh", out var refreshPath));
        Assert.True(paths.TryGetProperty("/api/v1/auth/sessions/revoke-all", out var revokeAllPath));
        Assert.Contains("application/json", pairingPath.GetProperty("post").GetProperty("requestBody").GetProperty("content").EnumerateObject().Select(x => x.Name));
        Assert.Contains("Idempotency-Key", pairPath.GetProperty("post").GetProperty("parameters").EnumerateArray().Select(x => x.GetProperty("name").GetString()));
        Assert.False(pairPath.GetProperty("post").TryGetProperty("security", out _));
        Assert.Contains("201", pairPath.GetProperty("post").GetProperty("responses").EnumerateObject().Select(x => x.Name));
        Assert.Contains("200", refreshPath.GetProperty("post").GetProperty("responses").EnumerateObject().Select(x => x.Name));
        AssertSecurityAlternatives(revokeAllPath.GetProperty("post"), "VaultCookie", "VaultBearer");
        AssertSecurityAlternatives(pairingPath.GetProperty("post"), "VaultCookie");
        var scheme = document.RootElement.GetProperty("components").GetProperty("securitySchemes").GetProperty("VaultCookie");
        Assert.Equal("apiKey", scheme.GetProperty("type").GetString());
        Assert.Equal("cookie", scheme.GetProperty("in").GetString());
        Assert.Equal("__Host-Vault.Auth", scheme.GetProperty("name").GetString());
        Assert.Contains("VaultCookie", paths.GetProperty("/api/v1/auth/me").GetProperty("get").GetProperty("security").ToString());
        Assert.Contains("X-CSRF-TOKEN", paths.GetProperty("/api/v1/auth/login").GetProperty("post").GetProperty("description").GetString());

        var textCreate = paths.GetProperty("/api/v1/items/text").GetProperty("post");
        AssertSecurityAlternatives(textCreate, "VaultCookie", "VaultBearer");
        Assert.Contains("Idempotency-Key", textCreate.GetProperty("parameters").EnumerateArray().Select(x => x.GetProperty("name").GetString()));
        Assert.Contains("201", textCreate.GetProperty("responses").EnumerateObject().Select(x => x.Name));
        Assert.Contains("409", textCreate.GetProperty("responses").EnumerateObject().Select(x => x.Name));
        Assert.Contains("413", textCreate.GetProperty("responses").EnumerateObject().Select(x => x.Name));
        Assert.Contains("422", textCreate.GetProperty("responses").EnumerateObject().Select(x => x.Name));
        var itemDelete = paths.GetProperty("/api/v1/items/{id}").GetProperty("delete");
        Assert.Contains("VaultCookie", itemDelete.GetProperty("security").ToString());
        Assert.Contains("Origin", itemDelete.GetProperty("parameters").EnumerateArray().Select(x => x.GetProperty("name").GetString()));

        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/v1/auth/me")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/v1/items")).StatusCode);
        using var logoutRequest = new HttpRequestMessage(HttpMethod.Post, "/api/v1/auth/logout");
        logoutRequest.Headers.Add("Origin", Origin);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.SendAsync(logoutRequest)).StatusCode);
    }

    private static void AssertSecurityAlternatives(JsonElement operation, params string[] expectedSchemes)
    {
        var alternatives = operation.GetProperty("security").EnumerateArray().ToArray();
        Assert.Equal(expectedSchemes.Length, alternatives.Length);
        var actualSchemes = alternatives.Select(requirement =>
        {
            var schemes = requirement.EnumerateObject().Select(property => property.Name).ToArray();
            Assert.Single(schemes);
            Assert.Empty(requirement.GetProperty(schemes[0]).EnumerateArray());
            return schemes[0];
        }).OrderBy(value => value, StringComparer.Ordinal).ToArray();
        Assert.Equal(expectedSchemes.OrderBy(value => value, StringComparer.Ordinal), actualSchemes);
    }

    [Fact]
    public async Task Csrf_origin_validation_login_cookie_me_and_device_scoped_logout_work()
    {
        using var host = await TestApiHost.CreateAsync();
        using var client1 = host.CreateClient();
        var csrf1 = await GetCsrfAsync(client1);

        using var missingOrigin = await client1.PostAsJsonAsync("/api/v1/auth/login", new { email = TestApiHost.Email1, password = Password1 });
        Assert.Equal(HttpStatusCode.Forbidden, missingOrigin.StatusCode);

        using var badOriginRequest = NewLoginRequest(TestApiHost.Email1, Password1, csrf1.Token, "https://evil.example");
        using var badOrigin = await client1.SendAsync(badOriginRequest);
        Assert.Equal(HttpStatusCode.Forbidden, badOrigin.StatusCode);

        foreach (var invalidOrigin in new[] { "null", "https://localhost/path" })
        {
            using var invalidRequest = NewLoginRequest(TestApiHost.Email1, Password1, csrf1.Token, invalidOrigin);
            using var invalidResponse = await client1.SendAsync(invalidRequest);
            Assert.Equal(HttpStatusCode.Forbidden, invalidResponse.StatusCode);
        }

        using var multipleOriginRequest = NewLoginRequest(TestApiHost.Email1, Password1, csrf1.Token, Origin);
        multipleOriginRequest.Headers.TryAddWithoutValidation("Origin", "https://evil.example");
        using var multipleOriginResponse = await client1.SendAsync(multipleOriginRequest);
        Assert.Equal(HttpStatusCode.Forbidden, multipleOriginResponse.StatusCode);

        using var missingCsrfRequest = new HttpRequestMessage(HttpMethod.Post, "/api/v1/auth/login")
        {
            Content = JsonContent.Create(new { email = TestApiHost.Email1, password = Password1 })
        };
        missingCsrfRequest.Headers.Add("Origin", Origin);
        using var missingCsrf = await client1.SendAsync(missingCsrfRequest);
        Assert.Equal(HttpStatusCode.Forbidden, missingCsrf.StatusCode);

        using var invalidCsrfRequest = NewLoginRequest(TestApiHost.Email1, Password1, "invalid-token", Origin);
        using var invalidCsrf = await client1.SendAsync(invalidCsrfRequest);
        Assert.Equal(HttpStatusCode.Forbidden, invalidCsrf.StatusCode);

        var account1 = await LoginAsync(host, client1, TestApiHost.Email1, Password1, csrf1.Token);
        Assert.NotEqual(Guid.Empty, account1.UserId);
        Assert.NotEqual(Guid.Empty, account1.DeviceId);
        Assert.Equal(TestApiHost.Email1, account1.Email);
        var savedCookie = host.GetAuthCookie(client1);

        using var me1 = await client1.GetAsync("/api/v1/auth/me");
        Assert.Equal(HttpStatusCode.OK, me1.StatusCode);
        var signedIn1 = await me1.Content.ReadFromJsonAsync<AccountResponse>();
        Assert.Equal(account1, signedIn1);

        using var client2 = host.CreateClient();
        var csrf2 = await GetCsrfAsync(client2);
        var account2 = await LoginAsync(host, client2, TestApiHost.Username2, Password2, csrf2.Token);
        Assert.NotEqual(account1.UserId, account2.UserId);
        Assert.NotEqual(account1.DeviceId, account2.DeviceId);
        using var me1AfterSecondLogin = await client1.GetAsync("/api/v1/auth/me");
        Assert.Equal(HttpStatusCode.OK, me1AfterSecondLogin.StatusCode);
        Assert.Equal(account1.UserId, (await me1AfterSecondLogin.Content.ReadFromJsonAsync<AccountResponse>())!.UserId);

        using var logoutWithoutCsrf = new HttpRequestMessage(HttpMethod.Post, "/api/v1/auth/logout");
        logoutWithoutCsrf.Headers.Add("Origin", Origin);
        using var missingLogoutCsrf = await client1.SendAsync(logoutWithoutCsrf);
        Assert.Equal(HttpStatusCode.Forbidden, missingLogoutCsrf.StatusCode);

        var logoutCsrf = await GetCsrfAsync(client1);
        using var logoutRequest = new HttpRequestMessage(HttpMethod.Post, "/api/v1/auth/logout");
        logoutRequest.Headers.Add("Origin", Origin);
        logoutRequest.Headers.Add("X-CSRF-TOKEN", logoutCsrf.Token);
        using var logout = await client1.SendAsync(logoutRequest);
        Assert.Equal(HttpStatusCode.NoContent, logout.StatusCode);
        using var replayClient = host.CreateClient();
        replayClient.DefaultRequestHeaders.Add("Cookie", savedCookie);
        Assert.Equal(HttpStatusCode.Unauthorized, (await replayClient.GetAsync("/api/v1/auth/me")).StatusCode);
        using var client2StillValid = await client2.GetAsync("/api/v1/auth/me");
        Assert.Equal(HttpStatusCode.OK, client2StillValid.StatusCode);

        var events = await host.GetAuditEventTypesAsync();
        Assert.Contains("login_succeeded", events);
        Assert.Contains("logout", events);
        var auditText = await host.GetAuditTextAsync();
        Assert.DoesNotContain(TestApiHost.Email1, auditText, StringComparison.Ordinal);
        Assert.DoesNotContain(TestApiHost.Email2, auditText, StringComparison.Ordinal);
        Assert.DoesNotContain(Password1, auditText, StringComparison.Ordinal);
        Assert.DoesNotContain(Password2, auditText, StringComparison.Ordinal);
        Assert.DoesNotContain(csrf1.Token, auditText, StringComparison.Ordinal);
        Assert.DoesNotContain(logoutCsrf.Token, auditText, StringComparison.Ordinal);

        var originalHash = await host.GetPasswordHashAsync(TestApiHost.Email1);
        await host.RunSeederAsync(TestApiHost.Email1, "IgnoredPasswordForIdempotence!");
        Assert.Equal(originalHash, await host.GetPasswordHashAsync(TestApiHost.Email1));
        await Assert.ThrowsAsync<InvalidOperationException>(() => host.RunSeederAsync(TestApiHost.Email2, Password2));
        Assert.Equal(originalHash, await host.GetPasswordHashAsync(TestApiHost.Email1));
    }

    [Fact]
    public async Task Login_accepts_legacy_email_payload_and_identifier_payload_with_username()
    {
        using var host = await TestApiHost.CreateAsync();
        using var emailClient = host.CreateClient();
        var emailCsrf = await GetCsrfAsync(emailClient);

        using var legacyEmailResponse = await SendLegacyEmailLoginAsync(emailClient, TestApiHost.Email1, Password1, emailCsrf.Token);
        Assert.Equal(HttpStatusCode.OK, legacyEmailResponse.StatusCode);
        Assert.Equal(TestApiHost.Email1, (await legacyEmailResponse.Content.ReadFromJsonAsync<AccountResponse>())!.Email);

        using var usernameClient = host.CreateClient();
        var usernameCsrf = await GetCsrfAsync(usernameClient);
        using var usernameResponse = await SendLoginAsync(usernameClient, " OWNER-B ", Password2, usernameCsrf.Token, "192.0.2.21");
        Assert.Equal(HttpStatusCode.OK, usernameResponse.StatusCode);
        Assert.Equal(TestApiHost.Email2, (await usernameResponse.Content.ReadFromJsonAsync<AccountResponse>())!.Email);
    }

    [Fact]
    public async Task Bootstrap_username_backfill_preserves_password_and_username_login_works()
    {
        using var host = await TestApiHost.CreateAsync();
        var originalHash = await host.GetPasswordHashAsync(TestApiHost.Email1);

        await host.RunSeederAsync(TestApiHost.Email1, "IgnoredPasswordForIdempotence!", TestApiHost.Username1);

        Assert.Equal(originalHash, await host.GetPasswordHashAsync(TestApiHost.Email1));
        Assert.Equal(TestApiHost.Username1, await host.GetUsernameAsync(TestApiHost.Email1));

        using var client = host.CreateClient();
        var csrf = await GetCsrfAsync(client);
        using var login = await SendLoginAsync(client, TestApiHost.Username1, Password1, csrf.Token, "192.0.2.22");
        Assert.Equal(HttpStatusCode.OK, login.StatusCode);
    }

    [Fact]
    public async Task Explicit_bootstrap_credential_update_changes_password_and_revokes_sessions()
    {
        using var host = await TestApiHost.CreateAsync();
        using var existingClient = host.CreateClient();
        var firstCsrf = await GetCsrfAsync(existingClient);
        _ = await LoginAsync(host, existingClient, TestApiHost.Email1, Password1, firstCsrf.Token);
        firstCsrf = await GetCsrfAsync(existingClient);
        var usedPairing = await CreatePairingAsync(existingClient, firstCsrf.Token);
        using var androidClient = host.CreateClient();
        var androidTokens = await PairAsync(androidClient, usedPairing, Guid.NewGuid());
        var pendingPairing = await CreatePairingAsync(existingClient, firstCsrf.Token);

        await host.UpdateBootstrapCredentialsAsync(TestApiHost.Email1, TestApiHost.Username1, UpdatedPassword);

        using var revokedSession = await existingClient.GetAsync("/api/v1/auth/me");
        Assert.Equal(HttpStatusCode.Unauthorized, revokedSession.StatusCode);
        using var revokedAndroid = await SendBearerAsync(androidClient, androidTokens.AccessToken, "/api/v1/items");
        Assert.Equal(HttpStatusCode.Unauthorized, revokedAndroid.StatusCode);
        using var pendingPairingRejected = await PairAsyncResponse(androidClient, pendingPairing, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.Unauthorized, pendingPairingRejected.StatusCode);

        using var oldPasswordClient = host.CreateClient();
        var oldPasswordCsrf = await GetCsrfAsync(oldPasswordClient);
        using var oldPassword = await SendLoginAsync(oldPasswordClient, TestApiHost.Email1, Password1, oldPasswordCsrf.Token, "192.0.2.23");
        Assert.Equal(HttpStatusCode.Unauthorized, oldPassword.StatusCode);

        using var newPasswordClient = host.CreateClient();
        var newPasswordCsrf = await GetCsrfAsync(newPasswordClient);
        using var newPassword = await SendLoginAsync(newPasswordClient, TestApiHost.Username1, UpdatedPassword, newPasswordCsrf.Token, "192.0.2.24");
        Assert.Equal(HttpStatusCode.OK, newPassword.StatusCode);
        Assert.Contains("bootstrap_credentials_updated", await host.GetAuditEventTypesAsync());
        Assert.DoesNotContain(UpdatedPassword, await host.GetAuditTextAsync(), StringComparison.Ordinal);
    }

    [Fact]
    public async Task Bootstrap_seeder_with_sync_credentials_automatically_updates_password_on_startup()
    {
        using var host = await TestApiHost.CreateAsync();
        using var existingClient = host.CreateClient();
        var csrf = await GetCsrfAsync(existingClient);
        await LoginAsync(host, existingClient, TestApiHost.Email1, Password1, csrf.Token);

        const string newPassword = "NewAutomatedVaultPassword_2026!";
        await host.RunSeederAsync(TestApiHost.Email1, newPassword, TestApiHost.Username1, syncCredentials: true);

        using var revokedSession = await existingClient.GetAsync("/api/v1/auth/me");
        Assert.Equal(HttpStatusCode.Unauthorized, revokedSession.StatusCode);

        using var newClient = host.CreateClient();
        var newCsrf = await GetCsrfAsync(newClient);
        using var newPasswordLogin = await SendLoginAsync(newClient, TestApiHost.Username1, newPassword, newCsrf.Token, "192.0.2.30");
        Assert.Equal(HttpStatusCode.OK, newPasswordLogin.StatusCode);
    }

    [Fact]
    public async Task Bootstrap_password_minimum_length_requirement_is_three_characters()
    {
        using var host = await TestApiHost.CreateAsync();

        var exception = await Assert.ThrowsAsync<InvalidOperationException>(
            () => host.RunSeederAsync(TestApiHost.Email1, "12", TestApiHost.Username1, syncCredentials: true));
        Assert.Equal("Bootstrap:Password must be at least 3 characters.", exception.Message);

        const string shortPassword = "123";
        await host.RunSeederAsync(TestApiHost.Email1, shortPassword, TestApiHost.Username1, syncCredentials: true);

        using var client = host.CreateClient();
        var csrf = await GetCsrfAsync(client);
        using var login = await SendLoginAsync(client, TestApiHost.Username1, shortPassword, csrf.Token, "192.0.2.31");
        Assert.Equal(HttpStatusCode.OK, login.StatusCode);
    }

    [Fact]
    public async Task Invalid_credentials_are_audited_and_sign_in_is_limited_to_five_per_client_ip()
    {
        using var host = await TestApiHost.CreateAsync();
        using var client = host.CreateClient();
        var csrf = await GetCsrfAsync(client);

        for (var attempt = 1; attempt <= 5; attempt++)
        {
            using var response = await SendLoginAsync(client, TestApiHost.Email1, "wrong-password", csrf.Token, "192.0.2.7");
            Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        }

        using var limited = await SendLoginAsync(client, TestApiHost.Email1, Password1, csrf.Token, "192.0.2.7");
        Assert.Equal(HttpStatusCode.TooManyRequests, limited.StatusCode);
        Assert.NotNull(limited.Headers.RetryAfter);
        var events = await host.GetAuditEventTypesAsync();
        Assert.Equal(5, events.Count(value => value == "login_failed"));
    }

    [Fact]
    public async Task Sign_in_global_limit_counts_successes_across_client_ips_and_does_not_reset_on_success()
    {
        using var host = await TestApiHost.CreateAsync();
        using var client = host.CreateClient();
        for (var attempt = 1; attempt <= 30; attempt++)
        {
            var requestCsrf = await GetCsrfAsync(client);
            using var response = await SendLoginAsync(client, TestApiHost.Email1, Password1, requestCsrf.Token, $"198.51.100.{attempt}");
            Assert.True(response.StatusCode == HttpStatusCode.OK, $"Attempt {attempt} failed: {response.StatusCode} {await response.Content.ReadAsStringAsync()}");
        }

        var finalCsrf = await GetCsrfAsync(client);
        using var limited = await SendLoginAsync(client, TestApiHost.Email1, Password1, finalCsrf.Token, "203.0.113.1");
        Assert.Equal(HttpStatusCode.TooManyRequests, limited.StatusCode);
        Assert.NotNull(limited.Headers.RetryAfter);
    }

    [Fact]
    public async Task Android_pairing_is_single_use_and_bearer_tokens_authorize_transfers()
    {
        using var host = await TestApiHost.CreateAsync();
        using var web = host.CreateClient();
        var csrf = await GetCsrfAsync(web);
        await LoginAsync(host, web, TestApiHost.Email1, Password1, csrf.Token);
        csrf = await GetCsrfAsync(web);
        var pairing = await CreatePairingAsync(web, csrf.Token);
        Assert.Equal("https://vault.example.test", pairing.ServerOrigin);
        Assert.Matches("^[A-Za-z0-9_-]{43}$", pairing.PairingSecret);
        Assert.Equal(DateTimeKind.Utc, pairing.ExpiresAt.Kind);
        Assert.Equal(10, (int)Math.Round((pairing.ExpiresAt - DateTime.UtcNow).TotalMinutes));

        using var mobile = host.CreateClient();
        var pairKey = Guid.NewGuid();
        var tokens = await PairAsync(mobile, pairing, pairKey);
        Assert.StartsWith("va1_", tokens.AccessToken);
        Assert.StartsWith("vr1_", tokens.RefreshToken);
        Assert.NotEqual(tokens.AccessToken, await host.GetStoredTokenHashAsync(tokens.AccessToken));
        Assert.Equal(Hash(tokens.AccessToken), await host.GetStoredTokenHashAsync(tokens.AccessToken));

        using var transfer = new HttpRequestMessage(HttpMethod.Post, "/api/v1/items/text") { Content = JsonContent.Create(new { text = "from android" }) };
        transfer.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", tokens.AccessToken);
        transfer.Headers.Add("Idempotency-Key", Guid.NewGuid().ToString("D"));
        using var created = await mobile.SendAsync(transfer);
        Assert.Equal(HttpStatusCode.Created, created.StatusCode);

        using var revokeAll = new HttpRequestMessage(HttpMethod.Post, "/api/v1/auth/sessions/revoke-all") { Content = JsonContent.Create(new { }) };
        revokeAll.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", tokens.AccessToken);
        using var bearerRevoked = await mobile.SendAsync(revokeAll);
        Assert.Equal(HttpStatusCode.NoContent, bearerRevoked.StatusCode);

        using var replay = await PairAsyncResponse(mobile, pairing, pairKey);
        Assert.Equal(HttpStatusCode.Conflict, replay.StatusCode);
        using var replayBody = JsonDocument.Parse(await replay.Content.ReadAsStringAsync());
        Assert.Equal("pairing_consumed", replayBody.RootElement.GetProperty("code").GetString());

        using var ambientPair = new HttpRequestMessage(HttpMethod.Post, "/api/v1/devices/pair")
        {
            Content = JsonContent.Create(new { pairingSessionId = pairing.PairingSessionId, pairingSecret = pairing.PairingSecret, deviceName = "Android phone" })
        };
        ambientPair.Headers.Add("Idempotency-Key", Guid.NewGuid().ToString("D"));
        ambientPair.Headers.Add("Cookie", "ambient=1");
        using var ambientPairResponse = await mobile.SendAsync(ambientPair);
        Assert.Equal(HttpStatusCode.BadRequest, ambientPairResponse.StatusCode);

        using var mixed = new HttpRequestMessage(HttpMethod.Get, "/api/v1/items");
        mixed.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", tokens.AccessToken);
        mixed.Headers.Add("Cookie", "ambient=1");
        using var rejected = await mobile.SendAsync(mixed);
        Assert.Equal(HttpStatusCode.BadRequest, rejected.StatusCode);
        var audit = await host.GetAuditTextAsync();
        Assert.DoesNotContain(pairing.PairingSecret, audit, StringComparison.Ordinal);
        Assert.DoesNotContain(tokens.AccessToken, audit, StringComparison.Ordinal);
        Assert.DoesNotContain(tokens.RefreshToken, audit, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Pairing_expiry_and_concurrent_redemption_fail_closed()
    {
        using var host = await TestApiHost.CreateAsync();
        using var web = host.CreateClient();
        var csrf = await GetCsrfAsync(web);
        await LoginAsync(host, web, TestApiHost.Email1, Password1, csrf.Token);
        csrf = await GetCsrfAsync(web);
        var expiredPairing = await CreatePairingAsync(web, csrf.Token);
        await host.ExpirePairingSessionAsync(expiredPairing.PairingSessionId);
        using var mobile = host.CreateClient();
        using var expired = await PairAsyncResponse(mobile, expiredPairing, Guid.NewGuid());
        Assert.Equal(HttpStatusCode.Unauthorized, expired.StatusCode);

        var concurrentPairing = await CreatePairingAsync(web, csrf.Token);
        using var clientA = host.CreateClient();
        using var clientB = host.CreateClient();
        var attempts = await Task.WhenAll(
            PairAsyncResponse(clientA, concurrentPairing, Guid.NewGuid()),
            PairAsyncResponse(clientB, concurrentPairing, Guid.NewGuid()));
        Assert.Single(attempts, response => response.StatusCode == HttpStatusCode.Created);
        Assert.All(attempts.Where(response => response.StatusCode != HttpStatusCode.Created), response => Assert.Equal(HttpStatusCode.Conflict, response.StatusCode));
        foreach (var response in attempts) response.Dispose();
        Assert.Equal(1, await host.GetAndroidSessionCountAsync(TestApiHost.Email1));
    }

    [Fact]
    public async Task Pairing_session_creation_is_limited_and_rate_limited_attempts_are_audited()
    {
        using var host = await TestApiHost.CreateAsync();
        using var web = host.CreateClient();
        var csrf = await GetCsrfAsync(web);
        await LoginAsync(host, web, TestApiHost.Email1, Password1, csrf.Token);
        csrf = await GetCsrfAsync(web);
        for (var i = 0; i < 5; i++) _ = await CreatePairingAsync(web, csrf.Token);

        using var limitedRequest = new HttpRequestMessage(HttpMethod.Post, "/api/v1/devices/pairing-sessions") { Content = JsonContent.Create(new { }) };
        limitedRequest.Headers.Add("Origin", Origin);
        limitedRequest.Headers.Add("X-CSRF-TOKEN", csrf.Token);
        using var limited = await web.SendAsync(limitedRequest);
        Assert.Equal(HttpStatusCode.TooManyRequests, limited.StatusCode);
        Assert.NotNull(limited.Headers.RetryAfter);
        Assert.Contains("device_pairing_rate_limited", await host.GetAuditEventTypesAsync());
    }

    [Fact]
    public async Task Pairing_redemption_is_limited_per_ip_with_retry_after()
    {
        using var host = await TestApiHost.CreateAsync();
        using var client = host.CreateClient();
        for (var attempt = 0; attempt < 10; attempt++)
        {
            using var request = NewPairRequest(Guid.NewGuid(), Guid.NewGuid(), new string('a', 43));
            using var response = await client.SendAsync(request);
            Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        }
        using var limitedRequest = NewPairRequest(Guid.NewGuid(), Guid.NewGuid(), new string('a', 43));
        using var limited = await client.SendAsync(limitedRequest);
        Assert.Equal(HttpStatusCode.TooManyRequests, limited.StatusCode);
        Assert.NotNull(limited.Headers.RetryAfter);
        Assert.Contains("device_pairing_rate_limited", await host.GetAuditEventTypesAsync());
    }

    [Fact]
    public async Task Expired_access_token_is_rejected_before_item_access()
    {
        using var host = await TestApiHost.CreateAsync();
        using var web = host.CreateClient();
        var csrf = await GetCsrfAsync(web);
        await LoginAsync(host, web, TestApiHost.Email1, Password1, csrf.Token);
        csrf = await GetCsrfAsync(web);
        var pairing = await CreatePairingAsync(web, csrf.Token);
        using var mobile = host.CreateClient();
        var tokens = await PairAsync(mobile, pairing, Guid.NewGuid());
        await host.ExpireAccessTokenAsync(tokens.AccessToken);
        using var expired = await SendBearerAsync(mobile, tokens.AccessToken, "/api/v1/items");
        Assert.Equal(HttpStatusCode.Unauthorized, expired.StatusCode);
    }

    [Fact]
    public async Task Refresh_rotates_tokens_and_replay_revokes_android_session_and_revoke_all_cuts_off_both_clients()
    {
        using var host = await TestApiHost.CreateAsync();
        using var web = host.CreateClient();
        var csrf = await GetCsrfAsync(web);
        await LoginAsync(host, web, TestApiHost.Email1, Password1, csrf.Token);
        csrf = await GetCsrfAsync(web);
        var pairing = await CreatePairingAsync(web, csrf.Token);
        using var mobile = host.CreateClient();
        var original = await PairAsync(mobile, pairing, Guid.NewGuid());

        using var refreshResponse = await mobile.PostAsJsonAsync("/api/v1/auth/refresh", new { refreshToken = original.RefreshToken });
        Assert.Equal(HttpStatusCode.OK, refreshResponse.StatusCode);
        var rotated = (await refreshResponse.Content.ReadFromJsonAsync<TokenResponse>())!;
        Assert.NotEqual(original.AccessToken, rotated.AccessToken);
        Assert.NotEqual(original.RefreshToken, rotated.RefreshToken);
        Assert.Equal(original.RefreshTokenExpiresAt, rotated.RefreshTokenExpiresAt);
        using var ambientRefresh = new HttpRequestMessage(HttpMethod.Post, "/api/v1/auth/refresh") { Content = JsonContent.Create(new { refreshToken = rotated.RefreshToken }) };
        ambientRefresh.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", rotated.AccessToken);
        using var ambientRefreshResponse = await mobile.SendAsync(ambientRefresh);
        Assert.Equal(HttpStatusCode.BadRequest, ambientRefreshResponse.StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await SendBearerAsync(mobile, original.AccessToken, "/api/v1/items")).StatusCode);

        using var reused = await mobile.PostAsJsonAsync("/api/v1/auth/refresh", new { refreshToken = original.RefreshToken });
        Assert.Equal(HttpStatusCode.Unauthorized, reused.StatusCode);
        using var afterReuse = await SendBearerAsync(mobile, rotated.AccessToken, "/api/v1/items");
        Assert.Equal(HttpStatusCode.Unauthorized, afterReuse.StatusCode);

        var nextCsrf = await GetCsrfAsync(web);
        var nextPair = await CreatePairingAsync(web, nextCsrf.Token);
        using var secondMobile = host.CreateClient();
        var active = await PairAsync(secondMobile, nextPair, Guid.NewGuid());
        var revokeCsrf = await GetCsrfAsync(web);
        using var revoke = new HttpRequestMessage(HttpMethod.Post, "/api/v1/auth/sessions/revoke-all") { Content = JsonContent.Create(new { }) };
        revoke.Headers.Add("Origin", Origin);
        revoke.Headers.Add("X-CSRF-TOKEN", revokeCsrf.Token);
        using var revoked = await web.SendAsync(revoke);
        Assert.Equal(HttpStatusCode.NoContent, revoked.StatusCode);
        Assert.Contains(revoked.Headers.GetValues("Set-Cookie"), value => value.StartsWith("__Host-Vault.Auth=", StringComparison.Ordinal));
        using var cookieCutoff = await web.GetAsync("/api/v1/auth/me");
        Assert.Equal(HttpStatusCode.Unauthorized, cookieCutoff.StatusCode);
        using var bearerCutoff = await SendBearerAsync(secondMobile, active.AccessToken, "/api/v1/items");
        Assert.Equal(HttpStatusCode.Unauthorized, bearerCutoff.StatusCode);
    }

    private static HttpRequestMessage NewLoginRequest(string email, string password, string token, string origin)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, "/api/v1/auth/login")
        {
            Content = JsonContent.Create(new { email, password })
        };
        request.Headers.Add("Origin", origin);
        request.Headers.Add("X-CSRF-TOKEN", token);
        return request;
    }

    private static async Task<HttpResponseMessage> SendLegacyEmailLoginAsync(HttpClient client, string email, string password, string token)
    {
        var request = NewLoginRequest(email, password, token, Origin);
        return await client.SendAsync(request);
    }

    private static async Task<CsrfResponse> GetCsrfAsync(HttpClient client)
    {
        using var response = await client.GetAsync("/api/v1/auth/csrf");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var csrf = await response.Content.ReadFromJsonAsync<CsrfResponse>();
        Assert.NotNull(csrf);
        Assert.Equal("X-CSRF-TOKEN", csrf.HeaderName);
        return csrf;
    }

    private static async Task<AccountResponse> LoginAsync(TestApiHost host, HttpClient client, string identifier, string password, string token)
    {
        using var response = await SendLoginAsync(client, identifier, password, token, "192.0.2.20");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        host.RecordAuthCookie(client, response);
        var account = await response.Content.ReadFromJsonAsync<AccountResponse>();
        Assert.NotNull(account);
        return account;
    }

    private static async Task<HttpResponseMessage> SendLoginAsync(HttpClient client, string identifier, string password, string token, string ip)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, "/api/v1/auth/login")
        {
            Content = JsonContent.Create(new { identifier, password })
        };
        request.Headers.Add("Origin", Origin);
        request.Headers.Add("X-CSRF-TOKEN", token);
        request.Headers.Add("X-Forwarded-For", ip);
        return await client.SendAsync(request);
    }

    private static async Task<PairingResponse> CreatePairingAsync(HttpClient client, string csrf)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, "/api/v1/devices/pairing-sessions") { Content = JsonContent.Create(new { }) };
        request.Headers.Host = "attacker-controlled.example";
        request.Headers.Add("Origin", Origin);
        request.Headers.Add("X-CSRF-TOKEN", csrf);
        using var response = await client.SendAsync(request);
        Assert.True(response.StatusCode == HttpStatusCode.Created, $"Pairing session creation returned {(int)response.StatusCode}: {await response.Content.ReadAsStringAsync()}");
        Assert.Equal("no-store", response.Headers.CacheControl?.ToString());
        return (await response.Content.ReadFromJsonAsync<PairingResponse>())!;
    }

    private static async Task<TokenResponse> PairAsync(HttpClient client, PairingResponse pairing, Guid key)
    {
        using var response = await PairAsyncResponse(client, pairing, key);
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        Assert.Equal("no-store", response.Headers.CacheControl?.ToString());
        return (await response.Content.ReadFromJsonAsync<TokenResponse>())!;
    }

    private static Task<HttpResponseMessage> PairAsyncResponse(HttpClient client, PairingResponse pairing, Guid key)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, "/api/v1/devices/pair")
        {
            Content = JsonContent.Create(new { pairingSessionId = pairing.PairingSessionId, pairingSecret = pairing.PairingSecret, deviceName = "Android phone" })
        };
        request.Headers.Add("Idempotency-Key", key.ToString("D"));
        return client.SendAsync(request);
    }

    private static HttpRequestMessage NewPairRequest(Guid sessionId, Guid idempotencyKey, string secret)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, "/api/v1/devices/pair")
        {
            Content = JsonContent.Create(new { pairingSessionId = sessionId, pairingSecret = secret, deviceName = "Android phone" })
        };
        request.Headers.Add("Idempotency-Key", idempotencyKey.ToString("D"));
        return request;
    }

    private static async Task<HttpResponseMessage> SendBearerAsync(HttpClient client, string token, string path)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, path);
        request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token);
        return await client.SendAsync(request);
    }

    private static string Hash(string token) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token))).ToLowerInvariant();

    private sealed record CsrfResponse(string Token, string HeaderName);
    private sealed record AccountResponse(Guid UserId, string Email, Guid DeviceId);
    private sealed record PairingResponse(Guid PairingSessionId, string PairingSecret, string ServerOrigin, DateTime ExpiresAt);
    private sealed record TokenResponse(string TokenType, string AccessToken, DateTime AccessTokenExpiresAt, string RefreshToken, DateTime RefreshTokenExpiresAt, Guid UserId, Guid DeviceId);

    private sealed class TestApiHost : IDisposable
    {
        public const string Email1 = "owner-a@example.test";
        public const string Email2 = "owner-b@example.test";
        public const string Username1 = "mihir0234";
        public const string Username2 = "owner-b";
        private readonly SqliteConnection _connection;
        private readonly WebApplicationFactory<Program> _factory;

        private TestApiHost(SqliteConnection connection, WebApplicationFactory<Program> factory)
        {
            _connection = connection;
            _factory = factory;
        }

        public static async Task<TestApiHost> CreateAsync()
        {
            var connection = new SqliteConnection("Data Source=:memory:");
            await connection.OpenAsync();
            var factory = new WebApplicationFactory<Program>().WithWebHostBuilder(builder =>
            {
                builder.ConfigureAppConfiguration((_, configuration) => configuration.AddInMemoryCollection(new Dictionary<string, string?>
                {
                    ["ConnectionStrings:Vault"] = "Data Source=:memory:",
                    ["Database:ApplyMigrations"] = "false",
                    ["TrustedProxyIp"] = "127.0.0.1",
                    ["Security:AllowedOrigins:0"] = Origin
                    ,["Security:ServerOrigin"] = "https://vault.example.test"
                }));
                builder.ConfigureServices(services =>
                {
                    services.RemoveAll<VaultDbContext>();
                    services.RemoveAll<DbContextOptions<VaultDbContext>>();
                    services.RemoveAll<IDbContextOptionsConfiguration<VaultDbContext>>();
                    services.AddDbContext<VaultDbContext>(options => options.UseSqlite(connection));
                    services.Configure<PasswordHasherOptions>(options => options.IterationCount = 10);
                });
            });
            var host = new TestApiHost(connection, factory);
            using var scope = factory.Services.CreateScope();
            var database = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            await database.Database.EnsureCreatedAsync();
            var hasher = new PasswordHasher<VaultUser>(Options.Create(new PasswordHasherOptions { IterationCount = 10 }));
            var user1 = new VaultUser { Email = Email1, NormalizedEmail = Email1.ToUpperInvariant(), IsBootstrap = true };
            user1.PasswordHash = hasher.HashPassword(user1, Password1);
            var user2 = new VaultUser
            {
                Email = Email2,
                NormalizedEmail = Email2.ToUpperInvariant(),
                Username = Username2,
                NormalizedUsername = Username2.ToUpperInvariant()
            };
            user2.PasswordHash = hasher.HashPassword(user2, Password2);
            database.Users.AddRange(user1, user2);
            await database.SaveChangesAsync();
            return host;
        }

        public HttpClient CreateClient() => _factory.CreateClient(new WebApplicationFactoryClientOptions
        {
            BaseAddress = new Uri(Origin),
            HandleCookies = true,
            AllowAutoRedirect = false
        });

        public string GetAuthCookie(HttpClient client)
        {
            return AuthCookieByLogin[client];
        }

        private readonly Dictionary<HttpClient, string> AuthCookieByLogin = new();

        public void RecordAuthCookie(HttpClient client, HttpResponseMessage response)
        {
            var setCookie = Assert.Single(response.Headers.GetValues("Set-Cookie"), value => value.StartsWith("__Host-Vault.Auth=", StringComparison.Ordinal));
            Assert.Contains("; path=/", setCookie, StringComparison.OrdinalIgnoreCase);
            Assert.Contains("; secure", setCookie, StringComparison.OrdinalIgnoreCase);
            Assert.Contains("; httponly", setCookie, StringComparison.OrdinalIgnoreCase);
            Assert.Contains("; samesite=strict", setCookie, StringComparison.OrdinalIgnoreCase);
            AuthCookieByLogin[client] = setCookie.Split(';', 2)[0];
        }

        public async Task<List<string>> GetAuditEventTypesAsync()
        {
            using var scope = _factory.Services.CreateScope();
            var database = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            return await database.AuditEvents.Select(value => value.EventType).ToListAsync();
        }

        public async Task<string> GetAuditTextAsync()
        {
            using var scope = _factory.Services.CreateScope();
            var database = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var rows = await database.AuditEvents.AsNoTracking().Select(value => new
            {
                value.EventType,
                value.ActorUserId,
                value.ActorDeviceId,
                value.CorrelationId,
                value.OccurredAt
            }).ToListAsync();
            return JsonSerializer.Serialize(rows);
        }

        public async Task<string> GetPasswordHashAsync(string email)
        {
            using var scope = _factory.Services.CreateScope();
            var database = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            return await database.Users.Where(value => value.NormalizedEmail == email.ToUpperInvariant())
                .Select(value => value.PasswordHash).SingleAsync();
        }

        public async Task<string> GetStoredTokenHashAsync(string token)
        {
            using var scope = _factory.Services.CreateScope();
            var database = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            return await database.DeviceCredentials.Where(value => value.TokenHash == Hash(token)).Select(value => value.TokenHash).SingleAsync();
        }

        public async Task ExpirePairingSessionAsync(Guid id)
        {
            using var scope = _factory.Services.CreateScope();
            var database = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var row = await database.DevicePairingSessions.SingleAsync(value => value.Id == id);
            row.ExpiresAt = DateTime.UtcNow.AddMinutes(-1);
            await database.SaveChangesAsync();
        }

        public async Task<int> GetAndroidSessionCountAsync(string email)
        {
            using var scope = _factory.Services.CreateScope();
            var database = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var userId = await database.Users.Where(user => user.Email == email).Select(user => user.Id).SingleAsync();
            return await database.AndroidSessions.CountAsync(session => session.UserId == userId);
        }

        public async Task ExpireAccessTokenAsync(string token)
        {
            using var scope = _factory.Services.CreateScope();
            var database = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var credential = await database.DeviceCredentials.SingleAsync(value => value.TokenHash == Hash(token));
            credential.ExpiresAt = DateTime.UtcNow.AddMinutes(-1);
            await database.SaveChangesAsync();
        }

        public async Task RunSeederAsync(string email, string password, string? username = null, bool syncCredentials = false)
        {
            using var scope = _factory.Services.CreateScope();
            var database = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var hasher = new PasswordHasher<VaultUser>(Options.Create(new PasswordHasherOptions { IterationCount = 10 }));
            var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["Bootstrap:Email"] = email,
                ["Bootstrap:Password"] = password,
                ["Bootstrap:Username"] = username,
                ["Bootstrap:SyncCredentials"] = syncCredentials ? "true" : "false"
            }).Build();
            await new BootstrapAccountSeeder(database, hasher, configuration).SeedAsync(CancellationToken.None);
        }

        public async Task<string?> GetUsernameAsync(string email)
        {
            using var scope = _factory.Services.CreateScope();
            var database = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            return await database.Users.Where(value => value.NormalizedEmail == email.ToUpperInvariant())
                .Select(value => value.Username).SingleAsync();
        }

        public async Task UpdateBootstrapCredentialsAsync(string email, string username, string password)
        {
            using var scope = _factory.Services.CreateScope();
            var database = scope.ServiceProvider.GetRequiredService<VaultDbContext>();
            var hasher = new PasswordHasher<VaultUser>(Options.Create(new PasswordHasherOptions { IterationCount = 10 }));
            var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["Bootstrap:Email"] = email,
                ["Bootstrap:Username"] = username,
                ["Bootstrap:Password"] = password
            }).Build();
            await new BootstrapAccountSeeder(database, hasher, configuration)
                .UpdateBootstrapCredentialsAsync(CancellationToken.None);
        }

        public void Dispose()
        {
            _factory.Dispose();
            _connection.Dispose();
        }
    }
}
