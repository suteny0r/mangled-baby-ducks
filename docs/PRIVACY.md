# Privacy Policy for Mangled Baby Ducks

Effective date: 4 October 2026

Mangled Baby Ducks is a free, open-source Android client for LoRa mesh radios running
Meshtastic® firmware. It is published by an individual developer (GitHub user suteny0r,
"the developer"). This policy explains what the app does with your data. The short version:
the app has no server, no accounts, no analytics and no advertising, and the developer never
receives any of your data.

## Data the developer collects

None. The app does not send any data to the developer or to any service operated by the
developer. There is no account, no sign-in, no crash reporting, no analytics and no
advertising SDK. The developer cannot see your messages, your location, your contacts or
your radio configuration.

## Data the app stores on your device

The app keeps a local database on your phone so it can show you your mesh. This database is
written only by the app and read only by the app. It contains:

- Messages you send and receive, with delivery status, and emoji reactions.
- Nodes heard on the mesh: node numbers, user names, hardware model, signal metrics,
  battery and environment telemetry, and reported positions.
- Public keys of other nodes, used by the radio firmware to encrypt direct messages.
- Your radio's configuration and channel settings, including channel keys, as raw
  protocol buffers.
- Waypoints shared on the mesh.
- The Bluetooth address or IP address and name of radios you have connected to, so the
  app can reconnect to them.
- Preferences such as whether location sharing is enabled.

Positions and telemetry older than 30 days are deleted each time the app connects.
Uninstalling the app deletes all of this data.

## Data the app sends, and where

### To your own radio

The app talks to one mesh radio at a time, over Bluetooth Low Energy or over TCP on your
local network. Everything you do in the app (sending a message, changing a setting,
dropping a waypoint, requesting a traceroute) is sent to that radio, which transmits it
over LoRa to the mesh. Once a packet leaves your radio it is received by every radio in
range, is relayed by them, and is readable by anyone holding the channel key. Direct
messages are additionally encrypted by the firmware to the recipient's public key. The
developer has no control over, and no visibility into, what happens on the mesh.

### Finding radios on your Wi-Fi

While the Connect tab is open, the app looks for radios on your local network in two
ways: it listens for multicast DNS announcements, and it tries the Meshtastic API port
(4403) on every address of your phone's local subnet, briefly handshaking with anything
that answers to read the radio's name. This traffic never leaves your local network, and
nothing about it is sent to the developer.

### Your location

The app asks for the precise location permission for two reasons:

1. Android requires it to scan for Bluetooth devices on some versions.
2. Optionally, to share your phone's GPS position on the mesh as your node's position.

Location sharing is **off by default**. When you turn it on in Settings, the app reads
your phone's GPS while it is connected to a radio and sends each fix to your radio, which
broadcasts it on the mesh. Turning the switch off stops this. Separately, if you choose
"send my position" to a specific node, the app sends your most recent GPS fix to that
node once. Your location is never sent anywhere other than your own radio.

The map tile requests described below reveal to the tile servers roughly which area of
the map you are looking at. That is a property of fetching map tiles and is not your GPS
position.

### Map tiles

The Map tab downloads map imagery from third-party tile servers. These requests include
your IP address and the tile coordinates you are viewing, as any web request does. The
app sends no other data to them.

- Street map: OpenFreeMap (tiles.openfreemap.org), serving OpenMapTiles data from
  OpenStreetMap contributors. See https://openfreemap.org.
- Satellite imagery: Esri World Imagery (server.arcgisonline.com). See Esri's privacy
  statement at https://www.esri.com/en-us/privacy/overview.

### Hardware catalog

To show a node's hardware model with its product image, the app downloads the public
device catalog from the Meshtastic project (api.meshtastic.org) about once every two days
and loads product images from the Meshtastic web flasher (flasher.meshtastic.org). These
requests carry your IP address and nothing else: no node numbers, names or positions are
sent. The catalog is cached on your device.

If you never open the Map tab, the only internet requests are the hardware catalog ones.

### Links you open

The About screen and some node actions offer links to GitHub, meshtastic.org and the
map providers. Tapping one opens your browser, and from then on that site's privacy
policy applies. Channel share links and contact share links are generated on your phone
and handed to the app you choose to share them with; nothing is uploaded to meshtastic.org.

## Notifications

Incoming messages are shown as notifications on your phone, including the sender's name
and the message text. Notifications stay on your device and follow your Android
notification settings.

## Android Auto

When connected to Android Auto, the app shows nodes, messages and positions on the car's
display and can read incoming messages aloud through Android's messaging notifications.
That data goes to the car head unit through Android; the developer does not receive it.

## Android backup

The app opts out of Android's automatic app backup (`android:allowBackup="false"`). Your
messages, node database, channel keys and preferences stay on the device and are not
copied to your Google account by Android backup or device-to-device transfer. The only
copies the app makes are the per-radio snapshots in Settings > Developers > Backup
Management, stored in the app's own folder on the device and deleted with the app.

## Permissions

| Permission | Why |
|---|---|
| Bluetooth scan and connect | Find and talk to your mesh radio |
| Precise location | Required for Bluetooth scanning; optionally shares your GPS on the mesh |
| Internet | Map tiles and the hardware catalog |
| Notifications | Show incoming messages |
| Foreground service (connected device, location) | Keep the radio link alive while the app is in the background |

## Children

The app is not directed at children under 13 and does not knowingly collect data from
anyone. The developer collects no data from any user.

## Changes

Changes to this policy are made in the app's public source repository, where the history
of every edit is visible:
https://github.com/suteny0r/mangled-baby-ducks/blob/main/docs/PRIVACY.md

## Contact

Open an issue at https://github.com/suteny0r/mangled-baby-ducks/issues.

---

Mangled Baby Ducks is an independent project. It is not affiliated with or endorsed by
Meshtastic LLC or the Meshtastic project. Meshtastic® is a registered trademark of
Meshtastic LLC.
