# Little Explorer

An Android kiosk app that locks a device to a full-screen browser starting at
**https://us.ed22.org/mobile**. It gives ed22.org pages a `window.kiosk` JavaScript API for
system information and device features, can report every page visit to
**https://us.ed22.org/me/history** (currently turned off), and hides a PIN-protected parent menu behind
**4 quick taps in the bottom-left corner**. Navigation is the system navigation bar (Back and
Home).

## Contents

- [Installing on a device](#installing-on-a-device)
- [The parent menu](#the-parent-menu)
- [Navigation](#navigation)
- [Browsing and history reporting](#browsing-and-history-reporting)
- [The `kiosk.*` JavaScript API](#the-kiosk-javascript-api)
- [Device account and policy](#device-account-and-policy)
- [Automatic updates](#automatic-updates)
- [Building](#building)
- [Configuration](#configuration)

## Installing on a device

The app works best as **device owner**, which is what locks the home button to the kiosk, blocks recent apps,
the notification shade, safe mode and factory reset. Android only allows a device owner to
be set on a device with **no accounts on it**, so start from a fresh or factory-reset device.

### Option A: QR code (recommended, no computer)

1. **Factory reset** the device (or start from a new one).
2. On the first **Welcome** screen, **tap the same spot 6 times**. A QR scanner opens. On
   Android 8 it first asks for Wi-Fi so it can download a QR reader.
3. **Connect to Wi-Fi** if asked. The QR code contains no Wi-Fi details.
4. **Scan the setup QR code** (`ed22-kiosk-setup.png`). The device downloads the latest
   Little Explorer from GitHub Releases, checks its signing certificate, and makes it **device owner**.
   Accept the prompts. There is no Google account step.
5. When setup ends, Little Explorer opens. **Set the parent PIN**, and the device locks into the kiosk.

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
5. Open **Little Explorer**. Set the parent PIN (4 to 8 digits). The device locks into the kiosk
   and opens https://us.ed22.org/mobile.
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
| Exit kiosk mode | Unlocks the device and goes to the normal home screen. Opening Little Explorer again, or restarting, locks it again. |
| Open Wi-Fi settings | Exits kiosk mode and opens Wi-Fi settings. |
| Reload start page | Goes back to https://us.ed22.org/mobile. |
| Change PIN | Sets a new parent PIN. |
| Updates | Shows the installed version, the last update check and its result, with a **Check for updates now** button. |
| Account | Shows the signed-in Firebase uid, the device doc id, the listener status (including errors such as `PERMISSION_DENIED`) and the apps allowed right now, with a **Sign out** button. |
| Remove kiosk lock | Removes device-owner status so the app can be uninstalled. Locking again needs a factory reset. |

Allowed apps are listed and opened by the ed22 page itself (the /mobile header apps icon) through `kiosk.apps.list()` / `kiosk.apps.launch()`.

Five wrong PINs in a row lock the prompt for 60 seconds.

## Navigation

Navigation is the **system navigation bar**; Little Explorer draws no buttons of its own. As device
owner it is the persistent default home app, and lock task mode enables the Home button
(`LOCK_TASK_FEATURE_HOME`, plus the power menu). Recents and notifications stay off.

| | In the kiosk | In another allowed app |
| --- | --- | --- |
| Back | Closes fullscreen video, then the popup (or goes back inside it), else goes back in page history | That app's own Back |
| Home | Resets to https://us.ed22.org/mobile (closes any popup or fullscreen video first) | Returns to the kiosk where the child left off (no reset) |

Returning with Home ends the `status.appUsage` session like any other return to the kiosk.
**Holding** Home (or a long-press assistant gesture) can't be customized by the app. The Home button
needs Android 9+ for the lock task feature; on Android 8 lock task mode hides it.

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
kiosk.on('policy', p => ...);    // the device doc's policy changed (see Device account and policy)
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
| `kiosk.setAuth({token, deviceDocId})` | Signs the app in to Firebase with a custom token and starts listening to the device doc. Resolves `{uid, deviceDocId}`; rejects if `token` is missing or sign-in fails (see [Device account and policy](#device-account-and-policy)) |
| `kiosk.getAuth()` | `{signedIn, uid, deviceDocId, policy}` (`policy` is the cached policy, `{}` if none) |
| `kiosk.signOut()` | Signs out, stops the listener, and clears the device doc id and cached policy |
| `kiosk.setActiveChild(studentId \| {studentId} \| null)` | Sets the child using the tablet (`null` / `""` = parent mode) for the [allowed-apps rules](#allowed-apps-rules): an id string (the students/{id} doc id), or `{studentId}`. An object with no `studentId` field is ignored (no change). Stored on the device, so it works offline. Resolves the active `studentId` (or `null`) |
| `kiosk.apps.list()` | `[{package, label, allowed, until, left, windowUntil, why, icon}]` for launchable installed apps except Little Explorer, for the active child right now. `until` is the entry's own `until` (epoch ms or `null`). `left` = minutes left (`null` = no limit or not allowed), `windowUntil` = epoch ms when the current answer ends (window end, `extraUntil`, `until`; `null` = no end or not allowed), `why` = edRules' reason: `rule`, `extra`, `legacy` (allowed or not, entry without rules), `rules` (no rule applies now: day, child, window or minutes used up), `none` (not in the policy). `icon` is the launcher icon as a 96x96 PNG data URL (`data:image/png;base64,...`), or `null` if it could not be rendered (cached per package + versionCode) |
| `kiosk.policy.set(policy)` (alias `kiosk.setPolicy`) | Pushes a policy from the page, offline too (same shape as the doc's `policy`; `locked` and `command` are ignored). Saved on the device and applied at once if its `rev` is newer than the doc's (see [Policy from the page](#policy-from-the-page)). Resolves `{rev, source}`; rejects when not signed in |
| `kiosk.policy.get()` | `{policy, rev, source: 'doc' \| 'local'}`: the policy in force (`rev` 0 = none) |
| `kiosk.apps.usageByChild()` | Same map as `status.appUsageByChild`, from the local session log |
| `kiosk.apps.launch(package)` | Opens the app; rejects unless the policy allows it right now |
| `kiosk.apps.usage()` | Time spent in apps opened through Little Explorer: `{"yyyy-MM-dd": {"<package>": {seconds, label, opens}}}` for the last 14 days (same as `status.appUsage`, see [Device account and policy](#device-account-and-policy)) |

| `kiosk.call(method, args)` | Low-level access to any of the above by name |

[`docs/kiosk-test.html`](docs/kiosk-test.html) exercises every call. Host it anywhere on
ed22.org and open it in the kiosk.

## Device account and policy

Once a parent signs in on https://us.ed22.org/mobile, the page creates the device's Firestore doc
(Firebase project `homeschool-express`) and hands the app a Firebase **custom token**:

```js
const {uid, deviceDocId} = await kiosk.setAuth({token, deviceDocId});
```

The app signs in natively (the session survives restarts), saves `deviceDocId`, and from then on
works with `share/library/myItems/<deviceDocId>` (type `explorerDevices`, created by /mobile, never
by the app) from the Application, so it keeps working while /mobile isn't loaded.

**Written by the app:** only fields inside `status`, with `update()` and dotted paths (never `set()`),
when signing in, at app start and every 15 minutes (WorkManager, when online):

```js
status: {
  lastSeen: serverTimestamp, version: '0.1.142', versionCode: 142,
  model, manufacturer, androidVersion,
  battery: 0.87,            // 0-1
  charging: true,
  deviceId,                 // same as kiosk.deviceId
  deviceOwner: true,
  policyNote: '',           // e.g. 'Allowed apps not enforced: app is not device owner'
  timeZone: 'America/Phoenix',          // the tablet's IANA time zone (rules use its local time)
  features: ['rules', 'ruleLimits', 'strict', 'localPolicy', 'elsewhere', 'appsUsageByChild', 'appsEvent', 'openApp'],
  policyRev: 1793768520000,             // rev of the policy in force (0 = none)
  policySource: 'doc',                  // 'doc' | 'local' (pushed with kiosk.policy.set)
  installedApps: [{package, label}]   // launchable apps, excluding Little Explorer
  appUsage: {                         // last 14 days, keyed by local date (device timezone)
    '2026-11-02': {'com.example.app': {seconds: 754, label: 'Example', opens: 3}}
  }
  appUsageByChild: {                  // last 7 days, by local date, then studentId ('parent' = no child)
    '2026-11-02': {'ava': {'com.example.app': {seconds: 600, opens: 2}}}
  }
}
```

`status.appUsage` is a rough log that needs no Usage access permission: it only counts apps
opened through Little Explorer (`kiosk.apps.launch`), from the launch until the kiosk is back in
front (including expiry, `goHome` and the system Home button), the screen turns off, or another app is launched. Time spent
in an app it switches to by itself is counted for the launched app. Sessions under 2 seconds are
ignored, one session counts at most 12 hours, and a session's time (and its `opens`) goes to the
date it started. The open session is kept in prefs, so it survives the app's process being
killed. The whole map is written as one value, only when it changed since the last successful
write, and a status report is sent after each session ends. A page on the tablet gets the same map from
`kiosk.apps.usage()`.

`status.appUsageByChild` comes from the per-child session log the rules use: every session is kept for 14 days as
`{pkg, studentId, startMs, endMs}`, split at local midnight and when the active child changes (both pieces count
for their own day / child; only a new launch counts as an `opens`). If the screen turns off and back on while the
app is still in front, the time after it counts here and for the limits, but not in `status.appUsage` (unchanged).
It is written only when it changed, like `appUsage`.

No icons are stored here; a page on the tablet gets them from `kiosk.apps.list()`. To keep writes small, `status.installedApps` is rewritten only when installed apps change
(packages, versionCodes or labels, compared with a hash of the last successful write); the other
`status` fields are updated every time without touching it.

**Read by the app:** the `policy` field, through a snapshot listener. The last policy is cached on
the device, so it still applies offline and after a reboot. Listener errors (such as
`PERMISSION_DENIED`) are logged (`adb logcat -s DeviceSync`), shown in Parent menu › Account, and
retried every 5 minutes.

```js
policy: {
  allowedApps: [{package: 'com.example.app', until: number (epoch ms) | Timestamp | null,  // null = no end time
                days?, studentIds?,                                  // legacy: only used without rules
                rules?: [{days?: [0-6], studentIds?: [id], start?: 'HH:MM', end?: 'HH:MM', minutesPerDay?: n}],
                extraUntil?: ms,
                elsewhere?: {<studentId>: {date: 'yyyy-MM-dd', minutes: n}}}],  // minutes used today on other tablets
  strict?: true,        // lock everything unless a rule allows it (see Allowed-apps rules)
  rev?: number,         // epoch ms; the newer of doc / kiosk.policy.set wins
  locked: false,
  command: {id: 'c-123', type: 'reload' | 'goHome' | 'reboot'}
        // or {id: '<epoch ms>', type: 'openApp', package: 'com.android.vending'}
}
```

- **`allowedApps`**: as device owner, the lock task allowlist becomes Little Explorer + the file
  picker + every app allowed right now for the active child (see [Allowed-apps rules](#allowed-apps-rules)),
  so those apps can be opened (see `kiosk.apps.launch`) without leaving kiosk mode. If an app stops being
  allowed while the kiosk isn't in front, the kiosk is brought back to the front. Apps are never hidden
  or uninstalled. Without device owner, nothing is enforced and `status.policyNote` says so.
- **`activeStudentId`** (top level of the doc, written by /mobile): see [the active child](#allowed-apps-rules).
- **`locked: true`**: covers the page with the native lock screen (local time, date, battery,
  then "This device is locked"). After 5 seconds with no touches the screen turns off for real
  (device-owner `lockNow`, not a black overlay), including while charging. The power button wakes
  back to that same lock screen. Tap-to-wake is only whatever the tablet already does; the app
  cannot turn it on. Parent-menu corner taps and the PIN still work, including after wake, and the
  screen is not put to sleep while a parent dialog or the lock-screen Wi-Fi panel is open. The Wi-Fi
  row shows the connection and can join a network without opening Android settings or unlocking the
  device. Unlocked, the screen stays as before
  (including staying on while plugged in).
- **`command`**: runs once per `id` (the last handled id is remembered on the device, including
  across sign-outs). `reboot` needs device owner. Note: a command that is already in the doc when
  a device first signs in runs once. The policy in the same snapshot is applied first, then the command.
  **`openApp`** (`status.features` has `'openApp'`) opens `package` like `kiosk.apps.launch` (usage session,
  alarm, back to the kiosk when its time ends or its entry is removed). It is dropped (id still used up) when
  the id isn't epoch ms or is more than 2 minutes old, when `locked` is true, or when the app isn't allowed
  right now in the policy in force.
- In the page, `kiosk.on('policy', fn)` gets the new policy on each change (`until` as epoch ms),
  and `kiosk.getAuth()` includes the cached one.

### Allowed-apps rules

The rule logic is a Kotlin port (`Rules.kt`) of ed22's `edRules.js`, tested on the same shared cases
(`app/src/test/resources/cases.json`, `./gradlew testDebugUnitTest`).

- **Entries without `rules`** work exactly as before: allowed while `until` is null or in the future (plus the
  optional top-level `days` / `studentIds`).
- **Entries with `rules`**: allowed if `extraUntil` is in the future (every child, past rules and limits; the time
  still counts as used), or if **any** rule matches the day, the active child and the time window and still has
  minutes left. The top-level `days` / `studentIds` are then only a copy for older /mobile builds and are ignored.
  Times are the tablet's local time; `start` is inclusive, `end` exclusive; `start` > `end` is overnight, and its
  `days` are the day it starts; no `start`/`end` = the whole day. `days` are 0 = Sunday … 6 = Saturday.
- **Limits** (`minutesPerDay`) count per child, per rule, per window (for an all-day rule, per local day), from
  the session log above, including the app that is open now. In parent mode (no active child) `studentIds` are
  ignored and a limit counts everyone's use. If several rules match, the one with the most minutes left wins.
- **`policy.strict: true`** locks everything unless a rule allows it: (a) in parent mode (no active child),
  entries with `rules` allow nothing except a future `extraUntil`; (b) entries without `rules` allow only while
  `until` is a number in the future (`until: null`, `days`, `studentIds` grant nothing); (c) an entry with an empty
  `rules` list allows nothing. Without `strict` nothing changes.
- **`elsewhere`**: `elsewhere[studentId] = {date, minutes}` is that child's use of the app on other tablets today.
  When `date` is today in the tablet's time zone, `minutes` is added to the child's used minutes in every limited
  rule of that app (even outside a rule's window, so it errs on the strict side). Ignored on other dates and in parent
  mode.
- **The active child** is the device's `studentId` (null = parent mode). `kiosk.setActiveChild(id | {studentId} | null)` sets it right
  away, offline too (`null` / `""` = parent mode; an object with no `studentId` is ignored). The doc's top-level `activeStudentId` also sets it, but only when that value changes in the
  doc (no other id field name is read), so a stale doc value doesn't undo a newer local `setActiveChild()`. A change splits the open session and
  re-checks the rules.
- **`kiosk.on('apps', fn)`** gets `{allowed: [package, …]}` whenever the allowed set changes (a window opens or
  ends, minutes run out, extra time ends, a policy or child change). The check alarm also fires at the next start of
  any timed rule, so an opening window is noticed on time.
- **Sending the child back:** while an app opened from the kiosk is in front, one exact alarm is set for the
  earliest of: its window end, its minutes running out, `extraUntil`, `until`, the soonest end of any other allowed
  app, and the next local midnight. When it fires the rules are re-checked: the lock task allowlist is updated and,
  if the open app is no longer allowed, the kiosk comes back to the front. About a minute before the open app runs
  out a "1 minute left" toast is shown. The alarm is re-armed on launch, child change, policy change, time or
  time-zone change, app start and boot. Lock task packages are also refreshed right before each launch, so an app
  whose window just opened can start.
- **Exact alarms:** the app declares `USE_EXACT_ALARM` (Android 13+) and `SCHEDULE_EXACT_ALARM` (Android 12).
  If exact alarms are still not allowed, an inexact alarm is used and the child may be sent back a few minutes late.
- **Automatic date and time:** as device owner the app turns on automatic date, time and time zone
  (`setAutoTimeEnabled` / `setAutoTimeZoneEnabled` on Android 11+, `setAutoTimeRequired` on Android 8–10, which
  only forces automatic time), so a child can't dodge the rules by changing the clock.

### Policy from the page

`kiosk.policy.set(policy)` saves the page's policy on the device (kept across restarts, cleared on sign-out) and
applies it at once: rules, lock task allowlist and the check alarm. The doc's policy and the local one are both kept;
the one with the higher `rev` (epoch ms, missing = 0) is in force, and a tie goes to the doc. So a doc snapshot with
an older or missing `rev` doesn't replace a newer local push, and a newer doc policy does. `locked` and `command`
always come from the doc. `kiosk.on('policy')`, `kiosk.getAuth().policy` and `kiosk.policy.get()` show the policy in
force; `status.policyRev` / `status.policySource` report it (a status report is sent when either changes).

**Firestore rules** must let the signed-in custom-token uid read the doc and update its `status`
field (for example, that uid is in the doc's `_writeIds` / `_readIds`).

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
| `GOOGLE_SERVICES_JSON_BASE64` | `base64 -w0 google-services.json` (Firebase config for `org.ed22.kiosk` in project `homeschool-express`) |

To build locally with Android Studio or the Android SDK, put the same values in a
`keystore.properties` file at the repository root (it is git-ignored):

```properties
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

and copy the Firebase config to `app/google-services.json` (also git-ignored; the build fails
without it), then run `./gradlew assembleRelease`. Without a signing key the release build falls back to the
debug key.

## Configuration

Everything site-specific is in
[`KioskConfig.kt`](app/src/main/java/org/ed22/kiosk/KioskConfig.kt): the start page, the history
endpoint, the trusted domain for `window.kiosk`, the corner-tap settings, and the update manifest URL.
