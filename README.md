# CloudStream Live Hub

**Live Hub** — all top live streams in one place for [CloudStream 3](https://github.com/recloudstream/cloudstream). Flagship aggregator plus focused single-platform Discover plugins for Twitch, Kick, YouTube Live, and Rumble.

Legitimate live platforms only.

## Install

Primary (cache-bust) repository URL:

```
https://raw.githubusercontent.com/sika200581/cloudstream-live-hub/main/repo-v21.json
```

Or the stable pointer:

```
https://raw.githubusercontent.com/sika200581/cloudstream-live-hub/main/repo.json
```

In CloudStream: **Extensions → Add repository** → paste one of the URLs above → install **Live Hub** and/or the Discover plugins you want.

## Plugins

| Plugin | Role |
|--------|------|
| **Live Hub** | Flagship aggregator — Twitch, Kick, YouTube Live & Rumble top streams on one homepage |
| **Twitch Discover** | Twitch live discover — top streams, categories, channel pages |
| **Kick Discover** | Kick live discover — top streams, categories, channel pages |
| **YouTube Live Discover** | YouTube Live discover — live streams only |
| **Rumble Discover** | Rumble live discover — Live now via fast JSON feed |

`internalName` values (`LiveHub`, `TwitchDiscover`, …) are unchanged so existing installs keep updating.

### Live Hub

- One CloudStream source named **Live Hub**
- Homepage rows: **Twitch · Top worldwide** · **Kick · Top worldwide** · **YouTube · Top live** · **Rumble · Top live**
- Playback routed inside Live Hub (standalones optional)

### Twitch Discover

- Homepage: worldwide + selectable language rows (plugin settings)
- Categories, channel pages (Live + VOD + clips), viewer counts
- Forked from the official [recloudstream/extensions](https://github.com/recloudstream/extensions) `TwitchProvider` (**CranberrySoup**)

### Kick Discover

- Homepage: category directory + top live + language rows
- Channel pages: Live + recent VODs/clips; HLS playback via Kick `playback_url`

### YouTube Live Discover

- **Live streams only** (no VODs / uploads / playlists / upcoming)
- Innertube browse + live-filtered search; playback via CloudStream `loadExtractor`

### Rumble Discover

- **Live now** via `rumble-live.json` feed / browse resolve
- Embed/HLS playback; Cloudflare-friendly headers

## Build

```bash
./gradlew :LiveHub:make
./gradlew :TwitchDiscover:make
./gradlew :KickDiscover:make
./gradlew :YouTubeLiveDiscover:make
./gradlew :RumbleDiscover:make
```

Requires JDK 17 + Android SDK. Plugins with settings are Android-only (`isCrossPlatform = false`).

## Credits

- Twitch base provider: **CranberrySoup** / [recloudstream/extensions](https://github.com/recloudstream/extensions)
- Live Hub + Kick / YouTube Live / Rumble Discover: **sika200581**

## Notes

- Kick / Rumble may hit intermittent Cloudflare 403s on some networks; real devices are usually fine.
- YouTube Live playback depends on CloudStream’s built-in YouTube extractor.
- See plugin settings where available (language rows, YouTube region `gl`, etc.).
