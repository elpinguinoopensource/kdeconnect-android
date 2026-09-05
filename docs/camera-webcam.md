# Camera-as-Webcam — implementation status & development notes

Living document for the **Camera as webcam** feature (Phase 0). Tracks overall
implementation state, the wire protocol, build/test recipes and hard-won
hardware gotchas. Keep this file updated when touching the camera plugin.

Desktop (receiver) counterpart: `elpinguinoopensource/kdeconnect` →
`docs/camera-webcam.md` and `plugins/camera/README.md`.

## Status (2026-09-04)

| Milestone | State |
|---|---|
| CAM-1..8 Android plugin (protocol, catalog, session, notification, watchdog, share UI, spike, instrumented test) | ✅ done |
| CAM-9 same-thread payload send fix | ✅ done |
| CAM-10 CameraShareActivity | ✅ done |
| CAM-11 drop-oldest backpressure | ✅ done |
| Low-latency encoder hints + Qualcomm cascade | ✅ done |
| Phase 0 validated end-to-end on real hardware | ✅ **GREEN** |
| PR | [kdeconnect-android#1](https://github.com/elpinguinoopensource/kdeconnect-android/pull/1) |

Validated on **Redmi Note 9S** (SM6125, Android 11/API 31) paired with the
desktop plugin over LAN: stable **640×480 @ 30 fps**, ~4 Mbps, `buffered=0`
on the sender, live frames verified on `/dev/video10` (not black/frozen),
clean teardown on stop.

## Pipeline

```
Camera2 ──► MediaCodec H.264 (Annex-B, low-latency cascade)
        ──► StreamedPayloadInputStream (4 KB chunks)
        ──► kdeconnect.camera.stream over the TCP/SSL payload socket (TCP_NODELAY)
        ──► desktop plugin ──► ffmpeg pipe ──► /dev/videoN (v4l2loopback)
```

## Wire protocol

Packet types (see `CameraProtocol.kt` for the single source of truth):

| Type | Direction | Notes |
|---|---|---|
| `kdeconnect.camera.list` | desktop→android, android→desktop | request / reply with `cameras` array |
| `kdeconnect.camera.start` | desktop→android | body: `cameraId, width, height, fps, bitrate` (bitrate in **bps**) |
| `kdeconnect.camera.stream` | android→desktop | carries the H.264 payload (`payloadSize=-1`, streamed); body repeats `cameraId/width/height/fps` |
| `kdeconnect.camera.stop` | desktop→android | also sent by the phone when the watchdog or user stops the session |
| `kdeconnect.camera.error` | android→desktop | `error` ∈ `in_use` \| `denied` \| `unsupported` \| `disconnected` \| `stopped` |

Camera list entry: `{ cameraId, facing: front|back|external, hasFlash, sizes: [{width, height, fps}] }`.

Payload framing: raw Annex-B access units. The Qualcomm encoder prefixes each
AU with an empty AUD NAL (`00 00 00 01 00`) — the desktop side must tolerate
this (see gotchas in the desktop doc).

## Architecture map (this repo)

`src/main/java/org/kde/kdeconnect/plugins/camera/`

| File | Responsibility |
|---|---|
| `CameraProtocol.kt` | wire constants (packet types, body keys, error values) |
| `CameraPlugin.kt` | packet dispatch, capability advertisement, service coordination |
| `Camera2Catalog.kt` / `CameraCatalog.kt` | camera enumeration + size/fps filtering |
| `CameraSession.kt` | Camera2 capture + MediaCodec encode; low-latency format **cascade**; stats logging; stop semantics |
| `StreamedPayloadInputStream.kt` | encoder output → chunked InputStream for the payload socket |
| `AnnexBUtils.kt` | start-code handling, SPS/PPS/IDR framing helpers |
| `CameraNotificationFactory.kt` / `CameraNotificationReceiver.kt` | persistent "camera shared" notification + Stop action |
| `CameraShareActivity.kt` | phone-side UI to start/stop sharing |

Also touched: `BackgroundService.kt` (FGS `camera` type promote/demote),
`AndroidManifest.xml` (`FOREGROUND_SERVICE_CAMERA`, activities, receiver),
`LanLink.java` (`setTcpNoDelay(true)` on the payload socket),
`src/debug/.../CameraSpikeActivity.kt` (encoder bring-up harness, debug builds only).

Tests: 5 JVM classes under `src/test/.../camera/` (protocol, Annex-B, size
filter, stall detector, payload stream) + `src/androidTest/.../CameraSessionInstrumentedTest.kt`
(drives the real production `CameraSession` on-device).

## Build & test recipes

JDK **21** is required (system default may be newer; `~/.gradle/gradle.properties`
pins `org.gradle.java.home`):

```sh
JAVA_HOME=/usr/lib64/jvm/java-21-openjdk-21 ./gradlew compileDebugKotlin -q
JAVA_HOME=/usr/lib64/jvm/java-21-openjdk-21 ./gradlew testDebugUnitTest --tests '*camera*' -q
JAVA_HOME=/usr/lib64/jvm/java-21-openjdk-21 ./gradlew assembleDebug -q
```

Debug APK: `build/outputs/apk/debug/`. App id for debug builds is
`org.kde.kdeconnect_tp.debug`.

### Instrumented tests (on-device)

Gradle `connectedAndroidTest` re-installs the main APK and re-triggers the
MIUI install gate — run against the already-installed APK instead:

```sh
./gradlew :assembleDebugAndroidTest
adb install -r build/outputs/apk/androidTest/debug/*.apk
adb shell am instrument -w \
  -e class org.kde.kdeconnect.plugins.camera.CameraSessionInstrumentedTest \
  org.kde.kdeconnect_tp.debug/androidx.test.runner.AndroidJUnitRunner
```

### MIUI install gate (Redmi Note 9S)

MIUI throws `INSTALL_FAILED_USER_RESTRICTED` unless the AdbInstallActivity
dialog is approved within an 8 s countdown. Helper (kept out of the repo; see
below) auto-taps "Recordar mi elección" + "Instalar". After every reinstall:

```sh
adb shell pm grant org.kde.kdeconnect_tp.debug android.permission.CAMERA
```

## E2E test loop (with desktop)

1. Desktop: load loopback + run the test daemon (see desktop doc), pair phone.
2. Start a stream from the phone UI (device page → Camera sharing) or from
   desktop DBus: `startCamera "<cameraId>" <w> <h> <fps> <bitrate_bps>` —
   on the test phone `cameraId "1"` is the back camera.
3. Verify on desktop: `streaming` property, ffmpeg child exists, frames on
   `/dev/video10` (recipes in the desktop doc).
4. Phone-side logcat: `adb logcat -s KDE/CameraSession` — periodic
   `frames=… bytes=… buffered=…`; `buffered=0` means the sender is not the
   bottleneck.

## Hardware & platform gotchas (read before "optimizing")

- **Qualcomm low-latency keys lie**: `KEY_LOW_LATENCY` / `KEY_PRIORITY` are
  accepted by `setInteger()` but `configure()` then fails with EINVAL on
  SM6125. `CameraSession` uses a **format cascade** (hints → hints-lite →
  plain) re-configuring the *same* MediaCodec instance; `setCallback()` must
  happen exactly once before the loop.
- **Profile=High, not Baseline**: `KEY_PROFILE` only applies on SDK≥34; on
  older devices the encoder emits High. Harmless — all desktop decoders accept it.
- **MediaCodec `KEY_I_FRAME_INTERVAL`** must be set via `setLong`, not `setInt`.
- **Camera2 callbacks must not run on the session handler thread** (latch
  self-deadlock); use a main-looper handler + `resourceLock` + late-callback
  guards (proven on this OEM HAL by session2 opening 201 ms after session1 stop).
- **`sendPacket()` closes payloads early**: its finally-block closes the
  payload right after the header write. Streaming payloads MUST use
  `device.sendPacketBlocking(np, cb, true)` on a background thread (CAM-9).
- **Android 14+**: camera foreground-service type is mandatory;
  `BackgroundService` promotes/demotes `FOREGROUND_SERVICE_TYPE_CAMERA` around
  an active session. On API 23 use the deprecated `stopForeground(true)`
  (`stopForeground(int)` is API 24).
- **Nagle**: payload socket sets `TCP_NODELAY`; without it every 4 KB chunk
  tail waits ~40 ms for the previous ACK.
- **minSdk 23 / target 37** — check API levels before using newer calls.

## Known gaps / next steps

- [ ] `FLAG_KEEP_SCREEN_ON` while sharing (screen may sleep mid-stream).
- [ ] Measure true end-to-end latency (optical-clock test: film a phone clock
      with a second camera, or network-ping-adjacent method).
- [ ] Re-measure throughput notes: earlier cross-subnet tests (~10 Mbps) were
      not representative; same-LAN L2 typically >100 Mbps.
- [ ] Phase 3 polish (desktop side): expose `devicePath` over DBus, mid-stream
      bitrate/resolution changes, camera selection by facing in UI.
- [ ] Upstream coordination: overlaps conceptually with KDE Connect MR
      `invent.kde.org/network/kdeconnect-kde!251` (remotevideo) — distinct
      protocol (`kdeconnect.remotevideo.*`), no packet collision, but worth
      aligning before upstreaming.

## Resources

- Plan & task specs (kept local, gitignored): `memory/plan-camera-webcam.md`,
  `memory/tasks/CAM-*.md`, `memory/camera-spike-howto.md`, `memory/miui_install.sh`.
- Upstream: <https://invent.kde.org/network/kdeconnect-android> (patches go here).
- KDE Connect community wiki (dev setup, contacts): <https://community.kde.org/KDEConnect>.
- Bug tracker: <https://bugs.kde.org> (product: kdeconnect).
- Android docs: Camera2 <https://developer.android.com/media/camera/camera2>,
  MediaCodec <https://developer.android.com/reference/android/media/MediaCodec>,
  FGS camera type <https://developer.android.com/guide/components/foreground-services>.
- H.264 Annex-B / NAL reference: ITU-T H.264 §B.1, start codes & AUD.
- Desktop requirements (v4l2loopback etc.): `kdeconnect` repo
  `plugins/camera/README.md`.
