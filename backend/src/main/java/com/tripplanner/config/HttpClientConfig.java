package com.tripplanner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class HttpClientConfig {
    /** Shared pooled client. HTTP/2 is used when the server allows it, otherwise HTTP/1.1. */
    @Bean
    HttpClient tripHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    @Bean
    RestClient placesRestClient(HttpClient tripHttpClient) {
        return client(tripHttpClient, Duration.ofSeconds(8));
    }

    /** 외부 API(OpenAI, 카카오) 호출용. 타임아웃을 명시해 앱이 무한 대기하지 않게 함 */
    @Bean
    RestClient externalRestClient(HttpClient tripHttpClient) {
        return client(tripHttpClient, Duration.ofSeconds(60));
    }

    public static RestClient client(HttpClient http, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory).build();
    }
}
