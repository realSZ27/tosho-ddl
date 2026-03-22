package dev.ddlproxy.model

interface DownloadSource {
    val name: String

    suspend fun search(query: String): List<Release>
    suspend fun getRecent(): List<Release>
    fun getLinks(identifier: String): List<LinkGroup>

    fun getHostPriority(host: String): Int = Int.MAX_VALUE
}
