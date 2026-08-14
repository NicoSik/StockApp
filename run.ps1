<#
.SYNOPSIS
    Starts Ticker.

.DESCRIPTION
    Checks the things that actually go wrong - missing .env, no JDK 17+, a port
    already in use - and reports each one with the fix, then hands off to the
    Maven wrapper. No Maven installation is required; the wrapper fetches it on
    first use.

.EXAMPLE
    .\run.ps1
    .\run.ps1 -Package     # build target/ticker.jar instead of running
#>

[CmdletBinding()]
param(
    [switch] $Package,
    [switch] $SkipTests
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$appDir = Join-Path $root 'demo'

function Say([string] $text, [string] $colour = 'Gray') {
    Write-Host $text -ForegroundColor $colour
}

Say ''
Say '  Ticker' Cyan
Say '  --------------------------------------------------' DarkGray

# --- 1. Configuration -------------------------------------------------------

$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
    Say '  [ok]   .env found' Green
} else {
    Say '  [X]    .env is missing' Red
    Say '         Copy .env.example to .env and fill in your credentials:' Yellow
    Say '           Copy-Item .env.example .env' DarkGray
    exit 1
}

# Read SERVER_PORT without importing the rest of the file into this session.
$port = 9090
$portLine = Get-Content $envFile | Where-Object { $_ -match '^\s*SERVER_PORT\s*=' } | Select-Object -First 1
if ($portLine) {
    $parsed = ($portLine -split '=', 2)[1].Trim()
    if ($parsed -match '^\d+$') { $port = [int] $parsed }
}

# --- 2. Java ----------------------------------------------------------------

# Javalin 6 is compiled for Java 17. Prefer an explicit JAVA_HOME if it is new
# enough, otherwise find the newest JDK installed and use it for this process
# only - changing the machine's JAVA_HOME is not this script's business.
# Returns the major version of the JDK at $JdkPath, or 0 if it is not one.
#
# Two things this deliberately avoids:
#  - The parameter is not called $home. That is a read-only PowerShell
#    automatic variable and binding it throws at call time.
#  - It does not run `java -version`. That writes to stderr, and in Windows
#    PowerShell 5.1 redirecting a native command's stderr wraps every line in
#    an ErrorRecord, which $ErrorActionPreference='Stop' turns into a
#    terminating error. Every JDK ships a `release` file with the version in
#    it, which is both safer to read and much faster than starting a JVM.
function Get-JdkVersion([string] $JdkPath) {
    if (-not $JdkPath) { return 0 }
    if (-not (Test-Path (Join-Path $JdkPath 'bin\java.exe'))) { return 0 }

    $releaseFile = Join-Path $JdkPath 'release'
    if (Test-Path $releaseFile) {
        $line = Get-Content $releaseFile | Where-Object { $_ -match '^JAVA_VERSION=' } | Select-Object -First 1
        if ($line -and $line -match '(\d+)') { return [int] $Matches[1] }
    }

    # Fallback for a layout without a release file: merge the streams inside
    # cmd so PowerShell only ever sees stdout.
    $exe = Join-Path $JdkPath 'bin\java.exe'
    $output = cmd /c "`"$exe`" -version 2>&1" | Select-Object -First 1
    if ($output -match '"(\d+)') { return [int] $Matches[1] }
    return 0
}

$jdk = $null
if ($env:JAVA_HOME -and (Get-JdkVersion $env:JAVA_HOME) -ge 17) {
    $jdk = $env:JAVA_HOME
} else {
    $candidates = @()
    foreach ($base in @("$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium",
                        "$env:ProgramFiles\Microsoft\jdk", "$env:LOCALAPPDATA\Programs\Eclipse Adoptium")) {
        if (Test-Path $base) {
            $candidates += Get-ChildItem $base -Directory -ErrorAction SilentlyContinue |
                           Select-Object -ExpandProperty FullName
        }
    }
    $best = 0
    foreach ($candidate in $candidates) {
        $version = Get-JdkVersion $candidate
        if ($version -ge 17 -and $version -gt $best) {
            $best = $version
            $jdk = $candidate
        }
    }
}

if (-not $jdk) {
    Say '  [X]    no JDK 17 or newer found' Red
    Say '         Javalin 6 requires Java 17+. Install a JDK, for example:' Yellow
    Say '           winget install EclipseAdoptium.Temurin.21.JDK' DarkGray
    exit 1
}

$env:JAVA_HOME = $jdk
Say "  [ok]   JDK $(Get-JdkVersion $jdk) at $jdk" Green

# --- 3. Port ----------------------------------------------------------------

$inUse = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if ($inUse) {
    $owner = (Get-Process -Id $inUse[0].OwningProcess -ErrorAction SilentlyContinue).ProcessName
    Say "  [X]    port $port is already in use by '$owner' (pid $($inUse[0].OwningProcess))" Red
    Say '         Stop that process, or set a different SERVER_PORT in .env.' Yellow
    exit 1
}
Say "  [ok]   port $port is free" Green

# --- 4. Go ------------------------------------------------------------------

Say '  --------------------------------------------------' DarkGray
Set-Location $appDir

$wrapper = Join-Path $appDir 'mvnw.cmd'
if ($Package) {
    $mvnArgs = @('clean', 'package')
    if ($SkipTests) { $mvnArgs += '-DskipTests' }
    Say "  Building target/ticker.jar ..." Cyan
    & $wrapper @mvnArgs
    if ($LASTEXITCODE -eq 0) {
        Say ''
        Say "  Built demo/target/ticker.jar - run it with:" Green
        Say "    java -jar demo/target/ticker.jar" DarkGray
    }
} else {
    Say "  Starting on http://localhost:$port  (Ctrl-C to stop)" Cyan
    Say ''
    $mvnArgs = @('compile', 'exec:java')
    if ($SkipTests) { $mvnArgs += '-DskipTests' }
    & $wrapper @mvnArgs
}

exit $LASTEXITCODE
