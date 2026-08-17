package com.fanshop.product.domain;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ReservationRepository extends JpaRepository<InventoryReservation, Long> {

    /**
     * 전이 판정과 수량 변경이 한 트랜잭션에서 일어나야 하므로 잠그고 읽는다. 잠그지 않으면 두 이벤트가 같은 {@code RESERVED}를 읽고 각각
     * 전이에 성공했다고 판단할 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM InventoryReservation r WHERE r.orderId = :orderId")
    Optional<InventoryReservation> findByOrderIdWithLock(@Param("orderId") Long orderId);

    Optional<InventoryReservation> findByOrderId(Long orderId);

}
