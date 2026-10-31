# ED22 Kiosk

An Android kiosk app that locks a device to a full-screen browser starting at
**https://us.ed22.org/me**. It gives ed22.org pages a `window.kiosk` JavaScript API for
system information and device features, reports every page visit to
**https://us.ed22.org/me/history**, and hides a PIN-protected parent menu behind
**4 quick taps in the bottom-left corner**.

## Contents

- [Installing on a device](#installing-on-a-device)
- [The parent menu](#the-parent-menu)
- [Browsing and history reporting](#browsing-and-history-reporting)
- [The `kiosk.*` JavaScript API](#the-kiosk-javascript-api)
- [Automatic updates](#automatic-updates)
- [Building](#building)
- [Configuration](#configuration)

## Installing on a device

The app works best as **device owner**, which is what blocks the home button, recent apps,
the notification shade, safe mode and factory reset. Android only allows a device owner to
be set on a device with **no accounts on it**, so start from a fresh or factory-reset device.

1. **Factory reset** the device (or start from a new one). During setup, connect to Wi-Fi
   but **skip signing in to a Google account**.
2. Turn on **Developer options** (Settings › About › tap *Build number* 7 times), then turn on
   **USB debugging**.
3. Connect the device to a computer with [adb](https://developer.android.com/tools/releases/platform-tools)
   and install the app:
   ```sh
   adb install ed22-kiosk.apk
   ```
4. Make the app device owner:
   ```sh
   adb shell dpm set-device-owner org.ed22.kiosk/.KioskAdminReceiver
   ```
   It should print `Success: Device owner set to package org.ed22.kiosk`. If it says there are
   already accounts or users on the device, remove them (or factory reset) and try again.
5. Open **ED22 Kiosk**. Set the parent PIN (4 to 8 digits). The device locks into the kiosk
   and opens https://us.ed22.org/me.
6. (Optional) Leave USB debugging on so you can push updates with adb later. Kids can't reach
   Settings from the kiosk, so it is safe to keep.

After this the kiosk starts on every boot, acts as the home screen, and keeps the screen on
while charging.

### Updating

Kiosks update themselves (see [Automatic updates](#automatic-updates)). To update one by hand:

```sh
adb install -r ed22-kiosk.apk
```

Updates must be signed with the same key as the installed app (see [Building](#building)), and
must have a higher `versionCode`.

### Without device owner

If you skip step 4 the app still runs, but it is just a full-screen browser: the home and
recents buttons will leave it. The PIN screen shows a warning in that case.

## The parent menu

Tap the **bottom-left corner 4 times within 3 seconds** (the target is about 80 dp square).
Taps still reach the page, so the gesture never blocks the site. Enter the parent PIN to get:

| Option | What it does |
| --- | --- |
| Exit kiosk mode | Unlocks the device and goes to the normal home screen. Opening ED22 Kiosk again, or restarting, locks it again. |
| Open Wi-Fi settings | Exits kiosk mode and opens Wi-Fi settings. |
| Reload start page | Goes back to https://us.ed22.org/me. |
| Change PIN | Sets a new parent PIN. |
| Updates | Shows the installed version, the last update check and its result, with a **Check for updates now** button. |
| Remove kiosk lock | Removes device-owner status so the app can be uninstalled. Locking again needs a factory reset. |

Five wrong PINs in a row lock the prompt for 60 seconds.

## Browsing and history reporting

There is **no domain allowlist**: the kiosk starts on us.ed22.org but links can go anywhere on
the web. Links that would leave the app (`tel:`, `mailto:`, Play Store and other app links)
are ignored; `intent:` links open their web fallback if they have one.

Every **top-level page visit** (including single-page-app route changes, but not iframes) is
sent as a `POST` to `https://us.ed22.org/me/history`:

```http
POST /me/history HTTP/1.1
Host: us.ed22.org
Content-Type: application/json; charset=utf-8
X-Kiosk-Device-Id: 9774d56d682e549c
Cookie: <the WebView's cookies for us.ed22.org, so you know which user is signed in>

{
  "url": "https://www.example.com/page",
  "host": "www.example.com",
  "title": "Example page",
  "referrer": "https://us.ed22.org/me",
  "visitedAt": "2026-10-30T19:30:12.345Z",
  "deviceId": "9774d56d682e549c",
  "deviceModel": "samsung SM-X200",
  "appVersion": "0.1.0"
}
```

- Any `2xx` response counts as delivered.
- If the device is offline, or the server answers `401`, `403`, `408`, `429` or `5xx`, the
  visit is queued on the device (up to 500) and retried when the network comes back or the
  next visit happens.
- Other `4xx` responses drop the visit so a bad record can't block the queue.
- `title` can be `null` if the page hasn't set one within 1.5 seconds.

## The `kiosk.*` JavaScript API

`window.kiosk` exists only on **https://ed22.org** and **https://\*.ed22.org** pages, and is
there before any of the page's own scripts run. Other sites never see it, and they never get
automatic camera, microphone or location access. Every call returns a Promise.

```js
if (window.kiosk) {
  const battery = await kiosk.getBattery();
  console.log(`Battery ${battery.level}%`, battery.charging ? 'charging' : '');
}
```

### Information

| Call | Returns |
| --- | --- |
| `kiosk.ready` | Promise that resolves once connected; also fires a `kioskready` event on `window`. |
| `kiosk.version`, `kiosk.deviceId` | Filled in once `kiosk.ready` resolves. |
| `kiosk.getBattery()` | `{level, charging, full, plugged, health, temperatureC, voltageMv}` |
| `kiosk.getNetwork()` | `{online, type, metered, downstreamKbps, upstreamKbps}` (`type` is `wifi`, `cellular`, `ethernet`, `vpn`, `other` or `none`) |
| `kiosk.getWifi()` | `{enabled, connected, ssid, bssid, rssi, signalLevel (0-4), linkSpeedMbps, frequencyMhz, ipAddress}` |
| `kiosk.getDevice()` | `{deviceId, serial, manufacturer, brand, model, device, androidVersion, sdkInt, securityPatch, appVersion, isDeviceOwner, lockTaskActive, locale, timeZone, uptimeMs, screen}` |
| `kiosk.getStorage()` | `{totalBytes, freeBytes}` |
| `kiosk.getMemory()` | `{totalBytes, availableBytes, lowMemory}` |
| `kiosk.getLocation({highAccuracy})` | `{latitude, longitude, accuracyM, altitudeM, speedMps, bearing, provider, timestamp}` |

### Events

```js
kiosk.on('battery', b => ...);   // level, charging or plug state changed
kiosk.on('network', n => ...);   // connectivity changed (same shape as getNetwork)
kiosk.off('battery', handler);
```

### Permissions, camera and microphone

Pages on ed22.org can use the **standard web APIs**: `navigator.mediaDevices.getUserMedia()`
for camera and microphone, `navigator.geolocation`, and `devicemotion`/`deviceorientation`
events. As device owner the app grants the Android permissions silently, so kids never see a
prompt. Without device owner, Android shows its normal prompt the first time.

| Call | Returns |
| --- | --- |
| `kiosk.permissions.query()` | `{camera, microphone, location, phone, activity}` as booleans |
| `kiosk.permissions.request(['camera', 'microphone'])` | Same shape, after asking |

### Sensors

```js
const types = await kiosk.sensors.list();           // what this device has
const id = await kiosk.sensors.watch('accelerometer', r => {
  const [x, y, z] = r.values;
}, { rateMs: 100 });
kiosk.sensors.clear(id);
```

Sensor types: `accelerometer`, `gyroscope`, `magnetometer`, `gravity`, `linear_acceleration`,
`rotation_vector`, `game_rotation_vector`, `light`, `proximity`, `pressure`, `humidity`,
`ambient_temperature`, `step_counter`, `step_detector`. Each reading is
`{watchId, type, values, accuracy, timestamp}`. Watches stop when the page navigates away
and pause while the screen is off.

### Device controls

| Call | Effect |
| --- | --- |
| `kiosk.vibrate(ms)` or `kiosk.vibrate([on, off, on, ...])` | Vibrate |
| `kiosk.setBrightness(0.0-1.0)` | Screen brightness for the kiosk; `-1` returns to the system setting |
| `kiosk.getVolume()` / `kiosk.setVolume(0.0-1.0, stream)` | `stream` is `music` (default), `ring`, `alarm`, `notification`, `system` or `call` |
| `kiosk.setKeepScreenOn(true/false)` | Keep the screen awake (on by default) |
| `kiosk.setTorch(true/false)` | Flashlight |
| `kiosk.setOrientation('portrait'/'landscape'/'auto')` | Lock or free screen rotation |
| `kiosk.toast(text)` | Show a short Android message |
| `kiosk.speak(text)` | Text to speech |
| `kiosk.reload()` / `kiosk.goHome()` | Reload, or go back to the start page |
| `kiosk.reboot()` | Restart the device (device owner only) |
| `kiosk.call(method, args)` | Low-level access to any of the above by name |

[`docs/kiosk-test.html`](docs/kiosk-test.html) exercises every call. Host it anywhere on
ed22.org and open it in the kiosk.

## Automatic updates

**To ship an update, push to `main`.** GitHub Actions builds a signed APK and publishes it with
an update manifest. Each kiosk checks that manifest at app start and every 6 hours (when it has
a network), and installs anything newer.

- **Version numbers:** CI sets `versionCode` to the workflow run number + 100 and `versionName`
  to `0.1.<versionCode>`. Every push to main is therefore newer than the last. Local builds
  are `versionCode` 1 unless you set `KIOSK_VERSION_CODE` / `KIOSK_VERSION_NAME`.
- **Manifest** (`update.json`): `{"versionCode": 142, "versionName": "0.1.142", "url":
  "ed22-kiosk-142.apk", "sha256": "…"}`. `url` can be relative to the manifest or absolute,
  and must be https.
- **On the device:** if `versionCode` is higher than the installed one, the kiosk downloads the
  APK, checks its SHA-256, and installs it with PackageInstaller. As **device owner** this is
  silent. Android then restarts the kiosk, which locks itself again. Without device owner, the
  kiosk only reports in the parent menu that an update is available. Android rejects any APK that
  is not signed with the same key.
- **Every build** becomes a GitHub Release tagged `build-<versionCode>` (APK + `update.json`),
  and is also kept as the run's `ed22-kiosk-apk` artifact. To stop a bad build from spreading,
  delete its release (or mark it pre-release) so that the previous one becomes "latest", and then
  push a fix. Android never installs a lower `versionCode`, so kiosks that already updated have
  to wait for that fix.

### Where updates are published

Updates are published as **GitHub Releases** of this (public) repository. Kiosks poll
`KioskConfig.UPDATE_MANIFEST_URL`, which is
`https://github.com/apex2060/ed22-kiosk/releases/latest/download/update.json`, the manifest of
the newest release. Its `url` points at that release's APK
(`…/releases/download/build-<n>/ed22-kiosk-<n>.apk`). No token is needed.

To host updates somewhere else (for example `https://us.ed22.org/kiosk/update.json`), copy each
release's `update.json` and APK there, change `UPDATE_MANIFEST_URL`, push, and install that build
on each kiosk once.

The first build that contains the updater must also be installed by hand (`adb install -r`).

## Building

GitHub Actions builds the APK on every push and pull request (see `.github/workflows/build.yml`);
download it from the run's **Artifacts**. Only pushes to `main` are published as updates.

Every build must be signed with the **same key**, or `adb install -r` updates will fail. Add
these repository secrets (Settings › Secrets and variables › Actions):

| Secret | Value |
| --- | --- |
| `KIOSK_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KIOSK_KEYSTORE_PASSWORD` | keystore password |
| `KIOSK_KEY_ALIAS` | key alias |
| `KIOSK_KEY_PASSWORD` | key password |

To build locally with Android Studio or the Android SDK, put the same values in a
`keystore.properties` file at the repository root (it is git-ignored):

```properties
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

then run `./gradlew assembleRelease`. Without a signing key the release build falls back to the
debug key.

## Configuration

Everything site-specific is in
[`KioskConfig.kt`](app/src/main/java/org/ed22/kiosk/KioskConfig.kt): the start page, the history
endpoint, the trusted domain for `window.kiosk`, the corner-tap settings, and the update manifest URL.
