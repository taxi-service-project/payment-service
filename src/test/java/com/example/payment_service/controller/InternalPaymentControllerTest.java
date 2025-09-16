package com.example.payment_service.controller;

import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.exception.PaymentNotFoundException;
import com.example.payment_service.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalPaymentController.class)
class InternalPaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    @DisplayName("GET /internal/api/payments - 성공")
    void getPaymentByTripId_Success() throws Exception {
        // given
        long tripId = 1L;
        PaymentResponse mockResponse = new PaymentResponse(1L, tripId, 15000,
                PaymentStatus.COMPLETED, "dummy-tx-id", LocalDateTime.now(), LocalDateTime.now());

        when(paymentService.getPaymentByTripId(tripId)).thenReturn(mockResponse);

        // when & then
        mockMvc.perform(get("/internal/api/payments")
                       .param("tripId", String.valueOf(tripId)))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.tripId").value(tripId))
               .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("GET /internal/api/payments - 실패 (404 Not Found)")
    void getPaymentByTripId_Fail_NotFound() throws Exception {
        // given
        long tripId = 99L;
        when(paymentService.getPaymentByTripId(tripId))
                .thenThrow(new PaymentNotFoundException("결제 내역 없음"));

        // when & then
        mockMvc.perform(get("/internal/api/payments")
                       .param("tripId", String.valueOf(tripId)))
               .andExpect(status().isNotFound());
    }
}