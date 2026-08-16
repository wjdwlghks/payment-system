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
                "card_auth",    Pattern.compile("^POST /v1/authentications$"),
                "card_approve", Pattern.compile("^POST /v1/authentications/[^/]+/approve$"),
                "card_capture", Pattern.compile("^POST /v1/approvals/[^/]+/capture$"),
                "card_cancel",  Pattern.compile("^POST /v1/approvals/[^/]+/cancel$"),
                "fds_check",    Pattern.compile("^POST /v1/fraud-checks$")
        );
        return new FailureAliasPatterns(patterns);
    }
}
