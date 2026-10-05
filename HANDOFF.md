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
- Known rough edges: the Nodes row has no distance/bearing line (needs my position),
  and the tap helper `tap.py "Connect"` matches "Connected" first; tap the tab by
  coordinates (930,2060 on the Note 20) instead.

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
