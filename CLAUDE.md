# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Mangled Baby Ducks: an Android (Kotlin + Compose) Meshtastic client, hand-ported from the
Swift app **Meshtastic-Apple**, cloned locally at `F:\Meshtastic-Apple`. The Meshtastic
protobufs are vendored (not a submodule) into `app/src/main/proto`.

Port provenance is part of the code style: classes carry a KDoc line naming the Swift file
they came from (`BleConnection.kt` = `BLEConnection.swift`, `PacketIngest.kt` =
`MeshPackets.swift` + `UpdateSwiftData.swift`, `RadioManager.kt` = `AccessoryManager`,
`Router.kt` = the iOS `Router`). When porting more behavior, read the Swift original first
and keep that citation habit.

`HANDOFF.md` is the running session log: current test rig, what has been verified on real
hardware, what is deliberately unfinished, and hard-won gotchas. Read it before starting
work and update it when finishing. Its gap list can lag the working tree, so check
`git diff` too.

## Build, install, run

Gradle must be invoked from PowerShell with the call operator; `cmd /c` chaining fails in
this harness:

```powershell
& .\gradlew.bat :app:assembleDebug
& .\gradlew.bat :app:lintDebug        # AGP default lint, no custom config
adb -s R5CN70YWT5Z install -r app\build\outputs\apk\debug\app-debug.apk
adb -s R5CN70YWT5Z logcat -s RadioManager:V BleConnection:V PacketIngest:V MapScreen:V
```

- JDK is pinned to the Android Studio JBR via `org.gradle.java.home` in `gradle.properties`;
  SDK path is in `local.properties` (untracked).
- There are **no test sources and no test infrastructure** in this repo (no `src/test`, no
  `src/androidTest`, no test dependencies). Verification is done by installing on the physical
  Galaxy Note 20 Ultra (adb serial `R5CN70YWT5Z`) and driving real radios. Adding a first
  test means adding the dependencies too.
- Multiline `python -c` also fails in Git Bash here; write a script to the scratchpad instead.

## Hardware test constraints (user instructions, not preferences)

- The phone's own radio is "SOBE GAT562 30s" on PC serial **COM14. Never open COM14**: it kills
  the phone's BLE session. Reach that radio only through the app.
- Send test traffic from **Spiney Norman on COM3**:
  `meshtastic --port COM3 --sendtext ... --dest '!0f352b79' --ack` (set `PYTHONIOENCODING=utf-8`).
- Never test with **swaffelen** or **6abc**.
- Config writes (`setConfig`, `applyChannelSet`, owner rename) make the radio save and reboot.
  Prove a write path with a no-op first (re-set the current value).

## Architecture

Data flows one way: transport -> `RadioManager` -> `PacketIngest` -> Room -> Flow ->
`AndroidViewModel` -> Compose. There is no repository layer and no DI framework; ViewModels
read `app.container` directly.

**`AppContainer`** (`AppContainer.kt`) is a hand-rolled singleton graph built in
`MeshtasticApplication.onCreate`, reached through the `Context.container` extension. It owns
the database, ingest, `RadioManager`, BLE scanner, notifier, `Router`, DataStore prefs and
`LocationSharer`. Add new long-lived collaborators here.

**Transport** (`radio/`): `RadioConnection` is the interface both links implement.

- `BleConnection` (GATT). One GATT operation per protobuf message, serialized behind
  `opMutex`; each callback completes a `CompletableDeferred`. `FROMNUM` notifications are a
  doorbell, not data: they trigger a coalesced drain of `FROMRADIO` reads until a zero-length
  read. MTU 512 must be requested explicitly. Subscribing to `FROMNUM` is the bonding gate
  (GATT status 5/15/137 means "create the bond, then retry").
- `TcpConnection`. Framing is `[0x94][0xC3][len_hi][len_lo][protobuf]`, and it is the only
  transport needing the app-driven heartbeat (`requiresPeriodicHeartbeat`).

Android quirks the transports absorb: turning Bluetooth off invalidates GATT handles without
firing `onConnectionStateChange`, so `BleConnection` watches `ACTION_STATE_CHANGED` directly;
any unrequested disconnect is treated as reconnect-worthy, because radio reboots arrive with
arbitrary GATT statuses, including 0.

**`RadioManager`** is the only gateway to the radio: connection lifecycle (`RadioState`), the
two-nonce handshake (`wantConfig` = 69420, then the node DB dump = 69421, then `setTimeOnly`),
retry (3 initial attempts, up to 10 reconnects with backoff), a heartbeat watchdog for TCP,
`FromRadio` dispatch, and every outbound packet builder (text, admin, traceroute, waypoint,
position, node info broadcast). Config writes are wrapped in `beginEditSettings` / `setConfig`
/ `commitEditSettings`.

Its connect path has one invariant that must not be relaxed: **exactly one attempt loop at a
time**. `runAttempts` (guarded by `attemptLock` plus a `requestGeneration` counter) is the only
caller of `establish`; event collectors are tagged with their connection so a superseded link
cannot act on the live session; only a session that reached `sessionWentLive` may auto-reconnect,
and a drop inside `establish` completes the `linkLost` deferred so the owning loop retries rather
than a second loop starting. Two loops running at once registers two GATT clients for the same
radio, which is exactly the startup connect/disconnect storm described in `HANDOFF.md`. Callers
that fire repeatedly (`MainActivity.onResume`) go through `autoConnect`, which spends exactly one
automatic attempt per process: a radio that is off or out of range must not be retried behind the
user's back, and the Connect card's Reconnect/Forget buttons are the way back. `Connecting` is for
a link that was never up, `Reconnecting` only for one that had been live, and every in-progress
state names its target radio. A known BLE radio is scanned for before any GATT connect
(`PresenceProbe` / `BleScanner.isAdvertising`), because a blind MAC connect to a radio that
is not advertising just burns a ~5 s timeout and returns status 133.

**`PacketIngest`** holds all packet-to-database business logic and no transport concerns.
Invariants worth knowing before touching it:

- Node numbers are unsigned 32-bit stored as `Long`; convert with `Int.uint()` from
  `MeshProtocol.kt`. Never use the raw proto `Int`.
- Messages dedupe on the wire packet id (`insertIgnore`, never REPLACE): the radio echoes our
  own sends back, and a replace would reset read/ack state and fire a phantom notification.
- `MessageEntity.toNum == null` is the channel-vs-DM discriminator used by every query.
- Acks and naks arrive on `ROUTING_APP` and are correlated back through `decoded.requestId`.
- Public keys are first-wins: a differing inbound key is refused and flags
  `UserEntity.keyMatch = false` so the UI can warn. A re-flashed radio therefore NAKs DMs with
  error 39 until "Broadcast node info" is used.
- A new radio's `MyNodeInfo` with a different node num wipes nodes **and** the `my_info` row
  (its single-row `LIMIT 1` queries would otherwise serve the old radio's identity).

**Persistence** (`db/`): Room, version 3, `fallbackToDestructiveMigration`. Schema changes just
bump the version and wipe local data; no migrations are written. Radio config is stored as
**raw proto bytes** in `configs` keyed `config.<section>` / `module.<section>` and parsed at
read time in the ViewModel, so new config sections need no schema change. `positions` keeps one
`latest = 1` row per node for the map; older positions and telemetry are pruned to 30 days on
connect.

**Protobufs**: `app/src/main/proto/meshtastic/*.proto` generate Java + Kotlin **lite** classes
into `org.meshtastic.proto` (`java_package` set in the protos; outer classes `MeshProtos`,
`AdminProtos`, `ConfigProtos`, `Portnums`, and so on). To update the wire format, copy fresh
protos from `F:\Meshtastic-Apple\MeshtasticProtobufs` and rebuild; never hand-edit generated
code.

**Radio config UI**: `ui/ConfigScreens.kt` has one form per config section, reached from
the Settings tab's "Radio configuration" list (a sub-screen held in local state plus
`BackHandler`, not a nav graph). Each form edits a **draft** of the section's proto and
writes it with a single Save, because every `setConfig` makes the radio save and reboot.
`SettingsViewModel.configFlow(key, extract)` parses one section out of the stored raw
bytes; `writeConfig {}` is the single write helper. Proto `uint32` fields arrive as signed
`Int`, so number rows render and parse them unsigned (`0xFFFFFFFF` is a firmware
"disabled" sentinel, not -1); genuinely signed fields like `tx_power` opt out.

**UI** (`ui/`): the `navigation-compose` dependency is present but unused. Navigation is a
`when (selected)` switch in `MainActivity` over five tabs, with cross-tab state in `Router`
(`selectedTab` plus a one-shot `pendingThread` consumed by the Messages tab). Notification deep
links arrive as `MainActivity` intent extras and are converted into `Router.openThread`. Each
screen is `fun XScreen(vm: XViewModel = viewModel())`; ViewModels are `AndroidViewModel`
exposing `stateIn(..., WhileSubscribed(5000), ...)` StateFlows. `MapScreen` keeps its ViewModel
in the same file; the rest live in `ViewModels.kt`.

Tapbacks are stored as ordinary messages with `isEmoji = 1` and `replyId` pointing at the
target, and every thread query filters on that flag.

**Radio memory**: DataStore holds two different things, and conflating them caused a bug once.
`RADIO_TYPE`/`RADIO_ADDRESS`/`RADIO_NAME` are the **auto-connect target** (cleared by a
deliberate Disconnect, so the app stops grabbing that radio on launch); `KNOWN_RADIOS` is the
**saved list** of every radio ever connected to, a JSON array of `RememberedRadio` that only the
Forget button deletes from. `AppContainer.rememberRadio()` writes both on every successful
connect and is the single place that does so.

**Foreground service**: `RadioService` exists only to keep the process alive during a session
(`connectedDevice|location`), started and stopped alongside connect/disconnect. A deliberate
Disconnect also clears the remembered radio from DataStore, which is what keeps
`autoConnectIfRemembered` (run on create and on resume) from reconnecting.

## Map gotchas (each one cost a debug cycle)

- `AndroidView`'s `update` block must read Compose state **synchronously**. Reads inside
  deferred callbacks such as `getMapAsync` are not snapshot-tracked and the map silently stops
  updating; pass values through `rememberUpdatedState`.
- The openfreemap glyph server serves only "Noto Sans" stacks and has no emoji, so marker
  labels use that font stack and strip emoji.
- Raster styles carry no glyphs: the inline satellite style JSON must declare its own `glyphs`
  URL or symbol layers vanish.
