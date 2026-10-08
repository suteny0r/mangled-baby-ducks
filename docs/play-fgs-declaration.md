# Play Console: foreground service permissions declaration

The app declares one foreground service, `RadioService`, with
`android:foregroundServiceType="connectedDevice|location"`. The console asks for a
use-case description and a demonstration video per type. Text below is ready to paste.

Demo video (68 s, screen recording on a Galaxy Note 20 Ultra):
https://github.com/suteny0r/mangled-baby-ducks/releases/download/v0.2.6/fgs-demo.mp4
Local copy: `art/play/0.2.6/fgs-demo.mp4`.

What the video shows, in order: the Connect tab with the radio linked; Disconnect and
reconnect through the Bluetooth LE link (0:03 to 0:30, the card goes Communicating then
Subscribed); the app sent to the background with Home and reopened with the link still
live (0:30 to 0:40); Settings > "Share phone location" switched on, then off (0:45 to 1:05).

## FOREGROUND_SERVICE_CONNECTED_DEVICE

The app is a client for LoRa mesh radios. The user pairs a radio over Bluetooth LE (or
Wi-Fi/TCP) from the Connect tab. While the radio is connected, the foreground service keeps
the process alive so that the link to the radio stays up when the user leaves the app:
incoming mesh messages are received and shown as notifications, delivery acknowledgements
for the user's own messages arrive, and the node database and map keep updating. The
service starts when a connection is established and stops when the user taps Disconnect or
the link is lost for good. Its notification reads "Mangled Baby Ducks: Connected to <radio
name>" and opens the app. Without the foreground service Android suspends the process in
the background within minutes, the GATT session is dropped by the radio, and the user misses
messages until they reopen the app.

## FOREGROUND_SERVICE_LOCATION

Optional, off by default. When the user turns on Settings > "Share phone location", the app
sends the phone's GPS position to the connected radio about once a minute (and only when
the phone has moved at least 25 m), so the radio can announce the user's position to the
mesh the way a radio with its own GPS would. The position goes only to the user's own radio
over the local Bluetooth or Wi-Fi link; the app has no server and sends nothing to the
developer. Location updates must continue while the app is in the background, otherwise the
user's position on the mesh freezes as soon as the screen turns off, which is the normal
state while hiking, driving or boating with the phone in a pocket. The same foreground
service carries this type; it runs with the `location` type only while the setting is on
and a radio is connected, and the user can turn it off at any time from the same switch.
