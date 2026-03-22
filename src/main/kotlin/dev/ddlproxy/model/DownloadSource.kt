package dev.ddlproxy.model

import dev.ddlproxy.AppConfig

interface DownloadSource {
    val name: AppConfig.Source

    suspend fun search(query: String): List<Release>
    suspend fun getRecent(): List<Release>
    fun getLinks(identifier: String): List<LinkGroup>

    fun getHostPriority(host: String): Int = Int.MAX_VALUE
}
