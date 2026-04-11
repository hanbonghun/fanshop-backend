package com.fanshop.order.service;

import com.fanshop.client.ProductClient;
import com.fanshop.client.ProductResponse;
import com.fanshop.messaging.event.OrderCreatedEvent;
import com.fanshop.order.api.CreateOrderRequest;
import com.fanshop.order.api.OrderResponse;
import com.fanshop.order.domain.Order;
import com.fanshop.order.domain.OrderRepository;
import com.fanshop.order.domain.OrderStatus;
import com.fanshop.outbox.OutboxEvent;
import com.fanshop.outbox.OutboxEventRepository;
import com.fanshop.support.error.CoreException;
import com.fanshop.support.error.ErrorType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;

    private final ProductClient productClient;

    private final OutboxEventRepository outboxEventRepository;

    private final ObjectMapper objectMapper;

    @Transactional
    public OrderResponse createOrder(Long memberId, CreateOrderRequest request) {
        ProductResponse product = fetchProduct(request.getProductId());

        long totalPrice = product.getPrice() * request.getQuantity();
        Order savedOrder = orderRepository
            .save(new Order(memberId, product.getId(), request.getQuantity(), totalPrice, OrderStatus.PENDING));

        OrderCreatedEvent event = new OrderCreatedEvent(savedOrder.getId(), memberId, product.getId(),
                request.getQuantity(), totalPrice);
        outboxEventRepository.save(new OutboxEvent("ORDER_CREATED", serialize(event)));

        log.info("Order created and outbox event saved — orderId={}", savedOrder.getId());
        return OrderResponse.from(savedOrder);
    }

    @Transactional
    public void waitForPayment(Long orderId) {
        Order order = findOrder(orderId);
        order.waitForPayment();
        log.info("Order waiting for payment: orderId={}", orderId);
    }

    @Transactional
    public void confirmOrder(Long orderId) {
        Order order = findOrder(orderId);
        order.confirm();
        log.info("Order confirmed: orderId={}", orderId);
    }

    @Transactional
    public void cancelOrder(Long orderId, String reason) {
        Order order = findOrder(orderId);
        order.cancel();
        log.info("Order cancelled: orderId={}, reason={}", orderId, reason);
    }

    private ProductResponse fetchProduct(Long productId) {
        try {
            return productClient.getProduct(productId).getData();
        }
        catch (HttpClientErrorException.NotFound e) {
            throw new CoreException(ErrorType.PRODUCT_NOT_FOUND, productId);
        }
    }

    private Order findOrder(Long orderId) {
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND, orderId));
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        }
        catch (Exception e) {
            throw new RuntimeException("Outbox 이벤트 직렬화 실패", e);
        }
    }

}
