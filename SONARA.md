# Sonara - what is in this repo

Fork of SimpMusic (upstream commit 0bc02c6) with the `core` submodule vendored in, so this is one
self-contained repo. Everything new is in commit `dcdbff6`; `git show dcdbff6 --stat` lists it.

## HONEST STATUS

* **Verified (ran here):** the pure logic - mix planner, fade curves, peak fusion, drop-start maths,
  bass-shelf and limiter DSP, mix builder - 39 unit tests pass on the JVM.
* **Verified on a real phone (by the owner, build 1):** Mix-on-Next, Drop Mode, Settings, login, likes.
* **NOT verified:** the Android/Gradle build. The sandbox that wrote this could not reach Google's
  Maven repository, so nothing Android-side has been compiled or run. Expect a few compile errors on the
  first CI run; the workflow log will name the file and line. Fix those first, before anything else.
* **Not built yet:** screens for "Create your own Mix" and "New to you" (logic and DI exist), the EQ
  visual upgrade, on-device audio analysis fallback for Drop Mode, lyrics-coverage work, the redesigned
  UI. See "Not done".

## Build (phone only)

1. Create a private GitHub repo, push this folder to `main`.
2. Actions tab -> "Sonara - test and build APK" -> Run workflow.
3. When green, open the repo's Releases page on your phone, download the arm64 APK, install.
4. Log in to YouTube Music from Settings -> YouTube Account.

## What was added

### Mix-on-Next (Settings -> Crossfade -> "Mix when I press Next")
`CrossfadeExoPlayerAdapter.seekToNext` no longer hard-cuts. It asks `MixPlanner.decideNext`
(domain/sonara/MixPlanner.kt) and, when allowed, starts the existing dual-player blend from where the
current song is, for the length set in "Crossfade duration" (now 1-45 s plus Auto).
Rules, all unit-tested: never longer than the time left in the current song, never more than half the
incoming song, rapid taps (<2 s apart) shorten the blend to 60 % (floor 1.2 s), pressing Next during a
blend promotes the incoming song at the gain it has reached and blends again (no loudness jump, the
displaced song fades out over 180 ms), songs under 60 s / video / live / paused -> plain skip.
Needs Crossfade ON (it is the "auto-mix" master switch).

### Round 2: transition quality (from the owner's screen recording)
Measured from the recording: the old DJ sweep took the outgoing song through a low-pass down to 200 Hz
(only bass thump left) and put the incoming song through a 2 kHz high-pass (only hi-hats and hiss),
for the whole length of the mix - 30 s of muffled-then-tinny sound. Two full songs also overlapped at
full level for most of a 30 s blend.
* **Bass swap** replaces the sweep: both songs keep mids and highs; only the low end changes hands
  (incoming bass held back below 220 Hz, outgoing bass released at the same moment, mid-blend).
* **SMART blend curve** (new default, Settings -> Crossfade -> Blend curve): the loud two-song overlap is
  capped at about 12 s in the middle however long the mix is. Tested: for 15 s and 30 s mixes both songs
  are above 30 % volume for under 7 s (the old curve: over 15 s of a 30 s mix).
* **Drop lead-in text** now says plainly that the start is peak minus max(lead-in, mix length).
* **Not fixed / unknown:** short (0.3 s) silences appear in the recording at some mix boundaries.
  They may be the screen recorder re-attaching to a new audio stream, not the app; listen on the phone.
  Pressing Previous during a mix still restarts the song with a hard cut (upstream behaviour).

### Smoother fades
The old fade moved volume in 50 steps with `delay()` between them (a 15 s blend = 300 ms steps, audible
as stair-stepping, and it drifted). Now gains follow the wall clock every 16 ms (10 ms under 4 s),
constant-power curves (`MixCurve`: equal power, S-curve, linear, dramatic; setting `mixCurve` exists,
no UI yet). Tested: biggest gain step in a 15 s blend is under 1 %.

### Drop Mode (Settings -> under Mix-on-Next)
The incoming song starts `lead-in` seconds before its most-replayed moment instead of 0:00.
Source: YouTube's "Most replayed" graph, fetched from the WEB client (`Ytmusic.webNextRaw`), parsed by
`MostReplayedParser`, turned into a peak by `PeakFusion.fromHeatmap`, cached 30 days (misses 7 days) by
`PeakRepository`, throttled to 6 requests/minute, prefetched for the next two songs.
Safeguards: peak confidence < 0.35 -> start at 0:00; never start in the last 25 % or with < 30 s left;
if the incoming song cannot seek and buffer within 2.5 s the blend starts anyway.
Limits: many songs have no graph (too few views); the Song version often has none, so the Video
counterpart is tried; there is no manual "set start point" UI yet (`PeakProvider.setManualPeak` exists).

### One heart
`SongRepositoryImpl.updateLikeStatus` now also likes/unlikes on YouTube Music when signed in, with a
persisted retry outbox for failures (flushed on app start). The separate check-circle button is hidden
(`SHOW_SEPARATE_YOUTUBE_LIKE = false`); the star is the single like. A song already liked on YouTube
shows as liked here.
Not verified: "Save to playlist" - upstream already pushes to YouTube playlists when the playlist is
linked (`LocalPlaylistRepositoryImpl.addYouTubePlaylistItem`); I did not change or test that flow.

### Bass boost and volume boost (Settings -> Audio)
`BoostAudioProcessor`: low shelf at 110 Hz (up to +12 dB, with 35 % automatic headroom) then gain
(up to +12 dB) then a soft limiter that never exceeds full scale. Sits after the equalizer and effects,
before the sleep fade. Bypassed when both are 0.

### New to you / Create your own Mix (logic only)
`SonaraMixer` (domain) / `SonaraMixerImpl` (data), registered in Koin. `newToYou(seeds)` returns radio
songs you have never played, from artists you have not played (relaxes to <= 2 plays if too few), max
2 per artist, none back to back. `createMix(seeds, discover)` blends seed radios on a familiar<->new
slider. "Heard" = every song in the local songs table.

## Test checklist for the first working APK
1. Settings -> Crossfade ON, duration 15 s, Mix when I press Next ON. Play a song, press Next at 1:00:
   current fades out while the next fades in over 15 s, no volume jump, no click.
2. Press Next 3 times quickly: blends shorten, nothing clicks, nothing goes silent.
3. Turn Drop Mode ON, lead-in 8 s. Press Next on a well-known song: it should enter at its chorus/drop.
   Check logcat for `Drop Mode:` lines (peak, confidence, start).
4. Like a song with the star, open YouTube Music on another device: it is in Liked music.
5. Airplane mode, like a song, turn network on, restart the app: it syncs.
6. Bass boost +6 dB / Volume boost +6 dB with loud music: no crackle.

## Not done (next phases, in this order)
1. Fix whatever the first CI build reports.
2. Screens: Create Mix (pick artists/songs, familiar<->new slider, Start), New to you shelf and queue chip.
3. Manual peak nudge in the player; on-device analysis fallback.
4. Equalizer UI upgrade (graph already exists upstream: 10 bands, AutoEq, preamp, presets).
5. Lyrics coverage; visual redesign; quick listens.
