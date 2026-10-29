# Crow Camera for Karoo

An initial standalone Android Bluetooth remote for the **Hammerhead Karoo 3** and **DJI Osmo Action 4**. No phone, Wi-Fi connection, DJI Mimo session, or cloud account is needed by the app.

## Current capabilities

- Discover cameras advertising DJI's supported remote protocol and select a specific camera.
- Confirm a pairing code on the camera; retain a per-install remote identity.
- Remember the last approved camera after live status arrives and reconnect when the app opens. Use **Reconnect** to retry or **Forget camera** to clear the saved camera.
- Trigger the shutter: start/stop video in Video mode, or take a photo in Photo mode.
- Select Photo or Video directly, without opening the camera's Quick Switch screen.
- Display camera-reported capture state, elapsed time, battery, and mode.
- Disable controls after five seconds without live status; serialize BLE writes and time out failed operations. Shutter commands are never automatically retried.

**Status:** prototype; the user has confirmed pairing, video start/stop, direct mode selection, and still capture on a Galaxy S23 with the Action 4. Automatic reconnection remains unverified on hardware; APK compilation and JVM checks are separate from physical-device validation. No Karoo/Action 4 hardware has been connected in this cloud workspace. Pairing, camera firmware compatibility, BLE packet fragmentation, and coexistence with cycling sensors require the checklist below. This is a launcher app, not yet a Karoo ride-screen extension. It disconnects when backgrounded and does not stop a camera recording when disconnected. No wake-from-sleep, GPS injection, video preview, resolution settings, or background reconnection yet.

## Build

### On GitHub

Open **Code → Codespaces → Create codespace on main**. The development container installs Java/Android build prerequisites, accepts the Android SDK licenses, and runs the tests, APK build, and lint. The first setup downloads the SDK and Gradle dependencies. Bluetooth hardware is not forwarded into Codespaces; download the APK and test on the physical Karoo.

GitHub Actions runs the same checks on pushes to `main` and pull requests. You can also run **Android checks** manually from the Actions tab. After a successful run, download **crow-debug-apk** from that run's Artifacts section. No repository secrets or paid Android build service are required. A GitHub account and available Codespaces/Actions quota are needed for those services.

### On your computer

Use JDK 21 and an Android SDK with platform 35 and build-tools 35.0.0. The app targets API 35 and supports Android 8/API 26 onward. No Hammerhead package-registry token is required because this version uses standard Android BLE APIs.

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
```

Set `ANDROID_HOME` to your SDK directory or set `sdk.dir` in an untracked `local.properties`. Install the debug APK with USB debugging enabled on the Karoo:

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build is for development; release signing and distribution are not configured.

## Automatic reconnection (0.3.0)

After installing this version, pair once with **Find camera** and wait for live camera status. Earlier versions did not save the camera address, so an existing 0.2.0 installation needs this one-time step too. The next time you open or return to the app, it attempts one connection to that camera. **Reconnect** retries after a timeout; **Find camera** lets you choose a different camera. **Disconnect** ends the current session. **Forget camera** disables automatic reconnection until a camera is paired again.

The camera must be awake, in range, and available over Bluetooth. The same remote identity is reused with DJI's reconnect verification mode; the camera remains responsible for approval and may ask for a code if its pairing history was cleared. A failed or rejected pairing never replaces the remembered camera. Reconnection does not start a recording, change camera mode, or run in the background.

To test, pair, leave the app, and reopen it with the camera awake. Check live status returns without another code prompt. Repeat with the camera off: the attempt must time out and allow **Reconnect** after waking it. Test **Forget camera**, a declined pairing, and switching cameras too.

## First physical-device test

1. Activate/update the Action 4 using DJI's normal setup if needed. Turn it on and disconnect other camera remotes/Mimo; the camera BLE connection can be occupied by another controller.
2. Open Crow Camera on Karoo. Grant Bluetooth permissions (on older Android versions, scanning requires Location permission and Location enabled).
3. Tap **Find camera**, select the intended camera, compare the four-digit code, and approve on the camera screen.
4. Check battery and mode against the camera. If no camera appears, check its wireless settings and firmware; discovery uses DJI manufacturer data, not a device-name guess.
5. Select **Video mode**, then start a short recording. Verify recording on the camera itself, elapsed-time updates, stopping, and that a playable file was saved.
6. Tap **Photo mode**, wait for the camera-reported mode to change and the shutter to read **Take photo**, then tap it and verify the saved photo. Mode selection is disabled during recording. Other camera modes deliberately disable the shutter in this version.
7. Walk out of range or turn off the camera. Confirm controls disable; wake the camera and tap **Reconnect**. Background the app during a recording and verify the camera continues recording; reopen and check it reconnects automatically before controlling it.
8. Repeat with Karoo cycling sensors connected. Record Karoo OS and Action 4 firmware versions and any pairing or command error messages.

## Protocol and architecture

`Protocol.java` implements little-endian DJI R SDK framing, DJI's seeded CRC16/CRC32, and bounded notification reassembly. `CameraLink.java` manages discovery, GATT serialization, the two-way pairing handshake, status subscription, and acknowledged shutter key events and explicit Photo/Video mode selection. `MainActivity.java` provides large foreground controls. Camera state comes from status pushes, not assumed successful writes.

Protocol reference: DJI's [Osmo GPS Controller demo](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo), inspected at commit `92fe23e5a749f189593f980a26a105c3bb66aa1c`, especially `docs/getting_started_guide.md`, `docs/protocol.md`, and `docs/protocol_data_segment.md`. Uses service FFF0, notifications FFF4, writes FFF5, pairing 0019, status subscription 1D05, status push 1D02, key events 0011, and direct mode selection 1D04. Unit tests include DJI's published pairing and mode-switch wire vectors as independent checksum/encoding references, plus fragmented, corrupt, and coalesced receive streams.

DJI's reference includes MIT terms and a DJI EULA notice; see [DJI-LICENSE.txt](DJI-LICENSE.txt). Crow is an independent project, unaffiliated with DJI or Hammerhead. Hammerhead's [Karoo Extensions](https://github.com/hammerheadnav/karoo-ext) is the planned route for later ride-screen integration.
