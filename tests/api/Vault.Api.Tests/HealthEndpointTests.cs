using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;

namespace Vault.Api.Tests;

public sealed class HealthEndpointTests : IClassFixture<WebApplicationFactory<Program>>
{
    private readonly WebApplicationFactory<Program> _factory;

    public HealthEndpointTests(WebApplicationFactory<Program> factory)
    {
        _factory = factory.WithWebHostBuilder(builder => builder.ConfigureAppConfiguration((_, configuration) =>
        {
            configuration.AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["ConnectionStrings:Vault"] = "Host=127.0.0.1;Port=1;Database=vault;Username=vault;Password=test;Timeout=1;Command Timeout=1"
            });
        }));
    }

    [Fact]
    public async Task Live_endpoint_is_healthy_without_database()
    {
        using var client = _factory.CreateClient();

        using var response = await client.GetAsync("/health/live");

        Assert.Equal(System.Net.HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("live", await response.Content.ReadAsStringAsync().ContinueWith(task =>
            System.Text.Json.JsonDocument.Parse(task.Result).RootElement.GetProperty("status").GetString()));
    }

    [Fact]
    public async Task Ready_endpoint_is_unhealthy_when_database_is_unavailable()
    {
        using var client = _factory.CreateClient();

        using var response = await client.GetAsync("/health/ready");

        Assert.Equal(System.Net.HttpStatusCode.ServiceUnavailable, response.StatusCode);
    }

    [Fact]
    public async Task Correlation_id_is_returned_and_untrusted_value_is_replaced()
    {
        using var client = _factory.CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, "/health/live");
        request.Headers.Add("X-Correlation-ID", "bad value with spaces");

        using var response = await client.SendAsync(request);

        Assert.True(response.Headers.TryGetValues("X-Correlation-ID", out var values));
        Assert.NotEqual("bad value with spaces", Assert.Single(values));
    }
}
