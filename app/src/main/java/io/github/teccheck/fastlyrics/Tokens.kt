package io.github.teccheck.fastlyrics

/**
 * API keys are loaded at build time from local.properties (not committed to VCS).
 * See local.properties.example for the required keys.
 */
object Tokens {
    private fun getBuildConfigString(name: String): String = try {
        BuildConfig::class.java.getField(name).get(null) as? String ?: ""
    } catch (_: Exception) {
        ""
    }

    val GENIUS_API: String get() = getBuildConfigString("GENIUS_API_KEY")
    val PETIT_LYRICS_API: String get() = getBuildConfigString("PETIT_LYRICS_API_KEY")
}
