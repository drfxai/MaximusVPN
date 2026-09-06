package com.example.browser

import android.net.Uri

object TrackerBlocker {

    private val BLOCKED_DOMAINS = setOf(
        "google-analytics.com",
        "googletagmanager.com",
        "doubleclick.net",
        "googleadservices.com",
        "facebook.net",
        "facebook.com/tr",
        "criteo.com",
        "criteo.net",
        "scorecardresearch.com",
        "hotjar.com",
        "taboola.com",
        "outbrain.com",
        "adnxs.com",
        "ads.twitter.com",
        "analytics.tiktok.com",
        "telemetry",
        "statcounter.com",
        "yandex.ru/metrika",
        "amplitude.com",
        "mixpanel.com",
        "appsflyer.com",
        "branch.io",
        "adjust.com",
        "segment.io",
        "segment.com",
        "optimizely.com",
        "fullstory.com",
        "mouseflow.com",
        "newrelic.com",
        "sentry.io",
        "chartbeat.com",
        "quantserve.com",
        "bugsnag.com",
        "intercom.io"
    )

    fun isBlocked(url: String): Boolean {
        try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false

            return BLOCKED_DOMAINS.any { blocked ->
                host == blocked || host.endsWith(".$blocked") || host.contains(blocked) || url.contains(blocked)
            }
        } catch (_: Exception) {
            return false
        }
    }
}
