# Tellybox

A live TV player for Android TV. Bring your own M3U playlist, Xtream Codes login or
Stalker portal; Tellybox loads the channels, pulls in the XMLTV guide and plays the
streams. It's free, with no accounts, ads or tracking, and everything is stored on the
device.

Tellybox doesn't provide, host or bundle any channels. You supply the playlist, and you
are responsible for making sure you are allowed to watch what is on it.

## Features

- Add playlists from an M3U link, an M3U file on the device, an Xtream Codes login or a
  Stalker portal (MAC address). Several playlists at once, each with its own guide
- Channel list with groups, Favorites, Recently watched, search and now-playing info
- XMLTV guide import (plain or gzipped), streamed so large guides don't run the box out of
  memory. Guide URLs advertised by the playlist (`url-tvg`) or by an Xtream panel are picked
  up automatically, and a guide can be attached to a playlist by hand
- Playback of HLS, DASH and MPEG-TS over HTTP through Media3, with channel up/down zapping,
  a channel banner, audio and subtitle track pickers and display modes
- TV guide grid with catch-up on channels whose provider supports it
- Movies and series: posters, categories, seasons and episodes, resume where you left off
- Recording: instant or scheduled from the guide, padding either side, recordings list
- Programme reminders that pop up before a show starts
- Sleep timer, and an option to open the last channel you were watching on startup
- Multiview: two, three or four live channels at once. Sound follows the focused screen, a
  screen can be enlarged or swapped. Warns when your provider's connection limit is likely to
  be exceeded, since each screen is one connection
- Playback runs in a media session service: picture-in-picture, remote and notification
  control, and optional background playback
- Parental controls with a PIN, channel and group blocking
- Playlist editing, hidden and reordered groups, channel name cleanup
- Backup and restore: playlists, favourites, blocked channels and settings to a single file
- Playlists and guides refresh on app start and in the background at the interval set in
  Settings
- Opens a stream in another player (VLC, MX Player) for the odd stream ExoPlayer struggles with

RTMP playback is compiled in but has not been tried against a real stream.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 36).

```
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew test              # parser and logic unit tests
```

Install on a TV box with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Release builds read the signing key from `local.properties`, which is not committed:

```
tellybox.storeFile=/path/to/keystore.jks
tellybox.storePassword=...
tellybox.keyAlias=...
tellybox.keyPassword=...
```

Without those entries the release build still runs, just unsigned.

## Layout

```
app/src/main/java/com/tellybox/tv/
  data/db/           Room entities and DAOs
  data/parser/       M3U, XMLTV, Xtream Codes and Stalker clients (no Android deps,
                     unit-tested on the JVM)
  data/repository/   import and refresh logic tying the parsers to the database
  player/            Media3 player setup and the playback service
  ui/                activities: channel list, guide, VOD, playlists, player, multiview,
                     recordings, settings
```

Favourites and watch history are keyed on a stable per-channel key (provider stream id or
URL), not the database row id, so they survive a playlist refresh.

## Remote control

| Key                      | Channel list                  | Player                                     |
|--------------------------|-------------------------------|--------------------------------------------|
| OK                       | preview channel, OK again for full screen | controls / retry                |
| Long-press OK            | channel options               | options (tracks, record, sleep, multiview) |
| Up / Down, CH+ / CH-     | move                          | previous / next channel                    |
| Back                     | groups, clear search, exit    | back to the list                           |

Remotes without a Menu key are fully supported: everything on the Menu is also in the
long-press OK menu, and Record and Sleep have their own buttons on screen.

In multiview the D-pad moves between screens, OK opens the focused screen's options, CH+ and
CH- change its channel, and Back returns to the single player with that channel.

## Licence

Apache 2.0. See LICENSE.
