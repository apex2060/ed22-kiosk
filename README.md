# ED22 Kiosk

An Android kiosk app that locks a device to a full-screen browser starting at
**https://us.ed22.org/roam**. It gives ed22.org pages a `window.kiosk` JavaScript API for
system information and device features, can report every page visit to
**https://us.ed22.org/me/history** (currently turned off), and hides a PIN-protected parent menu behind
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

### Option A: QR code (recommended, no computer)

1. **Factory reset** the device (or start from a new one).
2. On the first **Welcome** screen, **tap the same spot 6 times**. A QR scanner opens. On
   Android 8 it first asks for Wi-Fi so it can download a QR reader.
3. **Connect to Wi-Fi** if asked. The QR code contains no Wi-Fi details.
4. **Scan the setup QR code** (`ed22-kiosk-setup.png`). The device downloads the latest
   ED22 Kiosk from GitHub Releases, checks its signing certificate, and makes it **device owner**.
   Accept the prompts. There is no Google account step.
5. When setup ends, ED22 Kiosk opens. **Set the parent PIN**, and the device locks into the kiosk.

The setup QR code holds this JSON (regenerate the PNG with any QR tool if it changes):

```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "org.ed22.kiosk/org.ed22.kiosk.KioskAdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "https://github.com/apex2060/ed22-kiosk/releases/latest/download/ed22-kiosk.apk",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": "ILFyjcgmHcFg9CHLTyLMxhj1B2ibjEVc39h9gBUuxUk",
  "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true,
  "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": true
}
```

It uses the signing **certificate** checksum and the release's fixed-name `ed22-kiosk.apk`
asset, so the same QR code keeps working for every future build signed with the same key.
If the device was already set up (it has an account or has finished setup), tapping 6 times
does nothing: factory reset it first.

### Option B: adb

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
   and opens https://us.ed22.org/roam.
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
| Reload start page | Goes back to https://us.ed22.org/roam. |
| Change PIN | Sets a new parent PIN. |
| Updates | Shows the installed version, the last update check and its result, with a **Check for updates now** button. |
| Remove kiosk lock | Removes device-owner status so the app can be uninstalled. Locking again needs a factory reset. |

Five wrong PINs in a row lock the prompt for 60 seconds.

## Browsing and history reporting

There is **no domain allowlist**: the kiosk starts on us.ed22.org but links can go anywhere on
the web. Links that would leave the app (`tel:`, `mailto:`, Play Store and other app links)
are ignored; `intent:` links open their web fallback if they have one.

> **History reporting is currently off.** `KioskConfig.HISTORY_ENABLED` is `false`, so the app
> sends no history posts and queues no visits, and it deletes any queue left by an older build at
> startup. Set it to `true` and push to turn on the behavior described below.

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
| `kiosk.getBattery()` | `{level: 0-100 int, charging: bool, plugged: 'ac'\|'usb'\|'wireless'\|null}`, plus extras `full, health, temperatureC, voltageMv` |
| `kiosk.getNetwork()` | `{connected: bool, type: 'wifi'\|'cellular'\|'ethernet'\|'none', metered: bool}`, plus extras `online` (internet verified), `downstreamKbps, upstreamKbps`. A VPN reports its underlying type. Any other transport that has internet reports `wifi` |
| `kiosk.getScreen()` | `{on: bool, brightness: 0-1 float or -1 (system default), orientation: 'portrait'\|'landscape'}` |
| `kiosk.isInFocus()` | `true` when the kiosk window has input focus (`false` while, for example, the parent PIN dialog is open) |
| `kiosk.getWifi()` | `{enabled, connected, ssid, bssid, rssi, signalLevel (0-4), linkSpeedMbps, frequencyMhz, ipAddress}` |
| `kiosk.getDevice()` | `{deviceId, serial, manufacturer, brand, model, device, androidVersion, sdkInt, securityPatch, appVersion, isDeviceOwner, lockTaskActive, locale, timeZone, uptimeMs, screen}` |
| `kiosk.getStorage()` | `{totalBytes, freeBytes}` |
| `kiosk.getMemory()` | `{totalBytes, availableBytes, lowMemory}` |
| `kiosk.getLocation({highAccuracy})` | `{latitude, longitude, accuracyM, altitudeM, speedMps, bearing, provider, timestamp}` |

### Events

```js
kiosk.on('battery', b => ...);   // level, charging or plug state changed (same shape as getBattery)
kiosk.on('network', n => ...);   // connectivity changed (same shape as getNetwork)
kiosk.on('focus', e => ...);     // {focused: bool} when the kiosk window gains or loses focus
kiosk.on('screen', e => ...);    // {on: bool} when the screen turns on or off
kiosk.on('nfc', tag => ...);     // a tag was scanned (see NFC)
kiosk.on('speech', e => ...);    // text-to-speech progress (see Speech)
kiosk.off('battery', handler);
```

Events go only to the top-level page (not iframes), and only while it is a trusted ed22.org page.

### NFC

| Call | Returns |
| --- | --- |
| `kiosk.nfc.status()` | `{available, enabled, systemEnabled}`: `available` = the device has NFC, `enabled` = the kiosk is reading tags for the page, `systemEnabled` = NFC is turned on in Android settings |
| `kiosk.nfc.enable()` | Starts reading tags (same shape as `status()`) |
| `kiosk.nfc.disable()` | Stops reading tags (same shape as `status()`) |

While enabled, each scan fires `kiosk.on('nfc', fn)` with:

```js
{
  id: '04A2B3C4D5E6F7',            // tag UID, uppercase hex
  url: 'https://us.ed22.org/x',     // first URI record (incl. inside a smart poster), or null
  records: [                        // NDEF records, [] for tags without NDEF
    { type: 'uri', tnf: 1, url: '…', payload: '…', encoding: 'utf8' },
    { type: 'text', tnf: 1, lang: 'en', payload: 'hello', encoding: 'utf8' },
    { type: 'mime', tnf: 2, mimeType: 'application/json', payload: '{…}', encoding: 'utf8' },
    { type: 'external', tnf: 4, recordType: 'android.com:pkg', payload: '…', encoding: 'utf8' },
    { type: 'smartposter', tnf: 1, url: '…', records: [ … ] },
    // also 'empty', 'well_known' and 'unknown'; binary payloads use encoding: 'base64'
  ],
  techs: ['NfcA', 'MifareUltralight', 'Ndef']
}
```

- Reading uses Android **reader mode**. It works while the kiosk is in the foreground with the screen on and unlocked. While it is on, Android does not open other apps or the browser for scanned tags. Once enabled, it stays on (even on non-ed22 pages, where scans are ignored) until `disable()` is called. After an app restart (including a self-update) it is off until a page calls `enable()` again.
- **Legacy pages:** if the page defines `window.nfcScanResult`, the kiosk turns NFC on after the page loads and calls `nfcScanResult(url)` for each scan, or `nfcScanResult(id)` when the tag has no URL. This matches the old `Android.useJavaScriptCallbackNFC`.
- Devices without NFC: `available` is `false`, and `enable()` does nothing.

### Speech

| Call | Returns |
| --- | --- |
| `kiosk.speak(text)` | Resolves with an utterance id (for example `'u3'`) once queued. Utterances play in order |
| `kiosk.cancelSpeak(id)` | Removes a queued utterance or stops it if it is playing; `false` if it already finished |
| `kiosk.stopSpeaking()` | Stops the current utterance and clears the queue |

`kiosk.on('speech', e => ...)` gets `{id, state}`, where `state` is `'start'`, `'done'`,
`'stopped'` (cancelled or stopped) or `'error'`.

### Native storage

A small key/value store kept by the app. It survives clearing the WebView's cookies and storage,
but not uninstalling the app. It is shared by all trusted ed22.org pages.

| Call | Returns |
| --- | --- |
| `kiosk.storage.get(key)` | The string, or `null` |
| `kiosk.storage.set(key, value)` | `true`. `value` is converted to a string |
| `kiosk.storage.remove(key)` | `true` |
| `kiosk.storage.keys()` | Array of keys, sorted |

Limits: keys are 1-128 characters, values up to 65,536 characters, and at most 500 keys. Calls
over the limit reject with an error.

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
| `kiosk.setBrightness(level)` | Kiosk screen brightness, `0.0`-`1.0` (values below 0.01 become 0.01); `-1` returns to the system setting |
| `kiosk.getVolume()` / `kiosk.setVolume(0.0-1.0, stream)` | `stream` is `music` (default), `ring`, `alarm`, `notification`, `system` or `call` |
| `kiosk.setKeepScreenOn(true/false)` | Keep the screen awake (on by default) |
| `kiosk.setTorch(true/false)` | Flashlight |
| `kiosk.setOrientation('portrait'/'landscape'/'auto')` | Lock or free screen rotation |
| `kiosk.toast(text)` | Show a short Android message |
| `kiosk.speak(text)` | Text to speech (see [Speech](#speech)) |
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
