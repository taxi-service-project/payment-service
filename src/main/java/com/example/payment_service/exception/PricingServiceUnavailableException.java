package com.example.payment_service.exception;

public class PricingServiceUnavailableException extends RuntimeException {
    public PricingServiceUnavailableException(String message) {
        super(message);
    }
}