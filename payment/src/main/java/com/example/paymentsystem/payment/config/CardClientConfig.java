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
        // 매니저 하나가 카드사 한 곳만 담당하므로 실질 상한은 per-route 쪽이다.
        //
        // 필요 커넥션은 Little's law가 정한다: 처리율 × 평균 응답시간.
        // 150 TPS 실측에서 회사당 166 calls/s(인증+승인+매입)이고, 주입 타임아웃 5.2%가
        // 소켓 타임아웃 3초까지 커넥션을 붙잡아 평균 RTT가 175ms까지 올라간다 →
        // 166 × 0.175 ≈ 29개. 20으로는 평균조차 못 받아서 초과분이
        // ConnectionRequestTimeout(500ms)으로 떨어졌고, 그게 UNKNOWN의 53%였다.
        //
        // 버스트 여유를 봐서 2배 남짓으로 잡는다. 이 값은 부하마다 조정하는 값이 아니라
        // "여기서 막히면 안 된다"는 물리적 상한이다 — 실제 동시성 조절은 리미터 몫이다.
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
                .baseUrl(baseUrl)
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .requestInterceptor(interceptor)
                .build();
    }
}
