param(
    [int]$UserCount = 200,
    [int]$StartIndex = 1,
    [string]$BaseUrl = 'http://127.0.0.1:8081',
    [string]$OutputPath = 'target/perf-results/interaction-users.csv',
    [switch]$Append,
    [switch]$LoginOnly
)

$ErrorActionPreference = 'Stop'
$password = 'PerfPass2026!'
$outputDirectory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

$rows = New-Object System.Collections.Generic.List[object]
if ($Append -and (Test-Path $OutputPath)) {
    foreach ($row in (Import-Csv $OutputPath)) {
        $rows.Add($row)
    }
}

for ($index = $StartIndex; $index -lt ($StartIndex + $UserCount); $index++) {
    # 13988 + six digits stays within the application's 11-digit mobile-number validation.
    $phone = '13988' + $index.ToString('000000')

    if (-not $LoginOnly) {
        # Registration is idempotent for this script: existing test users are simply logged in again.
        $sendCode = @{ identifier = $phone; scene = 'register' } | ConvertTo-Json -Compress
        $codeResponse = Invoke-RestMethod "$BaseUrl/api/v1/auth/send-code" -Method Post -ContentType 'application/json' -Body $sendCode
        $registerBody = @{
            phone = $phone
            password = $password
            confirmPassword = $password
            nickname = "Perf Interaction $index"
            smsCode = $codeResponse.data.code
        } | ConvertTo-Json -Compress

        try {
            Invoke-RestMethod "$BaseUrl/api/v1/auth/register" -Method Post -ContentType 'application/json' -Body $registerBody | Out-Null
        } catch {
            # The already-registered response is expected when rerunning the preparation step.
            if ($_.Exception.Response.StatusCode.value__ -ne 409) {
                throw
            }
        }
    }

    $loginBody = @{ identifier = $phone; password = $password; channel = 'jmeter' } | ConvertTo-Json -Compress
    $session = Invoke-RestMethod "$BaseUrl/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body $loginBody
    if ($session.code -ne 'OK' -or [string]::IsNullOrWhiteSpace($session.data.tokens.accessToken)) {
        throw "Could not obtain an access token for $phone"
    }
    $targetId = 900000000100000000 + (($index - 1) % 500) + 1
    $rows.Add([pscustomobject]@{
        phone = $phone
        user_id = $session.data.userId
        access_token = $session.data.tokens.accessToken
        target_id = $targetId
    })
}

# This file contains short-lived access tokens, so it belongs under target and is intentionally not committed.
$rows | Export-Csv -Path $OutputPath -NoTypeInformation -Encoding UTF8
Write-Host "Prepared $($rows.Count) interaction users: $OutputPath"
