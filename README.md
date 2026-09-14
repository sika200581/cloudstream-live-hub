# CloudStream Twitch Discover

Improved Twitch **Live** extension for CloudStream 3 — denser homepage and better discoverability than the stock one-row provider.

## What’s better

- Homepage sections: worldwide, English / Arabic / Spanish / Portuguese / French / German
- Top games expanded into their own rows (up to 10 categories)
- Card titles include viewer counts (`Name · 37.8K`)
- Channel pages show game, language, viewers, rank as tags
- Search covers channels and matching game categories

Forked from the official [recloudstream/extensions](https://github.com/recloudstream/extensions) `TwitchProvider` (CranberrySoup).

## Install in CloudStream

Repository URL:

`https://raw.githubusercontent.com/sika200581/cloudstream-twitch-extension/main/repo.json`

Then install **TwitchProvider** (shown as **Twitch Discover**).

## Build

```bash
./gradlew TwitchProvider:make
```

Requires JDK 17 + Android SDK. CI workflow needs a GitHub token with `workflow` scope if you want automated `builds/` publishing.

## Notes

- Live Twitch only
- Catalog data via [twitchtracker.com](https://twitchtracker.com)
- Playback still uses the Twitch extractor (`twitch.tv` → m3u8)
