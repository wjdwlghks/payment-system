package com.example.paymentsystem.payment.client.fds;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class FdsClientConfig {

    @Bean
    RestClient fdsRestClient(@Value("${payment.fds.base-url}") String fdsBaseUrl) {
        return RestClient.builder().baseUrl(fdsBaseUrl).build();
    }
}

