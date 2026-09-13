# CloudStream Twitch Extension

CloudStream 3 plugin for **Twitch livestreams** (`TvType.Live`). Browse top streams and games, search channels, and play live m3u8 qualities.

Based on the official [recloudstream/extensions](https://github.com/recloudstream/extensions) `TwitchProvider` (author: CranberrySoup).

## Features

- Homepage: top global live streams + top games
- Search channels via TwitchTracker scrape
- Live stream playback through a Twitch extractor

## Build

Requirements: JDK 17+, Android SDK (for the CloudStream gradle plugin).

```bash
./gradlew TwitchProvider:make
```

Output plugin artifacts land under `TwitchProvider/build/` (and typically a `.cs3` / plugin zip used by CloudStream).

Optional ADB deploy to a device with CloudStream installed:

```bash
./gradlew TwitchProvider:deployWithAdb
```

## Install in CloudStream

**Local build**

1. Build with `./gradlew TwitchProvider:make`
2. Copy the generated plugin into CloudStream (Extensions → install from file / local), or use `deployWithAdb`

**Repository URL** (after you publish a `builds/plugins.json` via CI)

Add this raw `repo.json` URL in CloudStream → Extensions → Add repository:

`https://cdn.jsdelivr.net/gh/sika200581/cloudstream-twitch-extension@main/repo.json`

## Notes

- Live Twitch only — not movies/TV VODs as catalog content
- Catalog scrape uses [twitchtracker.com](https://twitchtracker.com); playback resolves `twitch.tv` URLs
- If streams fail to load, the upstream stream-resolver endpoint may need updating in `TwitchProvider.kt` (`TwitchExtractor`)

## License

Follows the upstream CloudStream extensions project licensing for the provider code adapted here.
