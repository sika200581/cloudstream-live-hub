# CloudStream Twitch Discover

Improved Twitch **Live** extension for CloudStream 3 — denser homepage and better discoverability than the stock one-row provider.

## What’s better

- Homepage sections: worldwide + selectable language rows (plugin settings)
- Category directory with live stream episodes and thumbnails
- Card / episode titles include viewer counts (`Name · 37.8K`)
- Channel pages (TvSeries): Live episode + recent VODs/clips with thumbnails
- Search covers channels and matching game categories

Forked from the official [recloudstream/extensions](https://github.com/recloudstream/extensions) `TwitchProvider` (CranberrySoup).

## Install in CloudStream

Repository URL (v9):

`https://raw.githubusercontent.com/sika200581/cloudstream-twitch-extension/main/repo-v9.json`

Or latest pointer:

`https://raw.githubusercontent.com/sika200581/cloudstream-twitch-extension/main/repo.json`

Then install **TwitchDiscover** (shown as **Twitch Discover**).

## Build

```bash
./gradlew :TwitchDiscover:make
```

Requires JDK 17 + Android SDK. Android-only plugin (`isCrossPlatform = false`) so settings (AlertDialog) work.

## Notes

- Live playback via Twitch extractor (`twitch.tv` → m3u8); VOD/clip playback depends on the same extractor/API
- Browse data via Twitch GQL; channel search via [twitchtracker.com](https://twitchtracker.com)
