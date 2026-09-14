# CloudStream Twitch, Kick & YouTube Live Discover

Improved **Live** extensions for CloudStream 3 — denser homepage and better discoverability.

## Plugins

### TwitchDiscover
- Homepage sections: worldwide + selectable language rows (plugin settings)
- Category directory with live stream episodes and thumbnails
- Card / episode titles include viewer counts (`Name · 37.8K`)
- Channel pages (TvSeries): Live episode + recent VODs/clips with thumbnails
- Search covers channels and matching game categories

Forked from the official [recloudstream/extensions](https://github.com/recloudstream/extensions) `TwitchProvider` (CranberrySoup).

### KickDiscover
- Homepage: category directory + top live worldwide + language rows (client-side filter; Kick’s lang path does not filter reliably)
- Live preview thumbnails + viewer counts on cards
- Category pages as TvSeries with live stream episodes
- Channel pages as TvSeries: Live + recent VODs/clips
- Search by channel / category (Kick search, ≥3 characters)
- Live playback via Kick `playback_url` HLS (`.m3u8` on live-video.net)
- Inspired by TwitchDiscover patterns in this repo (`sika200581`)

### YouTubeLiveDiscover
- **Live streams only** (no VODs, uploads, playlists, or scheduled upcoming)
- Homepage: Live trending (Innertube browse) + Gaming/Music/News/Sports live rows (search + live filter)
- Cards: title, channel, viewer count, thumbnail
- Search: live-only via Innertube `params=EgJAAQ==`
- Playback via CloudStream `loadExtractor` on `youtube.com/watch?v=…`
- Optional region (`gl`) in plugin settings
- Does **not** use public Invidious instances

## Install in CloudStream

Repository URL (v11 — TwitchDiscover + KickDiscover + YouTubeLiveDiscover):

`https://raw.githubusercontent.com/sika200581/cloudstream-twitch-extension/main/repo-v11.json`

Or latest pointer:

`https://raw.githubusercontent.com/sika200581/cloudstream-twitch-extension/main/repo.json`

Then install **TwitchDiscover**, **KickDiscover**, and/or **YouTubeLiveDiscover**.

## Build

```bash
./gradlew :TwitchDiscover:make
./gradlew :KickDiscover:make
./gradlew :YouTubeLiveDiscover:make
```

Requires JDK 17 + Android SDK. Plugins with settings are Android-only (`isCrossPlatform = false`) so AlertDialog settings work.

## Notes / limitations

- **Kick Cloudflare**: intermittent 403s possible; plugins send browser-like headers. Optional session cookie/token settings may be added later — not required for v1.
- **Kick language rows**: API `/stream/livestreams/{lang}` returns the same worldwide list; language rows filter client-side by the `language` field (best-effort).
- **Kick VODs/clips**: listed on channel pages when APIs return them; playback uses VOD `source` / clip `clip_url` HLS.
- Twitch live playback via Twitch extractor (`twitch.tv` → m3u8); VOD/clip playback depends on the same extractor/API.
- **YouTube Live**: discovery via Innertube (no login). Playback depends on CloudStream’s built-in YouTube extractor / NewPipe; extractor breakage on the host app affects play. Region (`gl`) affects trending/search mix. Upcoming scheduled lives are skipped. Channel live tabs are not a separate UX in v1 (search `channel + live` / watch URLs only).
