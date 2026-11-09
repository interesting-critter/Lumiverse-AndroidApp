# Lumiverse Android app

Tauri/Kotlin/Java host, embedded Bun runtime, Android native dependency builders,
and app verification tools. The APK supports local hosting and connecting to a
remote Lumiverse server. Native sources keep their `android/` layout.

## Background connections

Opening a local or hosted connection starts an **Active connections** foreground
service in the WebView process. It keeps a partial wake lock and leaves the
WebView resumed when switching apps or locking the screen, so generation tokens
and TTS can continue. The notification's **Close app** action ends this protection;
returning to the connection chooser also releases it. Backup restores and remote
hosting retain their separate service lifetimes.
Allow Android notifications to see the connection notification and its action.
The native **Close app** control also ends the session when notifications are blocked.

The matching backend frontend keeps heartbeats, token subscriptions and reconnects
active for protected Android sessions while still reporting background presence.
Hosted servers need that frontend update too. Force-stop, reboot, network outages
and manufacturer/Doze restrictions still require recovery. Keeping a connection
active uses more battery.

Device-only hosting remembers its private loopback port so restarting the app
preserves browser storage for extensions. A port conflict reports a startup
failure rather than silently choosing a new origin. This does not migrate storage
from older random ports or across explicit remote-hosting port changes.

Run the real-client background regression on an explicitly selected disposable
emulator with a test APK installed:

```sh
LUMIVERSE_BACKEND_DIR=/path/to/Lumiverse bun scripts/smoke-test-android-background-session.ts --serial emulator-5554 --seconds 180 --report .tmp/background-session.json
```

It checks Home and screen-off playback, live tokens, heartbeat, reconnect after a
server-side disconnect, quiet-session process reclamation, repeated picker cancel
and reopen, and notification/wake-lock release. DevTools is disconnected throughout
each background interval. `--baseline` records the old APK's behavior without
requiring protection to pass.

## Remote access

Create a local account with **Use this device**, then enable **Remote access** on
the connection page or in the native app controls (Android Back at the first page,
or hold two fingers). Choose a port from 1024–65535; the default is 7860. Other
clients on the same network can use the displayed address and sign in with the
local account. The backend listens on all IPv4 interfaces while enabled. HTTP
connections are unencrypted; use a trusted network or a VPN.

Remote access keeps the existing backend in a `specialUse` foreground service,
with a persistent notification and **Stop server** action. **Close app** closes
the frontend while leaving that server running. Turning remote access off
restarts the backend on loopback and reconnects an open local frontend. Wait for
backup restores to finish before changing the toggle. The port is remembered;
remote access is off by default. System reclamation can restart an enabled
service, but force-stop and reboot require reopening the app and enabling it again.

Connect to Wi-Fi, Ethernet, or a VPN before enabling access. If the network
addresses change, turn remote access off and on to refresh them. Android supplies
the exact addresses to the backend's trusted-origin list because Bun cannot
always enumerate interfaces on Android. Setup and diagnostics remain on loopback.

Android Doze and manufacturer battery management can still interrupt access;
**Battery settings** opens the system optimization list for users who need
screen-off access. Hosting holds a partial wake lock and uses more battery. It
does not bypass Doze. This app targets SDK 36; targeting SDK 37 will additionally
require the Android 17 `ACCESS_LOCAL_NETWORK` runtime permission.

## Setup and build

Use Bun 1.4.4, Node.js, Rust/rustup, Android Studio's JDK, Android SDK platform 36
or newer, build tools, and NDK r28c. Native LanceDB builds use Rust 1.97.0.
Configure `ANDROID_HOME`, `JAVA_HOME`, and `ANDROID_NDK_HOME`/`NDK_HOME` when they
are installed outside the build scripts' defaults.

Keep `Lumiverse` beside this checkout, or set `LUMIVERSE_BACKEND_DIR` to
its absolute path. Install that checkout's root and frontend dependencies with
its committed lockfiles. It supplies the server source, production dependency
manifest/lockfile, frontend, and user documentation at build time. The APK's
payload includes those required inputs, with server tests omitted; the app
repository does not duplicate them.

```sh
bun install --frozen-lockfile
bun run build:android-native
bun run build:android-tauri
```

The app uses its own pinned Tauri CLI and icon assets. The APK is written to
`dist/android/lumiverse-tauri-arm64.apk`. Build options include `--offline`,
`--skip-frontend-build`, `--runtime-only`, `--native-dir`, `--bun-zip`, `--sdk`,
and `--java-home`. `build:android-prototype --prepare-only` prepares the verified
runtime payload without invoking the Tauri/Gradle build.

Native dependency outputs live in `dist/native/android-arm64`; download/build
caches live in `.tmp/android-native`. For the GNU/glibc addon used by Termux
through grun or PRoot, run `bun run build:lancedb:termux`. Its outputs live in
`dist/native/termux-arm64` and its cache in `.tmp/termux-native`. See
[Termux build instructions](vendor/lancedb-android/BUILDING.md) for prerequisites.

## Local backup restores

`RuntimeService` owns the embedded server in its own Android process, connected
to the activity through a private Messenger binding. This also survives Tauri's
UI process exit when the activity closes. A restore
promotes it to a `dataSync` foreground service with progress, a cancel action, and
a partial wake lock while processing. Waiting for a decryption ticket releases
the wake lock. Returning through the notification selects local hosting and opens
Data Portability. An idle, detached service stops after 30 seconds when remote
access is disabled. With remote access enabled, the server's foreground service
also owns restore execution and displays a separate restore-progress notification.

The backend persists account-scoped job records and retains an interrupted
archive for an explicit **Resume import**. Resume replays merge operations,
publishes restored files atomically, and avoids duplicate vectors. Tickets and
decryption material are never written to job records. Android's foreground time
limit stops the runtime cleanly; force-stop and device restart also require
reopening the app. There is no automatic boot restart.

## Microphone access

The main WebView handles audio-capture requests through `NativeMicrophone` and
Android's `RECORD_AUDIO` runtime permission. Consent is scoped to the selected
server for the current activity. Only audio capture is granted; child browser
windows and requests from another origin are denied. Remote microphone use needs
HTTPS; the embedded loopback server is also supported. This enables recording and
recording-based speech providers; WebView support for browser speech recognition
is separate from microphone permission.

The device smoke test accepts `--background-restore` and `--microphone` alongside
`--attempt-backend --require-tauri`. Use a disposable, explicitly selected emulator
and `LUMIVERSE_ANDROID_TEST_PASSWORD` for an existing `prototypecheck` account. The
background check destroys the activity and turns off the screen; the microphone
check exercises refusal, Android permission approval, and recorded audio output.
Add `--background-connection` to verify the real embedded backend remains reachable
for 90 seconds with its screen off and survives quiet-session process reclamation.
This also checks the stable browser origin, committed browser storage and backend
settings across restart. The remote-access smoke checks those storage paths on a
hosted origin across **Close app** and reopen and accepts `--report path.json`.
For headless audio checks, `--microphone-virtual-tone` mixes a generated tone with
the granted live capture track so host microphone input can remain disabled.

After provisioning a disposable emulator with the backend smoke test and an
explicit `LUMIVERSE_ANDROID_TEST_PASSWORD`, run
`bun run scripts/smoke-test-android-remote-access.ts --serial emulator-PORT` with
the same password. It verifies the network address, remote login and WebSocket,
frontend close with the screen off beyond the idle timeout, turning access off,
the native toggle, and the notification's stop action.

## Remote notifications

Official builds use `android/firebase/google-services.json` for Firebase project
`lumiverse-bc911`, retaining application ID `chat.lumiverse.test`. Remote
backends default to the Lumiverse FCM relay without receiving Firebase provider
credentials. The relay must be deployed before releasing these clients. Users
still enable notifications explicitly; local hosting keeps its notification-only
socket and needs no Firebase secrets.

An operator can build with
`LUMIVERSE_ANDROID_FIREBASE_CONFIG=/path/to/google-services.json` to override the
bundled client configuration, then configure matching direct FCM credentials on
their backend. Provider private keys never belong in this repository or APK.
Firebase-project changes renew notification enrollment. Keep the existing
signing key, package and version-code continuity to preserve installed user data.

Relay enrollment verifies device possession through FCM and uses separate
delivery/management capabilities. Content keys and native capabilities are wrapped
with Android Keystore. Both new direct and relay enrollments decrypt authenticated
alerts before using the existing native renderer. Server/account switches clear
the active context immediately; background completion alerts do not depend on a
running WebView. Force-stop still blocks receipt until the app is reopened.

The backend changes must be adopted together with this APK. Update
`backend-source.json` after those changes have a committed revision; the private
nightly controller already builds current backend `staging`. Before rollout,
verify live Firebase delivery and an in-place upgrade over a populated existing
install using the same signing certificate.

## Home screen widgets

Android includes **Recent Characters** and **Favorite Characters**, matching the iOS home screen collections. Long-press the home screen, choose Widgets, and select Lumiverse. Resize either widget for a four, eight, or sixteen character portrait grid. Tapping a character resumes their latest eligible chat, or opens the character if they have no chat yet.

Widgets follow the server and account selected in the app, including **Use this device** and remote HTTP/HTTPS servers. Sign in once to enroll them. The bundled server payload and remote server must include the shared character widget frontend integration; an older server needs an update. No Firebase configuration or notification permission is required for widgets.

Collections refresh after app activity and approximately every 30 minutes in the background, subject to Android's scheduling and battery restrictions. A stopped bundled server or unavailable remote server leaves the last cached collection visible. A widget tap opens the app, where the bundled server starts through the normal connection flow. Logging out or changing servers clears the old collection. Widget backgrounds and accents follow the app theme after synchronization.

The native implementation uses Google's [AppWidgetProvider/RemoteViews APIs](https://developer.android.com/develop/ui/views/appwidgets), [responsive layouts](https://developer.android.com/develop/ui/views/appwidgets/layouts), and [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent). It reuses the existing `/api/ios-widgets/v1` read-only transport for compatibility with iOS, with private cached snapshots and an Android Keystore-encrypted scoped bearer. Background refresh never starts a foreground server service.

Native integration checks live in `CharacterWidgetsTest.kt`. Build with `:app:assembleUniversalDebugAndroidTest`, then run that class with AndroidJUnitRunner on an emulator or test device. The checks cover both connection modes, account isolation, invalidation races, redirects, portrait bounds, resizing, and taps across local port changes.

## Verify

```sh
bun run test
bun run test:backend
bun run typecheck
bun run scripts/smoke-test-android-connection.ts --serial emulator-5554
bun run scripts/smoke-test-android-prototype.ts --serial emulator-5554 --require-tauri --require-native --attempt-backend
```

`test:backend` tests first-time account setup against the separate backend
checkout in a temporary APK-shaped layout. The optional real-browser download
check is in `scripts/e2e-diagnostics` with its own package manifest; install its
dependencies and Chromium before running `bun run test` there. Its `--baseline`
option reads original app history from the backend repository.

## Local state and source control

The extraction preserves Gradle/Rust build state, downloaded native sources,
existing APKs, and `.tmp/android-prototype/debug.keystore`. Keeping that debug
key allows updates to existing development installs without losing app data.
Generated payloads, compiled libraries, caches, SDK paths, and signing files are
ignored. `LICENSE.md` is retained from the original project. This repository
starts from the extracted source snapshot; original history remains in
Lumiverse. Usage/connection guidance is in `android/README.md`.

## Continuous integration

`.github/workflows/ci.yml` runs independent unit/type checks on every push and
pull request. A separate integration job checks out the exact backend commit in
`backend-source.json` into `backend/`, sets `LUMIVERSE_BACKEND_DIR`, verifies the
checkout revision, and runs `test:backend`. No sibling checkout is needed for
`bun run test` / `bun run test:unit`.

`bun run test:ci` runs all local CI checks, including backend integration. That
command requires the backend checkout. Update the full SHA in
`backend-source.json` intentionally when adopting a newer backend contract;
missing checkouts and revision mismatches fail the integration job. CI does not
skip tests when required shared source is missing. Only the integration job
needs backend dependencies; native app builds and device smoke tests remain
separate from these source-level CI checks.

## Nightly APKs

[Public Android prereleases](https://github.com/prolix-oc/Lumiverse-AndroidApp/releases)
are built daily at 06:53 UTC by the private Lumiverse-Builds controller on the
physical Linux runner. Each build uses the latest Android `main` and backend
`staging` commits, installs their locked dependencies, runs app and backend
integration checks, and rebuilds the production frontend and complete ARM64 APK.
The release records both immutable source revisions, the APK checksum and build
provenance. Ordinary CI continues to use `backend-source.json`.

Nightlies use the existing development signing certificate and increasing
Android version codes, so they can update an installed development APK without
resetting app data. CI sets `LUMIVERSE_ANDROID_VERSION_CODE` and
`LUMIVERSE_ANDROID_VERSION_NAME` together; local builds retain Tauri versioning.
The private controller owns the schedule, SDK/NDK/Java/Rust container, caches and
release credentials. No public pull-request workflow uses the private runner.

The APK checks public Android nightly releases when opened and hourly while in
the foreground. Both scheduled and manually triggered builds are detected by
their Android version code, even when the base app version or source commits
have not changed. A native **Android app update available** prompt opens the
official release page with **View update**. Download its APK and install it over
the existing app to keep your data. Downloads and installation require user action.

Choose **Later** to defer that build's prompt for 24 hours; a newer build can
notify immediately. Use **Check for app updates** in the native controls (Android
Back at the first page, or hold two fingers) to check again at any time. Failed
automatic checks are quiet and retry after five minutes while the app is open.
Checks pause in the background and resume when the app is opened again.

These checks belong to the Android APK and also work on the connection chooser,
with local hosting, or while connected to a hosted server. They use the public
GitHub releases API without a token or backend account. Browser, desktop and iOS
clients do not run this checker. Existing APKs need an initial manual upgrade to
a build containing this feature before they can show update prompts.
