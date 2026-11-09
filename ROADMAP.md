# Roadmap

Not a schedule. This is roughly the order things get looked at, and it moves when a bug report
matters more.

## Next

- **Phones and tablets.** Touch on the guide and the channel list, then layouts that suit a
  finger rather than a remote. Until this lands the app runs on a phone but the channel list
  needs a remote or the on-screen D-pad.
- **Stalker portals that mix movies and series.** Some portals return both in one response with
  little to tell them apart, so the lists are read as one kind or empty. Needs the item flags and
  a name-based fallback.
- **Catch-up formats.** The common M3U `catchup-source` templates and Xtream timeshift are
  handled. Portals with their own URL shapes still need adding, one at a time, from real
  examples.

## Later

- **Update check.** On launch, ask the releases page whether a newer build exists and offer it.
  Download from inside the app, then hand the file to Android's installer, which shows its own
  prompt. Silent installing is not possible for a sideloaded app.
- **Network shares.** SMB or NFS as a playlist source, so recordings and playlists can live on a
  NAS.
- **Timeshift on low storage.** A cheaper buffer for boxes with little free space, and a clearer
  warning before it gives up.
- **Search inside the guide**, not just for channels.
- **Frame rate switching** was built, tested and pulled back out: it black-screened on a real
  switch and most channels are 30 fps where switching does nothing. Worth another look only with
  a box that handles the switch cleanly.

## Not planned

- Channels, playlists or subscriptions of any kind. You bring your own.
- Accounts, logins or anything that needs a server of ours.
- Analytics or telemetry. The app talks to your provider and GitHub, nothing else.
