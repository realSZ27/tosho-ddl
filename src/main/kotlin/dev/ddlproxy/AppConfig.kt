package dev.ddlproxy

import dev.ddlproxy.model.DownloadSource
import dev.ddlproxy.service.JDownloaderController
import dev.ddlproxy.sources.AnimeToshoNew.AnimeToshoNewSource
import dev.ddlproxy.sources.KayoAnime.KayoAnimeSource
import dev.ddlproxy.sources.TokyoInsider.TokyoInsiderSource
import dev.ddlproxy.sources.TsukiHime.TsukiHimeSource
import io.ktor.client.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

@Configuration
@EnableConfigurationProperties(AppConfig.AppProperties::class)
class AppConfig {

    @ConfigurationProperties(prefix = "app")
    data class AppProperties(
        val sources: Map<Source, Map<String, Any>> = emptyMap(),
        val cleanupEnabled: Boolean = true
    )

    @Bean
    fun restTemplate() = RestTemplate()

    @Bean
    fun httpClient() = HttpClient {
        expectSuccess = true
    }

    @Bean
    fun objectMapper() = jacksonObjectMapper()

    @Bean
    fun downloadSources(
        sources: List<DownloadSource>
    ): List<DownloadSource> {
        return sources
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "app.sources.tokyoInsider",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true
    )
    fun tokyoInsiderSource(
        client: HttpClient,
        jDownloaderController: JDownloaderController
    ) = TokyoInsiderSource(
        client,
        jDownloaderController
    )

    @Bean
    @ConditionalOnProperty(
        prefix = "app.sources.kayoAnime",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true
    )
    fun kayoAnimeSource(
        client: HttpClient,
        jDownloaderController: JDownloaderController
    ) = KayoAnimeSource(
        client,
        jDownloaderController
    )

    @Bean
    @ConditionalOnProperty(
        prefix = "app.sources.animeToshoNew",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true
    )
    fun animeToshoNewSource(
        client: HttpClient,
        objectMapper: ObjectMapper,
        jDownloaderController: JDownloaderController
    ) = AnimeToshoNewSource(
        client,
        objectMapper,
        jDownloaderController
    )

    @Bean
    @ConditionalOnProperty(
        prefix = "app.sources.tsukiHime",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true
    )
    fun tsukiHimeSource(
        client: HttpClient,
        objectMapper: ObjectMapper,
        jDownloaderController: JDownloaderController
    ) = TsukiHimeSource(
        client,
        objectMapper,
        jDownloaderController
    )

    enum class Source {
        AnimeToshoNew,
        TokyoInsider,
        KayoAnime,
        TsukiHime,
    }
}