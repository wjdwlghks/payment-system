package com.example.paymentsystem.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class WebhookClientConfig {

    @Bean
    public RestClient merchantWebhookRestClient(
            @Value("${payment.webhook.merchant-base-url}") String merchantBaseUrl
    ) {
        return RestClient.builder()
                .baseUrl(merchantBaseUrl)
                .build();
    }
}
