<#
  Deploys SOSync to your Oracle server in one step: builds the backend on this laptop, copies
  it to the server, and starts it there.

  Needs nothing installed beyond what the project already uses - ssh and scp are built into
  Windows. Run it through deploy.bat (double-click it, or run it from a terminal).

  Run it again after any backend change to redeploy. The server keeps its data and secrets,
  and this script remembers the IP and key you gave it the first time.
#>
param(
    [string]$ServerIp,
    [string]$KeyPath
)

$ErrorActionPreference = 'Stop'

$deployDir = $PSScriptRoot
$backendDir = Join-Path (Split-Path -Parent $deployDir) 'backend'
$settingsFile = Join-Path $deployDir '.deploy-settings'

function Step([int]$n, [string]$text) {
    Write-Host "`n==> $n/4 $text" -ForegroundColor Cyan
}

function Fail([string]$message) {
    Write-Host "`n$message" -ForegroundColor Red
    exit 1
}

# ── Server details, remembered between runs ─────────────────────────────────────────
$saved = $null
if (Test-Path $settingsFile) {
    $saved = Get-Content $settingsFile -Raw | ConvertFrom-Json
}

function Ask([string]$prompt, [string]$remembered) {
    $shown = if ($remembered) { "$prompt [$remembered]" } else { $prompt }
    $answer = Read-Host $shown
    if ([string]::IsNullOrWhiteSpace($answer)) { return $remembered }
    return $answer
}

if (-not $ServerIp) {
    $ServerIp = Ask 'Server public IP (from the Oracle console)' $(if ($saved) { $saved.ServerIp })
}
if (-not $KeyPath) {
    $KeyPath = Ask 'Path to the SSH private key Oracle gave you (drag the file here)' $(if ($saved) { $saved.KeyPath })
}

# Dragging a file into the window wraps its path in quotes.
$ServerIp = "$ServerIp".Trim()
$KeyPath = "$KeyPath".Trim().Trim('"')

if (-not $ServerIp) { Fail 'No server IP given.' }
if (-not (Test-Path $KeyPath)) { Fail "No key file found at: $KeyPath" }

@{ ServerIp = $ServerIp; KeyPath = $KeyPath } | ConvertTo-Json | Set-Content $settingsFile

# ── 1. Build ─────────────────────────────────────────────────────────────────────────
Step 1 'Building the backend'
Push-Location $backendDir
try {
    & .\gradlew.bat bootJar --console=plain -q
    if ($LASTEXITCODE -ne 0) { Fail 'The backend did not build. Fix the error above, then run this again.' }
}
finally {
    Pop-Location
}

$jar = Get-ChildItem (Join-Path $backendDir 'build\libs') -Filter '*.jar' |
    Where-Object { $_.Name -notlike '*-plain.jar' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) { Fail 'The build finished but produced no jar in backend\build\libs.' }
Write-Host "    built $($jar.Name)"

# ── 2. Key permissions ───────────────────────────────────────────────────────────────
Step 2 'Preparing the SSH key'
# Windows' ssh refuses a private key that other accounts on this PC can read, with an
# "UNPROTECTED PRIVATE KEY FILE" error, and a file saved to Downloads usually is readable by
# them. This narrows access to that one file to you alone. Nothing else is changed.
icacls $KeyPath /inheritance:r | Out-Null
icacls $KeyPath /grant:r "$($env:USERNAME):(R)" | Out-Null

# ── 3. Upload ────────────────────────────────────────────────────────────────────────
Step 3 "Uploading to $ServerIp"
$sshOptions = @('-i', $KeyPath, '-o', 'StrictHostKeyChecking=accept-new', '-o', 'ConnectTimeout=15')
$target = "ubuntu@$ServerIp"

& ssh @sshOptions $target 'mkdir -p sosync'
if ($LASTEXITCODE -ne 0) {
    Fail ("Could not connect to $ServerIp over SSH.`n" +
          'Check the IP, that the VM is running in the Oracle console, and that this is the ' +
          'private key you downloaded when creating the VM.')
}

& scp @sshOptions $jar.FullName "${target}:sosync/app.jar"
if ($LASTEXITCODE -ne 0) { Fail 'Uploading the backend failed.' }

$files = 'Dockerfile', 'docker-compose.yml', 'setup.sh' | ForEach-Object { Join-Path $deployDir $_ }
& scp @sshOptions $files "${target}:sosync/"
if ($LASTEXITCODE -ne 0) { Fail 'Uploading the deploy files failed.' }

# ── 4. Start on the server ───────────────────────────────────────────────────────────
Step 4 'Setting up and starting on the server (the first run takes a few minutes)'
# The sed strips Windows line endings, in case an editor or git added them: bash on the server
# fails on them with confusing "command not found" errors.
& ssh @sshOptions $target 'sed -i "s/\r$//" sosync/setup.sh && bash sosync/setup.sh'
if ($LASTEXITCODE -ne 0) { Fail 'Setup on the server failed - see the messages above.' }

# ── Check it from the outside ────────────────────────────────────────────────────────
# setup.sh has already confirmed the API answers on the server itself. This confirms the
# internet can reach it, which is a different thing: if it fails here, the one remaining
# blocker is Oracle's Security List.
Write-Host "`n==> Checking the API is reachable from the internet" -ForegroundColor Cyan
try {
    Invoke-WebRequest -Uri "http://$ServerIp/actuator/health" -UseBasicParsing -TimeoutSec 15 | Out-Null
}
catch {
    Fail ("The API is running on the server but the internet cannot reach it yet.`n`n" +
          "Oracle's Security List is not letting port 80 in. In the Oracle console:`n" +
          "  Networking > Virtual cloud networks > (your VCN) > Security Lists >`n" +
          "  Default Security List > Add Ingress Rules`n" +
          "  Source CIDR 0.0.0.0/0   IP Protocol TCP   Destination Port Range 80`n`n" +
          "No need to redeploy afterwards - just open http://$ServerIp/actuator/health to check.")
}

Write-Host "`nSOSync is live at http://$ServerIp" -ForegroundColor Green
Write-Host "  API docs:  http://$ServerIp/docs"
Write-Host "  For the app, set serverAddress in mobile\lib\config.dart to: http://$ServerIp"
