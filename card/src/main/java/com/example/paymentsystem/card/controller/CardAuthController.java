package com.example.paymentsystem.card.controller;

import com.example.paymentsystem.card.dto.CardAuthRequest;
import com.example.paymentsystem.card.dto.CardAuthResponse;
import com.example.paymentsystem.card.service.CardAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/authorizations")
@RequiredArgsConstructor
public class CardAuthController {

    private final CardAuthService cardAuthService;

    @PostMapping
    public CardAuthResponse authorize(@RequestBody CardAuthRequest request) {
        return cardAuthService.authorize(request);
    }
}
