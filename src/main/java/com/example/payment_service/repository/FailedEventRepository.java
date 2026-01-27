package com.example.payment_service.repository;

import com.example.payment_service.entity.FailedEvent;
import com.example.payment_service.entity.FailedEventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FailedEventRepository extends JpaRepository<FailedEvent, Long> {

    Slice<FailedEvent> findAllByTopicAndStatus(String topic, FailedEventStatus status, Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE FailedEvent f SET f.status = :status WHERE f.id IN :ids")
    void updateStatus(@Param("ids") List<Long> ids, @Param("status") FailedEventStatus status);

}