package com.example.paymentsystem.payment.config;

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
public class WebhookClientConfig {

    /**
     * 웹훅 배달 클라이언트.
     *
     * <p><b>read 타임아웃이 백오프 사다리를 완성한다.</b> {@code WebhookScheduler.deliver}는
     * 예외가 났을 때만 {@code retryWebhook}(1m/5m/…/24h, 7회 후 DEAD)을 태운다. 타임아웃이 없으면
     * "가맹점이 죽은" 경우(연결 거부 → 즉시 예외)만 백오프가 걸리고 <b>"가맹점이 살아서 느린"</b>
     * 경우는 예외가 안 나 영원히 대기한다. 그러면 배달 스레드가 안 끝나고,
     * {@code executor.close()}가 배치 전체를 기다리며, 스케줄러 스레드가 1개라
     * inquiry·FDS·잔액 플러시까지 함께 멈춘다.
     *
     * <p>커넥션 풀은 스케줄러의 fan-out(한 배치 최대 300건을 가상 스레드로 동시 발사)보다
     * <b>크게</b> 잡는다. 풀이 먼저 마르면 초과분이 {@code ConnectionRequestTimeout}으로 실패하고,
     * 그 실패가 곧바로 1분 백오프로 이어져 — 가맹점은 멀쩡한데 우리 쪽 자원 부족 때문에
     * 웹훅이 분 단위로 밀리게 된다. 배달 동시성 자체를 제한하려면 fan-out 쪽을 묶어야 하며
     * 풀로 대신하면 안 된다.
     */
    @Bean
    public RestClient merchantWebhookRestClient(
            @Value("${payment.webhook.merchant-base-url}") String merchantBaseUrl
    ) {
        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(400)
                        .setMaxConnPerRoute(400)
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofMilliseconds(1_000))
                                .setSocketTimeout(Timeout.ofMilliseconds(3_000))
                                .build())
                        .build();

        CloseableHttpClient httpClient = HttpClientBuilder.create()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(5_000))
                        .build())
                .build();

        return RestClient.builder()
                .baseUrl(merchantBaseUrl)
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
    }
}
