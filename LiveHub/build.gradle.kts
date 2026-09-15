version = 4

cloudstream {
    description = "All top live streams in one place — Twitch, Kick, YouTube Live, Rumble"
    authors = listOf("sika200581")

    status = 1

    tvTypes = listOf("Live", "TvSeries", "Movie")
    iconUrl = "https://raw.githubusercontent.com/sika200581/cloudstream-live-hub/main/assets/icon-live-hub.png"

    // Android Context/SharedPreferences — not cross-platform JAR
    isCrossPlatform = false
}

dependencies {
    compileOnly(project(":stubslib"))
}
