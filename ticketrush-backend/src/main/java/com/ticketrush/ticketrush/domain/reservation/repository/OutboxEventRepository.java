package com.ticketrush.ticketrush.domain.reservation.repository;

import com.ticketrush.ticketrush.domain.reservation.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
}
