version = 1

cloudstream {
    description = "YouTube Live Discover - live streams only"
    authors = listOf("sika200581")

    status = 1

    tvTypes = listOf("Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=youtube.com&sz=%size%"

    // Android-only so plugin settings (AlertDialog) are available
    isCrossPlatform = false
}

dependencies {
    compileOnly(project(":stubslib"))
}
