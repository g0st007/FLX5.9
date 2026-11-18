package app.flux.downloader.browser

import android.content.Context

class BrowserPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("browser", Context.MODE_PRIVATE)

    var blockAds: Boolean
        get() = sp.getBoolean("block_ads", true)
        set(v) = sp.edit().putBoolean("block_ads", v).apply()

    var blockRedirects: Boolean
        get() = sp.getBoolean("block_redirects", true)
        set(v) = sp.edit().putBoolean("block_redirects", v).apply()

    var desktopMode: Boolean
        get() = sp.getBoolean("desktop_mode", false)
        set(v) = sp.edit().putBoolean("desktop_mode", v).apply()

    /** Dominios en los que el usuario eligió "sitio de escritorio" (se recuerda por sitio). */
    val desktopSites: Set<String>
        get() = sp.getStringSet("desktop_sites", emptySet())?.toSet() ?: emptySet()

    /** Dominios en los que el usuario eligió "móvil" (para cuando el modo por defecto es escritorio). */
    val mobileSites: Set<String>
        get() = sp.getStringSet("mobile_sites", emptySet())?.toSet() ?: emptySet()

    /** Recuerda la elección del usuario para un sitio. */
    fun setSiteMode(domain: String, desktop: Boolean) {
        val d = desktopSites.toMutableSet()
        val m = mobileSites.toMutableSet()
        if (desktop) { d.add(domain); m.remove(domain) } else { m.add(domain); d.remove(domain) }
        sp.edit().putStringSet("desktop_sites", d).putStringSet("mobile_sites", m).apply()
    }

    var totalBlocked: Long
        get() = sp.getLong("total_blocked", 0L)
        set(v) = sp.edit().putLong("total_blocked", v).apply()

    /** Sitios en los que el usuario pausó la protección. */
    val allowedSites: Set<String>
        get() = sp.getStringSet("allowed_sites", emptySet())?.toSet() ?: emptySet()

    fun setSiteProtected(host: String, protectedOn: Boolean) {
        val cur = allowedSites.toMutableSet()
        if (protectedOn) cur.removeAll { host == it || host.endsWith(".$it") } else cur.add(host)
        sp.edit().putStringSet("allowed_sites", cur).apply()
    }
}
