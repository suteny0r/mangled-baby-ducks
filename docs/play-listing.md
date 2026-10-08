# Google Play listing draft

Rules this draft follows (Meshtastic trademark policy, https://meshtastic.org/docs/legal/trademark/):

- "Meshtastic" never appears in the app title, short description, package name, developer
  name, icon, feature graphic, or screenshots' captions.
- It appears in the full description only as a factual compatibility statement, with the ®
  symbol on first mention and the non-affiliation disclaimer.
- No Meshtastic logo anywhere in the listing or the app.
- The app is GPLv3, so the listing links to the source repository.

## App name (30 chars max)

Mangled Baby Ducks

## Short description (80 chars max)

Open-source client for LoRa mesh radios. Messages, nodes, map, Android Auto.

## Full description (4000 chars max)

Mangled Baby Ducks is a free, open-source Android client for LoRa mesh radios running
Meshtastic® firmware. Connect over Bluetooth LE or Wi-Fi/TCP and keep the session alive in
the background.

Messaging
• Channel and direct messages with delivery acks and failure reasons
• Tapback reactions, per-conversation notifications, reply from the notification
• Import and export channels with QR codes and share links

Nodes
• Live node list with signal, battery, distance, last heard
• Favorites, ignore, search, node detail, traceroute
• Public-key repair when a node has been re-flashed

Map
• Positioned nodes and waypoints on street or satellite imagery
• Traceroute paths drawn on the map
• Long-press to drop a waypoint

Radio configuration
• Edit every radio config section from the phone
• Each section saves with one write so the radio reboots once

Android Auto
• Nodes on the car's map, messages with quick replies, voice replies through notifications

Backups
• Per-radio database snapshots, so switching radios keeps each one's nodes and messages

Open source
Mangled Baby Ducks is licensed under the GNU General Public License v3.0. Source code:
https://github.com/suteny0r/mangled-baby-ducks

Mangled Baby Ducks is an independent project. It is not affiliated with or endorsed by
Meshtastic LLC or the Meshtastic project. Meshtastic® is a registered trademark of
Meshtastic LLC.

Map data © OpenFreeMap, © OpenMapTiles, © OpenStreetMap contributors. Satellite imagery:
Esri World Imagery.

## Store settings

- Category: Communication
- Price: Free, no ads, no in-app purchases
- Privacy policy URL: https://github.com/suteny0r/mangled-baby-ducks/blob/main/docs/PRIVACY.md
- Data safety form (answers follow `docs/PRIVACY.md`): the developer collects no data.
  Declare "Location > Precise location" as shared (optional, user-controlled, app
  functionality) because the user can send GPS to the mesh; "Messages > Other in-app
  messages" is stored on device only and not collected or shared. Data is not encrypted in
  transit to the developer because nothing is sent to the developer. Users can delete all
  data by uninstalling. Map tile requests to OpenFreeMap and Esri are ordinary web
  requests and do not need declaring as collection.
- Website: https://github.com/suteny0r/mangled-baby-ducks

## Release checklist

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Tag the commit `v<versionName>` and push the tag. The in-app About screen points at
   this repository as the GPLv3 source offer, so the tag must match the shipped build.
3. Build a signed release bundle: `gradlew :app:bundleRelease` writes
   `app/build/outputs/bundle/release/app-release.aab`, signed with the release keystore
   from `local.properties`. On the first upload Play App Signing takes that certificate as
   the upload key; let Google hold the app signing key.
4. Push and release as GitHub user `suteny0r` (`gh auth switch --user suteny0r` first).

## Console assets

- Icon: `art/icon-crops/play-icon-512.png` (512 x 512 PNG).
- Feature graphic: `art/icon-crops/feature-graphic-1024x500.png`.
- Phone screenshots: 2 to 8, PNG or JPEG, each side 320 to 3840 px and the long side at
  most twice the short side. A raw Note 20 Ultra capture is 1080 x 2316 and fails that
  rule; crop to 1080 x 2160 (drops the system navigation bar).

## Declarations the console will ask for

- Foreground service types `connectedDevice` and `location`
  (`FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_LOCATION`): the "Foreground
  service permissions" form wants the use case and a short video of the feature. The
  session keeps the radio link alive and, when the user turns on location sharing, sends
  the phone's GPS to the radio.
- Location (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`): required by Android for BLE
  scanning below API 31 and used, optionally, for location sharing and the map.
- Bluetooth (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`): the radio link.
- Android Auto: the listing's car app category is "POI" (`androidx.car.app.MAP_TEMPLATES`);
  Play reviews Auto apps against the car app quality guidelines.
- `allowBackup="false"` is set, and `docs/PRIVACY.md` says so; the Data safety form can
  state that no data leaves the device through Android backup.
