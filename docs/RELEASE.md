# Before tagging a release

Every bug that reached users got through the same way: it lived in a path nobody had run since
the change. Use this before a tag, and add to it whenever something slips through.

## Always

1. `./gradlew test assembleRelease --rerun-tasks` on a clean tree. Green, not "green because it
   was up to date".
2. Install the **release** APK on a device or emulator. R8 is where the surprises live, so a
   debug build doesn't count.
3. Open the app, play a channel, open the guide, back out. If that fails, stop there.
4. Read the diff since the last tag for anything touching an import, storage or the player. Those
   three break quietly.

## The paths that have actually broken

- **Stalker portals.** A fake that echoes back whatever it is given proves nothing: the film
  link test passed against one while every real film on a Ministra portal came back 403, because
  the portal only signs `/media/file_<id>.mpg` and refuses the bare storage URL. Answer like the
  portal does, including the refusals. Use `tools/fake-portal.py`. Add a
  playlist pointing at it, confirm channels appear in seconds, confirm the films arrive after,
  delete the playlist mid-import and confirm the app doesn't hang.
- **Backup and restore.** Export, wipe app data, restore, check channels and the parental PIN.
  Android 6 to 9 needs the storage permission prompt, which an emulator at API 28 covers.
- **Catch-up.** Needs an archive URL that really returns a past programme. If the provider has
  none, run it against a local HLS file with a `catchup-source` template.
- **Recording.** An instant record, plus one scheduled from the guide. Scheduled has been the
  weaker path.
- **Refresh after a partial import.** Kill the network mid-refresh, let it fail, then check the
  playlist still has its channels.

## Judgement

- Fix-only releases and feature releases don't ride together. A fix ships small and boring.
- No release with a provider path that has never been run once, unless the notes say so plainly.
- A second pair of eyes on the diff catches what the tests don't. Ask for one before a tag, not
  after a bug report.
