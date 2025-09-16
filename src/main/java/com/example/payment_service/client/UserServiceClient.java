package com.example.payment_service.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class UserServiceClient {
    private final WebClient webClient;
    public record PaymentMethodResponse(Long id) {}

    public UserServiceClient(WebClient.Builder builder, @Value("${services.user-service.url}") String serviceUrl) {
        this.webClient = builder.baseUrl(serviceUrl).build();
    }

    public Mono<PaymentMethodResponse> getDefaultPaymentMethod(Long userId) {
        return webClient.get()
                        .uri("/internal/api/users/{userId}/payment-methods/default", userId)
                        .retrieve()
                        .bodyToMono(PaymentMethodResponse.class)
                        .doOnError(e -> log.error("결제수단 조회 실패. userId: {}", userId, e));
    }
}