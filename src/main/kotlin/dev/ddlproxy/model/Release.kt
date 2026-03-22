package dev.ddlproxy.model

import kotlin.time.Instant

data class LinkGroup(
    val host: String,
    val links: List<String>
)

data class Release(
    val title: String,
    val source: String,
    val webpageLink: String,
    val identifier: String,
    val pubDate: Instant,
)