# Handoff — 2026-08-19 (~01:25)

## What this is
Mangled Baby Ducks: an Android (Kotlin/Compose) Meshtastic-compatible client, ported from
Meshtastic-Apple (cloned at `F:\Meshtastic-Apple`, protobufs vendored into
`app/src/main/proto`). Repo: https://github.com/suteny0r/mangled-baby-ducks (`main`,
head `0b8cdbe`). All work is committed and pushed; the working tree is clean.

## Build / run
- `gradlew.bat :app:assembleDebug` (JDK: Android Studio JBR via `org.gradle.java.home`
  in gradle.properties; SDK path in local.properties).
- Debug APK installs on the user's Galaxy Note 20 Ultra (adb serial `R5CN70YWT5Z`).
- App auto-reconnects on launch to the remembered radio (DataStore).

## Test rig (memory file `mesh-test-radios.md` has the full version)
- Phone radio: "SOBE GAT562 30s" (📣), BLE `📣_9f4a` = ED:A6:3B:FA:9F:4A,
  node 255142777 / `!0f352b79`. It sits on PC serial COM14, but **COM14 IS NOT TO BE
  TOUCHED** (user instruction): opening it kicks the phone's BLE session. Reach SOBE
  only through the app.
- Test traffic sender: **Spiney Norman on COM3** (🦔_8e18).
  `set PYTHONIOENCODING=utf-8; meshtastic --port COM3 --sendtext ... --dest '!0f352b79' --ack`
- Spanky Ham (🐷) at 192.168.20.129 (TCP): unreliable LoRa path, don't rely on it.
- **Never use 6abc or swaffelen for tests** (user instruction).
- Channels on all nodes: 0 = unnamed Primary, 1 = "LongPrivate" (swaffelen has ONLY LongPrivate).
- Re-flash key-mismatch lesson: stale public keys make DMs NAK with error 39;
  fix = Settings > "Broadcast node info" on the re-flashed radio.

## Verified working on hardware
- BLE connect pipeline (MTU 512, wantConfig/wantDatabase nonces 69420/69421), TCP framing,
  initial-connect retry (3x), auto-reconnect (BT-off detection via adapter receiver;
  reconnected in 1 attempt), heartbeat watchdog for TCP.
- Messaging: channels + DMs, acks (✓/✓✓), 200-byte composer, tapbacks (👍 verified over
  LoRa), replies, DM-from-node-list, notification deep links, per-message notifications.
- Nodes list + node detail (identity/link/position, 48h battery & channel-util charts,
  key-mismatch warning).
- Traceroute: verified to CAVE-GAT562, per-hop names + SNR both directions.
- Map: satellite default (Esri) + streets toggle (openfreemap liberty), node markers with
  labels, camera auto-fit, waypoints (orange), long-press waypoint creation ("MBD test"
  shared on LongPrivate). Labels: Noto Sans glyphs only, emoji stripped (SDF servers have
  no emoji). A test waypoint "MBD test" (never expires) exists on LongPrivate — delete by
  sending an empty-name waypoint with the same id (in `waypoints` table) if unwanted.
- Settings: owner rename dialog (send path implemented, NOT test-fired), LoRa/Device
  config display from stored raw proto sections, Broadcast node info (verified, fixed the
  key-mismatch), channel QR export (byte-identical to independent encoder) and URL import
  preview (Apply implemented, NOT fired), phone GPS sharing (OS-level HIGH_ACCURACY
  request verified; no indoor fix; toggle left OFF).

## Deliberately not done
- MQTT client proxy: radio has MQTT disabled → untestable, and enabling bridges the
  user's mesh to the public broker. Decision documented in commit `0b8cdbe`.
- Config writes (LoRa region/preset/hop-limit, device role, channel-set Apply) are
  implemented but never fired at the radio — they save+reboot it. Exercise with a no-op
  write first (e.g. re-set the current region) when the user wants them proven.

## Known gaps / next candidates
1. Fire a safe no-op set_config to prove the admin edit-transaction write path.
2. Notification tap while app is foreground on the same thread: no read-state sync of the
   notification shade (minor).
3. Nodes list live re-sort makes rows jump under a finger (same for scan list, fixed there
   by stable sort; consider for nodes).
4. Telemetry charts only battery/channel-util; environment metrics stored but unplotted.
5. Traceroute has no timeout state; a lost reply stays "pending" forever.
6. Positions/telemetry tables grow unbounded; add retention pruning.
7. Waypoint edit/delete UI; expiry option in the dialog.
8. Messages: unread badges on the Messages tab icon; channel names in thread list use
   index only for unnamed secondaries.
9. QR scanning (import is paste-URL only; export QR is scannable by other apps).
10. iOS-parity extras not started: MQTT proxy, range test, detection sensor UI, store &
    forward history, remote admin (session passkey), position exchange action.

## Gotchas learned (do not relearn)
- AndroidView `update` must read Compose state SYNCHRONOUSLY; reads inside deferred
  callbacks (getMapAsync) are not snapshot-tracked (fixed in `795daf5`, cost a debug cycle).
- Room `my_info` single-row LIMIT 1 needs the row cleared on cross-radio switch (fixed).
- openfreemap glyph server: only "Noto Sans" stacks exist; raster styles need an explicit
  `glyphs` URL for symbol layers.
- Samsung "BT off" keeps BLE_ON: GATT dies silently; the adapter-state receiver is what
  detects it, and reconnect can succeed immediately.
- gradle must run via PowerShell `& .\gradlew.bat` (cmd /c chaining fails in this harness);
  multiline `python -c` also fails in Git Bash here — write scripts to the scratchpad.
