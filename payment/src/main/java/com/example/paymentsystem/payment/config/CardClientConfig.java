package com.example.paymentsystem.payment.config;

import com.example.paymentsystem.payment.component.FailureSimulationInterceptor;
import com.example.paymentsystem.payment.domain.CardCompany;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.EnumMap;
import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class CardClientConfig {

    private final FailureSimulationInterceptor interceptor;

    @Bean
    public Map<CardCompany, RestClient> cardRestClients(
            @Value("${payment.card.card-a-base-url}") String cardABaseUrl,
            @Value("${payment.card.card-b-base-url}") String cardBBaseUrl
    ) {
        Map<CardCompany, RestClient> clients = new EnumMap<>(CardCompany.class);
        clients.put(CardCompany.CARD_CORP_A, buildRestClient(cardABaseUrl));
        clients.put(CardCompany.CARD_CORP_B, buildRestClient(cardBBaseUrl));
        return clients;
    }

    private RestClient buildRestClient(String baseUrl) {
        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(50)
                        .setMaxConnPerRoute(20)
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofMilliseconds(1_000))
                                .setSocketTimeout(Timeout.ofMilliseconds(3_000))
                                .build())
                        .build();

        CloseableHttpClient httpClient = HttpClientBuilder.create()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(500))
                        .build())
                .build();

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .requestInterceptor(interceptor)
                .build();
    }
}
