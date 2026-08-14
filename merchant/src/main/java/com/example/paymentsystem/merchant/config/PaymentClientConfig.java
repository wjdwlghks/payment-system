package com.example.paymentsystem.merchant.config;

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

@Configuration
public class PaymentClientConfig {

    /**
     * merchant → payment 클라이언트. payment의 카드사 클라이언트와 같은 형태로 맞췄다.
     *
     * <p>이전에는 {@code RestClient.builder()}를 static으로 불러 JDK 기본 클라이언트를 썼는데,
     * 그쪽은 read 타임아웃이 없다. 카오스 중 payment가 느려지면 merchant 워커 스레드가 무한정
     * 묶이고, 그 지연이 그대로 지연 측정값에 섞인다.
     *
     * <p>socket 타임아웃이 카드사(3s)보다 넉넉한 이유는 payment 한 번의 호출 안에
     * 카드사 왕복이 통째로 들어 있기 때문이다 — 카드사가 3초까지 끄는 정상 케이스를
     * merchant가 먼저 끊어버리면 안 된다.
     *
     * <p>풀은 워커 동시성(64)보다 크게 잡는다. 풀이 먼저 마르면 초과분이
     * {@code ConnectionRequestTimeout}으로 <b>실패</b>하는데, 웹훅은 이미 ACK돼서 재시도해줄
     * 주체가 없다 — 그 결제는 영구 미완결로 남는다. 동시성 제한은 워커 쪽에서만 건다.
     */
    @Bean
    public RestClient paymentRestClient(
            @Value("${payment.base-url}") String baseUrl
    ) {
        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(200)
                        .setMaxConnPerRoute(200)
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofMilliseconds(1_000))
                                .setSocketTimeout(Timeout.ofMilliseconds(15_000))
                                .build())
                        .build();

        CloseableHttpClient httpClient = HttpClientBuilder.create()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(5_000))
                        .build())
                .build();

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
