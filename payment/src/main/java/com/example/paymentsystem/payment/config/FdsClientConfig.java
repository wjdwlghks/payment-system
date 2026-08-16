package com.example.paymentsystem.payment.config;

import com.example.paymentsystem.payment.component.FailureSimulationInterceptor;
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

@Configuration
@RequiredArgsConstructor
public class FdsClientConfig {

    private final FailureSimulationInterceptor interceptor;

    @Bean
    public RestClient fdsRestClient(@Value("${payment.fds.base-url}") String fdsBaseUrl) {
        // FDS는 라우트가 하나뿐이라 전 트래픽이 이 한도 하나를 통과한다.
        // 150 TPS 실측에서 122 calls/s, 평균 RTT 175ms(주입 타임아웃 포함) → 필요치 약 21개.
        // 20이었으니 정확히 걸쳐 있었고, 카드사 풀과 같은 이유로 말랐다.
        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(128)
                        .setMaxConnPerRoute(64)
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
                .baseUrl(fdsBaseUrl)
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .requestInterceptor(interceptor)
                .build();
    }
}
