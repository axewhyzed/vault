[CmdletBinding()]
param(
    [string]$Password = 'mihir0234',
    [string]$Alias = 'vault',
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

if (-not $OutputPath) {
    $localDir = Join-Path $repoRoot '.local'
    if (-not (Test-Path $localDir)) { New-Item -ItemType Directory -Path $localDir -Force | Out-Null }
    $OutputPath = Join-Path $localDir 'vault-release.jks'
}

$keytool = 'keytool'
$jbrKeytool = 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
if (Test-Path $jbrKeytool) {
    $keytool = $jbrKeytool
}

if (-not $Password -or $Password.Length -lt 6) {
    throw 'Password must be at least 6 characters long.'
}

if (Test-Path $OutputPath) {
    Remove-Item $OutputPath -Force
}

Write-Host "Generating keystore at $OutputPath..." -ForegroundColor Yellow
$dname = "CN=Vault, OU=Mobile, O=Vault, L=Unknown, ST=Unknown, C=US"
& $keytool -genkeypair -v `
    -keystore $OutputPath `
    -alias $Alias `
    -keyalg RSA `
    -keysize 2048 `
    -validity 10000 `
    -storepass $Password `
    -keypass $Password `
    -dname $dname

if ($LASTEXITCODE -ne 0) {
    throw "Failed to generate keystore with keytool."
}

$bytes = [System.IO.File]::ReadAllBytes($OutputPath)
$base64 = [Convert]::ToBase64String($bytes)

# Save base64 to .local/vault-release.base64.txt for easy copy
$base64Path = Join-Path (Split-Path $OutputPath) 'vault-release.base64.txt'
[System.IO.File]::WriteAllText($base64Path, $base64)

try {
    Set-Clipboard -Value $base64
    $clipboardCopied = $true
} catch {
    $clipboardCopied = $false
}

Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host " SUCCESS! Release keystore generated successfully." -ForegroundColor Green
Write-Host " File location: $OutputPath" -ForegroundColor Green
Write-Host " Key alias:     $Alias" -ForegroundColor Green
Write-Host " Password:      $Password" -ForegroundColor Green
Write-Host " Base64 file:   $base64Path" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
Write-Host ""
if ($clipboardCopied) {
    Write-Host "[OK] VAULT_KEYSTORE_BASE64 has been copied to your clipboard!" -ForegroundColor Cyan
} else {
    Write-Host "Base64 string saved to: $base64Path" -ForegroundColor Cyan
}
