# Handoff — 2026-08-20 (~session resume)

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

## Backups keyed by device id, LoRa save without reboot, remote firmware version (2026-10-09 13:15, verified on the phone)
Three upstream catch-ups from the refreshed clone (`8425daa6`):
- **Backup keying** (`NodeBackupManager.swift` / `BackupModels.swift`): `BackupEntry.deviceId`
  (lowercase hex of `MyNodeInfo.device_id`, kept in `RadioManager.deviceId` from MY_INFO, no
  Room change) and `BackupEntry.key = deviceId ?: nodeNum`. Directories and the index map
  are keyed by `key`; index is version 2 (`deviceId` field), version-1 files load
  unchanged. `createBackup(nodeNum, deviceId, name)`; the cross-radio backup in
  `PacketIngest.myInfo` passes null and `performBackup` keeps whatever key the node's last
  backup used. `adoptLegacyBackups(deviceId, nodeNum, address)` runs from `myInfo` on every
  connect: node-number entries matching the node number or radio address move onto the
  device key (newest wins, duplicates deleted). Verified: SOBE's folder `255142777` became
  `e7a5e0e6…`, logcat "Backup for node 255142777 now keyed by device e7a5…"; Spiney Norman
  and Spanky Ham stay node-keyed until they next connect. Rows show name, `!hex` node num,
  device id with a badge, date. Export/import carry `deviceId`; import accepts alphanumeric
  directory names. `deleteBackup(key)`; `restoreFromBackup(nodeNum)` resolves through
  `entryFor(nodeNum)`.
- **LoRa no-reboot** (upstream b91d3f06): `RadioManager.appliesLoRaConfigWithoutReboot`
  = live `deviceMetadata.firmwareVersion` ≥ 2.8.0 (unknown → false).
  `SettingsViewModel.writeLoraConfig` calls `refreshAfterLoRaChange()` after a successful
  save on such firmware: NONCE_ONLY_CONFIG now, NONCE_ONLY_DB 2 s later. `ConfigForm`
  gained `saveNote`; the LoRa form shows "Your device may reboot after saving." on 2.8
  (seen on SOBE 2.8.1) and the default "After config values save the node will reboot."
  elsewhere. Not exercised with a real LoRa save.
- **Remote device metadata** (bug): `PacketIngest.adminResponse` wrote a remote node's
  `GET_DEVICE_METADATA_RESPONSE` into the `my_info` row. It now sets
  `NodeEntity.firmwareVersion` for `packet.from`, which the node detail's "Firmware
  Version" row already displays. Could not be verified end to end: firmware 2.5+
  (`AdminModule::handleReceived`) drops any remote admin payload whose sender key is not in
  the target's `security.admin_key` list with a NOT_AUTHORIZED nak, so Spiney Norman never
  answered SOBE. A node's firmware version is therefore only learnable from nodes that
  list our public key as an admin key. Proving it needs SOBE's key added to Spiney's
  admin keys (a config write on Spiney).

## The other seven config forms ported from the generated upstream forms (2026-10-09 12:50, verified on the phone)

- Upstream (2026-09-18 on) drives every config screen from `Config/Forms/<X>Config.swift`
  overlays (sections, order, symbols, controls, show/enable conditions, omitted fields)
  plus `Model/FieldMetadataRegistry.swift` (labels, descriptions, units, bounds, enum
  value labels and descriptions, deprecations). `ui/ConfigEnums.kt` carries the strings
  and option sets (interval sets, GPS / screen intervals, role order and warnings, enum
  labels, position flag specs, signature policy texts), plus `SecurityKey` / `X25519`
  (RFC 7748 over BigInteger, for Regenerate Private Key and deriving the public key) and
  `IPv4`.
- `RadioManager.deviceMetadata` keeps the handshake's DeviceMetadata in memory (hasWifi,
  hasEthernet, has_xeddsa, pio_env): no schema change. Admin commands added:
  `setFixedPosition`, `removeFixedPosition`, `sendNodeDbReset`, `sendFactoryReset`.
  `SettingsViewModel`: `deviceMetadata`, `adminResult`, `setFixedPosition(enable,
  onFailure)`, `resetNodeDb`, `factoryReset`.
- Device: Options (role with per-role description, the Router / Router Late / Client
  Base confirmation, deprecated note; rebroadcast mode with description; node-info
  interval from the broadcastLong set), Hardware (double tap, disable triple click, LED
  Heartbeat shown in the positive sense), Debug (time zone), GPIO (button, buzzer as
  0..48 pickers with Unset), Reset (NodeDB, Factory with both variants) while connected.
  Save normalizes Router Client -> Client Mute and the 10800 s node-info floor.
- Position: Position Packet (interval, Smart Position and its two limits), Device GPS
  (mode, update interval, Fixed Position with the Set / Remove confirmation that sends or
  clears the position and reverts on cancel or failure), Position Flags as toggles with
  Altitude MSL / Geoidal under Altitude and HDOP/VDOP under DOP, Advanced Device GPS.
- Bluetooth: enabled, pairing mode, six-digit PIN with "BLE Pin must be 6 digits long."
  and Save held back until it is.
- Display: Device Screen and Timing and Overrides with the curated screen-on / carousel
  option sets; the four OLED types plus the current one.
- Network: WiFi Options and Ethernet Options only when the metadata says the radio has
  them (the GAT562 has neither; the form says so), Network Servers, Address Mode, the
  static IPv4 fields validated (Save disabled until address, gateway and subnet are
  well formed; a DHCP save clears them), UDP Broadcast.
- Power: Power Saving, Shutdown on Power Loss (zero-means-off toggle, 1800 s when on,
  seconds row beneath), Wait for Bluetooth, ADC Override toggle with the multiplier.
  Deviation: upstream gates these on a hardware catalog (ESP32 / nRF52 + role) this port
  does not carry, so every row shows.
- Security: Packet Authenticity (policy with per-level description, enabled only when
  the metadata reports has_xeddsa, note when unknown), Direct Message Key (public key
  with Copy and a red tint when it does not match the private key; private key masked
  with reveal, typed text reaches the draft only as a valid 32-byte key; Regenerate),
  Admin Keys (three positional slots; clearing all turns Managed off), Logs, Managed
  Device gated on an admin key. Not ported: the iCloud key backup row and the app-local
  Lockdown section.
- Primitives: `ConfigPickerRow(icon)` with the value column yielding before the title,
  `ConfigTextRow(icon, description, invalid)`, `GpioPickerRow`, `IntervalPickerRow`
  (curated set + the stored value flagged as not optimized), `Ipv4Row`, `KeyField`,
  `DestructiveRow`, `configHeader`, `AdminResultLine`. Saves were not exercised (they
  reboot the radio); every section was opened and read on the phone.

## LoRa config form ported from LoRaConfig.swift (2026-10-09 11:35, verified on the phone)

- `ui/LoRaEnums.kt` ports LoraConfigEnums.swift: region and preset labels and picker
  order, 2.8 gating (`firmwareAtLeast(my_info.firmwareVersion, "2.8.0")`), deprecated
  presets, CodingRates (0 = follow preset; options above the preset's default), Bandwidths
  (picker value 0 = 250 kHz default; 2.4 GHz set), `LoRaRegionPresetMap.decoded()`,
  `presetToSelect` (region change picks Long Turbo for a factory-fresh US node, or the
  region's default when the current preset is illegal there).
- `RadioManager.regionPresets` keeps `FromRadio.REGION_PRESETS` (SOBE on 2.8.1 sends 34
  regions / 6 groups); `SettingsViewModel.regionPresets` decodes it. Only consulted on 2.8
  firmware.
- The form: "Configuration for: <name>" header (shown while connected), Options card
  (Region + description, licensed-band notice, Use Preset with icon + description, Presets
  + US compliance warning), Advanced card (Ignore MQTT, Ok to MQTT, Transmit Enabled with
  icons and descriptions; custom Bandwidth + Spread Factor when presets are off; Coding
  Rate with Follow Preset toggle and the two sliders; Hop Limit 0..7; Frequency Slot,
  disabled while an override is set; RX Boosted Gain; Frequency Override; Transmit Power
  stepper 0..30 with "Max" at 0). Wording follows the iOS build the user compared against,
  which is newer than the local clone in two places: the US warning covers every
  non-Turbo preset ("The Turbo presets are recommended"; the clone only names Long Fast),
  and the follow-preset text is the short form.
- The Apple clone was then pulled (2026-08-18 -> 2026-10-05; the user asked). Upstream
  moved the config screens to `Views/Settings/Config/Forms/` as generated forms over
  `Model/FieldMetadataRegistry.swift` (labels and descriptions per proto field). The port
  now follows that tree: US warning for every non-Turbo preset (`presetIsTurbo`), EU
  regions drop the Turbo presets from the picker, Lite/Narrow/Tiny hidden outside their
  regions when no map arrived, the current preset always listed, coding-rate override
  gated on firmware 2.7.18 (`CodingRates.effective`), Hop Limit 1..7, Spread Factor
  description, Override Duty Cycle only in bands with an hourly limit, PA Fan Disabled on
  the four boards with a fan (`PA_FAN_HARDWARE`), unsupported-region notice with Save
  disabled, Save also disabled on an unsupported custom bandwidth, Transmit Power shown
  as "N dBm" at zero too. Pull the clone before any port from now on (memory
  `refresh-apple-clone`).
- The derived Frequency row (region, slot, MHz from `LoRaChannelCalculator` against the
  draft) stays at the foot of the Options card at the user's request, and the Frequency
  Slot row shows "(now N)" beside a stored 0; neither is in the original. The duty-cycle
  override switch follows the upstream rule below. `ConfigForm` gained `header`
  and `grouped = false`; `ConfigPickerRow` (title, accent value, description, warning) and
  `ConfigSwitchRow(icon)` are the new primitives; `ConfigSection.pageTitle` gives the
  page "LoRa Config".

## Composer toolbar, markdown, mentions (2026-10-09 11:00, verified on the emulator)

- The pin-drop picker from the previous entry is gone (user: not what was wanted). In its
  place, TextMessageField.swift's FormattingComposeArea: `ui/MessageMarkdown.kt` ports
  MarkdownFormatting.swift (wrap/toggle/insert delimiters, link wrap/unwrap, orphan
  cleanup), MentionParser.swift (`@!<8 hex>` tokens, trailing-@ query, token insert,
  resolve to `[@Name](meshtastic:///nodes?nodenum=N)`), and an inline renderer
  (`renderMessageMarkdown`: code, `[text](url)`, bold, strike, italic by earliest match,
  recursive, backslash escapes, bare URLs autolinked, control chars dropped).
- Composer: draft is a `TextFieldValue` hoisted in ThreadView (selection drives the
  toolbar); markdown preview bubble above the field when `containsMarkdownSyntax`;
  @mention autocomplete (up to 10 users, avatar + long name + id); toolbar while focused
  (300 ms grace): Bold/Italic/Strike/Code/Link once the draft has 3 chars, Alert (appends
  the bell sentence + U+0007), Share position (sentence + flag; on send the ViewModel
  sends our position after the text: broadcast without response on a channel, to the peer
  with want-response in a DM, `sendDestPosition(wantResponse)`), Map link (this port's
  own: `mapsLink(here)`), and the Bytes gauge. Programmatic inserts that would pass 200
  bytes toast instead.
- Bubbles render through the markdown renderer with a `LinkInteractionListener`: a
  `meshtastic:///nodes?nodenum=` link opens the node detail in the thread (handleURL),
  anything else goes to the UriHandler. Conversation list previews render the markdown
  with links reduced to text (MessagePreviewText).
- Verified on the emulator: a seeded message with bold/italic/strike/link/two mentions
  rendered correctly; Bold at a collapsed caret inserted `****` with the caret inside;
  Alert and Share position filled the draft; Map link appended the coordinates; the
  preview bubble tracked the draft. Not covered: a real send (no radio on the emulator)
  and the position packet after it.
- Our notifier has no channel-mute or notification-preference gating yet, so the iOS
  "self-mention overrides a muted channel" rule has nothing to override; `containsMention`
  is there for when it does.

## Message links, sender distance, drop-a-pin (2026-10-09 10:30, verified on the emulator)

- `ui/LinkText.kt`: `linkifiedText` wraps web URLs (scheme or `www.`) in
  `LinkAnnotation.Url`; Compose 1.7 `Text` opens them through the UriHandler. White on our
  accent bubble, accent on theirs, underlined both. Verified: a channel message with two
  URLs rendered as links and the maps one opened Google Maps on the emulator.
- Distance under the sender avatar in threads (9 sp caption) and in the car thread row's
  status line through the host `DistanceSpan`; `MessagesViewModel.positionByNode` /
  `myLocation` mirror NodesViewModel. Car side builds but is unverified on the DHU (the
  phone runs the Play build).
- Composer pin button opens `LocationPickerScreen` (satellite MapLibre view, fixed centre
  pin, starts on our location) and inserts `https://maps.google.com/?q=lat,lon` into the
  draft. The draft is hoisted into ThreadView (`rememberSaveable`) so the picker's early
  return keeps it. Not iOS behaviour: its map-pin button sends the radio's own position
  after the message (RequestPositionButton), which this port still lacks.
- Emulator recipe for message UI: restore the SOBE snapshot through Backup Management,
  then insert rows with `run-as <pkg> sqlite3 databases/mesh.db` (all columns; the live
  schema has no defaults on relayNode/relays/pkiEncrypted/xeddsaSigned), force-stop and
  relaunch so Room re-reads. Sending without a radio stores nothing.

## Backup export/import, location tracking for distances, emulator (2026-10-08 15:00)

Shipped as 0.2.10 (versionCode 12): commits c96c048 + a09f056 + 7e0c3cd, tag v0.2.10, GitHub
release with both APKs, upload set in `art/play/0.2.10/` (bundle + `release-notes.txt`) for
the Play internal testing track. Reference position order: the connected radio's own fix
when present, else the phone's (user: radios can wait a long time for a GPS lock).

- The reinstall from Play (over the sideloaded debug build) wiped `Android/data`, backups
  included; the 02:08 set from the PC went back by adb. Positions do not come from the
  radio: Spiney Norman's NodeDB over COM3 has 226 nodes and no latitudes, so a fresh
  install starts with none and refills from position broadcasts (38 nodes in 6 min).
- Backup Management gained a Transfer card: Export Backups to File (one zip, through the
  system file picker: `backup-index.json` + `<nodeNum>/mesh.db`) and Import Backups from
  File (unzips to cache, checks each checksum, replaces an existing entry only when the
  archive's is newer, touches nothing in the live database). Not on iOS, where the backup
  folder is visible in Files. Verified on the emulator: export, wipe the folder, import,
  3 imported / 0 skipped, checksums identical. Release checklist step 5 in
  `docs/play-listing.md` says to do this before any uninstall.
- `LocationSharer` now tracks the phone's location whenever permission is granted
  (seeded from `getLastKnownLocation`), and only *sends* while sharing is on and the radio
  is live. `ensureTracking()` runs at construction, on each sharing/state change, and from
  `MainActivity.onResume`. Before this the node lists had no reference point until sharing
  was on or the radio had broadcast its own position: the user saw PA-I with a position on
  the node map but no distance line. `docs/PRIVACY.md` updated to match. Not yet verified
  on the phone (it runs the Play build); next upload.
- Shipped as 0.2.11 (versionCode 13): commits c57d643 + f801281, tag v0.2.11, GitHub release
  with both APKs, upload set in `art/play/0.2.11/` for the Play internal testing track.
- Exported zip vanished with the app (2026-10-08 15:20): the user saved through the
  picker's Downloads root on the Note 20 Ultra (Android 13), uninstalled, reinstalled, and
  the zip was gone from `/sdcard/Download` and the media index. On the AOSP emulator the
  same file (MediaStore owner `com.android.providers.downloads`) survived an uninstall, so
  it is the Samsung downloads provider purging the app's entries. Fix: both pickers open on
  the Documents folder via `EXTRA_INITIAL_URI` (`primary:Documents`, owner
  `com.android.externalstorage`), and the row subtitle says not to use Downloads. The
  02:08 backup set was pushed back to the phone by adb a second time.
- Emulator: AVD `test33` (API 33 google_apis x86_64, Pixel 6 geometry) hand-written in
  `~/.android/avd/` because cmdline-tools are not installed. Boots with
  `emulator -avd test33 -no-snapshot -no-boot-anim -no-audio` in about 90 s; `run-as`
  and `pm grant` work there. No radio, so it covers UI only.

## Car node detail, traceroute views, distance rows, per-tab state (2026-10-08 14:20, verified on the DHU and the phone)

Shipped as 0.2.9 (versionCode 11): commits 5b6cf04 + 7289ba7, tag v0.2.9, GitHub release
with both APKs, upload set in `art/play/0.2.9/` (bundle + `release-notes.txt`) for the Play
internal testing track. The phone runs the sideloaded 0.2.9 debug build (Play build
uninstalled, the 02:08 backup set pushed back and restored).

- Car node detail is a sectioned `ListTemplate` (Details, then Actions) instead of a
  `PaneTemplate`, which caps at two buttons. Actions: Message, Trace Route, Traceroute Log,
  Exchange Positions, Exchange User Info, Request Local Stats as rows; Favorite, Mute
  notifications, Ignore node as `Toggle` rows (titles never change, so a flip is a refresh,
  not a template step). Deliberately absent on the head unit: Share QR, S&F history/config,
  metadata refresh, Delete, Power Off, Reboot, Accept new key (no result view, or destructive
  with no confirmation dialog in a POI app).
- `CarTraceroutesScreen` (per-node log, phone text format) and `CarRouteMapScreen`
  (`PlaceListMapTemplate` with one numbered marker per positioned hop: green origin, blue
  hops, red destination). The host draws markers only, no lines, and its map is not
  user-zoomable; it fits the markers itself (took a few seconds once). Map behind the car
  list is the host's Google Maps, so no satellite style exists for the Place list template;
  a POI app may draw its own map with `MapWithContentTemplate` (library 1.7, permission
  already declared) but that is MapLibre into a car Surface, a separate job.
- `markerLabel(short, long)`: emoji-only short names fall back to long-name initials
  instead of "?".
- Distance and bearing (NodeListItem's ruler + "x mi away" + rotated north arrow + degrees)
  on the phone Nodes row after Unmonitored, and in the car Nodes row's second line through
  the host `DistanceSpan`. Geodesy in `ui/Geo.kt` (shared with `CarScreens`). Reference
  point: `LocationSharer.lastFix` while sharing, else the connected radio's position; iOS
  always has a phone fix because its LocationsHandler runs whenever permitted.
- `MainActivity` wraps the tab `when` in `rememberSaveableStateHolder().SaveableStateProvider(selected)`:
  each tab keeps its `rememberSaveable` state across tab switches, the iOS per-tab
  NavigationStack behaviour. Before this, Nodes came back at the list after any tab switch.
- Trace route from the node detail log is a sub-screen of the detail (`MapScreen(traceroute =
  entity)` under a `DetailHeader` back arrow), the same shape as the Node Map row. User
  direction: the tap-card-to-map link is this port's addition (iOS has a "Show on Map"
  button that switches to the Map tab), so it follows the sub-screen convention and back
  returns to the log. `Router.activeRoute`/`openRoute`/`clearRoute` and the map's X button
  are gone; `MapViewModel.routeView(route)` returns a Flow resolved per call.

## 0.2.8 (versionCode 10) on the Play internal track: Android Auto verified in a car (2026-10-08)

- Car service label and icon reverted to the app's own; the user reports everything works
  on the head unit from the Play build. Upload set in `art/play/0.2.8/`.

## Android Auto with the Play build (2026-10-08 10:00, verified on the DHU)

- The user saw no app icon on a real car with the internal-testing build. On the DHU the
  same build is in the launcher as **"Ducks Mesh"** (the car service label, blue map icon,
  sorted under D), opens to the home menu (Map 114 nodes, Messages, Nodes 391), and the
  log shows no `CAR.VALIDATOR: Package DENIED` for our package while it denies plenty of
  others. The release `hosts_allowlist_sample` validator accepts the real gearhead host.
  Most likely the user looked for "Mangled Baby Ducks" in the car's grid.
- Facts from developer.android.com/training/cars/testing: Android Auto's "Unknown sources"
  toggle covers media, messaging and parked apps and does NOT apply to Car App Library
  apps; a templated app on a real car must come from a trusted source, and the Play
  internal test track counts without review. So the sideloaded debug build never had its
  templated entry on a real car; what the user saw on the grid then was the messaging
  personality (green triangle), which has no tabs by design.
- Play Services' validator logs its verdict as `CAR.VALIDATOR: Package DENIED; ...` on the
  phone when a head unit connects; the phone's log buffer only holds about 15 minutes, so
  capture right after the car session or use the DHU.
- DHU recipe that works from Git Bash: `adb forward tcp:5277 tcp:5277`, then
  `(tail -f cmds.txt | ./desktop-head-unit.exe --adb=5277 > out.txt 2>&1 &)` from the DHU
  folder, and append `tap x y` / `screenshot path` lines to cmds.txt. The PowerShell
  Start-Process variant with a piped Get-Content never started the exe. The phone needs
  "Start head unit server" in Android Auto developer settings first (not persistent).
- DHU window size (2026-10-08 11:40): resolution comes from `--config=config/default_1080p.ini`
  (also default.ini 800x480, default_720p.ini; copy and edit `resolution`/`dpi` for other
  sizes). There is no fullscreen flag; on this 4K desktop at 200 % scaling a 1920x1080 window
  cannot fit the 1920x1080 logical desktop, so Windows maximizes it with the frame offscreen.
  Fix: mark the exe high-DPI aware (`AppCompatFlags\Layers` value `~ HIGHDPIAWARE`), done
  once in the registry and re-applied by `tools/dhu.ps1`. Then it is a bordered 1920x1080
  physical-pixel window.
- If the DHU logs `connected.` but never `Phone reported protocol version`, the phone's head
  unit server is wedged on a stale socket (`netstat` on the phone shows 5277 CLOSE_WAIT with
  unread bytes). Toggle "Start head unit server" off and on, then relaunch the DHU.

## Test rig change: the phone runs the Play internal-testing build (2026-10-08 02:10)

- Mangled Baby Ducks 0.2.7 (versionCode 9) is installed from Google Play (internal
  testing track, Play-signed). It is a release build: `run-as` is refused, so no database
  pulls, no index edits, no `exec-out ... cat`. `adb logcat` still works (release keeps
  `Log.i/w/e`; the `Log.d` handshake timings are gone). The external files folder
  `Android/data/<pkg>/files/NodeBackups` is still reachable with `adb push`/`adb shell ls`.
- A debug APK cannot be installed over it (signature mismatch). To go back to the bench
  setup: pull `NodeBackups` first (plain `adb pull` works), uninstall, sideload debug,
  push `NodeBackups` back, restore from Backup Management.
- The user's data was carried across the reinstall this way: `backups/2026-10-08/`
  (git-ignored) holds the pulled snapshots, the live store checkpointed into a 409600-byte
  9f4a snapshot (384 nodes, 854 messages, 30 traceroutes), and `restore/NodeBackups/` with
  the rewritten index. After restore the Play build shows 384 nodes.
- Saved radios and the location-sharing preference did not carry over (DataStore is app
  private); the user reconnects to each radio once.

## Release 0.2.7 (2026-10-08, tag v0.2.7, versionCode 9): targetSdk 36 for Play

- The Play Console rejected the 0.2.6 bundle: new apps must target API 36 (Android 16).
  `compileSdk`/`targetSdk` are 36; AGP 8.7.3 builds it with
  `android.suppressUnsupportedCompileSdk=36` in gradle.properties. Native libs (MapLibre,
  androidx graphics path, datastore) verify 16 KB aligned (`zipalign -c -P 16 -v 4`).
- Smoke-tested on the Note 20 Ultra only (Android 13). Target-36 behaviour changes apply on
  Android 15/16 hardware, which is not on the bench: edge-to-edge is enforced (the thread
  screen's "window shrinks for the keyboard" assumption in MessagesScreen.kt needs
  `imePadding` there), predictive back is on by default, and orientation/resizability
  restrictions are ignored on large screens. Test on an Android 16 emulator before
  production.
- Upload set in `art/play/0.2.7/`. The 0.2.6 GitHub release stays because the foreground
  service declaration video URL points at it.

## Release 0.2.6 (2026-10-08, tag v0.2.6, versionCode 8; first Play-ready bundle)

- Same recipe as 0.2.0, plus `:app:bundleRelease` for the Play Console. The upload set
  (both APKs, the `.aab`, 512 icon, feature graphic, six 1080 x 2160 screenshots) lives in
  `art/play/0.2.6/` (untracked `art/` folder). Deliverables go in the project tree, never
  the scratchpad. On first upload Play App Signing makes the release key the upload key;
  let Google hold the app signing key. `allowBackup="false"` was added after the first
  publish, so the v0.2.6 tag was moved and the release APKs replaced before anyone had
  them; `docs/PRIVACY.md` now states the opt-out.
- GitHub pushes and `gh` must run as `suteny0r`; the gh active account had drifted to the
  other login, which made git prompt for a password it could not read.
- Node map after node detail: the style callback drew the full mesh from the raw list and
  fitted to it, and `update`'s single-node set never re-ran. `displayNodes` is now computed
  once and shared. Reduced-precision positions draw their circle (node colour at 25 %,
  white edge) on both maps; the single-node camera uses the NodeMapSwiftUI distance
  (10 km, or 10x the circle radius for 12..24 bits). `MapNode.precisionBits` comes from
  the `mapNodes()` query.
- Traceroute map starts at the first hop because our own node has no position row in any
  store copy (9f4a and 8e18 both): the originator is dropped exactly as iOS drops hops
  without a snapshot. User will retest with nodes that have positions; if the origin is
  still missing, look at own-position ingest (the radio cc's its own POSITION_APP
  broadcasts to the phone, and the connect dump's own NodeInfo carries a position when
  the radio has one).

## Restore killed every Room observer, and a backup overwrote 845 messages (2026-10-07, installed and verified)

- Symptom 1: after a radio switch, a sent channel message only appeared once the thread
  was reopened; the unread badge and node counts stopped moving too. logcat had
  `E ROOM: Cannot run invalidation tracker. Is the db closed?` with
  `no such table: room_table_modification_log`, and `databases/` had no `-wal`/`-shm`.
- Cause: `importAllTables` used `ATTACH DATABASE` on Room's connection. Android's
  `SQLiteDatabase.executeSql` answers an ATTACH with `disableWriteAheadLogging()`, the
  pool treats the WAL flag change as "close and reopen every connection", and Room's
  tracker is TEMP objects on that connection (the log table and one trigger per table),
  so they vanished. The import now reads the staged copy with a read-only
  `SQLiteDatabase` and inserts row by row through Room's `SupportSQLiteDatabase.insert`
  (CONFLICT_IGNORE) inside `runInTransaction`. ContentValues keys must carry their own
  backticks: `channels.index` is a reserved word and the first run failed on it.
- Symptom 2: the user's 9f4a history (384 nodes, 845 messages) was replaced by a 100 kB
  snapshot of an empty store. `currentNodeNum()` preferred `radioManager.myNodeNum`, which
  outlived the clear when a switch to a never-seen radio (243c, not in range) was followed
  by another switch six seconds later: the empty store was filed under 9f4a. Now
  `currentNodeNum()` is the my_info row only, and `createBackup` refuses when the store's
  my_info is missing or belongs to another node.
- The on-device backups were lost once more during the repair (both directories deleted
  with an empty index saved at 23:31:56, cause not captured: logcat had been cleared).
  Reinstalled the PC copy `F:/mesh-9f4a-full-2130.db` as the 9f4a snapshot with the app
  force-stopped, index written from a script with the real sha256 and size, and the app
  relaunched by me in the same command so nothing else could start it first. Then restored
  it through Backup Management and reconnected: 384 / 845 / 29, WAL present, no ROOM errors.
  Messages from 21:30 to 23:00 on 9f4a are gone.
- The thread screen now runs with `windowSoftInputMode="adjustResize"`: without it the
  system picked pan for the Compose window and the header, search field and messages all
  slid off the top when the keyboard opened.
- Gotchas: `adb install` from Git Bash needs a Windows path (`F:/...`), the `/f/...` form
  fails with "failed to stat" and a `| tail -1` hid that twice. Pull a WAL-mode store as
  three files (`mesh.db`, `-wal`, `-shm`) or the counts are stale. Check that the install
  actually happened (`dumpsys package | grep lastUpdateTime`) before trusting a test.
- The Connect list's saved BLE rows now show the name the radio advertises right now when
  it is in range (iOS lists live scan results), and a connect through a saved row records
  that name.

## Handshake sat at "Retrieving nodes N" for 35 s after the dump had finished (2026-10-07, installed and verified)

- Symptom: the node counter reaches its final value, then Subscribed arrives 5 to 35 s later.
  Measured with three debug log lines (now at `Log.d`): the DB-nonce CONFIG_COMPLETE was
  emitted 20 ms after the last NodeInfo, and the handshake coroutine only looked at it 35 s
  later. `setTime` itself took 200 ms. It was not main-thread starvation: the gap was the
  same after moving `connect()` onto Dispatchers.Default, and the debug watchdog logged no
  stall.
- Cause: `awaitConfigComplete` runs `request()` to completion before awaiting the nonce,
  and `request()` ended with `conn.startDrainPendingPackets()`, which is a loop that reads
  FROMRADIO until a zero-length read. After a node dump the radio keeps streaming queued
  packets, so the drain did not go quiet for half a minute and the already-emitted nonce
  went unexamined. The FROMNUM doorbell path had always launched the drain; the handshake
  path now does too (`scope.launch { conn.startDrainPendingPackets() }`).
- After: nonce seen 1 ms after CONFIG_COMPLETE, Subscribed 262 ms later. The connect also
  runs on Default now (every caller is a Main-thread ViewModel; iOS's pipeline is off the
  main actor), and debug builds carry `MainThreadWatchdog`, which logs the main thread's
  stack whenever a posted no-op does not run within 2 s. Android itself only reports a stall
  when input is pending or a frame was due, so a quiet screen with a blocked main thread
  logs nothing without it.
- Reinstalling while connected leaves the radio holding the dead session: the next connect
  gets three `GATT status 133`. Force-stop, wait a few seconds, relaunch.

## Backups are keyed on the address stored IN the database, like peripheralId (2026-10-07, installed and verified)

- The previous fix (capture the saved address before `rememberRadio`) was not enough: the
  foreign-store guard fired one second after a switch and re-filed the backup under the
  radio the switch had just saved, because the guard read `rememberedRadio()` for the
  address too. Once one index entry is wrong the wrong `resolveNodeNum` makes the next switch
  a plain connect into a foreign store, the guard fires again, and the corruption
  self-perpetuates. That is what the user saw as "connects to the previous radio": GattService
  showed every connect at the tapped MAC, but the panel wore the foreign store's identity
  through the handshake and the user's history got overwritten by a fresh dump twice.
- The port now does what iOS does: the address lives in the store. `my_info.radioAddress`
  (`MyInfoEntity.peripheralId`, migration 7 -> 8) is written at MY_INFO from the address the
  connect was asked for (`RadioManager.connect(name, presence, address, factory)`;
  `lastAddress` -> `ingest.myInfo`). `NodeBackupManager.createBackup(nodeNum, nodeName)` reads
  the address out of the store it is snapshotting, so a guard-time backup is keyed on the
  radio whose data it holds, whatever prefs say. `isSwitch` checks the store's own address
  first.
- Verified: 9f4a -> 8e18 logged `current 255142777 (ED:A6...), target 2600902403 (3C:DC...)`,
  8e18 -> 9f4a the reverse; the index addresses stayed put through both; the panel read the
  target radio from the first Communicating frame; 9f4a came back with 384 nodes, 845
  messages, 29 traceroutes and the unread badge.
- The full 9f4a snapshot had to be reinstalled from the PC copy a second time
  (`F:/mesh-9f4a-full-2130.db`), with the app force-stopped so the in-memory index did not
  save over the edit, then restored from Backup Management before the switch test so the
  thin dump would not be backed up over it again.
- Driving the phone: it rotates to landscape on its own; a tap aimed from a portrait frame
  lands on nothing, and a tap aimed at the launcher opened WhatsApp once. Check
  `dumpsys window | grep mCurrentFocus` and take a fresh frame before every tap.
- `uiautomator dump` can return nothing at all, not just stale XML. Screencap is the
  reliable way to find a row.

## Switch filed each backup under the other radio's address (2026-10-07, installed and verified)

- First live switch after the backup port: 9f4a -> 8e18 -> 9f4a came back with "target
  unknown", no restore, and the user saw the previous radio's name in the connect panel
  during the handshake, then read that as connecting to the wrong radio. GattService
  showed every GATT connect went to the tapped MAC; it was the panel.
- Cause: `switchRadio` called `rememberRadio(target)` BEFORE `backupCurrentDatabase` read
  `rememberedRadio()?.address` for the backup's `radioAddress`, so 9f4a's snapshot was
  indexed under 8e18's address and Spiney's under 9f4a's. `resolveNodeNum(target)` then
  returned the CURRENT node, `isSwitch` said "same radio", a plain connect ran into a
  foreign store, the guard fired mid-handshake, and the panel showed the old store's
  identity while the handshake rewrote it.
- Fix: capture the current radio's address before `rememberRadio`, pass it through
  `backupCurrentAndRestore(currentAddress =)`; the guard path gets the address through a
  `PacketIngest(currentRadioAddress = { rememberedRadio()?.address })` supplier, which at
  guard time is still the previous radio's.
- Each wrong-way switch also OVERWROTE the per-radio snapshot with the fresh dump
  (`performBackup` deletes and recreates the node's directory), so the on-device 9f4a
  backup shrank to 131 kB with 0 messages. The 21:30 Backup Now copy pulled to the PC
  during verification (`F:/mesh-9f4a-full-2130.db`) was installed back as
  `NodeBackups/255142777/mesh.db` with the index checksum/size rewritten (app force-stopped
  first: the index is held in memory and saved over on every write), then a switch back to
  9f4a restored it: 384 nodes, 845 messages, 29 traceroutes, unread badge back.
- Verified after the fix: 9f4a -> 8e18 logged `Node switch: current 255142777, target
  2600902403`, restored Spiney's snapshot, and the panel read "Spiney Norman /
  Meshtastic_8e18" from Communicating through Subscribed; 8e18 -> 9f4a likewise.
- The Connect list is hidden while connected (the device box owns the screen), so a
  switch is swipe-to-Disconnect first, then tap. `disconnectRadio` clears the auto-connect
  target, which is why `isSwitch` cannot rely on `rememberedRadio()` alone and consults the
  backup index too.

## Per-radio database backups, ported from NodeBackupManager (2026-10-07, installed and verified)

- The cross-radio reset used to clear nodes and keep messages because nothing saved the
  previous radio first. That was my stand-in for the missing backup subsystem, not the
  original's behaviour. The subsystem is now ported and the reset matches iOS.
- `db/NodeBackupManager.kt` = `NodeBackupManager.swift` + `+Import.swift` + `BackupModels`:
  one compacted `mesh.db` per radio under the app's external files dir
  `Android/data/com.suteny0r.mangledbabyducks/files/NodeBackups/<nodeNum>/` (the Files-visible
  Documents counterpart; internal fallback), `backup-index.json` alongside, SHA-256 checked,
  50-backup cap, 50 MB free-space floor, retry once. A backup is a WAL checkpoint, file copy,
  then checkpoint + `journal_mode=DELETE` + `VACUUM` on the copy. Restore stages the snapshot
  into cache, opens it through Room so a pending migration runs on the copy, ATTACHes it to the
  live database and copies every table by named columns (the per-entity import helpers). The
  index also stores the radio's address, standing in for `MyInfoEntity.peripheralId`.
- **Room does not notice the raw copy.** After `clearAllTables()` the unread badge went to 0
  and stayed there with 1 unread in the table. `invalidationTracker.notifyObserversByTableNames`
  is the fix (Room's hook for external writes), the counterpart of iOS bumping
  `databaseResetID`. `refreshVersionsAsync()` alone was not enough.
- `AppContainer`: `currentNodeNum`, `backupCurrentAndRestore(targetNodeNum, disconnect)` and
  `switchRadio` are `Connect.swift`'s `backupCurrentDatabase` / `backupCurrentAndRestoreDatabase`
  / `switchToDevice`. Every Connect-tab path (`connectBle`, `connectTcp`, `connectKnown`, the
  container's `connectKnown`) asks `isSwitch(address)` first: different radio than the store
  holds means back up, record the new auto-connect target, disconnect, clear, restore, then
  connect. Same saved address, or a backup saying this address IS the store's node, means a
  plain reconnect. `Router.resetNavigation()` is the `popToRoot` on every tab.
- `PacketIngest.myInfo`'s foreign-store guard is now `defensiveResetIfForeignDatabase`:
  back the previous radio up, then `clearAllTables()`. Messages go with everything else, as
  on iOS; the backup is what makes that safe.
- `ui/BackupManagementScreen.kt` = `BackupManagement.swift` + `BackupRowView.swift`, reached
  from Settings > Developers > Backup Management: total storage, the list, Backup Now in the
  header, tap a row for Restore / Delete (iOS swipe actions and context menu), the delete
  confirmation text, the Restoring overlay, the Backup Failed / Restore Failed alerts. A
  restore from here is `disconnectCurrentDevice: true` and also drops the auto-connect
  target, or the next resume would reconnect the old radio and the guard would undo it.
- Verified on the phone: Backup Now wrote a 340 kB snapshot with counts identical to the live
  store (384 nodes, 845 messages); Restore of that snapshot cleared and re-imported in 180 ms
  with every count identical and the unread badge intact; reconnecting afterwards was a plain
  connect with no reset. **Not verified: a live switch to a different radio** (the backup /
  clear / restore-or-empty / connect sequence against a second BLE radio); the code path is
  the same `backupCurrentAndRestore`, but it has not been run against hardware.
- Getting a readable copy of the live database: `adb exec-out run-as ... cat`, not
  `adb shell ... cat` (the shell path mangles bytes and sqlite reports "malformed").

## Users without a node row rendered empty detail pages (2026-10-07, installed and verified)

- The DB held 383 users but only 127 `nodes` rows: **256 orphans**. `NodeDetailViewModel.node()`
  queries `nodes`, so an orphan's page showed "Node <num>" with Unknown hardware, and the
  node never appeared in the Nodes list at all. KZ4DE G3 (`!c1f5a508`) was one, despite
  having sent a channel message hours earlier.
- Cause: `PacketIngest.myInfo`'s cross-radio defensive reset clears `nodes`, `positions`,
  `telemetry` and `my_info` but NOT `users`, so every reset stranded the whole contact
  list. (Every `nodes.firstHeard` in the dump was within one 40-minute window, which is
  how the reset showed up.) iOS cannot have this: a `UserEntity` is reached through its
  `NodeInfoEntity`, and its cross-device guard calls `clearDatabase`.
- Three fixes: the reset now clears `users` too; `upsertUser` creates the node row first,
  matching `upsertNodeInfoPacket`'s bare-User branch; and connect backfills a minimal
  `NodeEntity` for any `NodeDao.orphanUserNums()`, which repairs an existing DB without a
  wipe. Messages are deliberately NOT cleared by the reset (iOS backs the store up first
  and we have no backup path), so old threads keep their text either way.
- Verified: orphans 256 -> 0, nodes 127 -> 383, and KZ4DE G3 now opens with its Station G2
  hardware card, name, node number and the rest.

## Node Map and map node taps stay where the Swift app puts them (2026-10-07, installed and verified)

- **"Node Map" used to jump to the Map tab** (`Router.openMapNode`), so back landed on the
  node list. `NodeMapSwiftUI` is pushed INSIDE the node detail stack, so it is now a
  sub-screen of node detail with its own `DetailHeader` back arrow, and back returns to the
  detail at its scroll position. `MapScreen(focusNode = ...)` is the same map scoped to one
  node: no waypoint long-press, no node taps, no toolbar (the detail header replaces it).
- iOS shows only that node's positions there, so there are no other nodes on the node map
  to tap. That is the original's behaviour, not an omission.
- **Tapping a node on the Map tab used to switch tabs** (`router.openNode`), so back landed
  on the node list. `MeshMapMK.swift` presents `NodeDetail(showMapLink: false)` as a sheet
  over the map, so it is now an overlay inside the Map tab, drawn after the map rather than
  as an early return so the `MapView` stays composed and the camera survives. Node detail
  gained `showMapLink` to hide its own Node Map row there.
- `renderNodes` now centres at zoom 13 when exactly one node is visible; `newLatLngBounds`
  needs two points, so the node map opened zoomed all the way out.
- Verified on the phone: node detail -> Node Map centres on the node, back returns to the
  detail; Map tab -> tap EYG2 opens Emir G2 over the map, back returns to the identical
  map view.

## Node detail keeps its scroll when a log closes (2026-10-07, installed and verified)

- `NodeDetailScreen` created its `rememberScrollState()` below the `log?.let { ... return }`
  early return, so opening a log sub-screen dropped it from composition and back landed at
  the top. Hoisted to `detailScroll` above the return. Fourth instance of this trap after
  the Nodes list, the Messages thread/section and the Settings list; the Nodes list's own
  `listState` was already hoisted and is fine.
- Verified by screenshot: the page is pixel-identical before opening Trace Route Log and
  after pressing back.

## Trace Route rate limit, ported from iOS (2026-10-06, installed and verified)

- `ui/RateLimit.kt` is `RateLimitedButton.swift` + `RateLimitStorage`: in-memory, never
  persisted, keyed by action (not by node), so "traceroute" is one 30 s floor shared by
  every node. `rememberRateLimit(key)` ticks at 100 ms only while a key is running.
- Both Trace Route entry points use it: the Actions row (`RateLimitedActionRow`) and the
  Trace Route Log page's button. While running they are disabled, read
  "Trace Route (in 21s)" and draw a draining `CircularProgressIndicator` where the icon
  goes; iOS uses the variable-value `progress.ring.dashed` symbol.
- The ring is `DashedProgressRing`, 20 dashes with the leading fraction lit, standing in
  for the SF Symbol.
- `TraceRouteLog.swift` has NO run button: it is a log, and the send lives on the node
  detail Actions row. I had added one here; it is removed. **Do not add UI the original
  does not have.**
- Verified on the phone against RAZOREDG Base: the Actions row read "Trace Route (in 27s)"
  greyed with the ring drawn, matching the iPhone screenshot exactly.
- **Gotcha: `uiautomator dump` served stale XML here.** Three dumps after taps still
  showed the old label while a screencap showed the countdown. Trust `adb shell screencap`
  over the XML when checking whether a tap landed.

## Settings scroll, node number wrap, broadcast confirmation (2026-10-06, installed; 0.2.5)

- **Settings list position was lost on back.** The main column's `rememberScrollState()`
  sat BELOW the `showAbout` / `showChannels` / `section` early returns, so opening a
  sub-screen took it out of composition and the offset was discarded. It is now
  `listScroll`, remembered above the returns. Same trap as the Nodes list, the thread
  scroll and the Messages `section` before it: state created under an early return does
  not survive that return. The section detail's own scroll is still shared between
  sections, so switching section to section keeps the previous offset; not keyed yet.
- **Node number wrapped onto two lines.** `DetailRow` capped the value at
  `weight(2f, fill = false)` against a `Spacer(weight(1f))`, which with the Copy button
  left a 10-digit node number ~297px when it needed more. The spacer is now a fixed 12dp
  and the value takes `weight(1f)` with `TextAlign.End`. Verified by uiautomator dump:
  the value went from a 138px two-line box to one 39px line.
- **Broadcast node info had no visible response.** The only feedback was the row's own
  subtitle flipping, which reads as part of the row. Now an AlertDialog ("Node Info Sent"
  / "Broadcast Failed"), matching `ExchangeUserInfoButton.swift`, with
  `clearBroadcastResult()` on dismiss so a second tap raises it again. NOT verified on
  hardware: tapping it transmits a real mesh-wide announce.

## Channels screen with a real editor (2026-10-06, installed; no-op write verified)

- Settings > Channels was an import dialog only. `ui/ChannelsScreen.kt` ports
  Channels.swift: the frequency summary row (region, MHz, slot, from
  LoRaChannelCalculator), a row per enabled channel (index disc, lock glyph, name, role
  caption, pin when it shares position), and Add Channel while fewer than 8 exist. The
  meshtastic.org/e/# import moved to its own row below.
- The editor is ChannelForm.swift: name (spaces stripped, 11 bytes max), key size
  (Empty / Default / 1 byte / 128 / 256) with a regenerate button, an editable base64 key
  that blocks Save when its length does not match the chosen size, role (Primary fixed on
  index 0, else Secondary / Disabled), Positions Enabled plus the 12..15 precision slider,
  and MQTT uplink / downlink. Save sends ONE setChannel wrapped in begin/commit.
- `ChannelEntity` gained `uplinkEnabled` / `downlinkEnabled` behind `MIGRATION_6_7`, so
  the editor round-trips the MQTT flags instead of clobbering them. **A migrated row
  defaults both to false**, and they only become the radio's truth after the next
  handshake re-sends the channel dump: do not Save before reconnecting after an upgrade.
- Write path proven with a no-op save on channel 0: two QUEUESTATUS acks, `Link lost`
  (the radio saving and rebooting), reconnect on attempt 3, then the post-reboot dump
  compared byte-for-byte (PSK hashed) against the pre-write snapshot. Identical, and
  channel 1 untouched.
- Still untested against hardware: a real edit, Add Channel, and Save with role Disabled
  (which is how a secondary channel is deleted, locally dropping its messages too).

## Connect box named the wrong radio; LoRa frequency is now shown (2026-10-05, installed and verified)

- **The box wore the previous radio's identity.** While connecting, it filled itself from
  `my_info` and our own user row, which still describe the radio from before until the new
  radio's handshake rewrites them: the box said `_4fae` while the pairing prompt said
  `_8e18`. Gating on the connection state was not enough, because `myNodeNum`, `my_info`
  and the user row only turn over at MY_INFO, after Communicating begins, and `_myNodeNum`
  was never cleared on connect or disconnect. `RadioManager.identityReady` now says
  whether the stored identity belongs to THIS session (false from the start of any
  connect, true once this radio's MyNodeInfo is ingested) and the box keys off that.
  Until it flips the box shows only the name of the radio being reached for.
- Connect rows are wrapped in `key(radio.key)` and hand `connectByKey(key)` to the
  ViewModel instead of a captured device. Scan results arrive while the list is up, and an
  unkeyed row can adopt its neighbour's data when the list shifts. A `connect requested:`
  log line (tag ConnectViewModel) names the radio every tap asks for.
- **Frequency slot 0 is not "no slot", it is "derive one".** `ui/LoRaChannelCalculator.kt`
  ports LoRaChannelCalculator.swift: slot count from the region band and preset bandwidth,
  djb2 hash of the primary channel name (or the preset's own name when the channel is
  unnamed, which is the case after a factory reset) into a 1-based slot, then the centre
  frequency. US + MEDIUM_FAST gives slot 45 at 913.125 MHz, verified on the phone.
- The LoRa section gained a read-only Frequency row (region, slot, MHz) computed from the
  DRAFT so it moves as you pick a region or preset, and the slot field shows `0 (now 45)`.
  The stored 0 stays as-is: writing the derived number would PIN the slot, and the radio
  would stay there even if the channel name changed. iOS binds its field to channel_num
  the same way.

## Per-message security flags and badges (2026-10-05, installed and verified)

- `messages` gained `pkiEncrypted` and `xeddsaSigned` behind `MIGRATION_5_6` (two more
  additive ALTER TABLEs). Verified in place: user_version 6, 687 messages / 203 nodes /
  362 users, nothing lost.
- Ingest sets `xeddsaSigned = packet.xeddsaSigned && isBroadcast` (firmware only signs
  broadcasts; our own broadcast test is the gate so a stray flag cannot shield a DM) and
  `pkiEncrypted = packet.pkiEncrypted && sender is a known PKI user`. The Swift original
  reads `fromUser?.pkiEncrypted ?? false && packet.pkiEncrypted`, where `&&` binds tighter
  than `??`, so its packet term is dead; we implement the intent and say so at the site.
- `RadioManager.sendTextMessage` marks our own DM row encrypted when we hold the
  recipient's key. Still unported: the outbound `packet.pkiEncrypted` / `publicKey` that
  iOS sets, which changes what the firmware is asked to do.
- Message Details shows "Encrypted" and "Signed · verified" above Channel, and `Bubble`
  draws MessageText's corner badges (white glyph on a green disc, bottom trailing). The
  store-and-forward envelope and translate badges are not ported.
- Verified encrypted end to end with a DM from Spiney Norman: stored pkiEncrypted=1,
  xeddsaSigned=0, lock disc on the bubble, "Encrypted" + SNR/RSSI in the dialog. **The
  signed shield is unverified**: no radio in range signs broadcasts (one node in the whole
  DB has ever been seen signing), so that path has code but no evidence.
- Old rows migrate in as false. These flags only exist in the packet at receive time, so
  they are truthful only for traffic arriving after this build.
- Fixed while testing: a DM thread opened before the handshake stayed empty forever.
  `directMessages` / `directTapbacks` sampled `myNodeNum.value` once, so a thread opened at
  0 queried node 0 for good. Both now `flatMapLatest` over `myNodeNum`; `markDmRead` waits
  for a nonzero number.

## Delivery status matched to iOS, with the first real Room migration (2026-10-05, installed and verified)

- `ui/RoutingError.kt` ports RoutingError.swift: per-error label, explanation and
  `canRetry`. `deliveryOf` now shows the error's own label ("Channel/key mismatch",
  "Recipient needs your key", ...), red with an X and no Try Again where a retry cannot
  work, orange with Try Again where it can. DM success reads "Delivered to recipient", and
  the 5 minute timeout reuses MAX_RETRANSMIT's paragraph as `notDelivered` does.
- Ack flags now only go up (`CASE WHEN :receivedAck THEN 1 ELSE receivedAck END`), which is
  what MeshPackets.routingPacket does: a nak after an ack can no longer pull a delivered
  row back to an error. `realAck` is set for any DM reply where `to != from`, whatever the
  error, with the DM test done through `destinationOf(messageId)`.
- `messages` gained `relayNode` and `relays`, written from the routing reply, and
  `MessagesViewModel.relayDisplay` resolves the low byte to a node name the way
  MessageEntityExtension.relayDisplay does (single match, else fewest hops, else hex).
- New "Message Details" item in the bubble's long-press menu, the content of iOS's
  submenu: time, channel, Ack Relay / Relay line, SNR+RSSI for a zero-hop neighbour or
  Hops Away otherwise, the relay tally, and the delivery status for our own sends. Its
  state is hoisted to ThreadView so an auto-scroll cannot close it.
- **First real migration in this repo.** `MIGRATION_4_5` adds the two columns with ALTER
  TABLE and is registered with `addMigrations`; the destructive fallback stays for
  everything else. Verified on the phone by upgrading in place: user_version 4 to 5, 656
  messages / 191 nodes / 360 users before and after, no crash, Room's post-migration schema
  check passed. A sideload over the top keeps user data; only a DB older than version 4, an
  uninstall, or a rollback to an older APK wipes.
- Not ported: per-message `pkiEncrypted` and `xeddsaSigned`, so iOS's "Encrypted" and
  "Signed · verified" lines are still missing. Another migration when wanted.

## Back from a thread skipped a level (2026-10-05, installed)

- `ThreadList` held the open section ("channels" / "direct") in its own `rememberSaveable`.
  Opening a thread swaps `ThreadList` out through the `when`, discarding that state, so
  Back rebuilt the list at `section == null`: the two-row sidebar, one level too far up.
- `section` and the conversation list's `LazyListState` are now hoisted into
  `MessagesScreen`, which stays composed while a thread is open. The scroll-to-top on a
  contact search moved up with them so it fires on a search change, not on every return.
- Third instance of the same trap (Nodes list, thread scroll, this): state remembered in a
  composable that an early return or a `when` takes out of composition is gone. Hoist it
  above the branch.

## Node detail from a message avatar + mesh notification icon (2026-10-05, installed)

- Tapping a sender avatar in a channel or DM thread pushes `NodeDetailScreen` inside the
  Messages tab, which is `ChannelMessageRow.swift`'s `NavigationLink(value: fromUser.num)`
  around the `CircleText`. Back returns to the thread, not to the Nodes tab.
- The thread keeps its scroll position across that excursion: `listState` and `detailNode`
  are hoisted above the early return (same trap as the Nodes list), and the
  scroll-to-bottom effect now fires only when the message count actually changed
  (`lastScrolled`), not on every re-entry.
- `MessagesViewModel` gained `toggleFavorite`, `toggleIgnored` and `openThread` so the
  detail screen's actions work from a thread, where there is no node list to read the
  current value from; both toggles read it from `nodeDao().get(num)`.
- The status bar / notification small icon was still the old triangle glyph. New
  `drawable/ic_notification.xml` (mesh constellation) is used by `MessageNotifier` and
  `RadioService`, and `ic_launcher_foreground.xml` was rewritten as the same glyph, which
  is now only the themed-icon monochrome layer.
- A photo cannot be a notification small icon: Android renders it as an alpha mask and
  tints it, so the launcher image collapses to a blob. The glyph is as close as it gets.
- Still default: the shade avatars are grey circles, since `MessageNotifier` builds its
  `Person` objects without icons.

## Direct Messages is now the Contacts list (2026-10-05, installed and verified)
User: the DM screen should be "Contacts (nnn)" with every known contact, history first,
a last-message timestamp, and a search field, like UserList.swift (reference screenshot
from the iPhone, 351 contacts).
- `UserDao.allContacts()` (new): every user LEFT JOINed to nodes, ignored nodes dropped,
  `ORDER BY lastMessage IS NULL, lastMessage DESC, LOWER(longName)` so threads with
  history sort above the rest, which is the iOS @Query sort. The old
  `dmContacts()` (history only) stays: Android Auto's `CarScreens` still uses it.
- `MessagesViewModel.contacts` combines that with `myNodeNum` and the new
  `contactSearch`, dropping our own radio the way iOS drops `activeDeviceNum`, and
  matching the search against long name, short name, user id, hardware model and node
  number (the fields `NodeFilterParameters.matches` uses).
- The screen's title is "Contacts (N)" over the count actually shown, so it tracks the
  search, and a "Find a contact" field sits under it. `ConversationRow` already drew the
  iOS row (unread dot, avatar, lock glyph, name, timestamp, preview, chevron) and needed
  no change; contacts with no history simply pass a null preview.
- `SearchField` moved into Chrome.kt and is now shared with the Nodes list.
- Not ported: the contact filter sheet (`NodeListFilter`) and the help sheet behind the
  two floating buttons at the bottom of the iOS list.
- Verified on the phone: 358 contacts, Spiney Norman and Spanky Ham on top with their
  times and previews, the rest alphabetical; searching "0day" narrowed it to 4 including
  a short-name match; opening a contact with no history gives an empty thread with the
  composer.

## Connect tab rebuilt to match Connect.swift (2026-10-05, installed and verified)
User: "the connect tab UI looks different than the iphone version" (reference: `1.jpg`).
The working tree already had the ViewModel half of this (startScan/stopScan,
connectManual, shutdownConnectedRadio, linkRssi polled every 5 s in BleConnection) but
`ConnectScreen.kt` still called the removed `toggleScan`, so it did not compile.
- One box at the top, three personalities: connected (90 dp avatar + battery; gray long
  name, "Connection Name:", transport glyph + BLE/TCP + `BleSignalBars` from the live link
  RSSI, "Firmware Version:", then green Subscribed / teal "Retrieving nodes N" / orange
  Communicating / orange "Retrying (attempt N)"), connecting (orange antenna, "Connecting
  . .", target name, "Connection Attempt a of b"), and idle (red error line if Failed, red
  broken link, "No device connected").
- Like iOS, the radio lists exist only while nothing is connected or connecting. Visible
  Disconnect/Connect/Forget/Scan buttons are gone: long-press the box for the iOS context
  menu (node number, Disconnect, Power Off with an "Are you sure?" confirm); tap a row to
  connect; long-press a saved row for Forget. BLE scanning runs continuously while the tab
  is up, idle, and Bluetooth is on (`LaunchedEffect(idle, bluetoothOff)`), stops on dispose.
- "Available Radios" (`.font(.title)` header, "+ Manual" menu on the right -> TCP ->
  "Manual connection string" dialog): saved BLE radios, scanned radios, and LAN-found
  radios merged, preferred (auto-connect target) first with a yellow star, then by name.
  Saved BLE radios stay listed when out of range (no bars); that is the Reconnect path
  CLAUDE.md requires. "Manual Connections": saved TCP radios, "Last seen device:" from the
  LAN scan name. `BluetoothPoweredOffRow` (tap opens Bluetooth settings) driven by
  `ConnectViewModel.bluetoothOff`, a receiver on `ACTION_STATE_CHANGED`.
- New shared pieces: `BleSignalBars` in Chrome.kt (BLESignalStrengthIndicator.swift
  thresholds -65/-85), `IosTeal`, `IosYellow`.
- Verified on the phone: box during Communicating / Retrieving nodes / Subscribed with
  signal bars; context menu; Disconnect -> list with four BLE radios (SOBE showing bars);
  tapping SOBE's row reconnected through the presence-probe path.
- Swipe-to-disconnect, matched to the iOS screenshots `c1.jpg` / `c2.jpg`:
  `SwipeToDisconnect` is hand-rolled (`draggable` + animated offset), not
  `SwipeToDismissBox`, because the user rule is that releasing the drag must NOT
  disconnect. Dragging left **narrows the card from its trailing edge** while the content
  slides and is clipped (the card keeps its 16 dp page margins); the uncovered page
  background carries the action: a 64 dp red rounded-square pill with the antenna-slash
  glyph and a "Disconnect" caption under it, and the card tints `surfaceVariant` while
  open. The action parks; only a tap on the pill disconnects; a tap on the card or a drag
  back closes it. Two layout traps, each a build/install cycle: `.fillMaxSize().width(w)`
  keeps the full width (incoming constraints are already fixed), and `Modifier.width(w)`
  is clamped by the narrowing card, which re-wrapped the text instead of sliding it. Use
  `.width().fillMaxHeight()` for the pill and `requiredWidth` for the sliding content.
- The connecting states use the **same device box**, not a separate orange panel: iOS keys
  that box on `activeConnection?.device`, so while connecting it shows a "?" avatar and
  only the rows whose data has arrived (`c1.jpg`). `ConnectedDeviceBox` now omits the long
  name and the firmware row when they are null, and `ConnectingBox` (orange antenna) is
  only the no-device-at-all branch. The old split rendered a too-narrow panel with the red
  action bleeding through beside it.
- Nodes list keeps its scroll position across node detail: `NodesScreen` returns early
  when `detailNode != null`, which takes the `LazyColumn` out of composition, so a state
  remembered inside it was discarded and the list came back at the top. The
  `rememberLazyListState()` is now hoisted above that branch. Verified on the phone:
  scrolled down, opened a node, pressed back, same position.
- Not ported: the Set LoRa Region banner, firmware update notice, nymea Wi-Fi Setup
  section, Mesh Live Activity.

## iOS visual parity round (2026-10-04 evening, installed and verified on the phone)
The user supplied five iOS screenshots (Connect, Settings, Map, Nodes, Messages) and asked
for the Android app to look like them, with the app's own icon as the title-bar logo
(the Meshtastic mark is trademarked and never appears in the app).
- `ui/Chrome.kt` (new): `AppLogo` (circular app icon, taps `Router.openAbout()`),
  `ConnectedDevicePill` (ConnectedDevice.swift: green link + own short name, red broken
  link when down; reads RadioManager state directly), `AppHeader` (large-title or inline),
  `NodeAvatar` + `nodeColor(num)` (CircleText.swift; color is the low 24 bits of the node
  number, text color by WCAG luminance, font scaled by glyph count because Compose 1.7 has
  no auto-size text), `BatteryCompact`, `IconAndText`, `SectionHeader`/`GroupCard`/
  `RowDivider`/`NavRow` (inset-grouped list pieces), `FloatingTabBar` (pill tab bar with
  the unread badge). `TabSpec` replaced MainActivity's private Tab.
- `ui/theme/Theme.kt`: iOS grouped palette (gray page, white cards, hairline dividers,
  iOS green/red/orange/gray constants). Material's tonal surfaces had tinted the page pink.
- Connect: Connect.swift device box (90 dp avatar + battery, long name, Connection Name,
  transport, firmware, green "Subscribed"); Saved / Available / Network as grouped cards.
  `ConnectViewModel` gained `myUser`, `myInfo`, `myNodeNum`, `myBattery`.
- Nodes: inline "Nodes (N)" title, gray rounded search bar, NodeListItem.swift rows
  (70 dp avatar, key glyph + name + star, Connected, last heard with online check /
  moon, Role with glyph, Unmonitored, MQTT, Hops Away box, SNR). Message / favorite /
  ignore moved to a long-press menu (iOS context menu); the row itself opens detail.
- Settings: Settings.swift grouping with accent glyphs (About, Help, location toggle;
  Configure chip "Connected Node X"; Radio Configuration: LoRa, Channels (import),
  Security, Share QR Code; Device Configuration: User (owner edit), Bluetooth, Device,
  Display, Network, Position, Power; Tools: Broadcast node info). The old Node number /
  Firmware / Known nodes rows are gone (Connect shows firmware, the Nodes title the count).
  `Router.pendingAbout` makes the logo open About.
- Messages: two-level like Messages.swift. Top: Channels / Direct Messages rows with
  per-section unread counts (`unreadChannelCount` / `unreadDirectCount` DAO queries);
  each opens its list (channel avatar = index on accent blue; DM avatar = node avatar).
- Map: logo + pill overlay at the top; layer and close-route buttons sit below it.
- System back (user rule): behaves like the top-left arrow. Sub-screens (node detail,
  thread, Messages section, config section, About, About documents) each register a
  BackHandler; with none open, MainActivity's handler jumps to the Nodes tab, and on the
  Nodes root it is disabled so the activity finishes. Verified: Messages -> back -> Nodes;
  detail -> back -> list; list -> back -> launcher.
- Node detail (NodeDetail.swift, from screenshots d1-d4): round back button + centered
  inline title; sections Hardware (chip glyph + model name; no hardware catalog images
  on this side), Node (75 dp avatar, signal bars when direct, BatteryGauge arc with
  voltage; icon rows Name / Node Number / User Id / Signed node / Public Key / Firmware /
  Role / Status / Messaging / Uptime / First heard / Last heard; tap a date row to
  toggle relative vs absolute), Environment (2-column weather tiles when env telemetry
  exists), Logs (Device Metrics, Node Map, Position, Environment, Trace Route live;
  Air Quality / Power / Detection / Local Stats rows present but disabled), Actions
  (accent rows: mute, share QR, favorite, message, exchanges, trace route, client
  history, S&F config, ignore, delete) and Administration. Charts moved into the log
  sub-pages (`DetailLog`); Position Log uses the new `PositionDao.history`; Node Map
  uses `Router.openMapNode` -> MapScreen flies to the node.
- Messages (m1-m3 screenshots): Messages tab is the large title with no status pill;
  Channels / Direct Messages lists have the round back button, large title, and
  ChannelList/UserList rows (unread dot, index or node avatar, lock glyph by PSK length or
  PKI state, bold name, time, last-message preview; `channelPreviews` / `dmPreviews` /
  `unreadChannelIndexes` / `unreadDmPeers` DAO queries). Thread: back + principal avatar
  + status pill, "Find in conversation" filter, 50 dp sender avatars, "Long (!id)"
  caption, 15 dp bubbles (accent/white for ours, gray for theirs), quoted reply above with
  the reply arrow, bordered tapback pill (emoji over short name), delivery status line,
  hour-gap timestamp headers, capsule composer with the up-arrow send button.
- Hardware card (user asked for the product images): `radio/HardwareCatalog.kt` fetches
  api.meshtastic.org/resource/deviceHardware (cached 48 h in cacheDir), resolves one
  entry per hwModel like HardwareCatalogResolver, and the card loads
  flasher.meshtastic.org/img/devices/<image> (SVG) through Coil + coil-svg with the
  green check / gray X support seal and the support-level section title. New hosts are
  in docs/PRIVACY.md; Coil is in NOTICES.txt and About.
- LAN discovery (user: "connect network has only direct entry... should scan"):
  `radio/LanScanner.kt`, started/stopped with the Connect screen. Two sources merged:
  NsdManager mDNS for `_meshtastic._tcp` (serial resolves, multicast lock held) and a
  /24 sweep every 30 s that connects to port 4403, sends want_config and reads framed
  FromRadio until my_info + its node_info (long name) or a 2.5 s budget. The sweep is
  needed: the Heltec V4 "Spanky Ham" (192.168.20.129, fw 2.7.17) does not answer mDNS,
  and neither did anything else on the LAN from the PC. The firmware takes ONE TCP
  client, so the sweep excludes the host of the live TCP link (ConnectViewModel passes
  it); a brief probe of any other radio is harmless. Network card lists every found
  radio (saved ones tagged "saved", live one "Connected"); saved TCP rows add "on this
  network". Manual entry stays as "Add by address". New permissions:
  ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE, ACCESS_NETWORK_STATE; the sweep is
  described in docs/PRIVACY.md.
- Phantom node off Africa (user: unknown.jpg, node 2621672129 / !9c438ac1 skews the map).
  Root cause, from the pulled DB: a positions row (262144, 262144) with NO node or user
  row. 262144 = 2^18 is what the firmware's precision reduction makes of (0, 0) at 13
  bits (keep top bits, add half a cell), so it passed the `== 0 && == 0` guard; and the
  node row was gone because switching radios wipes `nodes` but nothing ever wiped
  `positions` / `telemetry` (2465 orphan position rows for 173 nodes, 3464 telemetry
  rows). iOS never shows it because positions hang off the node there. Fixes: ingest
  uses `Position.hasValidCoordinates` (either axis zero, Apple Park, and the
  reduced-null-island (2^k, 2^k) pair are refused); `mapNodes` / `latestByNums` JOIN
  nodes; `pruneOrphans()` on both DAOs runs at app start (AppContainer init) and at
  every connect; the radio-switch wipe clears positions and telemetry; removeNode drops
  the node's rows. Verified: DB re-pulled after relaunch shows 0 orphans; map fits
  Florida only.
- DB pull recipe (debug build): `adb exec-out run-as com.suteny0r.mangledbabyducks cat
  databases/mesh.db` (plus -wal and -shm) then sqlite3 on the PC.
- RX/TX activity lights (user: omitted from the iOS header). Port of
  RXTXIndicatorWidget.swift + LEDIndicator: inside ConnectedDevicePill, left of the link
  icon, an up arrow with a green LED (packets to the radio) and a down arrow with a red
  LED (packets from it); each flashes on and eases out over 300 ms whenever
  `RadioManager.packetsSent` / `packetsReceived` ticks (counted in `send()` and on every
  ConnectionEvent.Data). Tap: sends a heartbeat when connected (so the lights blink on
  demand, as iOS does) and toggles the "Packet Count" popup with both totals.
- Known rough edges: the Nodes row has no distance/bearing line (needs my position),
  and the tap helper `tap.py "Connect"` matches "Connected" first; tap the tab by
  coordinates (930,2060 on the Note 20) instead.

## Message ordering + per-bubble times (2026-10-05, BUILT, NOT INSTALLED: phone dropped off USB)
- User: new public-channel messages sometimes land between older ones; not every
  message shows a time. Cause (from the pulled DB): incoming messages were stamped with
  the radio's rx_time (its clock, 1 s resolution, every stored value ends in 000) while
  our sends use the phone clock, so any skew between the two interleaves them.
  `PacketIngest.arrivalTime` now stamps a live packet with the phone clock and keeps
  rx_time only when it is more than 10 min old (a store-and-forward replay keeps its
  place). Per-bubble times were added and then removed at the user's request: the iOS
  hour-gap headers are the only timestamps, and that is the wanted behavior.
- Verified on the phone with DMs from Spanky Ham over TCP (COM3 was absent): Spanky,
  phone reply, phone reply, Spanky, in that order, the last stamped by the phone clock.
- Also fixed on the way: the thread view had `imePadding()` on top of the window's own
  resize, so the keyboard pushed the header and list off the top; removed.
- Stuck "Sending..." (user report, weak/absent coverage): ported
  `MessageEntity.deliveryStatus` + `sendAckTimeout` (5 min) and `RetryButton.swift`.
  No ack and no nak past 5 min renders "Not delivered"; a nak renders
  "Not delivered: <routing error>"; a DM with only an implicit ack renders "Relayed,
  not confirmed by recipient". Tapping the status opens the detail dialog with
  "Try Again", which deletes the row (`MessageDao.delete`) and re-sends the text as a
  new packet (`MessagesViewModel.retry`). The thread re-evaluates every 30 s so the
  flip happens without leaving. Verified on the stuck 8:10 AM channel message.
  The dialog's open state is held by ThreadView (message id), NOT the row: LazyColumn
  discards a row's remember state when it scrolls off, and a new channel message
  auto-scrolls the thread, which closed the dialog under the user. The dialog looks the
  message up fresh each composition (status updates live; closes if retry deleted it).
  "Try Again" is also in the bubble's long-press menu when the badge is retryable.
- Delete messages (user asked whether iOS has it; it does, in ChannelList / UserList
  contextMenu + MessageContextMenuItems): long-press a Channels or Direct Messages row
  -> "Delete Messages" (only when the thread has any) -> "This conversation will be
  deleted." confirm; long-press a bubble -> "Delete" -> confirm. Local only, like iOS:
  `MessageDao.deleteChannelMessages` / `deleteDirectMessages` / `delete`; nothing goes
  to the radio. Tapbacks on the deleted messages go with them.

## Release 0.2.0 (2026-10-04, tag v0.2.0, GitHub release with both APKs)
- Version lives in `app/build.gradle.kts` (versionCode 2, versionName 0.2.0). Tag is
  `v<versionName>`; the About screen's source link plus the tag is the GPL source offer.
- Release signing: keystore `C:/Users/User/.android/mangled-baby-ducks-release.jks`
  (PKCS12, alias `mangledbabyducks`, RSA 4096, valid 30 years, CN=Mangled Baby Ducks).
  Credentials are in the untracked `local.properties` (`release.store.file`,
  `release.store.password`, `release.key.alias`, `release.key.password`); the build
  script signs release when those exist and leaves it unsigned otherwise. BACK UP the
  keystore and local.properties: a lost key means a new Play listing (or Play App
  Signing key reset).
- Recipe: bump version, commit, `git tag -a vX.Y.Z`, push both, `gradlew
  :app:assembleDebug :app:assembleRelease`, verify with
  `build-tools/35.0.0/apksigner verify --print-certs` and `aapt dump badging`, then
  `gh release create vX.Y.Z <apks> --title X.Y.Z --notes-file notes.md`.
- Play upload is still manual (needs an AAB: `:app:bundleRelease`, same signing).

## Licensing, trademark and attribution for a Play release (2026-10-04, installed and clicked through)
Prompted by a release-risk review. The app is a derivative of GPL-3.0 Meshtastic-Apple and
bundles GPL-3.0 protobufs, and until this round the repo had no license at all.
- Root `LICENSE` (verbatim GPLv3, byte-identical to gnu.org), root `NOTICE` (derivation
  credits, trademark disclaimer), `README.md` (first one; license section is the source
  offer), `docs/play-listing.md` (listing text that keeps "Meshtastic" out of the title,
  short description and assets, plus the release checklist: tag `v<versionName>` per
  Play build so the in-app source URL matches the shipped binary).
- `ui/AboutScreen.kt` (new, no Swift original): Settings > "About and licenses". Version,
  disclaimer with ® on first mention, GPL text and third-party notices read from
  `app/src/main/assets/LICENSE.txt` and `NOTICES.txt`, source and issues links, credits
  for Meshtastic-Apple, protobufs, MapLibre, protobuf, AndroidX, Kotlin, ZXing, map data.
  `NOTICES.txt` carries the full BSD-2, BSD-3 and Apache-2.0 texts (Apache §4 and BSD
  both require the text to ship with the binary).
- Settings footer is one labelSmall line, "Not affiliated with or endorsed by Meshtastic
  LLC."; the full ® notice and compatibility statement are in About (user wants every
  attribution as unobtrusive as the licenses allow).
- Map: compact attribution chip at bottom-end, collapsed to an info icon by default
  (OSM attribution guidelines and Esri's SDKs accept this on small screens). Tap the
  icon to show the credit for 10 s (OpenFreeMap / OpenMapTiles / OpenStreetMap for
  streets, full Esri World Imagery string for satellite), tap the text to open the
  provider's copyright page. MapLibre's own logo and "i"
  button are disabled (the chip overlapped them, and the liberty style declares no
  source attribution so the "i" showed nothing for streets). The icon itself must stay:
  OSM and Esri require credit reachable on the map.
- Still open: Esri World Imagery is attributed but the user has to decide whether use
  outside ArcGIS is acceptable under Esri's terms or drop the layer.
- `docs/PRIVACY.md` is the Play privacy policy (URL is the GitHub blob link; About has a
  row for it; `docs/play-listing.md` has the Data safety answers). It states facts
  checked in code: location sharing off by default and sent only to the radio, the only
  network hosts are the two tile servers, no analytics. It also discloses that the
  manifest does not set `allowBackup`, so Android's default may back up the message DB
  and channel keys to the user's Google account. Setting `allowBackup=false` is a
  one-line change if the user prefers; the policy would then need that paragraph removed.
- Verified on the phone: Settings footer, About screen, GPL text view, third-party
  notices view, map chip expanded and collapsed on both layers. No crashes in logcat.

## Public-key self-healing, round 2 (2026-09-19 evening, INSTALLED; Accept test interrupted)
Refined after reading meshtastic/firmware master (Router.cpp, ReliableRouter.cpp, NodeDB.cpp,
NodeInfoModule.cpp, AdminModule.cpp). Facts that drove the design, worth keeping:
- Every text DM is PKI-encrypted when the radio holds a key for the destination; with no
  key it **refuses to send** ("refusing to send legacy DM") and naks the phone with 39.
  There is no channel-PSK fallback for DMs.
- NODEINFO_APP, POSITION_APP, ROUTING_APP, TRACEROUTE_APP unicasts are **never** PKI
  encrypted, so a user-info exchange always gets through regardless of key state.
- Receiver side (ReliableRouter): an undecryptable PKI packet naks **35** only when the
  receiver has *no* key for the sender (and it then sends its NodeInfo to the sender by
  itself); with a *stale* key it naks **6 NO_CHANNEL**. So stale-key DMs show up as
  error 6, not a PKI code; `pkiKeyErrors` includes 6 for that reason.
- `NodeDB.updateUser` drops any NodeInfo whose key differs from the stored one. Over the
  air a re-keyed node can never fix a peer's stale copy. Only a client can, via admin
  `add_contact` (NodeDB.addFromContact overwrites the user; stock clients send it before
  every DM) or `remove_by_nodenum`. addFromContact marks the node favorite as a side
  effect; `pushContactToRadio` undoes that unless the app row says favorite.
- NodeInfoModule suppresses a want_response reply to the same sender for 12 h
  (USERPREFS_NODEINFO_REPLY_SUPPRESS_SECS 43200), so an exchange may not be answered.
Implementation now: `healPkiFailure` on DM nak 6/34/35/39, per-peer 5 min cooldown:
on 39 push this app's stored key with add_contact, then `exchangeUserInfo(peer)`, plus a
NodeInfo broadcast at most every 15 min. `acceptNewKey` = promote locally,
remove_by_nodenum, add_contact(new key), exchangeUserInfo. SOBE runs firmware 2.7.23, so
add_contact is available.
Test state: three nodes were flagged mismatched (`!16cd737c` W1HQL-Shack, `!62f8eeac`
FAU ind, `!a6961cf4` Thing 2). Drove the phone UI via uiautomator to W1HQL-Shack's detail;
the Key mismatch row showed fingerprints `Hu2xvEMu…` -> `v+e2lgPx…` and the dialog opened;
the confirm tap was sent and the phone dropped off adb the same second, so whether it
landed is **unverified**. Check with the pulled DB (`run-as ... cat databases/mesh.db`):
`select keyMatch from users where num=0x16cd737c`, and `logcat -s RadioManager` for
"Accepted new key for !16cd737c". Note the auto-connect did not fire on this launch (BLE
state read as off at that moment?); connecting from the Connect tab worked.

## Public-key self-healing (2026-09-19 evening, built, NOT installed — phone unplugged again)
Until now a re-flashed node was detected (first-wins key policy, red lock, "Key mismatch"
row) but never repaired automatically; the manual fix was Settings > Broadcast node info.
Two pieces added, neither yet exercised on hardware:
- **Automatic key exchange on PKI naks** (`RadioManager.healPkiFailure`). `PacketIngest.routing`
  now returns `AckResult(messageId, errorReason)`; RadioManager reacts to Routing.Error
  34 PKI_FAILED, 35 PKI_UNKNOWN_PUBKEY (peer cannot decode us), 39 PKI_SEND_FAIL_PUBLIC_KEY
  (our radio has no key for the peer) on a **DM we stored** by sending `exchangeUserInfo(peer)`
  (per-peer cooldown 5 min) and `broadcastNodeInfo()` (global cooldown 15 min). Only text
  message ids trigger it, so the exchange packets cannot recurse. The failed message is not
  resent. New `MessageDao.get(messageId)`.
- **Accept new key** on the node detail "Key mismatch" row (only when `newPublicKey` is
  stored): confirm dialog shows old/new key fingerprints (first 8 base64 chars) and an
  impostor warning, then `RadioManager.acceptNewKey(num)`: `UserDao.acceptNewKey` promotes
  `newPublicKey` -> `publicKey`, `keyMatch = 1`; `remove_by_nodenum` admin **to our own
  radio** so its NodeDB forgets the node (firmware is also first-wins: NodeDB.cpp logs
  "Public Key mismatch, dropping NodeInfo" and keeps the old key, verified in the
  meshtastic/firmware master source); then `exchangeUserInfo(num)` so the node re-announces
  and both stores learn the new key. Result surfaces as a Toast via
  `NodeDetailViewModel.keyAcceptResult`.
- Test plan when a re-flashed node is available: DM it from the app, expect the nak, then
  `logcat -s RadioManager` shows "exchanging user info" / "Broadcasting node info"; a
  second DM after the exchange should ack. For Accept: node detail > Accept > confirm,
  then the red lock should turn green after the node's next NodeInfo.
- Pre-existing warning at RadioManager.kt:938 ("Condition is always true", localStats
  builder) is not from this change.

## Android Auto: templated app cannot run in a real car when sideloaded (2026-09-20, platform rule)
Google's testing page: Unknown sources "applies to media, messaging notifications, and parked
apps but doesn't apply to apps built using the Android for Cars App Library." So the Cadillac
shows only the notification/messaging personality (listed in Customize launcher with the app
icon, no grid entry); the templated app needs a Google Play install (internal testing track is
enough). The DHU runs the sideloaded build fine. `tools/dhu.ps1` launches it in its own window
(phone: Android Auto > overflow > Start head unit server first). Decision pending on Play.

## Android Auto: why the car only showed the message view (2026-09-20 evening, RESOLVED)
The app has **two personalities** in the Android Auto launcher because `automotive_app_desc.xml`
declares both `template` and `notification`: (1) the templated `MeshCarAppService` (POI), and
(2) a notification-messaging app keyed to `MainActivity`. The host de-duplicates the app grid
to one "Mangled…" entry, which opens the templated app, but the dock's "recent app" slot is
filled from whichever personality was last active; a fresh message notification makes that the
messaging one, and tapping it opens Android Auto's own message view (last message, Play aloud,
Reply). That is exactly what the user saw in the car twice. On the DHU, launching that
messaging entry crashed the host (`startCarActivity(MainActivity)` -> "No matching component").
Fix/mitigation: `MeshCarAppService` now has its own `android:label` ("Ducks Mesh") and
`android:icon` (`ic_car_launcher`, blue map glyph). The grid label is still de-duplicated to the
app name, but the dock icon now tells the two apart: **blue map = templated app, green triangle
= messaging view**. Open the app from the grid once and the dock slot switches to the blue one.
Read-aloud/voice reply are unaffected. Verified on the DHU 2026-09-20 19:50: grid entry opens the
home menu (Map 234 nodes, Messages, Nodes 96, Radio). Installed on the phone 19:48, not yet
seen in the car. Also: "Customize launcher" is not in this Android Auto build's settings at all
(checked with and without a head unit connected; per-vehicle settings have only Forget/Rename).

## Android Auto round 2 (2026-09-19 afternoon, built, NOT installed — phone unplugged)
The user drove with the first build: it worked but "very limited": no visible way to pick a
channel, open the node list, or connect/disconnect. The action-strip icons and the Radio
row inside the map list were not discoverable on the real head unit (they were on the DHU).
Changes, all in `auto/`:
- **Root is now `CarHomeScreen`**, a ListTemplate menu with four titled, chevroned rows:
  Map (count of positioned nodes), Messages (unread count), Nodes (count), Radio (link
  state). `CarMapScreen` is a child (BACK header, title "Map"); it keeps its strip and
  Radio row.
- **`CarRadioScreen` rewritten** from a MessageTemplate to a sectioned ListTemplate:
  "Status" row, then every saved radio (`knownRadios()`) as a row; tapping a saved radio
  connects (`AppContainer.connectKnown`, supersedes the live session), tapping the live
  one disconnects. Action strip "Disconnect" while connected or attempting.
- New `AppContainer.disconnectRadio(context)` = disconnect + stop RadioService + clear
  the auto-connect target; `ConnectViewModel.disconnect()` now delegates to it. Same
  semantics from the car as from the phone: after a car-side Disconnect the app will
  not auto-connect on the next launch until a radio is picked again.
- `ic_car_map.xml` icon added.
- Built clean; **not installed** (adb showed no device) and **not seen on the DHU** (the
  DHU process was killed by Claude Code for low system memory; this PC had ~1.1 GB free).
  Next: `adb install -r`, then either the car or
  `adb forward tcp:5277 tcp:5277` + `desktop-head-unit.exe --adb=5277` and walk
  Home > Radio (Disconnect / tap-to-connect), Home > Messages > channel > reply icon.

## Android Auto (2026-09-19, installed, verified on the Desktop Head Unit; no car yet)
The app now has a head-unit UI via the Car App Library (`androidx.car.app:app:1.7.0`;
1.8.0 is not released, only 1.8.0-rc01, so do not bump blindly) plus car-readable
message notifications. Verified on the DHU against the phone's Android Auto 17.7 host:
map with node markers + distances, Radio screen, Nodes list, node detail pane, Messages
(sectioned), thread view, quick-reply list all render with no host errors. Not sent from
the car (would broadcast on the public Primary channel). Later the same day the user ran
it in the car: it worked (see round 2 above for what was missing).

Two host-validation bugs found and fixed on the DHU, both invisible at compile time:
- `PlaceListMapTemplate` rejects any non-browsable row without a DistanceSpan
  ("All non-browsable rows must have a distance span"). All map rows are `setBrowsable(true)`.
- The host's `ConstraintManager` reports a content limit of **1000** for lists on Android
  Auto 17.7, and this mesh has ~197 positioned nodes. A template travels in one binder
  transaction (~1 MB ceiling); 197 place rows at ~9 KB each made a 1.8 MB parcel,
  `TransactionTooLargeException`, then "isn't responding" on the head unit and the host
  killing the process (which also drops the BLE session). `contentLimit(type, cap)` now
  clamps to `MAP_ROW_CAP = 24`, `LIST_ROW_CAP = 50`, `PANE_ROW_CAP = 6`.

Phone-side setup already done on R5CN70YWT5Z (2026-09-19): Android Auto developer mode
enabled, **Unknown sources ON**, head unit server started. The DHU (r2.1, Windows) was
downloaded from Google's repository index (no sdkmanager on this PC) into
`C:/Users/User/AppData/Local/Android/Sdk/extras/google/auto/`. To drive it from a script:
`tail -f cmds.txt | desktop-head-unit.exe --adb=5277` after `adb forward tcp:5277 tcp:5277`,
then append `tap x y` / `screenshot path.png` lines to cmds.txt (800x480 window; app
launcher is the grid at 42,440; our icon sits in the dock).
- **`auto/MeshCarAppService.kt`**: `CarAppService` + `Session`. Manifest category is
  `androidx.car.app.category.POI` because that is the only category allowed to draw
  places on the host's map (`PlaceListMapTemplate`, permission
  `androidx.car.app.MAP_TEMPLATES`). Debug builds accept any host
  (`ALLOW_ALL_HOSTS_VALIDATOR`, needed for the Desktop Head Unit); release uses the
  library's `hosts_allowlist_sample`. The session runs the same one-shot
  `autoConnectIfRemembered` the phone does, so plugging into the car reconnects the radio.
- **`auto/CarScreens.kt`**: root `CarMapScreen` (nodes with positions as markers on the
  host map, a "Radio" row with the link state, action strip -> Messages / Nodes);
  `CarRadioScreen` (MessageTemplate with Reconnect); `CarNodesScreen` -> `CarNodeDetailScreen`
  (PaneTemplate: last heard, signal, battery, position + distance from the car; actions
  Message / Traceroute); `CarMessagesScreen` (sectioned Channels / Direct Messages) ->
  `CarThreadScreen` (newest first, opening marks read) -> `CarQuickReplyScreen` (canned
  texts; the head unit has no keyboard while driving). Row counts come from
  `ConstraintManager`; all times in Room are already ms (no `* 1000`).
- **`MessageNotifier`** rewritten as `NotificationCompat.MessagingStyle`, one notification
  **per conversation** (id = hash of `channel:<n>` / `dm:<num>`, last 8 messages kept
  in memory) with Reply (RemoteInput, `SEMANTIC_ACTION_REPLY`, mutable PendingIntent) and
  Mark-as-read actions, both `setShowsUserInterface(false)`. That is what makes Android
  Auto read the message aloud and take a voice reply. `MessageActionReceiver` sends the
  reply over the mesh / marks read, then `dismiss(target)`; opening the thread on the
  phone or car also dismisses. Behaviour change on the phone: a burst in one thread now
  stacks into one notification instead of one per message.
- **`AppContainer.autoConnectIfRemembered(context)` / `connectKnown(context, target)` /
  `hasBlePermission(context)`** were lifted out of MainActivity so the car session can
  share them. MainActivity just delegates.
- `res/xml/automotive_app_desc.xml` declares `template` + `notification`.
- Lint: the 2 errors are pre-existing `ProduceStateDoesNotAssignValue` in
  MessagesScreen.kt:309 and NodeDetailScreen.kt:449; `ExportedService` on the car
  service is expected (the host binds it).

**In the car**: plug in; "Mangled Baby Ducks" is in the Android Auto launcher (Unknown
sources is already on). **Do not "Quit developer mode"**: it drops Unknown sources, the host
then hides the templated app, and the same icon opens Android Auto's built-in notification
messaging view instead (last message, read-aloud, nothing tappable). That is what the user saw
on 2026-09-20; re-enabled via adb the same day. Also seen that day: Android Auto's own
projection process crashed at connect ("Unable to start Preflight UI"), not our app. If a fresh phone ever needs it again: Settings > Apps > Android
Auto > Additional settings in the app > tap Version 10x > overflow > Developer settings >
Unknown sources. Test traffic from Spiney Norman on COM3; a DM to `!0f352b79` currently
NAKs with error 39 (key mismatch, see CLAUDE.md), so use a channel message or fix the
key first. A real channel message did post as a MessagingStyle notification
(category=msg, 2 actions) during this session.

Still unverified: the template step quota under churn (row titles kept stable), voice
reply end-to-end on a real head unit, and whether the foreground `RadioService` may be
started from the host-bound session on a cold start (wrapped in `runCatching`).

## Cosmetic parity round (2026-08-25, built, NOT installed — phone was not attached)
Display-only pass to match the iOS app's visible UI. No behavior or schema changes:
- **Tab bar reordered to iOS ContentView order**: Messages, Nodes, Map, Settings,
  Connect (was Connect-first). `Router.TAB_*` constants renumbered; Connect icon is now
  `Link` (iOS `link` symbol). Unread badge still on Messages.
- **Theme**: dropped Material You dynamic color; both modes now use the iOS AccentColor
  navy `#2855A8` (AccentColor.colorset sRGB 0.157/0.333/0.659).
- **Messages**: channel fallback name "Primary" → "Primary Channel"; header casing
  "Direct Messages"; DM rows use the iOS list timestamp convention (HH:mm today,
  literal "Yesterday", else MM/dd/yy) instead of relative time; muted channels/contacts
  show the bell-slash trailing icon; thread title lost its "#" prefix; screen title
  "Messages" added.
- **Nodes**: TopAppBar title "Nodes (<live count>)" (iOS sidebar title); sort is now
  connected-node first, then favorites, then lastHeard (was pure lastHeard); the
  "(this radio)" headline suffix is gone (iOS marks self with the Connected line only).
- **Node detail**: identity card got the iOS Section("Node") header plus a Name row;
  labels matched to iOS ("Node Number", "User Id", "Public Key"). Actions section now
  ordered like iOS actionsSection: Mute notifications, Share Contact QR, Exchange
  Positions, Request Local Stats, Exchange User Info, Client History, S&F config,
  Delete Node. New Administration section (iOS administrationSection): Refresh device
  metadata, Power Off, Reboot. All confirms are the canonical iOS shape: title
  "Are you sure?", destructive-role buttons labeled "Shutdown Node?" / "Reboot node?" /
  "Delete Node", destructive tint on button + label. ActionButton grew a `destructive`
  param; ConfirmableAction carries confirmLabel+destructive.
- **Settings**: config rows split into iOS groups — "Radio Configuration" (LoRa,
  Security) and "Device Configuration" (Bluetooth, Device, Display, Network, Position,
  Power).
- **Connect**: "Connect" page title added (iOS navigationTitle).
- Build clean (one pre-existing-style ExperimentalCoroutinesApi warning fixed by
  annotating NodesViewModel). **Install pending: adb devices showed nothing on
  2026-08-25** — install when the phone is attached:
  `adb -s R5CN70YWT5Z install -r app\build\outputs\apk\debug\app-debug.apk`.
- Not done by design (would need schema/data work beyond cosmetics): per-row unread
  dots and message previews in the thread list (ChannelEntity has no lastMessage column),
  iOS multi-line icon metadata rows, hardware hero card, Logs section, filter sheets.

## Feature parity round 2 (2026-08-24, built and installed, pending click-through)
Closed the remaining iOS node-action / parity gaps the user approved (NO skips):
- **Schema v3→v4** (destructive wipe, per convention): new columns `users.unmessagable`,
  `nodes.node_status`, `nodes.has_xeddsa_signed`; `fallbackToDestructiveMigration` as always.
- **PacketIngest NODE_STATUS_APP** dispatch (iOS `AccessoryManager+Nodes`); `NodeDao.setMute`,
  `UserDao` mute write.
- **RadioManager: `sendPayload` + 9 cross-node admin methods** — shutdown, reboot,
  remove-node, exchange-user-info, send-to-position, local-stats request, device-metadata
  request, store-and-forward config + client-history request. All `sessionPasskey` is
  **intentionally NOT set** (iOS sets it; deliberate divergence, documented in KDoc).
  `PacketIngest.adminResponse()` dispatches the responses.
- **NodeDetailScreen**: full Actions section per iOS NodeDetail — Alerts (mute bell toggle,
  now a local `users.mute` flag), Share Contact QR (gated on `unmessagable == false`),
  Favorite, Message, Exchange position/user, Local stats, Refresh metadata, Send to
  position, Client history, plus destructive Shutdown / Reboot / Remove with an
  AlertDialog confirm (ConfirmableAction is a `data class`, not sealed). Mute/Remove
  hidden on the self row. "Signed node — Verified automatically" positive row (iOS
  `hasXeddsaSigned`) above the key-mismatch warning.
- **NodesScreen rows**: PKI glyph from iOS NodeListItem keyStatus — solid green lock when
  `pkiEncrypted && keyMatch`, outlined red lock on mismatch, bell-slash when muted;
  self row and rows without a glyph render the avatar as before.
- **ShareContactURL**: `shareContactUrl()` builds `https://meshtastic.org/v/#` + base64url
  of a `SharedContact` proto (node_num, user proto, manually_verified=false). Prefix with
  the `#` is canonical: iOS `ContactURLHandler.canonicalPrefix` and the reference
  meshtastic-android-app manifest intent-filter both include it. Dialog shows the QR
  (shared `qrBitmap` helper, now internal), a Share intent (text/plain with the URL), and
  Copy link. NFC tag write is iOS-only, not ported.
- Build `:app:assembleDebug` clean; installed on R5CN70YWT5Z 2026-08-24.
  **Not yet click-verified.** Destructive admin calls (shutdown/reboot/remove) the user
  will fire manually.

## Node metadata alignment (2026-08-23, built and installed, on phone awaiting click-through)
Closed the per-node field gaps vs the iOS app using only data already in the local DB
(scope was explicitly limited to this; admin actions like delete/reboot/shutdown, the
XEdDSA "signed node" badge, and full telemetry parity were all deliberately NOT done):
- `NodeDetailScreen.kt`: new identity card rows — Node number (decimal + Copy),
  User ID (`!hex`), Role (via new `roleLabel(Int)` mapping the config.proto Role enum,
  shown in the Identity row), first-heard timestamp in the Link row, Battery % and
  Uptime rows (new `latestDevice()` VM helper over `TelemetryDao.latestDeviceMetrics`,
  "1d 4h 12m" style via new `uptimeLabel(Int)`), Position row now includes altitude /
  sats / speed / heading when present, Public key row (Base64 + new `CopyButton`
  composable using `ClipboardManager` + Toast) right above the existing key-mismatch
  warning.
- `NodesScreen.kt` rows: supporting line now leads with "connected" (self row), the
  role (omitted when 0 == CLIENT, matching iOS' affirmative-only display), and
  "battery NN%" when telemetry exists.
- `TelemetryDao.batteryByNums(nums)` (first match per node in time-desc order feeds
  `NodesViewModel.batteryByNode`, a `flatMapLatest` derive over the visible node list).
- Build: `:app:assembleDebug` clean; `adb devices` confirmed R5CN70YWT5Z; installed.
  Not yet click-verified on the phone.

## Favorite / ignore (2026-08-20, implemented, build passing, awaiting UI verification)
Port of iOS `FavoriteNodeButton`/`IgnoreNodeButton` (client-mode favorite/ignore via admin):
- `RadioManager`: `setFavorite(num, fav)` (uses `set_favorite_node` 39 / `remove_favorite_node`
  40) and `setIgnored(num, ignore)` (47/48), both on the admin path next to `sendAdmin`.
- `NodeDao`: `setFavorite(num, value)` / `setIgnored(num, value)` update queries.
- `NodesViewModel`: `toggleFavorite(num, on)` + `toggleIgnored(num, on)`;
  `myNodeNum` StateFlow (from `RadioManager.myNodeNum`) so the UI knows which row is self.
- `NodesScreen.kt`: list rows show star (favorite, filled/outline) + RemoveCircle (ignore,
  filled error-tinted / outlined) in trailingContent; ignore and message hidden for self.
  Detail-screen call site passes `isSelf = num == myNum` plus both callbacks (lines 44-63).
- `NodeDetailScreen.kt`: `TopAppBar` actions for favorite (star) and ignore (RemoveCircle,
  `isSelf` hides the ignore button), same toggle semantics.
- Build: `:app:assembleDebug` clean; APK installed on R5CN70YWT5Z and launched without crash.
  **Not yet verified by clicking on the phone**; that is the immediate next step.
- Note: favorite state here is the CLIENT-side view (DB flag + admin write), matching iOS
  behavior of preferring the `favorite` field in the node DB when the mesh is not in full
  mode.

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
- SOBE's node num depends on firmware major: 2.8.x derives it from CRC32 of the public key
  (**`!0f352b79`**), 2.7.x used the MAC-derived/legacy number (`!6bed6674`, earlier `!1eff739f`).
  Since 2026-09-20 the radio runs 2.8.1 again, so it is **`!0f352b79`**; the other numbers are
  stale entries in peers' node DBs carrying the same public key.
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
