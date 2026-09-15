version = 1

cloudstream {
    description = "All-in-one live discover: Twitch + Kick + YouTube Live + Rumble"
    authors = listOf("sika200581")

    status = 1

    tvTypes = listOf("Live", "TvSeries", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=twitch.tv&sz=%size%"

    // Android Context/SharedPreferences — not cross-platform JAR
    isCrossPlatform = false
}

dependencies {
    compileOnly(project(":stubslib"))
}
