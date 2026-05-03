package com.example.paymentsystem.card.config;


import com.example.paymentsystem.failure.FailureAliasPatterns;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.regex.Pattern;

@Configuration
public class CardFailureConfig {

    @Bean
    public FailureAliasPatterns failureAliasPatterns() {
        return new FailureAliasPatterns(Map.of(
                "auth", Pattern.compile("^POST /v1/authorizations$"),
                "capture", Pattern.compile("^POST /v1/authorizations/[^/]+/capture$")
        ));
    }
}
