# Perde launcher derleme betigi (Gradle'siz): aapt2 -> javac -> d8 -> zipalign -> apksigner
# Kullanim:  .\build.ps1            (yalniz derler)
#            .\build.ps1 -Install   (derler ve projeksiyona kurar)
#            .\build.ps1 -Normal    (sistem imzası yerine eski normal imza; yedek/geri dönüş için)
# Varsayılan: AOSP platform test anahtarıyla imzalı SİSTEM uygulaması (android.uid.system).
# Bu yalnızca platform anahtarı AOSP test anahtarı olan cihazlarda kurulabilir (HY350: doğrulandı).
param([switch]$Install, [switch]$Normal, [string]$Device = $(if (Test-Path (Join-Path $PSScriptRoot 'cihaz.txt')) { (Get-Content (Join-Path $PSScriptRoot 'cihaz.txt') -TotalCount 1).Trim() } else { "" }))
$ErrorActionPreference = 'Stop'
$P = $PSScriptRoot
$T = Join-Path $P '_tools'
$env:JAVA_HOME = (Get-ChildItem "$T\jdk" -Directory | Select-Object -First 1).FullName
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$BT = (Get-ChildItem "$T\bt" -Directory | Select-Object -First 1).FullName
$JAR = (Get-ChildItem "$T\plat" -Recurse -Filter android.jar | Select-Object -First 1).FullName
$ADB = "$T\platform-tools\adb.exe"
$B = Join-Path $P 'build'

if (Test-Path $B) { Remove-Item -Recurse -Force $B }
New-Item -ItemType Directory -Force "$B\gen", "$B\classes", "$B\dex" | Out-Null

function Invoke-Step($exe, [string[]]$a) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & $exe @a 2>&1 | ForEach-Object { "$_" } | Out-Host
    $ErrorActionPreference = $old
    if ($LASTEXITCODE -ne 0) { throw "$exe basarisiz ($LASTEXITCODE)" }
}

Invoke-Step "$BT\aapt2.exe" @('compile', '--dir', "$P\res", '-o', "$B\res.zip")
$MAN = "$P\AndroidManifest.xml"
if (-not $Normal) {
    $MAN = "$B\AndroidManifest.xml"
    $m = [IO.File]::ReadAllText("$P\AndroidManifest.xml")
    $m = $m.Replace('package="com.perde.launcher">', 'package="com.perde.launcher" android:sharedUserId="android.uid.system">')
    [IO.File]::WriteAllText($MAN, $m, (New-Object Text.UTF8Encoding($false)))
}
Invoke-Step "$BT\aapt2.exe" @('link', '-o', "$B\base.apk", '-I', $JAR, '--manifest', $MAN,
    "$B\res.zip", '--java', "$B\gen", '--min-sdk-version', '21', '--target-sdk-version', '35',
    '--version-code', '25', '--version-name', '3.15')

$srcs = Get-ChildItem "$P\src", "$B\gen" -Recurse -Filter *.java | ForEach-Object FullName
Invoke-Step javac (@('-encoding', 'UTF-8', '--release', '8', '-classpath', $JAR, '-d', "$B\classes") + $srcs)

$classes = Get-ChildItem "$B\classes" -Recurse -Filter *.class | ForEach-Object FullName
Invoke-Step "$BT\d8.bat" (@('--release', '--min-api', '21', '--lib', $JAR, '--output', "$B\dex") + $classes)

Copy-Item "$B\base.apk" "$B\unsigned.apk"
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$B\unsigned.apk", 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$B\dex\classes.dex", 'classes.dex') | Out-Null
$zip.Dispose()

Invoke-Step "$BT\zipalign.exe" @('-f', '-p', '4', "$B\unsigned.apk", "$B\aligned.apk")

$KS = "$P\perde.keystore"
if (-not (Test-Path $KS)) {
    Invoke-Step keytool @('-genkeypair', '-keystore', $KS, '-alias', 'perde', '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '20000', '-storepass', 'perde-yerel', '-keypass', 'perde-yerel', '-dname', 'CN=Perde')
}
if ($Normal) {
    Invoke-Step "$BT\apksigner.bat" @('sign', '--ks', $KS, '--ks-pass', 'pass:perde-yerel', '--out', "$B\perde.apk", "$B\aligned.apk")
} else {
    $PK = "$T\platformkey"
    Invoke-Step "$BT\apksigner.bat" @('sign', '--key', "$PK\platform.pk8", '--cert', "$PK\platform.x509.pem", '--out', "$B\perde.apk", "$B\aligned.apk")
}
Write-Host "APK hazir: $B\perde.apk"

if ($Install) {
    Invoke-Step $ADB @('-s', $Device, 'install', '-r', "$B\perde.apk")
}
