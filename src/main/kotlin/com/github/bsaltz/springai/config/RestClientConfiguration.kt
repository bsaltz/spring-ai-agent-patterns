package com.github.bsaltz.springai.config

import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestClient

@Configuration
class RestClientConfiguration {
    @Bean
    fun restClientBuilder(restClientBuilderConfigurer: RestClientBuilderConfigurer): RestClient.Builder =
        restClientBuilderConfigurer.configure(RestClient.builder()).messageConverters { converters ->
            converters.filterIsInstance<MappingJackson2HttpMessageConverter>().forEach { converter ->
                // This is a workaround for Ollama cloud returning text/plain instead of application/x-ndjson
                converter.supportedMediaTypes = converter.supportedMediaTypes +
                    listOf(
                        MediaType.TEXT_PLAIN,
                    )
            }
        }
}
