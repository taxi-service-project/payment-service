package com.example.payment_service.controller;

import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/api/payments")
@RequiredArgsConstructor
public class InternalPaymentController {

    private final PaymentService paymentService;

    @GetMapping
    public ResponseEntity<PaymentResponse> getPaymentByTripId(@RequestParam String tripId) {
        PaymentResponse response = paymentService.getPaymentByTripId(tripId);
        return ResponseEntity.ok(response);
    }
}