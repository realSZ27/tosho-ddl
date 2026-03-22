package dev.ddlproxy.model

import dev.ddlproxy.AppConfig
import kotlin.time.Clock
import kotlin.time.Instant

data class LinkGroup(
    val host: String,
    val links: List<String>
)

data class Release(
    val title: String,
    val source: AppConfig.Source,
    val webpageLink: String? = null,
    val identifier: String,
    val pubDate: Instant = Clock.System.now(),
    val fileSize: Long? = null
)