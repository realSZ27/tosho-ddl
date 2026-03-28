package dev.ddlproxy.model

import dev.ddlproxy.AppConfig

interface DownloadSource {
    val name: AppConfig.Source

    suspend fun search(query: String, season: Int? = null, episode: Int? = null): List<Release>
    suspend fun getRecent(): List<Release>
    suspend fun download(identifier: String)
}
