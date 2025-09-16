package com.example.payment_service.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;

@Component
@Slf4j
public class PricingServiceClient {
    private final WebClient webClient;
    public record FareResponse(Integer fare) {}

    public PricingServiceClient(WebClient.Builder builder, @Value("${services.pricing-service.url}") String serviceUrl) {
        this.webClient = builder.baseUrl(serviceUrl).build();
    }

    public Mono<FareResponse> calculateFare(Long tripId, Integer distance, Integer duration, LocalDateTime timestamp) {
        return webClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/internal/api/pricing/calculate")
                                .queryParam("tripId", tripId)
                                .queryParam("distance_meters", distance)
                                .queryParam("duration_seconds", duration)
                                .queryParam("end_timestamp", timestamp)
                                .build())
                        .retrieve()
                        .bodyToMono(FareResponse.class)
                        .doOnError(e -> log.error("가격 조회 실패. tripId: {}", tripId, e));
    }
}