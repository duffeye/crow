# Crow Camera for Karoo

An Android Bluetooth remote and Karoo ride-screen extension for the **Hammerhead Karoo 3** and **DJI Osmo Action 4**. No phone, Wi-Fi connection, DJI Mimo session, or cloud account is needed by the app.

## Current capabilities

- Discover cameras advertising DJI's supported remote protocol and select a specific camera.
- Confirm a pairing code on the camera; retain a per-install remote identity.
- Remember the last approved camera after live status arrives and reconnect when the app opens. Use **Reconnect** to retry or **Forget camera** to clear the saved camera.
- Trigger the shutter: start/stop video in Video mode, or take a photo in Photo mode.
- Select Photo or Video directly, without opening the camera's Quick Switch screen.
- Display camera-reported capture state, elapsed time, battery, and mode.
- Disable controls after five seconds without live status; serialize BLE writes and time out failed operations. Shutter commands are never automatically retried.

**Status:** the user has confirmed the 0.4.0 combined ride-screen extension and camera controls on Karoo 3. The separate-field styling and compact-layout fixes still need physical-device validation; the user has reported clipping in 0.5.1. No hardware is attached to this cloud workspace. No wake-from-sleep, GPS injection, video preview, or resolution settings yet.

## Compact fields (0.5.2)

The field chooser now uses short native headings: **Record**, **Photo**, **Status**, and **Mode**. Existing field IDs and ride-profile placements are preserved. The record value is Start/Stop, the photo action is Shoot, and status reads Ready, red REC, Offline, or No signal. Taller status fields also show battery and recording time/mode.

This update addresses bottom-clipped values in compact, two-column rows. Text fitting now budgets space for a single-line Karoo header, checks both width and font height, and hides the secondary line when there is insufficient room. Content uses its natural height instead of a weighted value filling the complete field. A measured-layout regression test covers 80–160 px rows with an oversized host text setting. Actual Karoo rendering still needs confirmation.

For a crowded metrics page, place **Record** and **Status** side by side. Put **Photo** and **Mode** on a second row or camera-focused page if needed. Use a taller Status field if battery/time should always remain visible. Test while stationary before relying on the controls during a ride.

## Karoo field styling (0.5.1)

All ride fields use Karoo's own header rendering, including the field label and a transparent monochrome camera icon. Custom values use bold condensed text, respect the profile's Left/Center/Right field alignment, and use the text size supplied by Karoo, reduced where necessary to fit a long label. The combined control panel caps its status font to leave room for touch targets.

Field backgrounds are transparent so Karoo can draw its page background and optional field boundaries. Secondary text is muted; disabled actions use a subdued value. Day/night text colours follow Android's UI mode. The combined field uses flat outlined text actions rather than raised Android buttons. Separate action fields use a subtle pressed state without an extra card border. Camera commands and field IDs are unchanged.

After installing, compare the fields beside a standard Karoo field at a small and large size, test profile alignment settings, and verify legibility with boundaries on/off and in the device's day/night modes. Karoo supplies the actual native headers; the custom text body is not the host's numeric renderer because these fields contain text and tap actions.

## Separate ride fields (0.5.0)

After installing 0.5.0, edit a ride profile's data page and choose any of these fields under **Crow Camera**. The existing combined **Camera control** field remains available, with its existing ID preserved.

| Field | Behavior |
| --- | --- |
| **Record** | Tap Start to select Video if needed and start recording after camera confirmation; tap Stop to stop an active video. |
| **Photo** | Tap to select Photo if needed, wait for confirmation, and trigger the photo shutter once. Disabled during recording/capture. The camera stays in Photo mode afterward. |
| **Status** | Read-only connection/capture state, battery percentage, and recording time. Shows Offline or No signal when unavailable. |
| **Mode** | Shows the current mode; tap to switch Photo/Video while idle. Disabled during capture. |

All fields use the same camera connection and update at 1 Hz. They can be placed independently and use text that scales to the available space. Very short fields hide the secondary detail line. Controls are disabled without fresh status or while a command is pending. A failed/timed-out/disconnected mode change cancels the requested capture; a later status update never replays it. A camera capture/playback/sleep transition during a pending mode change also cancels the capture. Profile previews have no click actions.

To test, place all four fields on one page. Start in Photo mode and use Start to verify Video switching followed by recording; confirm Stop works and Photo/Mode are disabled during recording. Then use Take photo from Video mode and verify the photo is saved. Check status, battery/time, mode toggling, and unavailable states after disconnecting. Keep your working 0.4.0 APK as a fallback.

## Ride-screen extension (0.4.0)

1. Install the new APK over Crow if Android accepts its signature. If Android rejects an update from a different debug signing key, uninstall the old build, install this one, and pair again. Keep your working 0.3.0 APK as a fallback.
2. Open **Crow Camera**, grant permissions, and connect to the camera once. Confirm live status.
3. Enable **Crow Camera** in Karoo's Extensions settings if required by your OS version.
4. Edit a data page in your ride profile. Add the **Crow Camera → Camera control** extension field. Start with a full-width, tall field (around half a page).
5. In the ride screen, use **Start recording**, **Stop recording**, or **Take photo**. Taller fields (at least 180 dp high and 160 dp wide) also show **Photo** and **Video** selectors. Smaller fields retain the shutter/status; very small fields may clip controls, so enlarge the field. Tap the status heading to open the full app.
6. For a compatible paired controller, assign Crow's **Camera shutter**, **Camera: Photo mode**, **Camera: Video mode**, or **Reconnect camera** bonus actions in Karoo's controller configuration.

The app and Karoo-bound extension share one camera session. After a real ride field or controller action activates it, the extension retains the connection between pages and when you leave the app, for as long as Karoo keeps the extension service bound. Disabling/unloading the extension releases its session; the camera continues any recording. Without an active extension, leaving the main app still disconnects. The app does not start an independent background service or automatically record when a ride begins.

Profile-editing previews show sample data and never initiate BLE connections or send commands. The ride field refreshes at the SDK's supported 1 Hz; all commands recheck fresh camera status, and mode changes are blocked during capture. Disconnected fields offer **Reconnect**. If the camera has no saved pairing or permissions are missing, open the full app to finish setup. A controller press while disconnected does not replay a shutter command after connection.

### Physical ride-screen checks

- Check preview/edit mode does not connect to or trigger the camera.
- With the camera awake, start a test ride and confirm the field's battery/mode and recording timer match the camera.
- Start/stop a short video and inspect the saved file. Select Photo, take a still, then return to Video.
- Change ride pages, open/close the Crow app, and return to the field; verify one connection stays usable.
- Turn the camera off or walk out of range: controls must stop working and show unavailable. Wake it, use Reconnect, and confirm status returns before using the shutter.
- Check a small and a large field, ride pause/resume, extension disable, and coexistence with cycling sensors. Verify configured controller actions separately.

## Build

### On GitHub

Open **Code → Codespaces → Create codespace on main**. The development container installs Java/Android build prerequisites, accepts the Android SDK licenses, and runs the tests, APK build, and lint. The first setup downloads the SDK and Gradle dependencies. Bluetooth hardware is not forwarded into Codespaces; download the APK and test on the physical Karoo.

GitHub Actions runs the same checks on pushes to `main` and pull requests. You can also run **Android checks** manually from the Actions tab. After a successful run, download **crow-debug-apk** from that run's Artifacts section. No repository secrets or paid Android build service are required. A GitHub account and available Codespaces/Actions quota are needed for those services.

### On your computer

Use JDK 21 and an Android SDK with platform 35 and build-tools 35.0.0. The app targets API 35 and supports Android 8/API 26 onward. The Hammerhead SDK is compiled from pinned Apache-licensed source in `third_party/karoo-ext`, so no GitHub Packages token is required. Dependencies resolve from Google Maven and Maven Central.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Set `ANDROID_HOME` to your SDK directory or set `sdk.dir` in an untracked `local.properties`. Install the debug APK with USB debugging enabled on the Karoo:

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build is for development; release signing and distribution are not configured.

## Automatic reconnection (0.3.0)

After installing this version, pair once with **Find camera** and wait for live camera status. Earlier versions did not save the camera address, so an existing 0.2.0 installation needs this one-time step too. The next time you open or return to the app, it attempts one connection to that camera. **Reconnect** retries after a timeout; **Find camera** lets you choose a different camera. **Disconnect** ends the current session. **Forget camera** disables automatic reconnection until a camera is paired again.

The camera must be awake, in range, and available over Bluetooth. The same remote identity is reused with DJI's reconnect verification mode; the camera remains responsible for approval and may ask for a code if its pairing history was cleared. A failed or rejected pairing never replaces the remembered camera. Reconnection does not start a recording or change camera mode. The active Karoo extension can now hold the session outside the app, as described above.

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

`Protocol.java` implements little-endian DJI R SDK framing, DJI's seeded CRC16/CRC32, and bounded notification reassembly. `CameraLink.java` manages discovery, GATT serialization, the two-way pairing handshake, status subscription, and acknowledged shutter key events and explicit Photo/Video mode selection. `MainActivity.java` provides large app controls. `CapturePlan.java` sequences dedicated recording/photo requests through a confirmed mode change and cancels them on failure. `CameraSession.java` shares a single application-context link between that activity and `CrowExtension.kt`, a Karoo-bound extension that emits RemoteViews and handles bonus actions. Immutable, explicit pending intents target a non-exported receiver for ride-field clicks. Shared `CameraControls.java` gates commands from every UI. Camera state comes from status pushes, not assumed successful writes.

Protocol reference: DJI's [Osmo GPS Controller demo](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo), inspected at commit `92fe23e5a749f189593f980a26a105c3bb66aa1c`, especially `docs/getting_started_guide.md`, `docs/protocol.md`, and `docs/protocol_data_segment.md`. Uses service FFF0, notifications FFF4, writes FFF5, pairing 0019, status subscription 1D05, status push 1D02, key events 0011, and direct mode selection 1D04. Unit tests include DJI's published pairing and mode-switch wire vectors as independent checksum/encoding references, plus fragmented, corrupt, and coalesced receive streams.

DJI's reference includes MIT terms and a DJI EULA notice; see [DJI-LICENSE.txt](DJI-LICENSE.txt). Crow is an independent project, unaffiliated with DJI or Hammerhead. Hammerhead's [Karoo Extensions](https://github.com/hammerheadnav/karoo-ext) SDK is included as source under Apache 2.0; see [third_party/karoo-ext/README.md](third_party/karoo-ext/README.md) for provenance and license. The upstream runtime source is unchanged; Crow supplies the build configuration.
