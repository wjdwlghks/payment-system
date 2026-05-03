package com.example.paymentsystem.fds.config;

import com.example.paymentsystem.failure.FailureAliasPatterns;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.regex.Pattern;

@Configuration
public class FdsFailureConfig {

    @Bean
    public FailureAliasPatterns failureAliasPatterns() {
        return new FailureAliasPatterns(Map.of(
                "fds_check", Pattern.compile("^POST /v1/fraud-checks$")
        ));
    }
}
