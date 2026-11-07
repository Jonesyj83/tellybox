# Tellybox

A live TV player for Android TV. Point it at your own playlist and it builds the channel
list, pulls the guide in, and plays the streams. No account, no ads, no tracking, nothing
leaving the box except the requests needed to load your playlist and play your channels.

Tellybox does not supply, host or bundle any channels. You bring the playlist, and you are
responsible for having the right to watch what is on it.

**[Download the latest APK](../../releases/latest)**

## What you need

- An Android TV or Google TV device on Android 6.0 or newer
- A playlist: an M3U/M3U8 link or file, an Xtream Codes login, or a Stalker portal MAC
- Optionally, an XMLTV guide link (most playlists advertise one, and Tellybox picks it up
  by itself)

## Install

1. Grab the APK from the [releases page](../../releases/latest).
2. Get it onto the TV. Easiest is the Downloader app on the TV: open it, type the download
   link, install. Or from a computer with `adb install -r Tellybox-0.2.0.apk`.
3. Allow installs from unknown sources when the TV asks. Android TV blocks sideloading by
   default, this is the normal prompt for any app that didn't come from the Play Store.
4. Open Tellybox, choose **Add playlist**, and paste your link.

## Features

**Live TV**
- Channel list with groups down the side, a preview window, and now/next information
- Preview first, then OK again for full screen. You can browse to any other channel while
  the preview plays and jump it to full screen without finding it again
- Channel up/down zapping, numeric entry for channel numbers
- HLS, DASH, MPEG-TS and RTMP through Media3/ExoPlayer
- Audio track, subtitle and display mode pickers, plus an audio passthrough option for
  surround sound setups

**Guide**
- XMLTV import, plain or gzipped, streamed in so a 200 MB guide doesn't run the box out of
  memory
- Guide URL taken from the playlist's `url-tvg` or from an Xtream panel, or attached by hand
- TV guide grid with a time ruler, current-time marker and programme details
- Guide offset setting for providers whose times are wrong

**Catch-up**
- Play past programmes straight from the guide, on channels whose provider supports it
- Works with Xtream timeshift, M3U `catchup-source` templates and Stalker archive links
- Future programmes are refused rather than failing silently

**Movies and series**
- Posters, categories, seasons and episodes from Xtream panels and M3U VOD entries
- Resume where you left off

**Recording**
- Record what you're watching, or schedule from the guide
- Start and end padding, so you don't lose the first or last minute
- Recordings list with playback and delete
- Reminders that pop up before a programme starts

**Multiview**
- Two, three or four live channels on one screen, laid out properly (two side by side,
  three as two-up plus one, four as quarters)
- Sound follows the focused screen; screens can be swapped, enlarged or removed
- Warns about your provider's connection limit, because each screen is one connection

**Comfort**
- Sleep timer: 30, 60, 90, 120 minutes or the end of the current programme
- Option to open the last channel you were watching when the app starts
- Picture-in-picture, and a media session so the TV remote and notification controls work
- Optional background playback

**Playlists and organisation**
- Several playlists at once, each with its own guide
- Favourites, recently watched, search across channels and programmes
- Hide, reorder and rename groups, clean up channel names
- Playlist editing and refresh, on demand or on a schedule

**Parental controls**
- PIN lock, with channels and whole groups blocked

**Backup**
- Export playlists, favourites, blocked channels and settings to one file, and restore it

**Escape hatch**
- Send any stream to another player (VLC, MX Player) when ExoPlayer struggles with it

RTMP playback is compiled in but hasn't been tested against a real stream.

## Remote control

| Key | Channel list | Player |
|---|---|---|
| OK | preview the channel, OK again for full screen | show controls / retry |
| Long-press OK | channel options | options: tracks, record, sleep, multiview, external player |
| Up / Down, CH+ / CH- | move | previous / next channel |
| Back | back to groups, clear search, then exit | back to the list |

Remotes without a Menu key are fully supported. Everything on the Menu is also in the
long-press OK menu, and Record and Sleep have their own buttons on screen.

In multiview the D-pad moves between screens, OK opens the focused screen's options, CH+
and CH- change its channel, and Back returns to the single player with that channel.

## Supported devices

Built against standard Android TV APIs, with no Google Play Services dependency and no
native libraries, so it runs on Shield TV, Chromecast with Google TV, Sony, TCL, Hisense,
Philips, Xiaomi Mi Box, Amazon Fire TV and the various Amlogic boxes.

- Android 6.0 (API 23) and newer
- Works on Fire TV, which has no Play Services: nothing here depends on it
- Phones and tablets are not supported yet. The app requires a TV (leanback) device and the
  layout assumes a large screen

## Known limits

- Recording captures the stream as it arrives. If your provider doesn't allow more than one
  connection, recording while you watch something else will fail, same as any other player
- Whether catch-up works depends entirely on the provider. Plenty don't support it at all
- Very old or unusual providers (some Stalker portals) may need the stream sent to VLC

## Building

Requires JDK 17+ and the Android SDK with compileSdk 36.

```
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew test              # parser and logic tests, run on the JVM
```

Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Release builds read the signing key from `local.properties`, which is not committed:

```
tellybox.storeFile=/path/to/keystore.jks
tellybox.storePassword=...
tellybox.keyAlias=...
tellybox.keyPassword=...
```

Without those entries the release build still runs, just unsigned.

## How it's put together

```
app/src/main/java/com/tellybox/tv/
  data/db/           Room entities and DAOs
  data/parser/       M3U, XMLTV, Xtream Codes and Stalker clients, no Android deps,
                     unit-tested on the JVM
  data/repository/   import and refresh logic tying the parsers to the database
  player/            Media3 player setup and the playback service
  ui/                activities: channel list, guide, VOD, playlists, player, multiview,
                     recordings, settings
```

Favourites and watch history are keyed on a stable per-channel key (provider stream id or
URL) rather than the database row id, so they survive a playlist refresh. Guide programmes
are matched to channels on the `tvg-id` the playlist gives them.

## Licence

Apache 2.0, see [LICENSE](LICENSE).

Tellybox is not affiliated with any playlist provider, IPTV service or TV manufacturer.
