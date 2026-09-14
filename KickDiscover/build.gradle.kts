version = 1

cloudstream {
    description = "Kick Discover - live streams, categories, channel pages (Live+VOD+clips)"
    authors = listOf("sika200581")

    status = 1

    tvTypes = listOf("Live", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=kick.com&sz=%size%"

    // Android-only so plugin settings (AlertDialog) are available
    isCrossPlatform = false
}

dependencies {
    compileOnly(project(":stubslib"))
}
