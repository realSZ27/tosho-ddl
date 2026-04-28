package dev.ddlproxy

import dev.ddlproxy.model.DownloadSource
import dev.ddlproxy.service.JDownloaderController
import dev.ddlproxy.sources.AnimeTosho.AnimeToshoSource
import dev.ddlproxy.sources.KayoAnime.KayoAnimeSource
import dev.ddlproxy.sources.TokyoInsider.TokyoInsiderSource
import io.ktor.client.HttpClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate
import tools.jackson.databind.ObjectMapper

@Configuration
@EnableConfigurationProperties(AppConfig.AppProperties::class)
class AppConfig {

    @ConfigurationProperties(prefix = "app")
    data class AppProperties(
        val sources: Map<Source, Map<String, Any>> = emptyMap()
    )

    @Bean
    fun restTemplate() = RestTemplate()

    @Bean
    fun httpClient() = HttpClient {
        expectSuccess = true
    }

    @Bean
    fun objectMapper() = ObjectMapper()

    @Bean
    fun downloadSources(
        props: AppProperties,
        sources: List<DownloadSource>
    ): List<DownloadSource> {
        return sources
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "app.sources.animeTosho",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true
    )
    fun animeToshoSource(
        client: HttpClient,
        objectMapper: ObjectMapper,
        jDownloaderController: JDownloaderController
    ) = AnimeToshoSource(
        client,
        objectMapper,
        jDownloaderController
    )

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

    enum class Source {
        AnimeTosho,
        TokyoInsider,
        KayoAnime,
    }
}