package com.example.payment_service.client;

import com.example.payment_service.exception.UserServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class UserServiceClient {
    private final WebClient webClient;
    private final ReactiveCircuitBreaker circuitBreaker;

    public record UserInfoForPaymentResponse(String userId, String userName, String userEmail, String paymentMethodId, String billingKey) {}

    public UserServiceClient(WebClient.Builder builder,
                             @Value("${services.user-service.url}") String serviceUrl,
                             ReactiveCircuitBreakerFactory cbFactory) {
        this.webClient = builder.baseUrl(serviceUrl).build();
        this.circuitBreaker = cbFactory.create("user-service");
    }

    public Mono<UserInfoForPaymentResponse> getUserInfoForPayment(String userId) {
        Mono<UserInfoForPaymentResponse> apiCall = webClient.get()
                                                            .uri("/internal/api/users/{userId}/payment-methods/default", userId)
                                                            .retrieve()
                                                            .bodyToMono(UserInfoForPaymentResponse.class)
                                                            .onErrorResume(e -> {
                                                                log.error("결제수단 조회 중 원본 오류 발생. userId: {}", userId, e);
                                                                return Mono.error(new UserServiceUnavailableException("사용자 서비스 호출 실패"));
                                                            });

        return circuitBreaker.run(apiCall, throwable -> {
            log.warn("사용자 서비스 서킷 브레이커가 열렸습니다. userId: {}. 결제 프로세스를 중단합니다.", userId, throwable);
            return Mono.error(new UserServiceUnavailableException("사용자 서비스 이용 불가"));
        });
    }
}