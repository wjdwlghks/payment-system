package com.example.paymentsystem.payment.config;

import com.example.paymentsystem.failure.FailureAliasPatterns;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.regex.Pattern;

@Configuration
public class PaymentFailureConfig {

    @Bean
    public FailureAliasPatterns paymentFailureAliasPatterns() {
        Map<String, Pattern> patterns = Map.of(
                "card_auth",    Pattern.compile("^POST /v1/authorizations$"),
                "card_capture", Pattern.compile("^POST /v1/authorizations/[^/]+/capture$"),
                "card_refund",  Pattern.compile("^POST /v1/authorizations/[^/]+/refund$"),
                "fds_check",    Pattern.compile("^POST /v1/fraud-checks$")
        );
        return new FailureAliasPatterns(patterns);
    }
}
