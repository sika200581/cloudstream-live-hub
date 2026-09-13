// Use an integer for version numbers
version = 2

cloudstream {
    description = "Watch livestreams from Twitch"
    authors = listOf("CranberrySoup", "sika200581")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf("Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=twitch.tv&sz=%size%"

    isCrossPlatform = true
}
