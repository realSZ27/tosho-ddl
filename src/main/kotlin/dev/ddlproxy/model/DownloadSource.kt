package dev.ddlproxy.model

import dev.ddlproxy.AppConfig

/**
 * A source capable of discovering and downloading releases.
 *
 * Implementations are responsible for searching, listing recent releases,
 * and resolving source-specific release identifiers into download links.
 *
 * The format of [Release.identifier] is entirely implementation-defined.
 * It is passed to [download] when a release from that source is downloaded.
 *
 * You must register your [DownloadSource] in [AppConfig] as a bean to integrate
 * it with the rest of the application.
 */
interface DownloadSource {

    /**
     * Configuration identifier for this source.
     */
    val name: AppConfig.Source

    /**
     * Searches for releases matching the provided criteria.
     *
     * @param query Search query.
     * @param season Optional season number for episodic content.
     * @param episode Optional episode number for episodic content.
     * @return Matching releases.
     */
    suspend fun search(
        query: String,
        season: Int? = null,
        episode: Int? = null
    ): List<Release>

    /**
     * Returns recently published releases.
     *
     * @return Recent releases.
     */
    suspend fun getRecent(): List<Release>

    /**
     * Downloads the release identified by [identifier].
     *
     * @param identifier Source-specific release identifier. Same as [Release.identifier].
     */
    suspend fun download(identifier: String)
}