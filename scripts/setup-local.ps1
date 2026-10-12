[CmdletBinding()]
param(
    [string] $TlsDirectory
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$localRoot = Join-Path $repoRoot '.local'
$tlsRoot = if ([string]::IsNullOrWhiteSpace($TlsDirectory)) { Join-Path $localRoot 'tls' } else { [System.IO.Path]::GetFullPath($TlsDirectory) }
$certPath = Join-Path $tlsRoot 'dev-cert.pem'
$keyPath = Join-Path $tlsRoot 'dev-cert.key'
$envPath = Join-Path $repoRoot '.env'

New-Item -ItemType Directory -Force -Path $tlsRoot | Out-Null

# Trusting creates a localhost development certificate if one does not exist.
# Do not use `--clean`: other local projects may rely on existing certificates.
dotnet dev-certs https --trust
if ($LASTEXITCODE -ne 0) { throw 'Could not create or trust the .NET HTTPS development certificate.' }

dotnet dev-certs https --export-path $certPath --format PEM --no-password
if ($LASTEXITCODE -ne 0) { throw 'Could not export the .NET HTTPS certificate to PEM.' }
if (-not (Test-Path -LiteralPath $certPath) -or -not (Test-Path -LiteralPath $keyPath)) {
    throw "Expected PEM certificate and key at $certPath and $keyPath."
}

if (-not (Test-Path -LiteralPath $envPath)) {
    function New-RandomSecret {
        $bytes = [byte[]]::new(32)
        $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $random.GetBytes($bytes) } finally { $random.Dispose() }
        return [BitConverter]::ToString($bytes).Replace('-', '')
    }
    $databasePassword = New-RandomSecret
    $bootstrapPassword = New-RandomSecret
    $environmentLines = @(
        'POSTGRES_DB=vault'
        'POSTGRES_USER=vault'
        "POSTGRES_PASSWORD=$databasePassword"
        'VAULT_BOOTSTRAP_EMAIL=owner@vault.local'
        "VAULT_BOOTSTRAP_PASSWORD=$bootstrapPassword"
        'VAULT_WEB_PORT=8080'
        'VAULT_BIND_ADDRESS=127.0.0.1'
        'VAULT_LAN_ORIGIN='
    )
    [System.IO.File]::WriteAllLines($envPath, $environmentLines, [System.Text.UTF8Encoding]::new($false))
    Write-Host "Created local credentials in $envPath. Keep that ignored file private."
} else {
    Write-Host "Using existing $envPath; existing local credentials were left unchanged."
}

Write-Host 'Local HTTPS certificate is trusted and exported. Run docker compose up --build --wait.'
