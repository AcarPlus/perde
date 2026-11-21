# Kare asamalarinin ortalama suresi (ms): gfxinfo framestats ayristirma.
param([int]$Sure = 5, [switch]$Uyandir, [string]$Device = $(if (Test-Path (Join-Path $PSScriptRoot 'cihaz.txt')) { (Get-Content (Join-Path $PSScriptRoot 'cihaz.txt') -TotalCount 1).Trim() } else { "" }))
$ADB = Join-Path $PSScriptRoot '_tools\platform-tools\adb.exe'
$d = @('-s', $Device)
if ($Uyandir) { & $ADB @d shell input keyevent KEYCODE_DPAD_UP | Out-Null; Start-Sleep -Milliseconds 800 }
& $ADB @d shell dumpsys gfxinfo com.perde.launcher reset | Out-Null
Start-Sleep -Seconds $Sure
$lines = & $ADB @d shell dumpsys gfxinfo com.perde.launcher framestats
$hdr = $null; $rows = @()
foreach ($l in $lines) {
    if ($l -match '^Flags,IntendedVsync') { $hdr = $l.Trim(',').Split(','); continue }
    if ($hdr -and $l -match '^0,\d') { $rows += , ($l.Trim(',').Split(',')) }
    if ($l -match '---PROFILEDATA---' -and $rows.Count) { break }
}
function ix($n) { [array]::IndexOf($hdr, $n) }
$st = @{ 'girdi+anim' = @('HandleInputStart', 'PerformTraversalsStart'); 'olcu/yerlesim' = @('PerformTraversalsStart', 'DrawStart');
    'cizim kaydi (onDraw)' = @('DrawStart', 'SyncQueued'); 'sync (bitmap/katman)' = @('SyncStart', 'IssueDrawCommandsStart');
    'GPU komut (RenderThread)' = @('IssueDrawCommandsStart', 'SwapBuffers'); 'swap' = @('SwapBuffers', 'FrameCompleted');
    'TOPLAM' = @('IntendedVsync', 'FrameCompleted') }
"kare sayisi: $($rows.Count)"
foreach ($k in $st.Keys) {
    $a = ix $st[$k][0]; $b = ix $st[$k][1]
    $v = $rows | ForEach-Object { ([double]$_[$b] - [double]$_[$a]) / 1e6 }
    "{0,-26} ort {1,6:N2}  maks {2,6:N2}" -f $k, ($v | Measure-Object -Average).Average, ($v | Measure-Object -Maximum).Maximum
}
