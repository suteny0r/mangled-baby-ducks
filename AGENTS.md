# AGENTS.md

Android (Kotlin + Compose) Meshtastic client, ported from the Swift app at `F:\Meshtastic-Apple`.
Architecture, data-flow, and invariants: read `CLAUDE.md` in full before touching code.
Session log, verified behavior, and known gaps: read `HANDOFF.md` before starting work.

## Build & install

```powershell
& .\gradlew.bat :app:assembleDebug
adb -s R5CN70YWT5Z install -r app\build\outputs\apk\debug\app-debug.apk
```

- Gradle must be invoked with the PowerShell call operator; `cmd /c` fails in this harness.
- Multi-line `python -c` also fails in Git Bash; write a script file instead.
- No test sources or test dependencies exist. Verification is on the physical device.

## Test rig

- **Never open COM14** (phone radio "SOBE GAT562 30s"). Opening it kills the phone's BLE session.
- Test-traffic sender: Spiney Norman on COM3. SOBE's current node num is `!1eff739f`.
  ```
  $env:PYTHONIOENCODING='utf-8'
  meshtastic --port COM3 --sendtext 'hello' --dest '!1eff739f' --ack
  ```
- **Never test with swaffelen or 6abc.**
- Config writes make the radio save and reboot. Prove a write path with a no-op first.

## Non-negotiable invariants

- **One connect attempt loop at a time.** `RadioManager.runAttempts` is the only caller of
  `establish`. `attemptLock` + `requestGeneration` guard it. Two concurrent loops register two
  GATT clients for the same radio and cancel each other.
- Callers that fire repeatedly (`onResume`, permission callbacks) go through `autoConnect`,
  which spends exactly one automatic attempt per process.
- **Scan before connecting.** Known BLE radios are probed with `BleScanner.isAdvertising`
  before any GATT connect. Blind MAC connects burn ~5 s and return status 133.
- **Messages dedupe on the wire packet id** (`insertIgnore`, never REPLACE). The radio echoes
  sends back; a REPLACE would reset read/ack state and fire a phantom notification.
- **Node numbers are unsigned 32-bit** stored as `Long`; use `Int.uint()` from
  `MeshProtocol.kt`. Proto `uint32` fields arrive as signed `Int`; render and parse
  unsigned. `0xFFFFFFFF` is a firmware "disabled" sentinel, not -1.
- **Public keys are first-wins.** A differing inbound key is refused; a re-flashed radio NAKs
  DMs with error 39 until "Broadcast node info" is used.
- **Room `my_info` single-row** `LIMIT 1` query: a new radio's `MyNodeInfo` with a different
  node num must wipe nodes and the `my_info` row.

## Expensive gotchas (do not relearn)

- `AndroidView`'s `update` block must read Compose state **synchronously**. Reads in deferred
  callbacks (e.g. `getMapAsync`) are not snapshot-tracked; the map silently stops updating.
  Use `rememberUpdatedState` to pass values through.
- GATT 133 on every attempt means the radio is not advertising, not that the app is broken.
  Check the remembered address and scan state before debugging app code.
- Samsung "BT off" keeps `BLE_ON`; GATT dies silently. The adapter-state receiver in
  `BleConnection` detects it; reconnect can succeed immediately after.
- Proto `uint32` in generated Java is a signed `Int`. Number rows must render/parse unsigned.
- Adding an optional parameter after a trailing `() -> T` parameter in Kotlin silently
  rebinds every lambda call site to the new parameter. `factory` must stay last in
  `RadioManager.connect`.
- openfreemap glyph server serves only "Noto Sans" stacks, no emoji. Raster styles need an
  explicit `glyphs` URL in the inline style JSON or symbol layers vanish.
- `lintDebug` fails at HEAD with two `ProduceStateDoesNotAssignValue` errors in
  `MessagesScreen.kt:270` and `NodeDetailScreen.kt:241`; these are pre-existing.
- Screenshots via `adb exec-out screencap -p > file.png` corrupt the PNG in PowerShell.
  Use `adb shell screencap -p /sdcard/x.png` then `adb pull`.
- Git Bash rewrites `/data/...` paths in adb shell arguments. Prefix with
  `MSYS_NO_PATHCONV=1` when reading app-private files via `run-as`.

## Conventions

- **Port provenance**: each class carries a KDoc line naming the Swift file it was ported
  from. When porting new behavior, read the Swift original first and keep the citation.
- **Protobufs**: vendored at `app/src/main/proto/meshtastic/`. Generated Java + Kotlin lite
  classes go to `org.meshtastic.proto`. To update, copy fresh protos from
  `F:\Meshtastic-Apple\MeshtasticProtobufs`; never hand-edit generated code.
- **Room**: version 3, `fallbackToDestructiveMigration`. Schema changes bump the version
  and wipe local data; no migrations are written.
- **Radio config UI** (`ui/ConfigScreens.kt`): one form per section, each edits a draft
  and writes with a single Save. Every `setConfig` makes the radio save and reboot.
  `SettingsViewModel.configFlow(key, extract)` and `writeConfig{}` are the read/write helpers.
- **UI**: `navigation-compose` is present but unused. Navigation is a `when(selected)` switch
  over five tabs in `MainActivity`; cross-tab state lives in `Router`.
- **DataStore radio memory**: `RADIO_TYPE`/`RADIO_ADDRESS`/`RADIO_NAME` are the auto-connect
  target (cleared by deliberate Disconnect). `KNOWN_RADIOS` is the saved list, JSON array,
  capped at 12, only Forget deletes from. `AppContainer.rememberRadio()` is the only writer.

## Logcat

```powershell
adb -s R5CN70YWT5Z logcat -s RadioManager:V BleConnection:V PacketIngest:V MapScreen:V
```

Diagnosing connection churn: filter for `clientConnect(com.suteny0r` and `clientIf`.
Two client interfaces at once is the tell for a double driver.

## Deliberately not done

- MQTT client proxy: radio has MQTT disabled; enabling would bridge the mesh to the public
  broker. Documented in commit `0b8cdbe`.
- Channel-set Apply: implemented but never test-fired (it REPLACES the radio's channels).
- Seeding saved-radio list from OS bonded-device list: bond list is full of unrelated devices.
