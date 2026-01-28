package com.example.payment_service.repository;

import com.example.payment_service.entity.PaymentOutbox;
import com.example.payment_service.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PaymentOutboxRepository extends JpaRepository<PaymentOutbox, Long> {

    @Query(value = """
            SELECT * FROM payment_outbox 
            WHERE status = 'READY' 
            ORDER BY created_at ASC 
            LIMIT :limit 
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PaymentOutbox> findEventsForPublishing(@Param("limit") int limit);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE PaymentOutbox p SET p.status = :status WHERE p.id IN :ids")
    void updateStatus(@Param("ids") List<Long> ids, @Param("status") OutboxStatus status);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE PaymentOutbox p SET p.status = :newStatus WHERE p.status = :oldStatus AND p.createdAt < :cutoffTime")
    int resetStuckEvents(@Param("oldStatus") OutboxStatus oldStatus,
                         @Param("newStatus") OutboxStatus newStatus,
                         @Param("cutoffTime") LocalDateTime cutoffTime);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM PaymentOutbox p WHERE p.status = :status AND p.createdAt < :cutoffTime")
    int deleteOldEvents(@Param("status") OutboxStatus status,
                        @Param("cutoffTime") LocalDateTime cutoffTime);
}