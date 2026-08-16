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
            @Value("${payment.card.card-b-base-url}") String cardBBaseUrl,
            @Value("${payment.card.max-conn-per-route}") int maxConnPerRoute,
            @Value("${payment.card.max-conn-total}") int maxConnTotal,
            @Value("${payment.card.shared-pool}") boolean sharedPool
    ) {
        Map<CardCompany, RestClient> clients = new EnumMap<>(CardCompany.class);

        if (sharedPool) {
            // 격리 제거 — 장애 전파를 관측하기 위한 측정 전용 모드다.
            //
            // 매니저를 공유하는 것만으로는 격리가 안 풀린다. maxConnPerRoute가 호스트별로
            // 따로 걸리기 때문에, 매니저 하나를 나눠 써도 카드사 A는 여전히 자기 몫만
            // 가져간다 — 격리하는 주체는 매니저가 아니라 **per-route 상한**이다.
            // 그래서 per-route를 total과 같게 둬서 한 카드사가 풀을 통째로 삼킬 수 있게 한다.
            PoolingHttpClientConnectionManager shared = buildConnectionManager(maxConnTotal, maxConnTotal);
            clients.put(CardCompany.CARD_CORP_A, buildRestClient(cardABaseUrl, shared, true));
            clients.put(CardCompany.CARD_CORP_B, buildRestClient(cardBBaseUrl, shared, true));
            return clients;
        }

        clients.put(CardCompany.CARD_CORP_A,
                buildRestClient(cardABaseUrl, buildConnectionManager(maxConnPerRoute, maxConnTotal), false));
        clients.put(CardCompany.CARD_CORP_B,
                buildRestClient(cardBBaseUrl, buildConnectionManager(maxConnPerRoute, maxConnTotal), false));
        return clients;
    }

    private RestClient buildRestClient(
            String baseUrl,
            PoolingHttpClientConnectionManager connectionManager,
            boolean connectionManagerShared
    ) {
        CloseableHttpClient httpClient = HttpClientBuilder.create()
                .setConnectionManager(connectionManager)
                // 공유 매니저를 클라이언트가 자기 것으로 알면, 한쪽이 닫힐 때 매니저째 내려가
                // 다른 카드사까지 못 쓰게 된다.
                .setConnectionManagerShared(connectionManagerShared)
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

    private PoolingHttpClientConnectionManager buildConnectionManager(int maxConnPerRoute, int maxConnTotal) {
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
        //
        // **항상 Bulkhead 퍼밋보다 위에 있어야 한다.** 아래로 내려가면 퍼밋을 얻고도
        // 커넥션이 없는 호출이 생기고, 그건 connectionRequestTimeout(500ms)을 버린 뒤
        // ConnectionRequestTimeoutException으로 떨어져 UNKNOWN이 된다 — 즉 거절 지점이
        // Bulkhead에서 풀로 옮겨가고, 즉시 실패가 조회까지 부르는 UNKNOWN으로 바뀐다.
        // 그래서 격리 A/B로 퍼밋을 올릴 때 이 값도 같이 올릴 수 있게 밖으로 뺐다.
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(maxConnTotal)
                .setMaxConnPerRoute(maxConnPerRoute)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(1_000))
                        .setSocketTimeout(Timeout.ofMilliseconds(3_000))
                        .build())
                .build();
    }
}
