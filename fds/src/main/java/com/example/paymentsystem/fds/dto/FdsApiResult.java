package com.example.paymentsystem.fds.dto;

public record FdsApiResult(
        int statusCode,
        String body
) {
}
