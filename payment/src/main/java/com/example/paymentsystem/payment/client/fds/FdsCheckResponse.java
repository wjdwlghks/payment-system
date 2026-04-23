package com.example.paymentsystem.payment.client.fds;

public record FdsCheckResponse(
        boolean success,
        String result,
        String externalId
) {
}

