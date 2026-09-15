# CloudStream Twitch, Kick, YouTube Live & Rumble Discover

Improved **Live** extensions for CloudStream 3 — denser homepage and better discoverability (Twitch, Kick, YouTube Live, Rumble).

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


### RumbleDiscover
- Homepage: **Live now** from multi-page `/browse/live` live cards → **watch page → short embed id** (`v7db0j2`) → embedJS; keep effective live (`live == 2` or HLS contains `live-hls`); **dedupe by HLS URL**; sort by viewers when available
- Do **not** use numeric `data-video-id` as the primary embed key (often wrong VOD)
- Category rows: search live-badged cards with the same watch→short-id resolve; if search blocks, filter browse/live by title/author keywords (best-effort)
- Cards: `/__discover_video__/{shortId}` (not `/embed/`); title/channel/thumb from embedJS
- Playback: HLS from `embedJS/u3/?v={shortId}`; warm-up hits `/browse/live` so watch HTML returns 200


### LiveHub (aggregator)
- **One** CloudStream source named **Live Hub** (not four separate providers)
- Homepage rows: **Twitch · Top worldwide** · **Kick · Top worldwide** · **YouTube · Top live** · **Rumble · Top live** (sorted by viewer count)
- Opens cards via the matching platform provider for load/playback
- Registers Twitch + Kick extractors for HLS playback
- Standalone plugins (TwitchDiscover / KickDiscover / YouTubeLiveDiscover / RumbleDiscover) remain available

## Install in CloudStream

Repository URL (v19 — standalones + **LiveHub** aggregator; LiveDiscover removed):

`https://raw.githubusercontent.com/sika200581/cloudstream-twitch-extension/main/repo-v19.json`

Or latest pointer:

`https://raw.githubusercontent.com/sika200581/cloudstream-twitch-extension/main/repo.json`

Then install **LiveHub** for the single combined homepage, and/or the standalones (**TwitchDiscover**, **KickDiscover**, **YouTubeLiveDiscover**, **RumbleDiscover**).

## Build

```bash
./gradlew :TwitchDiscover:make
./gradlew :KickDiscover:make
./gradlew :YouTubeLiveDiscover:make
./gradlew :RumbleDiscover:make
./gradlew :LiveHub:make
```

Requires JDK 17 + Android SDK. Plugins with settings are Android-only (`isCrossPlatform = false`) so AlertDialog settings work.

## Notes / limitations

- **Kick Cloudflare**: intermittent 403s possible; plugins send browser-like headers. Optional session cookie/token settings may be added later — not required for v1.
- **Kick language rows**: API `/stream/livestreams/{lang}` returns the same worldwide list; language rows filter client-side by the `language` field (best-effort).
- **Kick VODs/clips**: listed on channel pages when APIs return them; playback uses VOD `source` / clip `clip_url` HLS.
- Twitch live playback via Twitch extractor (`twitch.tv` → m3u8); VOD/clip playback depends on the same extractor/API.
- **YouTube Live**: discovery via Innertube (no login). Playback depends on CloudStream’s built-in YouTube extractor / NewPipe; extractor breakage on the host app affects play. Region (`gl`) affects trending/search mix. Upcoming scheduled lives are skipped. Channel live tabs are not a separate UX in v1 (search `channel + live` / watch URLs only).
- **Rumble Cloudflare**: browse/search HTML may challenge datacenter IPs; plugins send browser-like headers and warm up `rumble.com` for cookies. Real devices usually fine.
- **Rumble HTML pairing**: page slug ↔ `data-video-id` pairing in listing HTML is unreliable — homepage prefers live-marked cards then resolves via embedJS. Page slugs often return `false` on embedJS; watch-page scrape is used only as a fallback to discover the real id. Never use embed JSON `vid` as the plugin id.
- **Rumble categories**: `/browse/live?category=` does not filter server-side; category homepage rows use search + live filter (best-effort). Channel pages are best-effort.
- **Rumble embedJS**: may flake under CF/rate limits on some networks; client retries with Origin/Referer. Homepage may still show fewer cards when many resolves fail.

