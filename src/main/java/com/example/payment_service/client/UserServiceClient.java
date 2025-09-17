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
    public record UserInfoForPaymentResponse(String userId, String userName, String userEmail, String paymentMethodId, String billingKey) {}

    public UserServiceClient(WebClient.Builder builder, @Value("${services.user-service.url}") String serviceUrl) {
        this.webClient = builder.baseUrl(serviceUrl).build();
    }

    public Mono<UserInfoForPaymentResponse> getUserInfoForPayment(String userId) {
        return webClient.get()
                        .uri("/internal/api/users/{userId}/payment-methods/default", userId)
                        .retrieve()
                        .bodyToMono(UserInfoForPaymentResponse.class)
                        .doOnError(e -> log.error("결제수단 조회 실패. userId: {}", userId, e));
    }
}