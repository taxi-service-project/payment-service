package com.example.payment_service.repository;

import com.example.payment_service.entity.FailedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

public interface FailedEventRepository extends JpaRepository<FailedEvent, Long> {
}