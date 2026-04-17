package dev.ddlproxy.model

import dev.ddlproxy.AppConfig
import java.time.Instant

data class LinkGroup(
    val host: String,
    val links: List<String>
)

data class Release(
    val title: String,
    val source: AppConfig.Source,
    val webpageLink: String? = null,
    // a string that uniquely indentifies a release
    // it is 100% up to the source to decide what to do with it 
    val identifier: String,
    val pubDate: Instant = Instant.now(),
    val fileSize: Long? = null // in bytes
)