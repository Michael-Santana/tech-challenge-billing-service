package com.tech.challenger.execution.service.demo;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.time.Instant;

interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
    @Query("select e from OutboxEvent e where (e.published = false or e.osUpdated = false) and e.nextAttemptAt <= :now order by e.createdAt asc limit 50")
    List<OutboxEvent> findReady(Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OutboxEvent e where e.id = :id")
    Optional<OutboxEvent> findLockedById(UUID id);
}
