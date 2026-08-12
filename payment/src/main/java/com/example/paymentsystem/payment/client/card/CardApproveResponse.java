package com.example.paymentsystem.payment.client.card;

public record CardApproveResponse(
        boolean success,
        String externalId
) {
}

