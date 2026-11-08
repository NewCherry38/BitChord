package com.music.bitchord.wear

import android.content.Context

/** No-op stand-in: the TV build has no watch and no hotspot remote. */
object HotspotLink {
    @Suppress("UNUSED_PARAMETER")
    fun start(context: Context) = Unit

    fun pushState() = Unit
}
