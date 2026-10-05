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
Kotlin, ZXing) are listed in [NOTICE](NOTICE) and in
`app/src/main/assets/NOTICES.txt`.

Map data: © [OpenFreeMap](https://openfreemap.org), © [OpenMapTiles](https://openmaptiles.org),
data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright) (ODbL).
Satellite imagery: Esri World Imagery (Esri, Maxar, Earthstar Geographics, and the GIS
User Community).
