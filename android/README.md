# Lumiverse Android app

The ARM64 APK includes the Tauri client, Bun backend, and native libraries. On
first launch, choose **Use this device** to host locally or enter an existing
Lumiverse server's HTTP or HTTPS address and choose **Connect**. Enter the server
origin, such as `https://chat.example.com` or `http://192.168.1.10:7860`, without a
path or login credentials. Sign in through the server's normal login page.

The selected connection is saved for the next launch. Remote mode does not start
the embedded backend. Switching modes preserves the on-device database; remote
data remains on the selected server. HTTP connections are unencrypted.

An active connection keeps running when you switch apps or lock the screen.
Its **Lumiverse connection active** notification has a **Close app** action to
end the session and release its battery protection. Closing the app stops its
audio; an enabled remote server or ongoing backup restore can continue separately.
Allow notifications to see that action; the native **Close app** control also works
when notifications are blocked.
Android power restrictions and network outages can still interrupt a session.
Hosted servers need the updated frontend for background token delivery and
reconnection. Device-only hosting now preserves its browser storage origin when
restarting, which keeps extension browser settings on subsequent launches.

To let other clients connect to this device's backend, enable **Remote access**
on the connection page or in the native app controls. Create your local account
first. Choose a port (7860 by default), then use the displayed network address
and your local login on the other client. **Close app** closes the frontend and
keeps the server running while remote access is enabled. The persistent
notification has a **Stop server** action; turning the toggle off instead returns
to device-only access and reconnects the local frontend. Wait for any backup
restore to finish before changing the toggle.

Use a trusted network or a VPN: HTTP is unencrypted. If your network changes,
turn remote access off and on to refresh the address. **Battery settings** opens
Android's optimization settings; Doze or device-specific power management can
interrupt screen-off access even with the foreground service. Force-stop and
reboot require reopening Lumiverse and enabling remote access again.

Expand **Connection history** below the server address to see the ten most recent
successful remote connections saved on this device. Select an address, then tap
**Connect**, or choose **Remove** to delete an entry from history. App controls
also have a **Connection history** shortcut that opens the list while connected.

Remote mode uses the hosted server's frontend. Deploy the updated frontend on
that server to receive the scrolling and import-refresh fixes there too.

Character imports in the APK can select `.charx` files even when Android labels
them as ZIP archives or unknown files. These pickers show all file types;
Lumiverse validates the selected card when importing. Character exports retain
the `.charx` extension in Downloads and in a selected export folder.

Native clients skip the browser's silent audio unlock workaround. Notification
sounds and enabled TTS playback initialize audio only when a sound is requested;
touching the app or sending a text message does not play a silent file or buffer.

Layered card avatars use native gyro and accelerometer fusion for tilt, with the
same grip calibration and horizontal/vertical response as iOS. Motion stops in
the background and when animations are disabled. Devices without a suitable
gyro sensor keep the browser fallback. Remote connections need the matching
frontend deployed on their server; local hosting includes it in the APK.

To switch servers or return to local hosting, press Android Back at the first
page, or press and hold with two fingers to open the native app controls. The
controls also work when a server cannot load. Reload and Close app are available
there as well.

Data Portability imports automatically reload the frontend after completion so
database-backed views and extensions start from fresh data. The native app and
backend remain running. Imports are tracked outside the settings panel and
recover through polling after a dropped WebSocket, backgrounding, or a page
restart. The import result is shown again after reloading.

## Build and verify

Build with `bun run build:android-tauri`. The debug-signed APK is written to
`dist/android/lumiverse-tauri-arm64.apk`. Install it over an existing build signed
with the same development key to preserve local data.

On an explicitly selected test emulator with the APK installed:

```sh
bun run scripts/smoke-test-android-connection.ts --serial emulator-5554
bun run scripts/smoke-test-android-exports.ts --serial emulator-5554
bun run scripts/smoke-test-android-device-motion.ts --serial emulator-5554
bun run scripts/smoke-test-android-prototype.ts --serial emulator-5554 --require-tauri --require-native --attempt-backend
```

The connection smoke test checks HTTP/HTTPS navigation, cookies, saved selection,
native recovery, IPC isolation, and both connection modes. It finishes in local
mode. The backend smoke test checks native libraries, storage persistence, and
frontend startup. Add `--restore-backup` on a fresh disposable install, or supply
`LUMIVERSE_ANDROID_TEST_PASSWORD` for its existing `prototypecheck` test account,
to verify real backup restoration and the frontend reload.

The export smoke test verifies CHARX downloads and re-imports through the native
file picker. Add `--storage-recovery` to exercise the selected-folder fallback.
