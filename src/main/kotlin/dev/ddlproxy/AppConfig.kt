package dev.ddlproxy

import dev.ddlproxy.model.DownloadSource
import dev.ddlproxy.sources.AnimeTosho.AnimeToshoSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate

@Configuration
class AppConfig {

    @Bean
    fun restTemplate() = RestTemplate()

    @Bean
    fun downloadSources(): List<DownloadSource> = listOf(
        AnimeToshoSource()
    )
}