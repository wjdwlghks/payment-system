package com.example.paymentsystem.failure;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

@Component
@Order(1)
@RequiredArgsConstructor
public class FailureFilter implements Filter {

    private final FailureRegistry registry;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpReq =  (HttpServletRequest) req;
        HttpServletResponse httpRes = (HttpServletResponse) res;

        FailureRule rule = registry.consumeForRequest(httpReq);

        if (rule == null || !rule.shouldTrigger()) {
            chain.doFilter(req, res);
            return;
        }

        try {
            applyFailure(rule, chain, req, res, httpRes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServletException("failure sleep interrupted", e);
        }
    }

    private void applyFailure(FailureRule rule, FilterChain chain, ServletRequest req, ServletResponse res, HttpServletResponse httpRes) throws ServletException, IOException, InterruptedException {
        switch (rule.getFailure()) {
            case ERROR_500 -> httpRes.sendError(
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failure injected: ERROR_500"
            );

            case TIMEOUT_BEFORE_PROCESS -> {
                Thread.sleep(rule.getDelayMs());
                // 처리 안 함, 응답 안 보냄 → 클라이언트는 read timeout
            }

            case TIMEOUT_AFTER_PROCESS -> {
                ContentCachingResponseWrapper wrappedRes = new ContentCachingResponseWrapper(httpRes);
                chain.doFilter(req, wrappedRes);
                Thread.sleep(rule.getDelayMs());
                wrappedRes.copyBodyToResponse();
            }

            case SLOW_SUCCESS -> {
                Thread.sleep(rule.getDelayMs());
                chain.doFilter(req, res);
            }
        }
    }
}
