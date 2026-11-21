# Projeksiyonu Perde öncesi haline döndürür.
#   .\geri-don.ps1            -> varsayılan ana ekranı HTC launcher yapar (Perde kurulu kalır)
#   .\geri-don.ps1 -Kaldir    -> ayrıca Perde'nin verdiğin yetkilerini geri alır ve Perde'yi tamamen kaldırır
# ADB yoksa: Perde › Diğer › Uyku zamanlayıcısı › "Uyutma yetkisini kaldır", sonra Ayarlar › Uygulamalar › Perde › Kaldır.
param([switch]$Kaldir, [string]$Device = $(if (Test-Path (Join-Path $PSScriptRoot 'cihaz.txt')) { (Get-Content (Join-Path $PSScriptRoot 'cihaz.txt') -TotalCount 1).Trim() } else { "" }))
$adb = Join-Path $PSScriptRoot '_tools\platform-tools\adb.exe'
& $adb connect $Device
& $adb -s $Device shell cmd package set-home-activity com.htc.htclauncherhighenglishd08/com.htc.activity.HomeActivity
if ($Kaldir) {
    # cihaz yöneticisi (uyku zamanlayıcısı) açıksa kaldırma engellenir; önce o, sonra bildirim erişimi
    & $adb -s $Device shell dpm remove-active-admin com.perde.launcher/.AdminReceiver
    & $adb -s $Device shell cmd notification disallow_listener com.perde.launcher/com.perde.launcher.MediaWatcher
    & $adb -s $Device uninstall com.perde.launcher
}
& $adb -s $Device shell input keyevent HOME
Write-Host "Tamam: ana ekran HTC launcher."
