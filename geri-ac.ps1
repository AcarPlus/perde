# 2026-11-21'de devre disi birakilan on yuklu uygulamalari geri acar.
# Kullanim:  .\geri-ac.ps1                 (hepsini acar)
#            .\geri-ac.ps1 org.videolan.vlc (yalniz verilen paketi acar)
param([string[]]$Paket, [string]$Device = $(if (Test-Path (Join-Path $PSScriptRoot 'cihaz.txt')) { (Get-Content (Join-Path $PSScriptRoot 'cihaz.txt') -TotalCount 1).Trim() } else { "" }))
$ADB = Join-Path $PSScriptRoot '_tools\platform-tools\adb.exe'
if (-not $Paket) { $Paket = Get-Content (Join-Path $PSScriptRoot '_yedek\2026-11-21_kapatilan-uygulamalar.txt') }
foreach ($p in $Paket) { if ($p.Trim()) { & $ADB -s $Device shell pm enable --user 0 $p.Trim() } }
