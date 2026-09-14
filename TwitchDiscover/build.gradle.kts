version = 9

cloudstream {
    description = "Twitch Discover - channel pages (Live+VOD+clips), viewer counts"
    authors = listOf("CranberrySoup", "sika200581")

    status = 1

    tvTypes = listOf("Live", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=twitch.tv&sz=%size%"

    // Android-only so plugin settings (AlertDialog) are available
    isCrossPlatform = false
}

dependencies {
    compileOnly(project(":stubslib"))
}
