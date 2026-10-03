# Requires Windows PowerShell 5.1 or PowerShell 7+. No administrator privileges required.
[CmdletBinding()]
param(
    [string]$Tag = $env:VOLAN_TAG,
    [string]$InstallDir = $(if ($env:VOLAN_INSTALL_DIR) { $env:VOLAN_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'Programs\Volan' })
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$repository = 'https://github.com/thirtyeighttwentysix/volan'
$raw = 'https://raw.githubusercontent.com/thirtyeighttwentysix/volan/main/scripts'

function Download-VolanFile([string]$Url, [string]$Path) {
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Path -TimeoutSec 120
}

# Match the Java selection used by the Gradle launcher (JAVA_HOME takes precedence).
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw 'JAVA_HOME does not contain bin\java.exe. Install Java 17+ or fix JAVA_HOME.' }
# Native stderr is normal for java -version, including on Windows PowerShell 5.1.
$savedPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVersion = (& $java -version 2>&1 | Out-String)
$javaExit = $LASTEXITCODE
$ErrorActionPreference = $savedPreference
if ($javaExit -ne 0 -or $javaVersion -notmatch 'version "(?:1\.)?(\d+)') { throw 'Cannot determine Java version. Install Java 17+.' }
if ([int]$Matches[1] -lt 17) { throw 'Volan requires Java 17 or newer.' }

if ($InstallDir -match '[;\r\n]') { throw 'The installation directory must not contain PATH separators or line breaks.' }
$root = [IO.Path]::GetFullPath($InstallDir).TrimEnd('\', '/')
if ($root -eq [IO.Path]::GetPathRoot($root).TrimEnd('\', '/') -or $root -eq [IO.Path]::GetFullPath($env:USERPROFILE).TrimEnd('\', '/')) {
    throw 'Choose a dedicated installation directory, not a drive root or home directory.'
}
if (Test-Path -LiteralPath $root) {
    $managed = Test-Path -LiteralPath (Join-Path $root '.volan-install') -PathType Leaf
    $legacy = (Test-Path -LiteralPath (Join-Path $root 'bin\volan.bat') -PathType Leaf) -and
        (Get-ChildItem -LiteralPath (Join-Path $root 'lib') -Filter 'volan-cli-*.jar' -ErrorAction SilentlyContinue)
    if (-not ($managed -or $legacy)) { throw "Refusing to replace an unrelated directory: $root" }
    if ((Get-Item -LiteralPath $root -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'The installation directory must not be a link.' }
}
$parent = Split-Path -Parent $root
$null = New-Item -ItemType Directory -Path $parent -Force
$lock = "$root.install-lock"
# New-Item without Force provides an exclusive lock across installers.
$null = New-Item -ItemType Directory -Path $lock
$stage = Join-Path $parent ('.volan-stage-' + [Guid]::NewGuid().ToString('N'))
$backup = Join-Path $parent ('.volan-backup-' + [Guid]::NewGuid().ToString('N'))
$activated = $false
$oldMoved = $false
$completed = $false
$oldUserPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$oldProcessPath = $env:Path
$pathChanged = $false
try {
    $null = New-Item -ItemType Directory -Path $stage
    if (-not $Tag) {
        Download-VolanFile "$raw/cli-release.txt" (Join-Path $stage 'channel.txt')
        $Tag = (Get-Content -LiteralPath (Join-Path $stage 'channel.txt') -Raw).Trim()
    }
    if ($Tag -cnotmatch '^(?:cli-)?v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?$') { throw "Invalid release tag: $Tag" }
    $base = "$repository/releases/download/$Tag"
    Write-Host "Downloading Volan CLI ($Tag)..."
    $zip = Join-Path $stage 'volan-cli.zip'
    Download-VolanFile "$base/volan-cli.zip" $zip
    Download-VolanFile "$base/SHA256SUMS" (Join-Path $stage 'SHA256SUMS')
    $hashes = @(Get-Content -LiteralPath (Join-Path $stage 'SHA256SUMS') | Where-Object { $_ -cmatch '^[a-f0-9]{64}  volan-cli\.zip$' })
    $sha = [Security.Cryptography.SHA256]::Create()
    $stream = [IO.File]::OpenRead($zip)
    try { $actualHash = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $stream.Dispose(); $sha.Dispose() }
    if ($hashes.Count -ne 1 -or $actualHash -cne $hashes[0].Substring(0, 64)) { throw 'CLI checksum verification failed.' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($zip)
    try {
        foreach ($entry in $archive.Entries) {
            $name = $entry.FullName
            if ($name -notmatch '^volan/[^\\:]+$' -or $name -match '(^|/)\.\.?(/|$)' -or (($entry.ExternalAttributes -shr 16) -band 0xF000) -eq 0xA000) {
                throw "Unsafe archive entry: $name"
            }
        }
    } finally { $archive.Dispose() }
    [IO.Compression.ZipFile]::ExtractToDirectory($zip, $stage)
    $payload = Join-Path $stage 'volan'
    if (-not (Test-Path -LiteralPath (Join-Path $payload 'bin\volan.bat') -PathType Leaf) -or
        -not (Get-ChildItem -LiteralPath (Join-Path $payload 'lib') -Filter 'volan-cli-*.jar' -ErrorAction SilentlyContinue)) { throw 'Incomplete CLI archive.' }
    & (Join-Path $payload 'bin\volan.bat') --help | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Downloaded CLI failed its launch check.' }
    Set-Content -LiteralPath (Join-Path $payload '.volan-install') -Value $Tag -Encoding ASCII
    if (Test-Path -LiteralPath $root) { Move-Item -LiteralPath $root -Destination $backup; $oldMoved = $true }
    Move-Item -LiteralPath $payload -Destination $root
    $activated = $true
    $bin = Join-Path $root 'bin'
    function Add-VolanPath([string]$Existing) {
        $parts = @($Existing -split ';' | Where-Object { $_ -and $_.TrimEnd('\', '/') -ine $bin.TrimEnd('\', '/') })
        return (@($bin) + $parts) -join ';'
    }
    $pathChanged = $true
    [Environment]::SetEnvironmentVariable('Path', (Add-VolanPath $oldUserPath), 'User')
    $env:Path = Add-VolanPath $oldProcessPath
    $completed = $true
    Write-Host "Installed Volan CLI in $root. Run: volan --help"
    Write-Host 'PATH is ready in this PowerShell session. Restart other terminals to pick up the change.'
} finally {
    if (-not $completed) {
        if ($pathChanged) { [Environment]::SetEnvironmentVariable('Path', $oldUserPath, 'User'); $env:Path = $oldProcessPath }
        if ($activated -and (Test-Path -LiteralPath $root)) { Remove-Item -LiteralPath $root -Recurse -Force }
        if ($oldMoved) { Move-Item -LiteralPath $backup -Destination $root }
    }
    # All recursive cleanup targets are exclusive directories created by this invocation.
    foreach ($owned in @($stage, $backup)) {
        if (Test-Path -LiteralPath $owned) { Remove-Item -LiteralPath $owned -Recurse -Force }
    }
    Remove-Item -LiteralPath $lock -Force
}
