# Ekrana GERCEKTEN cikan Perde kareleri (SurfaceFlinger --latency): son ~127 karenin gosterim araliklari.
# Kullanim: .\ekran.ps1   (olcumden hemen once animasyonu baslatin; son 2 sn civarini gosterir)
param([string]$Device = $(if (Test-Path (Join-Path $PSScriptRoot 'cihaz.txt')) { (Get-Content (Join-Path $PSScriptRoot 'cihaz.txt') -TotalCount 1).Trim() } else { "" }))
$ADB = Join-Path $PSScriptRoot '_tools\platform-tools\adb.exe'
$layer = 'com.perde.launcher/com.perde.launcher.MainActivity#0'
$l = & $ADB -s $Device shell dumpsys SurfaceFlinger --latency "'$layer'"
$t = @()
foreach ($x in $l | Select-Object -Skip 1) {
    $p = $x.Trim() -split '\s+'
    if ($p.Count -ge 3 -and [int64]$p[1] -gt 0 -and [int64]$p[1] -lt [int64]::MaxValue) { $t += [int64]$p[1] }
}
$t = $t | Sort-Object
if ($t.Count -lt 3) { "yeterli veri yok"; return }
$d = @(); for ($i = 1; $i -lt $t.Count; $i++) { $d += ($t[$i] - $t[$i - 1]) / 1e6 }
$span = ($t[-1] - $t[0]) / 1e9
"gosterilen kare: $($t.Count)  sure: {0:N2} sn  ->  {1:N1} fps" -f $span, (($t.Count - 1) / $span)
$v1 = ($d | Where-Object { $_ -lt 20 }).Count; $v2 = ($d | Where-Object { $_ -ge 20 -and $_ -lt 37 }).Count; $v3 = ($d | Where-Object { $_ -ge 37 }).Count
"aralik: 1 vsync (16,7ms) = $v1 | 2 vsync (33ms) = $v2 | 3+ vsync = $v3"
