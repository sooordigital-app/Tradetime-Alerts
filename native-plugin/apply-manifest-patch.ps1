<#
  apply-manifest-patch.ps1
  -------------------------
  Run this from the project root (the folder that contains the "android"
  folder), AFTER "npx cap add android" has already generated the Android
  project once.

  It adds to android/app/src/main/AndroidManifest.xml:
    - The permissions AlertMonitorService needs (foreground service,
      notifications, wake lock).
    - The <service> declaration for AlertMonitorService itself.

  Safe to run more than once - it checks for its own markers first and
  skips anything already applied.
#>

$manifestPath = "android\app\src\main\AndroidManifest.xml"

if (-not (Test-Path $manifestPath)) {
    Write-Error "Could not find $manifestPath - run this from the project root, after 'npx cap add android'."
    exit 1
}

$content = Get-Content $manifestPath -Raw

$permAnchor = '<uses-permission android:name="android.permission.INTERNET" />'
$extraPerms = @"
$permAnchor
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
"@

if ($content -match 'FOREGROUND_SERVICE_DATA_SYNC') {
    Write-Host "Permissions already patched - skipping that part."
} elseif ($content -match [regex]::Escape($permAnchor)) {
    $content = $content -replace [regex]::Escape($permAnchor), $extraPerms
    Write-Host "Added foreground-service / notification / wake-lock permissions."
} else {
    Write-Warning "Could not find the INTERNET permission line to anchor on. Add these lines manually inside <manifest>...</manifest> (as siblings of <application>, not inside it):"
    Write-Warning '  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />'
    Write-Warning '  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />'
    Write-Warning '  <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />'
    Write-Warning '  <uses-permission android:name="android.permission.WAKE_LOCK" />'
}

$serviceTag = @"
        <service
            android:name="com.tradetime.alerts.AlertMonitorService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
    </application>
"@

if ($content -match 'AlertMonitorService') {
    Write-Host "Service declaration already patched - skipping that part."
} elseif ($content -match '</application>') {
    $content = $content -replace '</application>', $serviceTag
    Write-Host "Registered AlertMonitorService in the manifest."
} else {
    Write-Warning "Could not find </application> to anchor on. Add this manually just before </application>:"
    Write-Warning '  <service android:name="com.tradetime.alerts.AlertMonitorService" android:enabled="true" android:exported="false" android:foregroundServiceType="dataSync" />'
}

Set-Content -Path $manifestPath -Value $content -NoNewline -Encoding UTF8
Write-Host ""
Write-Host "Done. Current manifest saved to $manifestPath"
