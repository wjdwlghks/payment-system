package com.example.paymentsystem.payment.service;

import java.net.SocketTimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ExternalCallExecutor {

    public <T, R> R execute(
            Supplier<T> call,
            Function<T, R> onResponse,
            Supplier<R> onUnknown,
            Supplier<R> onFailure
    ) {
        try {
            return onResponse.apply(call.get());
        } catch (ResourceAccessException | RestClientResponseException e) {
            if (shouldRemainUnknown(e)) {
                return onUnknown.get();
            }
            return onFailure.get();
        }
    }

    public <T> void executeVoid(
            Supplier<T> call,
            Consumer<T> onResponse,
            Runnable onUnknown,
            Runnable onFailure
    ) {
        execute(call, response -> {
            onResponse.accept(response);
            return null;
        }, () -> {
            onUnknown.run();
            return null;
        }, () -> {
            onFailure.run();
            return null;
        });
    }

    private boolean shouldRemainUnknown(Throwable throwable) {
        if (throwable instanceof ResourceAccessException) {
            return isReadTimeout(throwable);
        }

        if (throwable instanceof RestClientResponseException e) {
            return e.getStatusCode().is5xxServerError();
        }

        return false;
    }

    private boolean isReadTimeout(Throwable throwable) {
        return isCausedBy(throwable, SocketTimeoutException.class)
                && !isCausedBy(throwable, ConnectTimeoutException.class);
    }

    private boolean isCausedBy(Throwable throwable, Class<? extends Throwable> causeType) {
        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
