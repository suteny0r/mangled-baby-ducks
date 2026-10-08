# Mangled Baby Ducks

A free, open-source Android client for mesh radios running Meshtastic® firmware.
Kotlin and Jetpack Compose, with an Android Auto templated UI.

Mangled Baby Ducks is an independent project. It is **not affiliated with or endorsed by
Meshtastic LLC** or the Meshtastic project. Meshtastic® is a registered trademark of
Meshtastic LLC.

## What it does

- Connects to a radio over Bluetooth LE or TCP and keeps the session alive in the background.
- Channel and direct messages, tapbacks, acks and naks, per-conversation notifications.
- Node list with favorites, ignore, search, node detail, traceroute, and public-key repair.
- Map of positioned nodes and waypoints (OpenFreeMap streets, Esri satellite).
- Editable radio configuration for all eight config sections, channel import/export via
  `meshtastic.org/e/#` links and QR codes.
- Android Auto: nodes on the host map, messages with canned replies, voice replies through
  messaging notifications.

## Backups

Settings > Backup Management keeps one snapshot per radio. The app takes one on its own
whenever you switch radios, and Backup Now takes one on demand. Switching back to a radio
restores its snapshot before the new node dump lands.

A snapshot is the app's whole database. Much of it exists nowhere else: the radio does
not hold it, so a fresh install cannot recover it from the radio.

| Only in the backup | Why the radio cannot supply it |
|---|---|
| Messages: every channel and direct message, read state, acks and naks, replies, tapbacks | The radio keeps no message history for the phone |
| Node positions: latest fix per node and the position log | The radio's node table carries no positions; a fresh install refills only as nodes broadcast again |
| Telemetry history: battery, voltage, channel utilisation, air time, environment readings | The radio keeps one latest reading per node |
| Traceroute log: every traceroute with its reply paths and per-hop SNR | Not stored on the radio |
| Nodes beyond the radio's table, with names, hardware, roles, first-heard times | The radio evicts older nodes as its table fills |
| Local flags: mute per node, the key-mismatch warning and refused key, first-heard times, device metadata | Phone-side state |
| Waypoints received over the mesh | Not retained by the radio |

Favorites and ignored nodes live in both places. Channels, radio configuration and the
radio's identity are in the snapshot too, but the radio supplies those on every connect.

Backups live in the app's data folder, which Android deletes with the app. Before an
uninstall or a move to another phone: Backup Now, then Export Backups to File, and keep
the zip in Documents, not Downloads (some phones delete an app's downloads along with the
app). On the new install, Import Backups from File brings the snapshots back, then
Restore the one for your radio.

## Privacy

The app has no server, no accounts and no analytics; the developer receives no data. The
full policy is [docs/PRIVACY.md](docs/PRIVACY.md).

## Build

Requires Android Studio (or its bundled JDK) and an Android SDK. `local.properties` holds
the SDK path and is not committed.

```powershell
& .\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Architecture notes and hardware test conventions are in `CLAUDE.md`; the running session
log is `HANDOFF.md`.

## License

Mangled Baby Ducks is licensed under the **GNU General Public License v3.0**. See
[LICENSE](LICENSE). Every binary of this app ships with the license text and this
repository's URL under Settings > About and licenses, which is the source offer
required by GPLv3 section 6. Each Play Store release is tagged in this repository with
its `versionName`.

This app is a derivative work of
[Meshtastic-Apple](https://github.com/meshtastic/Meshtastic-Apple) (GPL-3.0), hand-ported
from Swift to Kotlin; each ported class names its Swift original in its KDoc. The
[Meshtastic protobufs](https://github.com/meshtastic/protobufs) (GPL-3.0) are vendored
unmodified in `app/src/main/proto`.

Third-party components and their licenses (MapLibre Native, Protocol Buffers, AndroidX,
Kotlin, ZXing, Coil) are listed in [NOTICE](NOTICE) and in
`app/src/main/assets/NOTICES.txt`.

Map data: © [OpenFreeMap](https://openfreemap.org), © [OpenMapTiles](https://openmaptiles.org),
data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright) (ODbL).
Satellite imagery: Esri World Imagery (Esri, Maxar, Earthstar Geographics, and the GIS
User Community).
