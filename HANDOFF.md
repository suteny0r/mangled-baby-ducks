# Handoff — 2026-08-19 (~21:45)

## What this is
Mangled Baby Ducks: an Android (Kotlin/Compose) Meshtastic-compatible client, ported from
Meshtastic-Apple (cloned at `F:\Meshtastic-Apple`, protobufs vendored into
`app/src/main/proto`). Repo: https://github.com/suteny0r/mangled-baby-ducks (`main`; run
`git log -1` for the head). All work is committed and pushed; the working tree is clean.

`CLAUDE.md` (repo root, committed) carries the architecture, build commands, and the
invariants worth not breaking. Read it first; this file is the session log on top of it.

## Build / run
- PowerShell: `& .\gradlew.bat :app:assembleDebug` (JDK: Android Studio JBR via
  `org.gradle.java.home` in gradle.properties; SDK path in local.properties).
- Install: `adb -s R5CN70YWT5Z install -r app\build\outputs\apk\debug\app-debug.apk`
  (the user's Galaxy Note 20 Ultra).
- There are still no tests of any kind in the repo; verification is on the phone.

## Current device state
- **Radio config editing shipped for all 8 sections** (LoRa, Device, Position, Bluetooth,
  Display, Network, Power, Security) and the write path is now proven on hardware. See
  "Config sections" below.
- Connected to **📣_9f4a (SOBE)**, which is the auto-connect target again. The saved-radio
  list holds SOBE and **🐭_4fae (Peewee Herman)**, which is powered off (it was used to
  test the not-in-range path).
- **POST_NOTIFICATIONS is denied** (`granted=false, USER_SET`), so message notifications
  are silently dropped. The launch-time permission dialog was dismissed with back, not
  answered — the choice is the user's. Grant it from system settings if notifications are
  wanted for testing.
- The nodes/channels/my_info tables were wiped and rebuilt twice by the radio switches
  (the cross-radio guard); they are now populated from SOBE. Messages, positions,
  telemetry and traceroutes were untouched.
- 47+ unread messages were sitting in the badge from the day's test traffic; harmless.

## Test rig (memory file `mesh-test-radios.md` has the full version)
- Phone radio: "SOBE GAT562 30s" (📣), BLE `📣_9f4a` = ED:A6:3B:FA:9F:4A. It normally sits
  on PC serial COM14, but **COM14 IS NOT TO BE TOUCHED** (user instruction): opening it
  kicks the phone's BLE session. Reach SOBE only through the app. (COM14 was not even
  enumerated on 2026-08-19 evening; ports present were COM3, COM5, COM18.)
- SOBE's node num is now **`!1eff739f`**. The **`!0f352b79`** node in mesh node DBs is a
  stale duplicate carrying the same public key — address DM tests to `!1eff739f`.
- Test traffic sender: **Spiney Norman on COM3** (🦔_8e18 = 3C:DC:75:6F:8E:19).
  `set PYTHONIOENCODING=utf-8; meshtastic --port COM3 --sendtext ... --dest '!1eff739f' --ack`
  `meshtastic --port COM3 --nodes` is the quickest way to see whether a radio is alive.
- Spanky Ham (🐷) at 192.168.20.129 (TCP): unreliable LoRa path, don't rely on it.
- **Never use 6abc (🍆_6abc, 10:20:BA:6A:6A:BD) or swaffelen for tests** (user instruction).
- Other bonded radios that are NOT SOBE: "Peewee Herman" 🐭_4fae (CD:12:B4:98:4F:AE,
  `!b4984fae`, WISMESH_TAG, low battery, drops off BLE) and "Pickle Rick" 🥒_f1e4
  (E1:B4:D6:DE:F1:E4). The app had been remembering Peewee Herman, which is what looked
  like a connection bug for most of a session.
- Channels on all nodes: 0 = unnamed Primary, 1 = "LongPrivate" (swaffelen has ONLY
  LongPrivate).
- Re-flash key-mismatch lesson: stale public keys make DMs NAK with error 39;
  fix = Settings > "Broadcast node info" on the re-flashed radio.

## Connection lifecycle: what was wrong and what must stay true
Symptom (reported twice): on launch the app cycled "connecting / connection lost /
reconnecting" or stalled, and never said which radio it was reaching for.

Root cause, straight out of logcat: **two attempt loops driving `establish()` at once** —
`connect()`'s own 3-attempt retry plus the reconnect fired by any unrequested BLE
disconnect, which a failed connect also is. Both registered a GATT client for the same
radio (`clientIf 7` *and* `8` connecting simultaneously, interleaved "Connect attempt N/3"
and "Reconnect attempt N" lines) and each cancelled the other. `MainActivity` re-armed it
from onCreate, the permission result and every onResume, with a state guard sitting behind
a suspending DataStore read. A drop during the node-DB step also waited out the full 120 s
nonce timeout — that was the "hang".

Invariants now in `RadioManager` (do not relax any of these):
- `attemptLock` + `requestGeneration`: `runAttempts` is the only caller of `establish`,
  one loop at a time, and an older loop aborts as soon as a newer request lands.
- Event collectors are tagged with their connection; a superseded link's events are
  dropped so a dying predecessor cannot fail or reconnect the live session.
- Only a session that finished the handshake (`sessionWentLive`) may auto-reconnect. A
  drop inside `establish` completes the `linkLost` deferred, which aborts the handshake at
  once; the owning loop owns the retrying.
- `awaitConfigComplete` subscribes UNDISPATCHED (a fast nonce echo could otherwise be
  emitted before the collector existed) and does not leak its waiter on timeout.
- `autoConnect()` spends exactly **one** automatic attempt per process. A radio that is
  off or out of range is never chased in the background; the Connect tab is the way back.
- **Scan before connecting.** A known radio is only connected to after its advertisement
  is seen (`BleScanner.isAdvertising`, wrapped as a `PresenceProbe`, 6 s window). Blind
  MAC connects cost a ~5 s GATT timeout each and return status 133, which reads like an
  app bug. An absent radio is terminal for the initial-connect loop ("<name> is not in
  range"); in the reconnect loop it is not, and attempt 1 there skips the probe entirely
  because a radio rebooting after a config write comes back within seconds. Connecting
  from the scan list passes no probe: it was just seen.
- `Connecting(attempt, of)` is for a link that was never up; `Reconnecting(attempt)` only
  for one that had been live. Every in-progress state names its target radio.

Radio memory is two separate things (conflating them caused a lost-radio incident):
`RADIO_TYPE`/`RADIO_ADDRESS`/`RADIO_NAME` are the auto-connect target, cleared by a
deliberate Disconnect; `KNOWN_RADIOS` is the saved list (JSON, `org.json`, capped at 12,
`lastConnectedMs` for ordering) that only Forget deletes from.
`AppContainer.rememberRadio()` writes both and is the only writer.

## Config sections (Settings → Radio configuration)
`ui/ConfigScreens.kt` holds one form per section plus the shared rows/dialogs;
`SettingsScreen` is now a list that opens a section as a sub-screen (local state +
`BackHandler`, since navigation is still a tab switch, not a nav graph).

- Sections read from the raw proto rows already stored by `PacketIngest`
  (`config.<payloadVariantCase.lowercase()>`), so no schema change was needed.
- **Each section is a draft edited locally and written by one Save button.** Every write
  makes the radio save and reboot, so one write per toggled field would mean a reboot per
  tap. `Revert` restores the radio's values; the draft is keyed on the incoming config, so
  a fresh config dump (which is what a successful save produces) replaces it.
- `SettingsViewModel` gained `configFlow(key, extract)` and one `writeConfig {}` helper;
  adding a section is two lines there plus a form.
- Not editable on purpose: the security private key (read-only "set"/"not set"; the public
  key is shown base64) and anything under `module.*` (no module config UI yet).

## Verified working on hardware
- Scan-before-connect, radio present (2026-08-19 20:47): auto-connect started a filtered
  scan, saw SOBE 739 ms later, then issued the single GATT connect and negotiated MTU 247.
- Scan-before-connect, radio absent (2026-08-19 21:04, Peewee Herman powered off by the
  user for the test): scan 21:04:10.633 → 21:04:16.644 (the 6 s window), then
  "🐭_4fae not advertising (attempt 1/3)" and a terminal "🐭_4fae is not in range" with a
  Retry button. **Zero** GATT connects and one scan start for the whole launch; the old
  behaviour was three ~5 s blind connects returning status 133.
- Saved-radio list with two entries (🐭_4fae and 📣_9f4a), each with its own Connect and
  Forget, "in range <rssi>" shown for whichever the running scan sees, and switching
  between radios from the list.
- Retry loop still earns its keep: reconnecting to SOBE right after switching away from it
  (2026-08-19 21:08) was seen advertising each time yet returned GATT 133 on attempts 1
  and 2 before succeeding on attempt 3 ("Connected after 3 attempt(s)"). Scanning first
  removes pointless attempts at an absent radio; it does not make 133 go away for a radio
  whose link was just torn down.
- Connect flow (2026-08-19 19:24-19:46): one bounded burst of 3 sequential attempts against
  an unreachable radio, one GATT client at a time, ending in a terminal "Could not reach
  🐭_4fae"; resume cycles adding zero attempts; scan → Connect reaching `Subscribed`
  ("Connected to 📣_9f4a", node DB loaded, MTU 247); force-stop → relaunch auto-connecting
  on the first attempt; saved list rendering "📣_9f4a • ED:A6:3B:FA:9F:4A • last used just
  now • Connected".
- BLE connect pipeline (wantConfig/wantDatabase nonces 69420/69421), TCP framing,
  auto-reconnect (BT-off detection via adapter receiver), heartbeat watchdog for TCP.
- Messaging: channels + DMs, acks (✓/✓✓), 200-byte composer, tapbacks (👍 verified over
  LoRa), replies, DM-from-node-list, notification deep links, per-message notifications,
  unread badge on the Messages tab.
- Nodes list + node detail (identity/link/position, 48h battery & channel-util charts,
  key-mismatch warning).
- Traceroute: verified to CAVE-GAT562, per-hop names + SNR both directions.
- Map: satellite default (Esri) + streets toggle (openfreemap liberty), node markers with
  labels, camera auto-fit, waypoints (orange), long-press waypoint creation ("MBD test"
  shared on LongPrivate). Labels: Noto Sans glyphs only, emoji stripped (SDF servers have
  no emoji). A test waypoint "MBD test" (never expires) exists on LongPrivate — delete by
  sending an empty-name waypoint with the same id (in `waypoints` table) if unwanted.
- **Config write path proven end to end (2026-08-19 21:36-21:38)**: Display section,
  `heading_bold` false → true → Save. Radio stored it and rebooted (link lost, GATT 133 on
  attempts 1-2, "Connected after 3 attempt(s)"), and the fresh config dump came back with
  the new value (Save greyed, Revert gone). Then set back to false the same way, so SOBE is
  as it was. All six new sections were also opened against the live radio and render its
  real values (Security shows the public key and `serial_enabled` on, Position decodes the
  flag bitmask, Network/Power/Bluetooth populated).
- Settings: owner rename dialog (send path implemented, NOT test-fired), LoRa/Device
  config display from stored raw proto sections, Broadcast node info (verified, fixed the
  key-mismatch), channel QR export (byte-identical to independent encoder) and URL import
  preview (Apply implemented, NOT fired), phone GPS sharing (OS-level HIGH_ACCURACY
  request verified; no indoor fix; toggle left OFF).
- Retention pruning of positions/telemetry (30 days) runs on connect.

## Deliberately not done
- MQTT client proxy: radio has MQTT disabled → untestable, and enabling bridges the
  user's mesh to the public broker. Decision documented in commit `0b8cdbe`.
- Channel-set Apply is still implemented-but-never-fired (it REPLACES the radio's
  channels). Config `setConfig` writes are now proven (see above); the admin
  begin/set/commit transaction is the same path for both.
- Seeding the saved-radio list from the OS bonded-device list: the bond list is full of
  unrelated devices (car, Sonos, watch) and does not reliably expose the Meshtastic
  service UUID, so filtering would be name-pattern guesswork. Offered to the user, not
  taken up.

## Known gaps / next candidates
1. `MainActivity.requestNeededPermissions()` fires the whole permission list on every
   onCreate; on 2026-08-19 that put a POST_NOTIFICATIONS dialog on screen at launch (the
   grant had lapsed, and message notifications are silently dropped without it). Request
   only what is missing, and only when it is needed.
2. `RadioService`'s notification always reads "Connected to <name>", including while an
   attempt is still running or after it failed.
3. Config forms are a flat field list per section: no grouping, no "advanced" disclosure,
   no per-field validation beyond number/decimal parsing, and no interval pickers (iOS has
   `UpdateIntervalPicker`). Sentinel values are shown raw (super deep sleep reads
   `4294967295 s` rather than "disabled").
4. Notification tap while the app is foreground on the same thread: no read-state sync of
   the notification shade (minor).
5. Nodes list live re-sort makes rows jump under a finger (scan list was fixed with a
   stable sort; do the same for nodes).
6. Telemetry charts only battery/channel-util; environment metrics stored but unplotted.
7. Traceroute has no timeout state; a lost reply stays "pending" forever.
8. Waypoint edit/delete UI; expiry option in the dialog.
9. Messages: channel names in the thread list use the index only for unnamed secondaries.
10. QR scanning (import is paste-URL only; export QR is scannable by other apps).
11. Saved-radio row is cramped when connected ("Connected" + Forget side by side).
12. Android throttles an app to 5 scan starts per 30 s. The reconnect loop probes on
    attempts 2+, so a long recovery can hit that ceiling; the probe then reports "not
    visible" and the loop just backs off, but recovery is slower than it looks.
13. Module configs (`module.*`): 0 of iOS's 17 screens. MQTT, Telemetry, Position and
    Store & Forward are the ones people actually change.
14. Parity assessment (2026-08-19): the port covers the daily-driver core, roughly 15-20%
    of the iOS surface (461 Swift files / ~111k lines vs 27 Kotlin files / ~5.6k). At
    parity: transports, handshake, messaging with acks/tapbacks/replies, notifications,
    channel QR, waypoints, traceroute, first-wins keys. Partial: node detail (4 charts vs
    9 metric logs with tables + CSV), telemetry ingest (no air-quality/power/local-stats/
    pax rows), map (no clustering, offline tiles, geofence), node list (no filter/search),
    channels (no per-channel edit, no mute). Missing outright: 9 of 10 iOS node actions
    (delete, ignore, exchange position/user info, local stats, client history, alerts,
    navigate-to) plus reboot/shutdown/refresh-metadata, remote admin (no `sessionPasskey`
    anywhere), firmware OTA/DFU, MQTT proxy, TAK, device profile import/export, backup
    management, log/packet viewers, mesh discovery, route recording, lockdown, WiFi
    provisioning, onboarding, App Intents/CarPlay/Watch, weather + compact widgets, NFC and
    contact QR, serial transport, and localization (18 languages vs English only).
    Next by value: favorite/ignore through admin (both DB fields exist and lie today),
    then the no-schema node actions, then node list filter/search.

## Gotchas learned (do not relearn)
- **One attempt loop at a time.** Two concurrent connect drivers register two GATT clients
  for the same radio and cancel each other; that is what the whole reconnect-loop bug was.
  Anything that fires repeatedly (onResume, permission callbacks) must go through
  `autoConnect`, not its own state check.
- **GATT 133 on every attempt means the radio is not advertising**, not that the app is
  broken. Check the remembered address first (`adb shell dumpsys bluetooth_manager` for
  bonded names, the Connect tab's scan for what is actually in range, and
  `meshtastic --port COM3 --nodes` for whether the node is alive on LoRa).
- Diagnosing connection churn: filter logcat for `clientConnect(com.suteny0r` and
  `clientIf` — two client interfaces at once is the tell for a double driver. A working
  scan-then-connect looks like `BluetoothLeScanner: Start Scan with callback` followed by
  one `clientConnect` under a second later.
- **Proto `uint32` is a signed `Int` in the generated Java.** The Power section showed
  super deep sleep as `-1 s` until the number rows rendered/parsed unsigned
  (`toUInt()` / `toUIntOrNull()`); `tx_power` is a real `int32` and stays signed. Same
  family of bug as node numbers needing `Int.uint()`.
- Kotlin trap hit while adding the probe: appending an optional parameter AFTER a trailing
  `() -> T` parameter silently rebinds every `f(x) { ... }` call site to the new parameter
  (a `fun interface` SAM-converts happily). `factory` must stay last in
  `RadioManager.connect`.
- AndroidView `update` must read Compose state SYNCHRONOUSLY; reads inside deferred
  callbacks (getMapAsync) are not snapshot-tracked (fixed in `795daf5`, cost a debug cycle).
- Room `my_info` single-row LIMIT 1 needs the row cleared on cross-radio switch (fixed).
- openfreemap glyph server: only "Noto Sans" stacks exist; raster styles need an explicit
  `glyphs` URL for symbol layers.
- Samsung "BT off" keeps BLE_ON: GATT dies silently; the adapter-state receiver is what
  detects it, and reconnect can succeed immediately.
- `lintDebug` already failed at HEAD before this session's work: two
  `ProduceStateDoesNotAssignValue` errors in `MessagesScreen.kt:270` and
  `NodeDetailScreen.kt:241`. Unrelated to config editing, still unfixed.
- gradle must run via PowerShell `& .\gradlew.bat` (cmd /c chaining fails in this harness);
  multiline `python -c` also fails in Git Bash here — write scripts to the scratchpad.
- Screenshots: `adb exec-out screencap -p > file.png` through a PowerShell redirect
  corrupts the PNG (BOM/encoding). Use `adb shell screencap -p /sdcard/x.png` then
  `adb pull`.
- Git Bash rewrites `/data/...` paths in adb shell arguments; prefix the command with
  `MSYS_NO_PATHCONV=1` when reading app-private files via `run-as`.
