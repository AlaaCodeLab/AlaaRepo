package com.cinemabox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinemaBoxPlugin : Plugin() {
    override fun load(context: Context) {
        // تسجيل إضافة سينما بوكس
        registerMainAPI(CinemaBoxProvider())
    }
}
