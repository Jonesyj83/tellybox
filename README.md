# OpenStream

A live TV player for Android TV. Bring your own M3U playlist, Xtream Codes login or
Stalker portal; OpenStream loads the channels, pulls in the XMLTV guide and plays the
streams. It's free, with no accounts, ads or tracking, and everything is stored on the device.

## Status

Early. What works today:

- Add playlists from an M3U link, an M3U file on the device, an Xtream Codes login or a
  Stalker portal (MAC address)
- Channel list with groups, Favorites, Recently watched, search and now-playing info
- XMLTV guide import (plain or gzipped), streamed so large guides don't run the box out
  of memory. Guide URLs advertised by the playlist (`url-tvg`) or by an Xtream panel are
  picked up automatically
- Playback of HLS, DASH and MPEG-TS over HTTP through Media3 (RTMP support is built in
  but hasn't been tried against a real stream yet), with channel
  up/down zapping and a channel banner
- Stale playlists and guides are refreshed when the app starts

Not there yet: TV guide grid, catch-up, recording, reminders, VOD browsing, settings,
parental controls, backup.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 36).

```
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew test              # parser and client unit tests
```

Install on a TV box with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Layout

```
app/src/main/java/com/openstream/tv/
  data/db/           Room entities and DAOs
  data/parser/       M3U, XMLTV, Xtream Codes and Stalker clients (no Android deps,
                     unit-tested on the JVM)
  data/repository/   import/refresh logic tying the parsers to the database
  player/            Media3 player setup
  ui/                activities: channel list, playlists, add-playlist wizard, player
```

Favorites and watch history are keyed on a stable per-channel key (provider stream id or
URL), not the database row id, so they survive a playlist refresh.

## Remote control

| Key                      | Channel list            | Player                    |
|--------------------------|-------------------------|---------------------------|
| OK                       | play channel            | show info / retry on error|
| Menu or long-press OK    | add/remove Favorites    |                           |
| Up / Down, CH+ / CH-     | move                    | previous / next channel   |
| Back                     | exit                    | back to the list          |
