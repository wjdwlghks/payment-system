package com.example.paymentsystem.payment.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class CardClientConfig {

    @Bean
    RestClient cardRestClient(
            @Value("${payment.card.base-url}") String cardBaseUrl
    ) {
        return RestClient.builder().baseUrl(cardBaseUrl).build();
    }
}
