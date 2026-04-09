package com.fanshop.order.api;

import com.fanshop.order.domain.Order;
import com.fanshop.order.domain.OrderStatus;

import lombok.Getter;

@Getter
public class OrderResponse {

    private Long id;

    private Long memberId;

    private Long productId;

    private int quantity;

    private long totalPrice;

    private OrderStatus status;

    public OrderResponse(Long id, Long memberId, Long productId, int quantity, long totalPrice, OrderStatus status) {
        this.id = id;
        this.memberId = memberId;
        this.productId = productId;
        this.quantity = quantity;
        this.totalPrice = totalPrice;
        this.status = status;
    }

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getMemberId(), order.getProductId(), order.getQuantity(),
                order.getTotalPrice(), order.getStatus());
    }

}
