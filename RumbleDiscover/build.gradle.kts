version = 5

cloudstream {
    description = "Rumble Discover - Live now via fast JSON feed"
    authors = listOf("sika200581")

    status = 1

    tvTypes = listOf("Live", "Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=rumble.com&sz=%size%"

    // Android Plugin Context import — not cross-platform JAR
    isCrossPlatform = false
}

dependencies {
    compileOnly(project(":stubslib"))
}
