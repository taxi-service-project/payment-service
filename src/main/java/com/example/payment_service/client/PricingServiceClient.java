package com.example.payment_service.client;

import com.example.payment_service.exception.PricingServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;

@Component
@Slf4j
public class PricingServiceClient {
    private final WebClient webClient;
    private final ReactiveCircuitBreaker circuitBreaker;

    private static final int FALLBACK_FARE = -1;

    public record FareResponse(Integer fare) {}

    public PricingServiceClient(WebClient.Builder builder,
                                @Value("${services.pricing-service.url}") String serviceUrl,
                                ReactiveCircuitBreakerFactory cbFactory) {
        this.webClient = builder.baseUrl(serviceUrl).build();
        this.circuitBreaker = cbFactory.create("pricing-service");
    }

    public Mono<FareResponse> calculateFare(String tripId, Integer distance, Integer duration, LocalDateTime timestamp) {
        Mono<FareResponse> apiCall = webClient.get()
                                              .uri(uriBuilder -> uriBuilder
                                                      .path("/internal/api/pricing/calculate")
                                                      .queryParam("tripId", tripId)
                                                      .queryParam("distance_meters", distance)
                                                      .queryParam("duration_seconds", duration)
                                                      .queryParam("end_timestamp", timestamp)
                                                      .build())
                                              .retrieve()
                                              .bodyToMono(FareResponse.class)
                                              .onErrorResume(e -> {
                                                  log.error("가격 조회 중 원본 오류 발생. tripId: {}", tripId, e);
                                                  return Mono.error(new PricingServiceUnavailableException("가격 조회 서비스 호출 실패", e));
                                              });

        return circuitBreaker.run(apiCall, throwable -> {
            // 서킷이 열렸을 때도 -1이 아니라 에러를 던져야 함
            log.warn("가격 서비스 서킷 OPEN. tripId: {}", tripId);
            return Mono.error(new PricingServiceUnavailableException("가격 서비스 서킷 차단됨 (잠시 후 재시도 필요)", throwable));
        });
    }
}