# Test script for infra-services via API Gateway
# Usage: .\test_infra_services.ps1

$BASE   = "http://localhost:8080"
$EMAIL  = "testadmin_$(Get-Date -Format 'HHmmss')@test.com"
$PASS   = "Elcom@123"
$EMAIL2 = "testuser2_$(Get-Date -Format 'HHmmss')@test.com"

$script:pass = 0
$script:fail = 0

function Test-Case {
    param($name, $expected, $actual)
    if ("$actual" -eq "$expected") {
        Write-Host "  [PASS] $name" -ForegroundColor Green
        $script:pass++
    } else {
        Write-Host "  [FAIL] $name  expected=[$expected]  got=[$actual]" -ForegroundColor Red
        $script:fail++
    }
}

# Returns [status, body_object]
function curlj {
    param($method, $url, $body = $null, [hashtable]$headers = @{})
    $curlArgs = @("-s", "-w", "`n%{http_code}", "-X", $method)
    foreach ($k in $headers.Keys) { $curlArgs += @("-H", "$k`: $($headers[$k])") }
    $tmpBody = $null
    if ($body) {
        $tmpBody = [System.IO.Path]::GetTempPath() + "curlbody_$([System.Guid]::NewGuid()).json"
        [System.IO.File]::WriteAllText($tmpBody, $body, [System.Text.Encoding]::UTF8)
        $curlArgs += @("-H", "Content-Type: application/json", "-d", "@$tmpBody")
    }
    $raw = & curl.exe @curlArgs $url 2>&1
    if ($tmpBody) { Remove-Item $tmpBody -ErrorAction SilentlyContinue }
    $lines = ($raw -join "`n") -split "`n"
    $code    = ([string]$lines[-1]).Trim()
    $bodyStr = ($lines[0..($lines.Count-2)] -join "`n").Trim()
    $obj = $null
    try { if ($bodyStr) { $obj = $bodyStr | ConvertFrom-Json } } catch {}
    return @{ status=[int]$code; body=$obj }
}

# ============================================================
Write-Host ""
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "  AUTH SERVICE TESTS" -ForegroundColor Cyan
Write-Host "=====================================================" -ForegroundColor Cyan

# 1. Register primary test user
Write-Host "`n[1] Register primary user ($EMAIL)"
$r = curlj POST "$BASE/api/auth/register" "{`"email`":`"$EMAIL`",`"password`":`"$PASS`",`"fullName`":`"Test Admin`"}"
Test-Case "Register 200" 200 $r.status
Test-Case "Register success" "True" "$($r.body.success)"

# 1b. Register second user (for duplicate-email test later)
$r2b = curlj POST "$BASE/api/auth/register" "{`"email`":`"$EMAIL2`",`"password`":`"$PASS`",`"fullName`":`"Test User2`"}"

# 2. Login
Write-Host "`n[2] Login ($EMAIL)"
$r = curlj POST "$BASE/api/auth/login" "{`"email`":`"$EMAIL`",`"password`":`"$PASS`"}"
Test-Case "Login 200" 200 $r.status
Test-Case "Login success" "True" "$($r.body.success)"

$ACCESS  = $r.body.data.accessToken
$REFRESH = $r.body.data.refreshToken
$USER_ID = $r.body.data.userId

# 3. GET /me
Write-Host "`n[3] GET /me"
$r = curlj GET "$BASE/api/auth/me" -headers @{ Authorization="Bearer $ACCESS" }
Test-Case "/me 200" 200 $r.status
Test-Case "/me email" $EMAIL $r.body.data.email

# 4. Validate token
Write-Host "`n[4] Validate token"
$r = curlj POST "$BASE/api/auth/validate" "{`"token`":`"$ACCESS`"}"
Test-Case "Validate 200" 200 $r.status
Test-Case "Validate valid=true" "True" "$($r.body.valid)"

# 5. Refresh token
Write-Host "`n[5] Refresh token"
$r = curlj POST "$BASE/api/auth/refresh" "{`"refreshToken`":`"$REFRESH`"}"
Test-Case "Refresh 200" 200 $r.status
Test-Case "Refresh success" "True" "$($r.body.success)"
$NEW_REFRESH = if ($r.body.data.refreshToken) { $r.body.data.refreshToken } else { $REFRESH }

# 6. Forgot password
Write-Host "`n[6] Forgot password"
$r = curlj POST "$BASE/api/auth/forgot-password" "{`"email`":`"$EMAIL`"}"
Test-Case "ForgotPassword 200" 200 $r.status

# 7. Wrong password → fail
Write-Host "`n[7] Login wrong password (expect fail)"
$r = curlj POST "$BASE/api/auth/login" "{`"email`":`"$EMAIL`",`"password`":`"BadPass999`"}"
Test-Case "Wrong password not 200" $true ($r.status -ne 200)

# 8. Logout (requires Bearer token)
Write-Host "`n[8] Logout"
$r = curlj POST "$BASE/api/auth/logout" "{`"refreshToken`":`"$NEW_REFRESH`"}" -headers @{ Authorization="Bearer $ACCESS" }
Test-Case "Logout 200" 200 $r.status

# 9. Refresh after logout → fail
Write-Host "`n[9] Refresh after logout (expect fail)"
$r = curlj POST "$BASE/api/auth/refresh" "{`"refreshToken`":`"$NEW_REFRESH`"}"
Test-Case "Refresh after logout not 200" $true ($r.status -ne 200)

# ============================================================
Write-Host ""
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "  FILE SERVICE TESTS" -ForegroundColor Cyan
Write-Host "=====================================================" -ForegroundColor Cyan

# Re-login
$r = curlj POST "$BASE/api/auth/login" "{`"email`":`"$EMAIL`",`"password`":`"$PASS`"}"
$ACCESS  = $r.body.data.accessToken
$USER_ID = if ($r.body.data.userId) { $r.body.data.userId } else { "unknown" }

# 10. Upload file
Write-Host "`n[10] Upload test file"
$FILE_ID = $null
$tmpFile = [System.IO.Path]::GetTempPath() + "test_upload_$([System.Guid]::NewGuid()).txt"
"Hello from test $(Get-Date)" | Out-File $tmpFile -Encoding utf8 -NoNewline
$raw = & curl.exe -s -w "`n%{http_code}" -X POST `
    -H "Authorization: Bearer $ACCESS" `
    -H "X-User-Id: $USER_ID" `
    -F "file=@$tmpFile;type=text/plain" `
    "$BASE/api/files/upload" 2>&1
Remove-Item $tmpFile -ErrorAction SilentlyContinue
$lines = $raw -split "`n"
$uploadCode = [int]($lines[-1]).Trim()
$uploadBody = $null
try { $uploadBody = ($lines[0..($lines.Count-2)] -join "") | ConvertFrom-Json } catch {}
Test-Case "Upload 200" 200 $uploadCode
Test-Case "Upload success" "True" "$($uploadBody.success)"
$FILE_ID = $uploadBody.data.fileId

# 11. List user files
Write-Host "`n[11] List files (user=$USER_ID)"
$r = curlj GET "$BASE/api/files/user/$USER_ID" -headers @{ Authorization="Bearer $ACCESS" }
Test-Case "List files 200" 200 $r.status

# 12. Get presigned URL
if ($FILE_ID) {
    Write-Host "`n[12] Get presigned URL ($FILE_ID)"
    $raw302 = & curl.exe -s -o NUL -w "%{http_code}" -H "Authorization: Bearer $ACCESS" "$BASE/api/files/$FILE_ID" 2>&1
    Test-Case "Get file 302" 302 ([int]$raw302.Trim())
}

# 13. Delete file
if ($FILE_ID) {
    Write-Host "`n[13] Delete file ($FILE_ID)"
    $r = curlj DELETE "$BASE/api/files/$FILE_ID" -headers @{ Authorization="Bearer $ACCESS" }
    Test-Case "Delete 200" 200 $r.status
    Test-Case "Delete success" "True" "$($r.body.success)"
}

# ============================================================
Write-Host ""
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "  NOTIFICATION SERVICE" -ForegroundColor Cyan
Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "  [INFO] Event-driven via RabbitMQ - check logs:" -ForegroundColor DarkYellow
$logOut = & docker logs microservice_template-notification-service-1 --tail 10 2>&1
$logOut | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }

# ============================================================
Write-Host ""
Write-Host "=====================================================" -ForegroundColor Cyan
$color = if ($script:fail -gt 0) { "Red" } else { "Green" }
Write-Host "  RESULT:  PASS=$($script:pass)  FAIL=$($script:fail)" -ForegroundColor $color
Write-Host "=====================================================" -ForegroundColor Cyan
if ($script:fail -gt 0) { exit 1 }
