FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY src/api/Vault.Api/Vault.Api.csproj src/api/Vault.Api/
RUN dotnet restore src/api/Vault.Api/Vault.Api.csproj
COPY src/api/Vault.Api/ src/api/Vault.Api/
RUN dotnet publish src/api/Vault.Api/Vault.Api.csproj -c Release -o /app/publish --no-restore

FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS runtime
WORKDIR /app
ENV ASPNETCORE_HTTP_PORTS=8080
EXPOSE 8080
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /home/app/.aspnet/DataProtection-Keys /var/lib/vault/files \
    && chown -R $APP_UID:$APP_UID /home/app /var/lib/vault
COPY --from=build /app/publish .
USER $APP_UID
HEALTHCHECK --interval=15s --timeout=3s --start-period=15s --retries=3 CMD curl --fail --silent http://localhost:8080/health/ready || exit 1
ENTRYPOINT ["dotnet", "Vault.Api.dll"]
