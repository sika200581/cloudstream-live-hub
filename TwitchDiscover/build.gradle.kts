version = 8

cloudstream {
    description = "Twitch Discover - category episodes, language settings"
    authors = listOf("CranberrySoup", "sika200581")

    status = 1

    tvTypes = listOf("Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=twitch.tv&sz=%size%"

    // Android-only so plugin settings (AlertDialog) are available
    isCrossPlatform = false
}

dependencies {
    compileOnly(project(":stubslib"))
}
