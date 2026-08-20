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

## Startup reconnect loop (diagnosed and fixed 2026-08-19 ~18:45, on device)
Symptom: on launch the app connected, dropped, reconnected, repeated, or sat stalled.

Cause, from logcat: two attempt loops drove `establish()` at the same time. Any
unrequested BLE disconnect (which now includes a failed connect) fired
`scheduleReconnect`, while `connect()`'s own 3-attempt loop was still running, so the
stack registered two GATT clients for the same radio (`clientIf 7` **and** `8`,
interleaved "Connect attempt N/3" and "Reconnect attempt N" lines) and each cancelled
the other's connection. `MainActivity` made it worse: onCreate, the permission callback
and every onResume all called `autoConnectIfRemembered`, whose state guard sat behind a
suspending DataStore read, and it retried from `Failed`, so every resume re-armed the
loop. A link drop during the node-DB step also waited out the full 120 s nonce timeout,
which was the "hang".

Fix (`RadioManager` + `MainActivity`):
- `attemptLock` (Mutex) + `requestGeneration`: `runAttempts` is the only caller of
  `establish`, one loop at a time, and an older loop aborts when a newer request lands.
- Event collectors are tagged with their connection; events from a superseded link are
  dropped, so a dying predecessor cannot reconnect or fail the live session.
- Only a session that finished the handshake (`sessionWentLive`) may auto-reconnect;
  failures inside `establish` complete a `linkLost` deferred that aborts the handshake
  immediately, and the owning loop does the retrying.
- `awaitConfigComplete` subscribes UNDISPATCHED (a fast nonce echo could previously be
  emitted before the collector existed) and no longer leaks its waiter on timeout.
- Attempt loops always land on a terminal `Failed`; auto-connect is an atomic claim with
  a 20 s cooldown (`tryClaimAutoConnect`).

Second round, after the user reported the churn was still visible: the storm was gone
(logs show single bursts of 3 sequential attempts) but the *experience* was still a loop,
because a) each burst rendered as "Connecting… / Connection lost, reconnecting (attempt
2)… / …" for ~21 s, b) every burst was re-armed on resume once the cooldown expired, and
c) no state except `Subscribed` named the radio, so the user could not see what it was
reaching for. Fixed:
- `RadioState.Connecting(attempt, of)` is now distinct from `Reconnecting(attempt)`,
  which is only used for a link that had actually been live. Wording matches.
- `RadioManager.autoConnect()` spends **one** automatic attempt per process (CAS), so a
  radio that is off or out of range is never chased in the background. The resume-driven
  cooldown retry is gone.
- `_deviceName` is set before the first attempt, and the Connect card names the target in
  every state ("Connecting to 📣_9f4a…", "Could not reach 🐭_4fae").
- The Connect card now shows the remembered radio (label + address) with **Reconnect**
  and **Forget** buttons whenever idle or failed — the only way back after auto-connect is
  spent, and the way to drop a dead remembered radio without scanning.
- `AppContainer.rememberedRadio()` / `connectionFactory()` / `forgetRadio()` are shared by
  MainActivity and ConnectViewModel, so the two paths cannot drift.

Third round: the app only ever stored **one** radio (`radio_type`/`radio_address`/
`radio_name`), and Disconnect deleted it, so every reconnect meant a fresh scan. Added a
saved-radio list:
- `PrefKeys.KNOWN_RADIOS` holds a JSON array (`org.json`, no new dependency) of
  `RememberedRadio(type, address, name, lastConnectedMs)`, most-recent-first, capped at 12.
  `Preferences.knownRadios()` migrates a pre-list install by folding in its single
  auto-connect target.
- `AppContainer.rememberRadio()` is called on every successful connect (scan list, saved
  list, TCP, and auto-connect) and both sets the auto-connect target and upserts the list
  entry with a fresh timestamp.
- **Disconnect no longer forgets the radio**: it clears the auto-connect target
  (`clearAutoConnectTarget()`) so the app does not grab it on next launch, but the entry
  stays in the list. `forgetRadio(address)` is the explicit delete, from the row's Forget
  button.
- The Connect tab shows "Saved radios" above the scan results: label, address, live
  "in range <rssi>" when the scan currently sees it, "last used <relative>", a Connect
  button (or "Connected"), and Forget. Scan results exclude anything already saved.

Verified on device (2026-08-19 19:44-19:46): connect from the scan list saved 📣_9f4a, and
a force-stop + relaunch auto-connected to it with a single GATT connect while the Connect
tab listed it as "Saved radios → 📣_9f4a, last used just now, Connected".

Note the prefs were wiped once mid-session: a Disconnect on the pre-list build deleted the
only stored radio, which is why a relaunch then did nothing at all (no target to connect
to). That failure mode is gone with the list.

Verified on device (2026-08-19 19:24-19:29): one burst of 3 sequential attempts then a
terminal "Could not reach 🐭_4fae"; three home/resume cycles added zero attempts; then a
scan showed SOBE advertising, connect-from-scan reached `Subscribed` ("Connected to
📣_9f4a", node DB loaded), and a force-stop + relaunch auto-connected to SOBE on the first
attempt (1 GATT connect, MTU 247). The app is now pointed at SOBE again.

Why nothing would connect: the remembered radio in DataStore was **CD:12:B4:98:4F:AE =
🐭_4fae = "Peewee Herman"** (!b4984fae, WISMESH_TAG, last seen at 4% battery), not SOBE,
and it was not advertising (GATT 133 on every attempt; a scan at 19:26 showed 🍆_6abc,
📣_9f4a and 🦔_8e18 but no 🐭_4fae). Note SOBE's node num is now **!1eff739f**; the
**!0f352b79** entry in these notes is a stale duplicate with the same public key. Its BLE
identity (📣_9f4a / ED:A6:3B:FA:9F:4A) is unchanged.

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
