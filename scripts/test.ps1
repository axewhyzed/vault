[CmdletBinding()]
param(
    [int] $StartupTimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$projectName = 'vault-slice3-test-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$testWebPort = $listener.LocalEndpoint.Port
$listener.Stop()
$baseUrl = "https://localhost:$testWebPort"
$testEmail = 'slice3-test@example.invalid'
$testUsername = 'vault-test-owner'
$initialTestPassword = 'VaultInitialPassword_2026!'
$testPassword = 'VaultUpdatedPassword_2026!'
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("vault-slice3-" + [guid]::NewGuid().ToString('N'))
$testTlsRoot = Join-Path $temporaryRoot 'tls'
$environmentNames = @(
    'VAULT_WEB_PORT', 'VAULT_BIND_ADDRESS', 'VAULT_LAN_ORIGIN', 'VAULT_TLS_DIRECTORY',
    'VAULT_NETWORK_SUBNET', 'VAULT_PROXY_IP', 'VAULT_API_IP',
    'POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD',
    'VAULT_BOOTSTRAP_EMAIL', 'VAULT_BOOTSTRAP_USERNAME', 'VAULT_BOOTSTRAP_PASSWORD'
)
$previousEnvironment = @{}
foreach ($name in $environmentNames) { $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

function Invoke-Curl([string[]] $CurlArguments, [string] $BodyPath) {
    $statusText = & curl.exe --silent --show-error --output $BodyPath --write-out '%{http_code}' --max-time 15 @CurlArguments
    if ($LASTEXITCODE -ne 0) { throw "curl failed ($LASTEXITCODE): $($CurlArguments -join ' ')" }
    return [int](($statusText -join '').Trim())
}

function Assert-Status([int] $Actual, [int] $Expected, [string] $Context, [string] $BodyPath) {
    if ($Actual -ne $Expected) {
        $body = if (Test-Path -LiteralPath $BodyPath) { Get-Content -Raw -LiteralPath $BodyPath } else { '' }
        throw "$Context returned HTTP $Actual, expected $Expected. Body: $body"
    }
}

function Invoke-ItemApi([string] $Method, [string] $Path, [string] $Payload, [string] $Key) {
    $csrfBody = Join-Path $temporaryRoot 'items-csrf.json'
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, '--cookie-jar', $jarPath, "$baseUrl/api/v1/auth/csrf") $csrfBody
    Assert-Status $status 200 'Item request CSRF token' $csrfBody
    $csrfResponse = Get-Content -Raw -LiteralPath $csrfBody | ConvertFrom-Json
    $requestBody = Join-Path $temporaryRoot 'items-request.json'
    [System.IO.File]::WriteAllText($requestBody, $Payload, [System.Text.UTF8Encoding]::new($false))
    $args = @('--cacert', $certPath, '--cookie', $jarPath, '--header', "Origin: $origin", '--header', "$($csrfResponse.headerName): $($csrfResponse.token)", '--header', 'Content-Type: application/json', '--request', $Method)
    if ($Method -eq 'POST') { $args += @('--header', "Idempotency-Key: $Key", '--data-binary', "@$requestBody") }
    $args += "$baseUrl$Path"
    return Invoke-Curl $args $bodyPath
}

function Invoke-FileApi([string] $Method, [string] $Path, [string] $Key, [string] $ContentType, [string] $PayloadPath) {
    $csrfBody = Join-Path $temporaryRoot 'file-csrf.json'
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, '--cookie-jar', $jarPath, "$baseUrl/api/v1/auth/csrf") $csrfBody
    Assert-Status $status 200 'File request CSRF token' $csrfBody
    $csrfResponse = Get-Content -Raw -LiteralPath $csrfBody | ConvertFrom-Json
    $args = @('--cacert', $certPath, '--cookie', $jarPath, '--header', "Origin: $origin", '--header', "$($csrfResponse.headerName): $($csrfResponse.token)", '--header', "Content-Type: $ContentType", '--header', "Idempotency-Key: $Key", '--request', $Method, '--data-binary', "@$PayloadPath", "$baseUrl$Path")
    return Invoke-Curl $args $bodyPath
}

function Invoke-DockerCompose([string[]] $ComposeArguments) {
    & docker compose -p $projectName -f compose.yaml -f compose.test.yaml @ComposeArguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($ComposeArguments -join ' ')" }
}

function Invoke-TestPsql([string] $Sql, [switch] $TuplesOnly, [switch] $OnErrorStop) {
    $psqlArgs = @()
    if ($TuplesOnly) { $psqlArgs += '-At' }
    if ($OnErrorStop) { $psqlArgs += '-v'; $psqlArgs += 'ON_ERROR_STOP=1' }
    $output = $Sql | & docker compose -p $projectName -f compose.yaml -f compose.test.yaml exec -T postgres psql -U vault_test -d vault_test @psqlArgs
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL smoke query failed: $Sql" }
    return $output
}

function ConvertTo-IPv4Range([string] $Cidr) {
    $parts = $Cidr.Split('/')
    $prefix = [int]$parts[1]
    if ($prefix -lt 1 -or $prefix -gt 32) { return $null }
    $octets = $parts[0].Split('.') | ForEach-Object { [long]$_ }
    if ($octets.Count -ne 4) { return $null }
    $address = (($octets[0] * 256 + $octets[1]) * 256 + $octets[2]) * 256 + $octets[3]
    $hostBits = 32 - $prefix
    $blockSize = [long][math]::Pow(2, $hostBits)
    $start = [long][math]::Floor($address / $blockSize) * $blockSize
    return [pscustomobject]@{ Start = $start; End = $start + $blockSize - 1 }
}

function Get-AvailableTestSubnet {
    $existing = @()
    foreach ($networkId in (& docker network ls -q)) {
        $subnets = & docker network inspect --format '{{range .IPAM.Config}}{{println .Subnet}}{{end}}' $networkId
        foreach ($subnet in $subnets) {
            if ($subnet -match '^\d+\.\d+\.\d+\.\d+/\d+$') {
                $range = ConvertTo-IPv4Range $subnet
                if ($range) { $existing += $range }
            }
        }
    }
    for ($attempt = 0; $attempt -lt 100; $attempt++) {
        $octet2 = Get-Random -Minimum 16 -Maximum 32
        $octet3 = Get-Random -Minimum 0 -Maximum 256
        $candidateCidr = "172.$octet2.$octet3.0/24"
        $candidate = ConvertTo-IPv4Range $candidateCidr
        if (-not ($existing | Where-Object { $candidate.Start -le $_.End -and $candidate.End -ge $_.Start })) { return $candidateCidr }
    }
    throw 'Could not find an unused private Docker subnet after 100 attempts.'
}

$cleanupNeeded = $false
Push-Location $repoRoot
try {
    docker info --format '{{.ServerVersion}}' *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Engine is unavailable. Start Docker Desktop, wait for its engine to finish starting, then run this script again.'
    }

    $env:VAULT_TLS_DIRECTORY = $testTlsRoot
    $env:VAULT_BIND_ADDRESS = '127.0.0.1'
    $env:VAULT_LAN_ORIGIN = ''
    & ./scripts/setup-local.ps1 -TlsDirectory $testTlsRoot
    if ($LASTEXITCODE -ne 0) { throw 'Local HTTPS certificate setup failed.' }

    $env:VAULT_WEB_PORT = "$testWebPort"
    # Pick a private /24 per run and compare it with configured Docker network ranges.
    $env:VAULT_NETWORK_SUBNET = Get-AvailableTestSubnet
    $subnetParts = $env:VAULT_NETWORK_SUBNET.Split('.')
    $env:VAULT_PROXY_IP = "$($subnetParts[0]).$($subnetParts[1]).$($subnetParts[2]).10"
    $env:VAULT_API_IP = "$($subnetParts[0]).$($subnetParts[1]).$($subnetParts[2]).11"
    $env:POSTGRES_DB = 'vault_test'
    $env:POSTGRES_USER = 'vault_test'
    $env:POSTGRES_PASSWORD = 'slice2-test-database-password'
    $env:VAULT_BOOTSTRAP_EMAIL = $testEmail
    $env:VAULT_BOOTSTRAP_USERNAME = $testUsername
    $env:VAULT_BOOTSTRAP_PASSWORD = $initialTestPassword

    New-Item -ItemType Directory -Force -Path $temporaryRoot | Out-Null
    $cleanupNeeded = $true
    Invoke-DockerCompose @('up', '--detach', '--wait', '--wait-timeout', "$StartupTimeoutSeconds", 'postgres')
    Invoke-DockerCompose @('up', '--detach', 'dpkeys-init')
    Invoke-DockerCompose @('run', '--build', '--rm', '--no-deps', '-e', 'Database__ApplyMigrations=true', '-e', 'Database__MigrationOnly=true', 'api')
    Invoke-DockerCompose @('run', '--rm', '--no-deps', '-e', "Bootstrap__Password=$testPassword", 'api', '--update-bootstrap-credentials')
    $env:VAULT_BOOTSTRAP_PASSWORD = $testPassword
    Invoke-DockerCompose @('up', '--build', '--detach', '--wait', '--wait-timeout', "$StartupTimeoutSeconds", 'api', 'web')

    $migrationCount = & docker compose -p $projectName -f compose.yaml -f compose.test.yaml exec -T postgres psql -U vault_test -d vault_test -Atc "SELECT COUNT(*) FROM pg_tables WHERE schemaname = 'public' AND tablename IN ('__EFMigrationsHistory', 'users', 'devices', 'audit_events');"
    if ($LASTEXITCODE -ne 0 -or [int]($migrationCount -join '').Trim() -ne 4) {
        throw 'A fresh PostgreSQL database did not receive the expected EF Core migrations.'
    }

    $transferTables = & docker compose -p $projectName -f compose.yaml -f compose.test.yaml exec -T postgres psql -U vault_test -d vault_test -Atc "SELECT COUNT(*) FROM pg_tables WHERE schemaname = 'public' AND tablename IN ('relay_items', 'item_idempotency_records', 'upload_sessions');"
    if ($LASTEXITCODE -ne 0 -or [int]($transferTables -join '').Trim() -ne 3) {
        throw 'A fresh PostgreSQL database is missing Slice 3/5 transfer tables.'
    }

    $deviceAuthTables = & docker compose -p $projectName -f compose.yaml -f compose.test.yaml exec -T postgres psql -U vault_test -d vault_test -Atc "SELECT COUNT(*) FROM pg_tables WHERE schemaname = 'public' AND tablename IN ('android_sessions', 'device_credentials', 'device_pairing_sessions');"
    if ($LASTEXITCODE -ne 0 -or [int]($deviceAuthTables -join '').Trim() -ne 3) {
        throw 'A fresh PostgreSQL database is missing Slice 6 Android session and pairing tables.'
    }

    $bootstrapCount = & docker compose -p $projectName -f compose.yaml -f compose.test.yaml exec -T postgres psql -U vault_test -d vault_test -Atc 'SELECT COUNT(*) FROM users WHERE is_bootstrap = TRUE;'
    if ($LASTEXITCODE -ne 0 -or [int]($bootstrapCount -join '').Trim() -ne 1) {
        throw 'A fresh database must contain exactly one bootstrap account.'
    }

    $certPath = Join-Path $testTlsRoot 'dev-cert.pem'
    $jarPath = Join-Path $temporaryRoot 'cookies.txt'
    $bodyPath = Join-Path $temporaryRoot 'response.json'
    $headersPath = Join-Path $temporaryRoot 'headers.txt'
    $csrfUrl = "$baseUrl/api/v1/auth/csrf"
    $origin = $baseUrl

    $status = Invoke-Curl @('--cacert', $certPath, '--cookie-jar', $jarPath, '--dump-header', $headersPath, $csrfUrl) $bodyPath
    Assert-Status $status 200 'CSRF bootstrap' $bodyPath
    $csrf = Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json
    if (-not $csrf.token -or $csrf.headerName -ne 'X-CSRF-TOKEN') { throw 'CSRF endpoint returned an invalid token contract.' }

    $loginBody = @{ identifier = $testUsername; password = $testPassword } | ConvertTo-Json -Compress
    $loginPath = Join-Path $temporaryRoot 'login.json'
    [System.IO.File]::WriteAllText($loginPath, $loginBody, [System.Text.UTF8Encoding]::new($false))
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, '--cookie-jar', $jarPath, '--dump-header', $headersPath, '--header', "Origin: $origin", '--header', "$($csrf.headerName): $($csrf.token)", '--header', 'Content-Type: application/json', '--data-binary', "@$loginPath", "$baseUrl/api/v1/auth/login") $bodyPath
    Assert-Status $status 200 'Login through HTTPS proxy' $bodyPath
    $login = Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json
    if (-not $login.userId -or -not $login.deviceId -or $login.email -ne $testEmail) { throw 'Login returned an invalid session identity.' }
    $cookieHeaders = Get-Content -Raw -LiteralPath $headersPath
    $authCookieHeader = [regex]::Match($cookieHeaders, '(?im)^Set-Cookie:\s*__Host-Vault\.Auth=[^\r\n]+').Value
    if (-not $authCookieHeader -or $authCookieHeader -notmatch '(?i);\s*path=/' -or
        $authCookieHeader -notmatch '(?i);\s*secure(?:;|\s|$)' -or
        $authCookieHeader -notmatch '(?i);\s*httponly(?:;|\s|$)' -or
        $authCookieHeader -notmatch '(?i);\s*samesite=strict(?:;|\s|$)') {
        throw 'Authentication cookie is missing required Path=/, Secure, HttpOnly, or SameSite=Strict attributes.'
    }

    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, "$baseUrl/api/v1/auth/me") $bodyPath
    Assert-Status $status 200 'Authenticated /me' $bodyPath
    $me = Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json
    if ($me.userId -ne $login.userId -or $me.deviceId -ne $login.deviceId) { throw '/me did not resolve the same user and web device.' }

    $syntheticText = 'Slice3-smoke-' + [guid]::NewGuid().ToString('N')
    $itemKey = [guid]::NewGuid().ToString('D')
    $payload = @{ text = $syntheticText; displayName = 'Synthetic PostgreSQL smoke'; retentionDays = 2 } | ConvertTo-Json -Compress
    $status = Invoke-ItemApi 'POST' '/api/v1/items/text' $payload $itemKey
    Assert-Status $status 201 'Create text item in fresh PostgreSQL database' $bodyPath
    $createdItem = Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json
    if (-not $createdItem.id -or $createdItem.type -ne 'text' -or $createdItem.version -ne 1 -or $createdItem.text) { throw 'Create response must contain metadata only.' }
    $itemId = $createdItem.id

    $status = Invoke-ItemApi 'POST' '/api/v1/items/text' $payload $itemKey
    Assert-Status $status 201 'Idempotent text replay' $bodyPath
    $replayedItem = Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json
    if ($replayedItem.id -ne $itemId -or $replayedItem.text) { throw 'Idempotent replay must return only the original item metadata.' }

    $conflictingPayload = @{ text = 'A different synthetic request'; displayName = 'Synthetic PostgreSQL smoke'; retentionDays = 2 } | ConvertTo-Json -Compress
    $status = Invoke-ItemApi 'POST' '/api/v1/items/text' $conflictingPayload $itemKey
    Assert-Status $status 409 'Idempotency key conflict' $bodyPath

    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, "$baseUrl/api/v1/items") $bodyPath
    Assert-Status $status 200 'List items' $bodyPath
    $list = Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json
    if (-not ($list.items | Where-Object { $_.id -eq $itemId })) { throw 'List did not return the created text item.' }
    if ($list.quota.activeItems -ne 1 -or $list.quota.activeBytes -ne [System.Text.Encoding]::UTF8.GetByteCount($syntheticText)) { throw 'List quota did not match the active synthetic item.' }

    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, "$baseUrl/api/v1/items/$itemId") $bodyPath
    Assert-Status $status 200 'Read item detail' $bodyPath
    $detail = Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json
    if ($detail.text -ne $syntheticText) { throw 'Item detail did not return the stored synthetic text.' }

    $urlPayload = @{ url = '  https://example.invalid/slice3-smoke  ' } | ConvertTo-Json -Compress
    $urlKey = [guid]::NewGuid().ToString('D')
    $status = Invoke-ItemApi 'POST' '/api/v1/items/url' $urlPayload $urlKey
    Assert-Status $status 201 'Create normalized URL item' $bodyPath
    $urlItem = Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, "$baseUrl/api/v1/items/$($urlItem.id)") $bodyPath
    Assert-Status $status 200 'Read URL item detail' $bodyPath
    if ((Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json).text -ne 'https://example.invalid/slice3-smoke') { throw 'URL surrounding whitespace was not normalized.' }

    $fileBytes = [System.Text.Encoding]::UTF8.GetBytes('Synthetic Slice5 attachment payload')
    $filePath = Join-Path $temporaryRoot 'synthetic-upload.bin'
    $downloadPath = Join-Path $temporaryRoot 'synthetic-download.bin'
    [System.IO.File]::WriteAllBytes($filePath, $fileBytes)
    $fileHash = (Get-FileHash -LiteralPath $filePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $fileKey = [guid]::NewGuid().ToString('D')
    $initPayload = @{ displayName = 'synthetic.bin'; contentType = 'application/octet-stream'; sizeBytes = $fileBytes.Length; sha256 = $fileHash; retentionDays = 2 } | ConvertTo-Json -Compress
    $initPath = Join-Path $temporaryRoot 'upload-initiate.json'
    [System.IO.File]::WriteAllText($initPath, $initPayload, [System.Text.UTF8Encoding]::new($false))
    $status = Invoke-FileApi 'POST' '/api/v1/uploads/initiate' $fileKey 'application/json' $initPath
    Assert-Status $status 201 'Initiate synthetic file upload' $bodyPath
    $upload = Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json
    if (-not $upload.uploadId -or $upload.maxAcceptedBytes -ne 100000000) { throw 'Upload initiation returned an invalid contract.' }
    $status = Invoke-FileApi 'PUT' $upload.uploadUrl $fileKey 'application/octet-stream' $filePath
    Assert-Status $status 200 'Stream synthetic file bytes' $bodyPath
    $stagedFile = Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json
    if ($stagedFile.state -ne 'staged' -or $stagedFile.sha256 -ne $fileHash) { throw 'Staged file SHA-256 did not match the synthetic source.' }
    $emptyJsonPath = Join-Path $temporaryRoot 'upload-complete.json'
    [System.IO.File]::WriteAllText($emptyJsonPath, '{}', [System.Text.UTF8Encoding]::new($false))
    $completePath = "/api/v1/uploads/$($upload.uploadId)/complete"
    $status = Invoke-FileApi 'POST' $completePath $fileKey 'application/json' $emptyJsonPath
    Assert-Status $status 201 'Complete synthetic file upload' $bodyPath
    $fileItem = Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json
    if ($fileItem.type -ne 'file' -or -not $fileItem.id) { throw 'Completed upload did not return file item metadata.' }
    $status = Invoke-FileApi 'POST' $completePath $fileKey 'application/json' $emptyJsonPath
    Assert-Status $status 201 'Replay file completion' $bodyPath
    if ((Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json).id -ne $fileItem.id) { throw 'Completion replay returned a different item.' }
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, "$baseUrl/api/v1/items/$($fileItem.id)") $bodyPath
    Assert-Status $status 200 'Read synthetic file metadata' $bodyPath
    $fileDetail = Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json
    if ($null -ne $fileDetail.text -or $fileDetail.sha256 -ne $fileHash -or $fileDetail.downloadUrl -ne "/api/v1/items/$($fileItem.id)/download") { throw 'File detail did not match the safe attachment contract.' }
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, '--dump-header', $headersPath, "$baseUrl$($fileDetail.downloadUrl)") $downloadPath
    Assert-Status $status 200 'Download synthetic file attachment' $downloadPath
    if ((Get-FileHash -LiteralPath $downloadPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $fileHash) { throw 'Downloaded synthetic bytes did not match the uploaded file.' }
    $downloadHeaders = Get-Content -Raw -LiteralPath $headersPath
    if ($downloadHeaders -notmatch '(?im)^Content-Disposition:\s*attachment' -or $downloadHeaders -notmatch '(?im)^X-Content-Type-Options:\s*nosniff' -or $downloadHeaders -notmatch '(?im)^Cache-Control:\s*no-store') { throw 'Download response is missing attachment, nosniff, or no-store headers.' }
    $status = Invoke-ItemApi 'DELETE' "/api/v1/items/$($fileItem.id)" '' ''
    Assert-Status $status 204 'Delete downloaded synthetic file' $bodyPath
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, "$baseUrl/api/v1/items/$($fileItem.id)/download") $bodyPath
    Assert-Status $status 404 'File download after delete' $bodyPath

    $status = Invoke-ItemApi 'DELETE' "/api/v1/items/$itemId" '' ''
    Assert-Status $status 204 'Delete item' $bodyPath
    $status = Invoke-ItemApi 'DELETE' "/api/v1/items/$itemId" '' ''
    Assert-Status $status 204 'Repeat item delete' $bodyPath
    $expiredId = $urlItem.id
    $expireSql = 'UPDATE relay_items SET "ExpiresAt" = NOW() WHERE "Id" = ''{0}'';' -f $expiredId
    $null = Invoke-TestPsql $expireSql
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, "$baseUrl/api/v1/items/$expiredId") $bodyPath
    Assert-Status $status 410 'Expired item detail' $bodyPath
    if ((Get-Content -Raw -LiteralPath $bodyPath) -match 'https://example.invalid') { throw 'Expired content appeared in the error response.' }

    $auditDump = & docker compose -p $projectName -f compose.yaml -f compose.test.yaml exec -T postgres psql -U vault_test -d vault_test -Atc "SELECT row_to_json(a)::text FROM audit_events a;"
    if ($LASTEXITCODE -ne 0 -or ($auditDump -join '') -match [regex]::Escape($syntheticText)) { throw 'Audit rows must not contain item text.' }

    $parallelCsrfPath = Join-Path $temporaryRoot 'parallel-csrf.json'
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, '--cookie-jar', $jarPath, $csrfUrl) $parallelCsrfPath
    Assert-Status $status 200 'Concurrent create CSRF token' $parallelCsrfPath
    $parallelCsrf = Get-Content -Raw -LiteralPath $parallelCsrfPath | ConvertFrom-Json
    $parallelKey = [guid]::NewGuid().ToString('D')
    $parallelPayload = @{ text = 'Synthetic concurrent idempotency smoke'; displayName = 'Concurrent smoke' } | ConvertTo-Json -Compress
    $parallelRequest = Join-Path $temporaryRoot 'parallel-request.json'
    [System.IO.File]::WriteAllText($parallelRequest, $parallelPayload, [System.Text.UTF8Encoding]::new($false))
    $parallelJobs = @()
    for ($i = 0; $i -lt 2; $i++) {
        $parallelResponse = Join-Path $temporaryRoot "parallel-response-$i.json"
        $parallelJobs += Start-Job -ArgumentList $certPath, $jarPath, $origin, $parallelCsrf.headerName, $parallelCsrf.token, $parallelKey, $parallelRequest, $parallelResponse, "$baseUrl/api/v1/items/text" -ScriptBlock {
            param($cert, $cookies, $requestOrigin, $csrfHeader, $csrfValue, $idempotencyKey, $requestFile, $responseFile, $url)
            $statusText = & curl.exe --silent --show-error --output $responseFile --write-out '%{http_code}' --max-time 30 --cacert $cert --cookie $cookies --header "Origin: $requestOrigin" --header "$csrfHeader`: $csrfValue" --header 'Content-Type: application/json' --header "Idempotency-Key: $idempotencyKey" --data-binary "@$requestFile" $url
            if ($LASTEXITCODE -ne 0) { throw "Concurrent curl failed with exit code $LASTEXITCODE" }
            [pscustomobject]@{ Status = [int]$statusText; Body = Get-Content -Raw -LiteralPath $responseFile }
        }
    }
    try {
        $null = Wait-Job -Job $parallelJobs -Timeout 45
        if ($parallelJobs | Where-Object State -eq 'Running') { throw 'Concurrent PostgreSQL same-key requests timed out.' }
        $parallelResults = @(Receive-Job -Job $parallelJobs -ErrorAction Stop)
    } finally { Remove-Job -Job $parallelJobs -Force -ErrorAction SilentlyContinue }
    if ($parallelResults.Count -ne 2 -or $parallelResults[0].Status -ne 201 -or $parallelResults[1].Status -ne 201) { throw "Concurrent same-key creates did not both return success metadata: $($parallelResults | ConvertTo-Json -Compress)" }
    $parallelIds = @($parallelResults | ForEach-Object { (ConvertFrom-Json $_.Body).id } | Select-Object -Unique)
    if ($parallelIds.Count -ne 1) { throw 'Concurrent requests with one key returned different item IDs.' }
    $parallelId = $parallelIds[0]
    $auditCountSql = 'SELECT COUNT(*) FROM audit_events WHERE "EventType" = ''item_created'' AND "ItemId" = ''{0}'';' -f $parallelId
    $auditCount = Invoke-TestPsql $auditCountSql -TuplesOnly
    if ([int]($auditCount -join '').Trim() -ne 1) { throw 'Concurrent replay must write exactly one item_created audit event.' }

    $raceText = 'Synthetic concurrent detail delete race'
    $racePayload = @{ text = $raceText } | ConvertTo-Json -Compress
    $status = Invoke-ItemApi 'POST' '/api/v1/items/text' $racePayload ([guid]::NewGuid().ToString('D'))
    Assert-Status $status 201 'Create item for detail/delete race' $bodyPath
    $raceId = (Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json).id
    $raceCsrfPath = Join-Path $temporaryRoot 'detail-delete-csrf.json'
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, '--cookie-jar', $jarPath, $csrfUrl) $raceCsrfPath
    Assert-Status $status 200 'Detail/delete race CSRF token' $raceCsrfPath
    $raceCsrf = Get-Content -Raw -LiteralPath $raceCsrfPath | ConvertFrom-Json
    $raceJobs = @()
    $raceGetResponse = Join-Path $temporaryRoot 'detail-race-get.json'
    $raceJobs += Start-Job -ArgumentList $certPath, $jarPath, "$baseUrl/api/v1/items/$raceId", $raceGetResponse -ScriptBlock {
        param($cert, $cookies, $url, $responseFile)
        $statusText = & curl.exe --silent --show-error --output $responseFile --write-out '%{http_code}' --max-time 30 --cacert $cert --cookie $cookies $url
        if ($LASTEXITCODE -ne 0) { throw "Concurrent detail curl failed with exit code $LASTEXITCODE" }
        [pscustomobject]@{ Method = 'GET'; Status = [int]$statusText; Body = Get-Content -Raw -LiteralPath $responseFile }
    }
    $raceDeleteResponse = Join-Path $temporaryRoot 'detail-race-delete.json'
    $raceJobs += Start-Job -ArgumentList $certPath, $jarPath, $origin, $raceCsrf.headerName, $raceCsrf.token, "$baseUrl/api/v1/items/$raceId", $raceDeleteResponse -ScriptBlock {
        param($cert, $cookies, $requestOrigin, $csrfHeader, $csrfValue, $url, $responseFile)
        $statusText = & curl.exe --silent --show-error --output $responseFile --write-out '%{http_code}' --max-time 30 --cacert $cert --cookie $cookies --header "Origin: $requestOrigin" --header "$csrfHeader`: $csrfValue" --request DELETE $url
        if ($LASTEXITCODE -ne 0) { throw "Concurrent delete curl failed with exit code $LASTEXITCODE" }
        [pscustomobject]@{ Method = 'DELETE'; Status = [int]$statusText; Body = Get-Content -Raw -LiteralPath $responseFile }
    }
    try {
        $null = Wait-Job -Job $raceJobs -Timeout 45
        if ($raceJobs | Where-Object State -eq 'Running') { throw 'Concurrent item detail/delete requests timed out.' }
        $raceResults = @(Receive-Job -Job $raceJobs -ErrorAction Stop)
    } finally { Remove-Job -Job $raceJobs -Force -ErrorAction SilentlyContinue }
    $raceRead = $raceResults | Where-Object Method -eq 'GET' | Select-Object -First 1
    $raceDelete = $raceResults | Where-Object Method -eq 'DELETE' | Select-Object -First 1
    if ($raceRead.Status -notin @(200, 404) -or $raceDelete.Status -ne 204) { throw "Detail/delete race returned an unexpected status: $($raceResults | ConvertTo-Json -Compress)" }
    if ($raceRead.Status -eq 200 -and (ConvertFrom-Json $raceRead.Body).text -ne $raceText) { throw 'A successful racing detail response did not match the item read before deletion.' }
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, "$baseUrl/api/v1/items/$raceId") $bodyPath
    Assert-Status $status 404 'Detail after committed delete race' $bodyPath
    if ((Get-Content -Raw -LiteralPath $bodyPath) -match [regex]::Escape($raceText)) { throw 'Detail after committed delete returned content.' }

    $expireBeforeCursorSql = 'UPDATE relay_items SET "ExpiresAt" = NOW() - INTERVAL ''1 day'' WHERE "OwnerUserId" = ''{0}'' AND "State" = ''available'';' -f $login.userId
    $null = Invoke-TestPsql $expireBeforeCursorSql
    $tieIds = @()
    for ($i = 0; $i -lt 5; $i++) {
        $tiePayload = @{ text = "Synthetic cursor tie $i" } | ConvertTo-Json -Compress
        $status = Invoke-ItemApi 'POST' '/api/v1/items/text' $tiePayload ([guid]::NewGuid().ToString('D'))
        Assert-Status $status 201 "Create cursor tie item $i" $bodyPath
        $tieIds += (Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json).id
    }
    $idSql = ($tieIds | ForEach-Object { "'$_'" }) -join ', '
    $tieSql = 'UPDATE relay_items SET "CreatedAt" = TIMESTAMPTZ ''2999-10-12 04:45:00+00'' WHERE "Id" IN ({0});' -f $idSql
    $null = Invoke-TestPsql $tieSql
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, "$baseUrl/api/v1/items?limit=2") $bodyPath
    Assert-Status $status 200 'First PostgreSQL cursor page for tied timestamps' $bodyPath
    $page1 = Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, "$baseUrl/api/v1/items?limit=2&cursor=$([uri]::EscapeDataString($page1.nextCursor))") $bodyPath
    Assert-Status $status 200 'Second PostgreSQL cursor page for tied timestamps' $bodyPath
    $page2 = Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, "$baseUrl/api/v1/items?limit=2&cursor=$([uri]::EscapeDataString($page2.nextCursor))") $bodyPath
    Assert-Status $status 200 'Third PostgreSQL cursor page for tied timestamps' $bodyPath
    $page3 = Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json
    $pagedIds = @($page1.items.id) + @($page2.items.id) + @($page3.items.id)
    if ($pagedIds.Count -ne 5 -or @($pagedIds | Select-Object -Unique).Count -ne 5 -or @($pagedIds | Where-Object { $_ -notin $tieIds }).Count -ne 0) { throw 'PostgreSQL cursor pagination skipped or repeated an item with tied timestamps.' }

    $expireAllSql = 'UPDATE relay_items SET "ExpiresAt" = NOW() WHERE "OwnerUserId" = ''{0}'' AND "State" = ''available'';' -f $login.userId
    $null = Invoke-TestPsql $expireAllSql
    $seedSql = @"
INSERT INTO relay_items ("Id", "OwnerUserId", "CreatedByDeviceId", "Type", "State", "DisplayName", "TextContent", "ContentType", "SizeBytes", "CreatedAt", "AvailableAt", "ExpiresAt", "UpdatedAt", "Version")
SELECT gen_random_uuid(), '$($login.userId)', '$($login.deviceId)', 'text', 'available', 'Synthetic quota seed', 'x', 'text/plain', 1, NOW(), NOW(), NOW() + INTERVAL '7 days', NOW(), 1
FROM generate_series(1, 9999);
"@
    $null = Invoke-TestPsql $seedSql -OnErrorStop
    $quotaCsrfPath = Join-Path $temporaryRoot 'quota-csrf.json'
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, '--cookie-jar', $jarPath, $csrfUrl) $quotaCsrfPath
    Assert-Status $status 200 'Quota-race CSRF token' $quotaCsrfPath
    $quotaCsrf = Get-Content -Raw -LiteralPath $quotaCsrfPath | ConvertFrom-Json
    $quotaBody = Join-Path $temporaryRoot 'quota-race-request.json'
    [System.IO.File]::WriteAllText($quotaBody, (@{ text = 'Synthetic quota race' } | ConvertTo-Json -Compress), [System.Text.UTF8Encoding]::new($false))
    $quotaJobs = @()
    for ($i = 0; $i -lt 2; $i++) {
        $quotaResponse = Join-Path $temporaryRoot "quota-race-response-$i.json"
        $quotaKey = [guid]::NewGuid().ToString('D')
        $quotaJobs += Start-Job -ArgumentList $certPath, $jarPath, $origin, $quotaCsrf.headerName, $quotaCsrf.token, $quotaKey, $quotaBody, $quotaResponse, "$baseUrl/api/v1/items/text" -ScriptBlock {
            param($cert, $cookies, $requestOrigin, $csrfHeader, $csrfValue, $idempotencyKey, $requestFile, $responseFile, $url)
            $statusText = & curl.exe --silent --show-error --output $responseFile --write-out '%{http_code}' --max-time 30 --cacert $cert --cookie $cookies --header "Origin: $requestOrigin" --header "$csrfHeader`: $csrfValue" --header 'Content-Type: application/json' --header "Idempotency-Key: $idempotencyKey" --data-binary "@$requestFile" $url
            if ($LASTEXITCODE -ne 0) { throw "Concurrent quota curl failed with exit code $LASTEXITCODE" }
            [pscustomobject]@{ Status = [int]$statusText; Body = Get-Content -Raw -LiteralPath $responseFile }
        }
    }
    try {
        $null = Wait-Job -Job $quotaJobs -Timeout 45
        if ($quotaJobs | Where-Object State -eq 'Running') { throw 'Concurrent PostgreSQL quota requests timed out.' }
        $quotaResults = @(Receive-Job -Job $quotaJobs -ErrorAction Stop)
    } finally { Remove-Job -Job $quotaJobs -Force -ErrorAction SilentlyContinue }
    $createdCount = @($quotaResults | Where-Object Status -eq 201).Count
    $rejectedCount = @($quotaResults | Where-Object Status -eq 409).Count
    if ($createdCount -ne 1 -or $rejectedCount -ne 1) { throw "Concurrent quota boundary should yield one 201 and one 409: $($quotaResults | ConvertTo-Json -Compress)" }
    $quotaError = $quotaResults | Where-Object Status -eq 409 | Select-Object -First 1
    if ((ConvertFrom-Json $quotaError.Body).code -ne 'quota_exceeded') { throw 'The quota-race 409 must identify quota_exceeded.' }
    $activeCountSql = 'SELECT COUNT(*) FROM relay_items WHERE "OwnerUserId" = ''{0}'' AND "State" = ''available'' AND "ExpiresAt" > NOW();' -f $login.userId
    $activeCount = Invoke-TestPsql $activeCountSql -TuplesOnly
    if ([int]($activeCount -join '').Trim() -ne 10000) { throw 'PostgreSQL quota race exceeded or underfilled the 10,000 active-item boundary.' }

    # Pair an Android session, then race refresh against account-wide revocation on PostgreSQL.
    $pairCsrfPath = Join-Path $temporaryRoot 'pairing-csrf.json'
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, '--cookie-jar', $jarPath, $csrfUrl) $pairCsrfPath
    Assert-Status $status 200 'Pairing session CSRF token' $pairCsrfPath
    $pairCsrf = Get-Content -Raw -LiteralPath $pairCsrfPath | ConvertFrom-Json
    $pairingRequestPath = Join-Path $temporaryRoot 'pairing-session-request.json'
    [System.IO.File]::WriteAllText($pairingRequestPath, '{}', [System.Text.UTF8Encoding]::new($false))
    $pairingResponsePath = Join-Path $temporaryRoot 'pairing-session-response.json'
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, '--cookie-jar', $jarPath, '--header', "Origin: $origin", '--header', "$($pairCsrf.headerName): $($pairCsrf.token)", '--header', 'Content-Type: application/json', '--data-binary', "@$pairingRequestPath", "$baseUrl/api/v1/devices/pairing-sessions") $pairingResponsePath
    Assert-Status $status 201 'Create Android pairing session' $pairingResponsePath
    $pairing = Get-Content -Raw -LiteralPath $pairingResponsePath | ConvertFrom-Json
    if ($pairing.serverOrigin -ne $origin -or $pairing.pairingSecret -notmatch '^[A-Za-z0-9_-]{43}$') { throw 'Pairing response did not use the configured origin or a 32-byte Base64url secret.' }

    $pairRequestPath = Join-Path $temporaryRoot 'pair-request.json'
    $pairResponsePath = Join-Path $temporaryRoot 'pair-response.json'
    $pairPayload = @{ pairingSessionId = $pairing.pairingSessionId; pairingSecret = $pairing.pairingSecret; deviceName = 'Slice 6 PostgreSQL test device' } | ConvertTo-Json -Compress
    [System.IO.File]::WriteAllText($pairRequestPath, $pairPayload, [System.Text.UTF8Encoding]::new($false))
    $pairKey = [guid]::NewGuid().ToString('D')
    $status = Invoke-Curl @('--cacert', $certPath, '--header', "Idempotency-Key: $pairKey", '--header', 'Content-Type: application/json', '--data-binary', "@$pairRequestPath", "$baseUrl/api/v1/devices/pair") $pairResponsePath
    Assert-Status $status 201 'Pair Android device against PostgreSQL' $pairResponsePath
    $androidTokens = Get-Content -Raw -LiteralPath $pairResponsePath | ConvertFrom-Json
    if (-not $androidTokens.accessToken.StartsWith('va1_') -or -not $androidTokens.refreshToken.StartsWith('vr1_')) { throw 'Pairing did not issue the expected token prefixes.' }

    $refreshRequestPath = Join-Path $temporaryRoot 'refresh-race-request.json'
    $refreshResponsePath = Join-Path $temporaryRoot 'refresh-race-response.json'
    $revokeRequestPath = Join-Path $temporaryRoot 'revoke-race-request.json'
    $revokeResponsePath = Join-Path $temporaryRoot 'revoke-race-response.json'
    [System.IO.File]::WriteAllText($refreshRequestPath, (@{ refreshToken = $androidTokens.refreshToken } | ConvertTo-Json -Compress), [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText($revokeRequestPath, '{}', [System.Text.UTF8Encoding]::new($false))
    $raceJobs = @()
    $raceJobs += Start-Job -ArgumentList $certPath, $refreshRequestPath, $refreshResponsePath, "$baseUrl/api/v1/auth/refresh" -ScriptBlock {
        param($cert, $requestFile, $responseFile, $url)
        $statusText = & curl.exe --silent --show-error --output $responseFile --write-out '%{http_code}' --max-time 30 --cacert $cert --header 'Content-Type: application/json' --data-binary "@$requestFile" $url
        if ($LASTEXITCODE -ne 0) { throw "Concurrent refresh curl failed with exit code $LASTEXITCODE" }
        [pscustomobject]@{ Method = 'refresh'; Status = [int]$statusText; ResponsePath = $responseFile }
    }
    $raceJobs += Start-Job -ArgumentList $certPath, $revokeRequestPath, $revokeResponsePath, $androidTokens.accessToken, "$baseUrl/api/v1/auth/sessions/revoke-all" -ScriptBlock {
        param($cert, $requestFile, $responseFile, $accessToken, $url)
        $statusText = & curl.exe --silent --show-error --output $responseFile --write-out '%{http_code}' --max-time 30 --cacert $cert --header "Authorization: Bearer $accessToken" --header 'Content-Type: application/json' --data-binary "@$requestFile" $url
        if ($LASTEXITCODE -ne 0) { throw "Concurrent revoke-all curl failed with exit code $LASTEXITCODE" }
        [pscustomobject]@{ Method = 'revoke-all'; Status = [int]$statusText; ResponsePath = $responseFile }
    }
    try {
        $null = Wait-Job -Job $raceJobs -Timeout 45
        if ($raceJobs | Where-Object State -eq 'Running') { throw 'Concurrent Android refresh/revoke-all requests timed out.' }
        $credentialRaceResults = @(Receive-Job -Job $raceJobs -ErrorAction Stop)
    } finally { Remove-Job -Job $raceJobs -Force -ErrorAction SilentlyContinue }
    $refreshRace = $credentialRaceResults | Where-Object Method -eq 'refresh' | Select-Object -First 1
    $revokeRace = $credentialRaceResults | Where-Object Method -eq 'revoke-all' | Select-Object -First 1
    if ($refreshRace.Status -notin @(200, 401) -or $revokeRace.Status -notin @(204, 401) -or @($credentialRaceResults | Where-Object { $_.Status -ge 500 }).Count -gt 0) {
        throw "Concurrent refresh/revoke-all returned unexpected statuses: $($credentialRaceResults | ConvertTo-Json -Compress)"
    }
    $issuedDuringRace = if ($refreshRace.Status -eq 200) { Get-Content -Raw -LiteralPath $refreshResponsePath | ConvertFrom-Json } else { $null }
    if ($revokeRace.Status -eq 401 -and $issuedDuringRace) {
        $status = Invoke-Curl @('--cacert', $certPath, '--header', "Authorization: Bearer $($issuedDuringRace.accessToken)", '--header', 'Content-Type: application/json', '--data-binary', "@$revokeRequestPath", "$baseUrl/api/v1/auth/sessions/revoke-all") $bodyPath
        Assert-Status $status 204 'Retry revoke-all with rotated Android access token' $bodyPath
    } elseif ($revokeRace.Status -ne 204) {
        throw 'Revoke-all was rejected without a successful concurrent refresh that supplied a replacement access token.'
    }
    foreach ($accessToken in @($androidTokens.accessToken, $issuedDuringRace.accessToken) | Where-Object { $_ }) {
        $status = Invoke-Curl @('--cacert', $certPath, '--header', "Authorization: Bearer $accessToken", "$baseUrl/api/v1/items") $bodyPath
        Assert-Status $status 401 'Android access token after refresh/revoke-all race' $bodyPath
    }
    foreach ($refreshToken in @($androidTokens.refreshToken, $issuedDuringRace.refreshToken) | Where-Object { $_ }) {
        $checkRefreshPath = Join-Path $temporaryRoot 'refresh-after-revoke-request.json'
        [System.IO.File]::WriteAllText($checkRefreshPath, (@{ refreshToken = $refreshToken } | ConvertTo-Json -Compress), [System.Text.UTF8Encoding]::new($false))
        $status = Invoke-Curl @('--cacert', $certPath, '--header', 'Content-Type: application/json', '--data-binary', "@$checkRefreshPath", "$baseUrl/api/v1/auth/refresh") $bodyPath
        Assert-Status $status 401 'Android refresh token after refresh/revoke-all race' $bodyPath
    }
    $activeCredentialSql = 'SELECT COUNT(*) FROM device_credentials c JOIN android_sessions s ON s."Id" = c."SessionId" WHERE s."UserId" = ''{0}'' AND (c."RevokedAt" IS NULL OR s."RevokedAt" IS NULL);' -f $login.userId
    $activeCredentialCount = Invoke-TestPsql $activeCredentialSql -TuplesOnly
    if ([int]($activeCredentialCount -join '').Trim() -ne 0) { throw 'Refresh/revoke-all race left an active Android credential or session in PostgreSQL.' }
    $activeDeviceSql = 'SELECT COUNT(*) FROM devices WHERE "UserId" = ''{0}'' AND "RevokedAt" IS NULL;' -f $login.userId
    $activeDeviceCount = Invoke-TestPsql $activeDeviceSql -TuplesOnly
    if ([int]($activeDeviceCount -join '').Trim() -ne 0) { throw 'Refresh/revoke-all race left a web or Android device active in PostgreSQL.' }
    # Revoke-all also closes the web cookie session, so sign in again before the logout smoke check.
    $reauthCsrfPath = Join-Path $temporaryRoot 'reauth-csrf.json'
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie-jar', $jarPath, $csrfUrl) $reauthCsrfPath
    Assert-Status $status 200 'CSRF token after account-wide revocation' $reauthCsrfPath
    $reauthCsrf = Get-Content -Raw -LiteralPath $reauthCsrfPath | ConvertFrom-Json
    $reauthBodyPath = Join-Path $temporaryRoot 'reauth-request.json'
    [System.IO.File]::WriteAllText($reauthBodyPath, (@{ identifier = $testUsername; password = $testPassword } | ConvertTo-Json -Compress), [System.Text.UTF8Encoding]::new($false))
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, '--cookie-jar', $jarPath, '--header', "Origin: $origin", '--header', "$($reauthCsrf.headerName): $($reauthCsrf.token)", '--header', 'Content-Type: application/json', '--data-binary', "@$reauthBodyPath", "$baseUrl/api/v1/auth/login") $bodyPath
    Assert-Status $status 200 'Web sign-in after account-wide revocation' $bodyPath

    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, '--cookie-jar', $jarPath, $csrfUrl) $bodyPath
    Assert-Status $status 200 'Authenticated CSRF refresh' $bodyPath
    $logoutCsrf = Get-Content -Raw -LiteralPath $bodyPath | ConvertFrom-Json
    if (-not $logoutCsrf.token -or $logoutCsrf.headerName -ne 'X-CSRF-TOKEN') { throw 'Authenticated CSRF refresh returned an invalid token contract.' }

    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, '--cookie-jar', $jarPath, '--header', "Origin: $origin", '--header', "$($logoutCsrf.headerName): $($logoutCsrf.token)", '--request', 'POST', "$baseUrl/api/v1/auth/logout") $bodyPath
    Assert-Status $status 204 'Logout' $bodyPath
    $status = Invoke-Curl @('--cacert', $certPath, '--cookie', $jarPath, "$baseUrl/api/v1/auth/me") $bodyPath
    Assert-Status $status 401 'Session after logout' $bodyPath

    dotnet test tests/api/Vault.Api.Tests/Vault.Api.Tests.csproj
    if ($LASTEXITCODE -ne 0) { throw 'API security test suite failed.' }

    Push-Location (Join-Path $repoRoot 'web')
    try {
        if (-not (Test-Path -LiteralPath 'node_modules')) { npm ci }
        if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' }
        npm run build
        if ($LASTEXITCODE -ne 0) { throw 'Web production build failed.' }
        npm run lint
        if ($LASTEXITCODE -ne 0) { throw 'Web lint failed.' }
    } finally { Pop-Location }

    Write-Host 'Identity and Slice 3/5/6 checks passed: Android pairing/bearer migrations; bootstrap and session security; PostgreSQL refresh/revoke-all concurrency; text, URL, and file flows; API tests; web build/lint.'
}
finally {
    if ($cleanupNeeded) {
        try { Invoke-DockerCompose @('down', '--volumes', '--remove-orphans') } catch { Write-Warning "Test stack cleanup reported: $_" }
        & docker image rm "$projectName-api" "$projectName-web" *> $null
    }
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
    if (Test-Path -LiteralPath $temporaryRoot) { Remove-Item -LiteralPath $temporaryRoot -Recurse -Force }
    Pop-Location
}
