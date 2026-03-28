package dev.ddlproxy

import dev.ddlproxy.model.DownloadSource
import dev.ddlproxy.sources.AnimeTosho.AnimeToshoSource
import dev.ddlproxy.sources.TokyoInsider.TokyoInsiderSource
import io.ktor.client.HttpClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate
import tools.jackson.databind.ObjectMapper

@Configuration
class AppConfig {

    @Bean
    fun restTemplate() = RestTemplate()

    @Bean
    fun httpClient() = HttpClient { 
        expectSuccess = true
    }

    @Bean 
    fun objectMapper() = ObjectMapper()

    @Bean
    fun downloadSources(client: HttpClient, objectMapper: ObjectMapper): List<DownloadSource> = listOf(
        AnimeToshoSource(client, objectMapper),
        TokyoInsiderSource(client),
    )

    enum class Source {
        AnimeTosho,
        TokyoInsider,
    }
}