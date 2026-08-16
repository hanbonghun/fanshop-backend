package com.fanshop.order.domain;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByStatusAndUpdatedAtBefore(OrderStatus status, LocalDateTime threshold);

}
