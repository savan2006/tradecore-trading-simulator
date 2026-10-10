<#
.SYNOPSIS
Runs a live, read-only-first smoke check against an already running TradeCore backend.

.USAGE
  .\scripts\smoke-live.ps1
  .\scripts\smoke-live.ps1 -BaseUrl https://tradecore.example.com
  .\scripts\smoke-live.ps1 -AdminEmail admin@example.com -AdminPassword (Read-Host -AsSecureString)

The script creates a synthetic user, watchlist, and alert. It places one simulated
market BUY only when the persisted TCS quote is LIVE and the market session is OPEN.
It never connects to a real broker or handles real money.
#>
[CmdletBinding()]
param(
    [string] $BaseUrl = 'http://localhost:8080',
    [string] $AdminEmail,
    [System.Security.SecureString] $AdminPassword
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$script:Results = [System.Collections.Generic.List[object]]::new()

function Add-Result {
    param([string] $Name, [ValidateSet('PASS', 'FAIL', 'SKIPPED')][string] $Status, [string] $Detail)
    $script:Results.Add([pscustomobject]@{ Check = $Name; Status = $Status; Detail = $Detail })
}

function Get-HttpStatusCode {
    param([System.Management.Automation.ErrorRecord] $ErrorRecord)
    try { return [int]$ErrorRecord.Exception.Response.StatusCode } catch { return 0 }
}

function Invoke-Api {
    param(
        [ValidateSet('GET', 'POST')][string] $Method,
        [string] $Path,
        [hashtable] $Headers,
        [object] $Body
    )
    $request = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        Headers = $Headers
        ErrorAction = 'Stop'
    }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json'
        $request.Body = ConvertTo-Json -InputObject $Body -Depth 10 -Compress
    }
    try {
        return Invoke-RestMethod @request
    } catch {
        $statusCode = Get-HttpStatusCode $_
        if ($statusCode -gt 0) { throw "HTTP $statusCode for $Method $Path" }
        throw "Request failed for $Method $Path ($($_.Exception.GetType().Name))"
    }
}

function Invoke-Check {
    param([string] $Name, [scriptblock] $Action)
    try {
        $value = & $Action
        Add-Result $Name 'PASS' 'Request completed'
        return $value
    } catch {
        Add-Result $Name 'FAIL' $_.Exception.Message
        return $null
    }
}

function Invoke-OptionalCheck {
    param([string] $Name, [scriptblock] $Action)
    try {
        $value = & $Action
        Add-Result $Name 'PASS' 'Request completed'
        return $value
    } catch {
        if ((Get-HttpStatusCode $_) -eq 404 -or $_.Exception.Message -match '^HTTP 404 ') {
            Add-Result $Name 'SKIPPED' 'Endpoint is not available on this backend'
        } else {
            Add-Result $Name 'FAIL' $_.Exception.Message
        }
        return $null
    }
}

function New-BasicHeader {
    param([string] $Email, [string] $Password)
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${Email}:$Password"))
    return @{ Authorization = "Basic $encoded" }
}

if ([string]::IsNullOrWhiteSpace($AdminEmail) -xor ($null -eq $AdminPassword)) {
    Write-Error 'Provide both -AdminEmail and -AdminPassword, or neither.'
    exit 1
}

$health = Invoke-Check 'Health' {
    $response = Invoke-Api -Method GET -Path '/actuator/health' -Headers @{}
    if ($response.status -ne 'UP') { throw 'Health endpoint did not report UP' }
    $response
}

$userHeaders = $null
$registered = $null
if ($null -ne $health) {
    $runId = [DateTimeOffset]::UtcNow.ToString('yyyyMMddHHmmssfff')
    $email = "smoke-$runId@example.invalid"
    $generatedPassword = 'Smoke!A9-' + [guid]::NewGuid().ToString('N')
    $registered = Invoke-Check 'Register synthetic user' {
        Invoke-Api -Method POST -Path '/api/v1/auth/register' -Headers @{} -Body @{
            email = $email; password = $generatedPassword; displayName = 'TradeCore Smoke'
        }
    }
    if ($null -ne $registered) {
        $userHeaders = New-BasicHeader -Email $email -Password $generatedPassword
        Invoke-Check 'Account me' {
            Invoke-Api -Method GET -Path '/api/v1/account/me' -Headers $userHeaders
        } | Out-Null

        $instruments = Invoke-Check 'Instruments' {
            $result = Invoke-Api -Method GET -Path '/api/v1/market/instruments' -Headers $userHeaders
            if (@($result | Where-Object { $_.symbol -eq 'TCS' }).Count -ne 1) {
                throw 'Supported instrument list did not contain TCS exactly once'
            }
            $result
        }
        $quote = Invoke-Check 'TCS quote' {
            Invoke-Api -Method GET -Path '/api/v1/market/quotes/NSE/TCS' -Headers $userHeaders
        }
        $marketSession = Invoke-OptionalCheck 'Market session' {
            Invoke-Api -Method GET -Path '/api/v1/market/session' -Headers $userHeaders
        }

        $watchlist = Invoke-Check 'Create watchlist' {
            Invoke-Api -Method POST -Path '/api/v1/watchlists' -Headers $userHeaders -Body @{
                name = "Smoke $runId"
            }
        }
        if ($null -ne $watchlist) {
            Invoke-Check 'Add TCS to watchlist' {
                Invoke-Api -Method POST -Path "/api/v1/watchlists/$($watchlist.id)/items" `
                    -Headers $userHeaders -Body @{ exchange = 'NSE'; symbol = 'TCS' }
            } | Out-Null
        } else {
            Add-Result 'Add TCS to watchlist' 'SKIPPED' 'Watchlist creation failed'
        }

        $profile = Invoke-Check 'TCS learning profile' {
            Invoke-Api -Method GET -Path '/api/v1/learning/companies/TCS' -Headers $userHeaders
        }
        if ($null -ne $watchlist -and $null -ne $profile) {
            Invoke-Check 'Create price alert' {
                Invoke-Api -Method POST -Path '/api/v1/alerts' -Headers $userHeaders -Body @{
                    watchlistId = $watchlist.id
                    instrumentId = $profile.instrumentId
                    condition = 'ABOVE'
                    targetPrice = 100000
                }
            } | Out-Null
        } else {
            Add-Result 'Create price alert' 'SKIPPED' 'A watchlist or TCS learning profile was unavailable'
        }

        $orderRequest = @{
            exchange = 'NSE'; symbol = 'TCS'; side = 'BUY'; orderType = 'MARKET'
            tradingMode = 'DELIVERY'; quantity = 1
        }
        $preview = Invoke-Check 'Order preview' {
            Invoke-Api -Method POST -Path '/api/v1/orders/preview' -Headers $userHeaders -Body $orderRequest
        }

        $quoteFresh = $null -ne $quote -and $quote.dataStatus -eq 'LIVE' `
            -and $null -ne $quote.freshnessAgeSeconds -and [long]$quote.freshnessAgeSeconds -le 600
        $marketOpen = $null -ne $marketSession -and $marketSession.status -eq 'OPEN'
        $filledOrder = $null
        if ($marketOpen -and $quoteFresh -and $null -ne $preview -and $preview.valid) {
            $placed = Invoke-Check 'Place simulated market order' {
                Invoke-Api -Method POST -Path '/api/v1/orders' -Headers ($userHeaders + @{
                    'Idempotency-Key' = [guid]::NewGuid().ToString()
                }) -Body $orderRequest
            }
            if ($null -ne $placed) {
                $filledOrder = Invoke-Check 'Wait up to 3 minutes for fill' {
                    $deadline = [DateTimeOffset]::UtcNow.AddMinutes(3)
                    do {
                        Start-Sleep -Seconds 5
                        $current = Invoke-Api -Method GET -Path "/api/v1/orders/$($placed.orderId)" -Headers $userHeaders
                        if ($current.status -in @('REJECTED', 'CANCELLED', 'FAILED')) {
                            throw "Order ended with status $($current.status) before filling"
                        }
                    } while ($current.status -ne 'FILLED' -and [DateTimeOffset]::UtcNow -lt $deadline)
                    if ($current.status -ne 'FILLED') { throw 'Order was not FILLED within 3 minutes' }
                    $current
                }
            } else {
                Add-Result 'Wait up to 3 minutes for fill' 'SKIPPED' 'Order placement failed'
            }
        } else {
            if (-not $marketOpen) { $reason = 'Market session is not OPEN or the session endpoint is unavailable' }
            elseif (-not $quoteFresh) { $reason = 'TCS quote is not LIVE and no older than 10 minutes' }
            else { $reason = 'Order preview did not pass validation' }
            Add-Result 'Place simulated market order' 'SKIPPED' $reason
            Add-Result 'Wait up to 3 minutes for fill' 'SKIPPED' $reason
        }

        Invoke-Check 'Portfolio' {
            Invoke-Api -Method GET -Path '/api/v1/portfolio/me' -Headers $userHeaders
        } | Out-Null
        Invoke-Check 'Trades' {
            Invoke-Api -Method GET -Path '/api/v1/trades?page=0&size=20' -Headers $userHeaders
        } | Out-Null
        if ($null -ne $filledOrder) {
            Invoke-Check 'Create journal entry' {
                Invoke-Api -Method POST -Path '/api/v1/journal' -Headers $userHeaders -Body @{
                    orderId = $filledOrder.orderId
                    thesis = 'Automated live smoke check for the virtual trading flow.'
                    strategyTag = 'SMOKE_TEST'
                    wentWell = 'The order reached FILLED through the normal simulator path.'
                    wentWrong = ''
                    lessonLearned = 'Verify the simulated order and its persisted trade history.'
                    rating = 5
                }
            } | Out-Null
        } else {
            Add-Result 'Create journal entry' 'SKIPPED' 'No filled simulated order is available'
        }
        Invoke-Check 'Notifications' {
            Invoke-Api -Method GET -Path '/api/v1/notifications?page=0&size=20' -Headers $userHeaders
        } | Out-Null
    }
} else {
    foreach ($name in @('Register synthetic user', 'Account me', 'Instruments', 'TCS quote', 'Market session',
            'Create watchlist', 'Add TCS to watchlist', 'TCS learning profile', 'Create price alert', 'Order preview',
            'Place simulated market order', 'Wait up to 3 minutes for fill', 'Portfolio', 'Trades',
            'Create journal entry', 'Notifications')) {
        Add-Result $name 'SKIPPED' 'Health check failed'
    }
}

if ($null -ne $AdminPassword -and -not [string]::IsNullOrWhiteSpace($AdminEmail)) {
    $adminPlainPassword = [System.Net.NetworkCredential]::new('', $AdminPassword).Password
    $adminHeaders = New-BasicHeader -Email $AdminEmail -Password $adminPlainPassword
    foreach ($check in @(
            @{ Name = 'Admin overview'; Path = '/api/v1/admin/overview'; Optional = $false },
            @{ Name = 'Admin market status'; Path = '/api/v1/admin/market-status'; Optional = $false },
            @{ Name = 'Admin audit logs'; Path = '/api/v1/admin/audit-logs?page=0&size=5'; Optional = $false },
            @{ Name = 'Admin market completeness'; Path = '/api/v1/admin/market-data/completeness?months=12'; Optional = $true },
            @{ Name = 'Admin reconciliation'; Path = '/api/v1/admin/reconciliation'; Optional = $true }
        )) {
        if ($check.Optional) {
            Invoke-OptionalCheck $check.Name {
                Invoke-Api -Method GET -Path $check.Path -Headers $adminHeaders
            } | Out-Null
        } else {
            Invoke-Check $check.Name {
                Invoke-Api -Method GET -Path $check.Path -Headers $adminHeaders
            } | Out-Null
        }
    }
} else {
    foreach ($name in @('Admin overview', 'Admin market status', 'Admin audit logs',
            'Admin market completeness', 'Admin reconciliation')) {
        Add-Result $name 'SKIPPED' 'Admin credentials were not supplied'
    }
}

$script:Results | Format-Table -Property Check, Status, Detail -AutoSize | Out-String -Width 240 | Write-Output
if (@($script:Results | Where-Object Status -eq 'FAIL').Count -gt 0) { exit 1 }
exit 0
