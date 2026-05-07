package com.example.paymentsystem.card.dto;

public record ApiResult(
        int statusCode,
        String body
) {
}
