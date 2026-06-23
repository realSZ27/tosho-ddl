package dev.ddlproxy.model

import dev.ddlproxy.AppConfig
import java.time.Instant

/**
 * Download links for a single release from a specific host.
 *
 * A release may consist of multiple files (for example, split archives),
 * in which case all links in [links] are required.
 *
 * @property host Human-readable name of the hosting provider.
 * @property links URLs required to download the release.
 */
data class LinkGroup(
    val host: String,
    val links: List<String>
)

/**
 * Metadata describing a release discovered by a [DownloadSource].
 *
 * The [identifier] is opaque to consumers. Its format is entirely
 * source-specific and is only meaningful to the source that created it.
 * It is later passed back to that source's `download` function to
 * retrieve download links.
 *
 * @property title Display title of the release.
 * @property source Source that discovered the release.
 * @property webpageLink Optional URL to the release's webpage.
 * @property identifier Source-specific identifier used in [DownloadSource.download].
 * @property pubDate Publication date of the release.
 * @property fileSize Size of the release in bytes, if known.
 */
data class Release(
    val title: String,
    val source: AppConfig.Source,
    val webpageLink: String? = null,
    val identifier: String, 
    val pubDate: Instant = Instant.now(),
    val fileSize: Long? = null
)