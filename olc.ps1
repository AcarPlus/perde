# Perde performans olcumu: N saniye boyunca kare istatistigi + islemci.
# Kullanim: .\olc.ps1 [-Sure 10] [-Uyandir]   (Uyandir: once bekleme ekranini kapatip ana ekrani canlandirir)
param([int]$Sure = 10, [switch]$Uyandir, [string]$Device = $(if (Test-Path (Join-Path $PSScriptRoot 'cihaz.txt')) { (Get-Content (Join-Path $PSScriptRoot 'cihaz.txt') -TotalCount 1).Trim() } else { "" }))
$ADB = Join-Path $PSScriptRoot '_tools\platform-tools\adb.exe'
$d = @('-s', $Device)
if ($Uyandir) { & $ADB @d shell input keyevent KEYCODE_DPAD_UP | Out-Null; Start-Sleep -Milliseconds 800 }
& $ADB @d shell dumpsys gfxinfo com.perde.launcher reset | Out-Null
$top = & $ADB @d shell "top -b -d $Sure -n 2 -o %CPU,NAME | grep -E 'perde|surfaceflinger|system_server' | tail -3"
$g = & $ADB @d shell dumpsys gfxinfo com.perde.launcher
$g | Select-String 'Total frames rendered|Janky frames:|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed Vsync|Number Slow UI|Number Slow bitmap|Number Frame deadline' | ForEach-Object { $_.Line.Trim() }
$n = ($g | Select-String 'Total frames rendered: (\d+)').Matches[0].Groups[1].Value
"fps ~ " + [math]::Round([int]$n / $Sure, 1)
"islemci (tek cekirdek %):"; $top
