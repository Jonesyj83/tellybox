# OpenStream

A live TV player for Android TV. Bring your own M3U playlist, Xtream Codes login or
Stalker portal; OpenStream loads the channels, pulls in the XMLTV guide and plays the
streams. It's free, with no accounts, ads or tracking, and everything is stored on the device.

## Features

- Add playlists from an M3U link, an M3U file on the device, an Xtream Codes login or a
  Stalker portal (MAC address)
- Channel list with groups, Favorites, Recently watched, search and now-playing info
- XMLTV guide import (plain or gzipped), streamed so large guides don't run the box out
  of memory. Guide URLs advertised by the playlist (`url-tvg`) or by an Xtream panel are
  picked up automatically
- Playback of HLS, DASH and MPEG-TS over HTTP through Media3 (RTMP support is built in
  but hasn't been tried against a real stream yet), with channel
  up/down zapping and a channel banner
- TV guide grid, catch-up, movies and series, track and display-mode options
- Recording (instant and scheduled) and programme reminders
- Parental controls with a PIN, channel and group blocking
- Playlist editing, hidden and reordered groups, channel name cleanup
- Multiview: up to four live channels at once. Sound follows the focused screen; a screen
  can be enlarged, swapped or removed. Warns before going past an Xtream account's
  connection limit
- Playback runs in a media session service: picture-in-picture, remote and notification
  control, and (optional) keeps playing after leaving the player
- Playlists and guides refresh on app start and in the background at the interval set
  in Settings

Not there yet: backup and restore.

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
  player/            Media3 player setup and the playback service
  ui/                activities: channel list, guide, VOD, playlists, player, multiview,
                     recordings, settings
```

Favorites and watch history are keyed on a stable per-channel key (provider stream id or
URL), not the database row id, so they survive a playlist refresh.

## Remote control

| Key                      | Channel list            | Player                    |
|--------------------------|-------------------------|---------------------------|
| OK                       | play channel            | channel list / retry      |
| Menu or long-press OK    | add/remove Favorites    | options (tracks, record, multiview, PiP) |
| Up / Down, CH+ / CH-     | move                    | previous / next channel   |
| Back                     | to groups, clear search, exit | back to the list    |

In multiview, the D-pad moves between screens, OK or Menu opens the screen's options,
CH+ / CH- changes the focused screen's channel and Back returns to the single player
with the focused channel.
