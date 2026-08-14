<#
.SYNOPSIS
    Provisions the Apache Maven distribution declared in maven-wrapper.properties.

.DESCRIPTION
    Downloads Apache Maven into ~/.m2/wrapper/dists on first use, verifies its
    SHA-512 against the pinned checksum, extracts it, and prints the resulting
    MAVEN_HOME to stdout. Subsequent runs find the existing install and print
    the path immediately without touching the network.

    stdout carries ONLY the MAVEN_HOME path, because mvnw.cmd captures it.
    All human-readable progress goes to stderr.
#>

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Write-Status([string] $Message) {
    [Console]::Error.WriteLine("[mvnw] $Message")
}

$wrapperDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$propsFile = Join-Path $wrapperDir 'maven-wrapper.properties'

if (-not (Test-Path $propsFile)) {
    Write-Status "ERROR: missing $propsFile"
    exit 1
}

# Parse the java.util.Properties file (key=value, # comments, blank lines).
$props = @{}
foreach ($line in Get-Content $propsFile) {
    $trimmed = $line.Trim()
    if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
    $split = $trimmed.IndexOf('=')
    if ($split -lt 1) { continue }
    $props[$trimmed.Substring(0, $split).Trim()] = $trimmed.Substring($split + 1).Trim()
}

$distributionUrl = $props['distributionUrl']
$expectedSha512 = $props['distributionSha512Sum']

if (-not $distributionUrl) {
    Write-Status 'ERROR: distributionUrl is not set in maven-wrapper.properties'
    exit 1
}

# apache-maven-3.9.16-bin.zip -> apache-maven-3.9.16
$zipName = $distributionUrl.Substring($distributionUrl.LastIndexOf('/') + 1)
$distName = $zipName -replace '-bin\.zip$', ''

$distsRoot = Join-Path $env:USERPROFILE '.m2\wrapper\dists'
$mavenHome = Join-Path $distsRoot $distName

if (Test-Path (Join-Path $mavenHome 'bin\mvn.cmd')) {
    Write-Output $mavenHome
    exit 0
}

Write-Status "Apache Maven is not installed yet."
Write-Status "Downloading $distributionUrl"
Write-Status "Destination: $mavenHome"

# Windows PowerShell 5.1 may default to TLS 1.0, which Maven Central rejects.
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
} catch {
    # Newer runtimes negotiate TLS automatically; nothing to do.
}

$staging = Join-Path ([System.IO.Path]::GetTempPath()) ("mvnw-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $staging -Force | Out-Null

try {
    $zipPath = Join-Path $staging $zipName
    Invoke-WebRequest -Uri $distributionUrl -OutFile $zipPath -UseBasicParsing

    if ($expectedSha512) {
        Write-Status 'Verifying SHA-512 checksum...'
        $actual = (Get-FileHash -Path $zipPath -Algorithm SHA512).Hash
        if ($actual -ne $expectedSha512.ToUpperInvariant()) {
            Write-Status 'ERROR: checksum mismatch - refusing to install.'
            Write-Status "  expected: $($expectedSha512.ToLowerInvariant())"
            Write-Status "  actual:   $($actual.ToLowerInvariant())"
            exit 1
        }
        Write-Status 'Checksum OK.'
    } else {
        Write-Status 'WARNING: no distributionSha512Sum pinned; skipping verification.'
    }

    Write-Status 'Extracting...'
    $extractDir = Join-Path $staging 'unzipped'
    Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force

    # The zip contains a single top-level apache-maven-<version> directory.
    $extracted = Join-Path $extractDir $distName
    if (-not (Test-Path $extracted)) {
        $extracted = (Get-ChildItem -Path $extractDir -Directory | Select-Object -First 1).FullName
    }

    New-Item -ItemType Directory -Path $distsRoot -Force | Out-Null
    if (Test-Path $mavenHome) { Remove-Item -Path $mavenHome -Recurse -Force }
    Move-Item -Path $extracted -Destination $mavenHome

    Write-Status "Installed Apache Maven at $mavenHome"
} finally {
    if (Test-Path $staging) {
        try { Remove-Item -Path $staging -Recurse -Force } catch {}
    }
}

if (-not (Test-Path (Join-Path $mavenHome 'bin\mvn.cmd'))) {
    Write-Status 'ERROR: installation finished but bin\mvn.cmd is missing.'
    exit 1
}

Write-Output $mavenHome
