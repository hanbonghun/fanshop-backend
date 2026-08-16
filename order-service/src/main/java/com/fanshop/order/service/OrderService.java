package com.fanshop.order.service;

import com.fanshop.client.ProductClient;
import com.fanshop.client.ProductResponse;
import com.fanshop.messaging.event.OrderCreatedEvent;
import com.fanshop.order.api.CreateOrderRequest;
import com.fanshop.order.api.OrderResponse;
import com.fanshop.order.domain.Order;
import com.fanshop.order.domain.OrderRepository;
import com.fanshop.order.domain.OrderStatus;
import com.fanshop.outbox.OutboxRecorder;
import com.fanshop.support.error.CoreException;
import com.fanshop.support.error.ErrorType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;

    private final ProductClient productClient;

    private final OutboxRecorder outboxRecorder;

    private final TransactionTemplate transactionTemplate;

    /**
     * 상품 조회(동기 HTTP)는 트랜잭션 밖에서 한다. 트랜잭션 안에 두면 네트워크 왕복이 끝날 때까지 DB 커넥션을 점유해, 커넥션 풀 크기가 곧 동시
     * 처리량의 상한이 된다.
     *
     * <p>
     * 쓰기 구간만 {@link TransactionTemplate}으로 묶는다. 메서드를 쪼개 {@code @Transactional}을 붙이는 방식은
     * 같은 빈 안의 자기 호출이라 프록시를 거치지 않아 애너테이션이 조용히 무시된다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OrderResponse createOrder(Long memberId, CreateOrderRequest request) {
        ProductResponse product = fetchProduct(request.getProductId());
        long totalPrice = product.getPrice() * request.getQuantity();

        return transactionTemplate.execute(status -> persistOrder(memberId, request, product, totalPrice));
    }

    private OrderResponse persistOrder(Long memberId, CreateOrderRequest request, ProductResponse product,
            long totalPrice) {
        Order savedOrder = orderRepository
            .save(new Order(memberId, product.getId(), request.getQuantity(), totalPrice, OrderStatus.PENDING));

        OrderCreatedEvent event = new OrderCreatedEvent(savedOrder.getId(), memberId, product.getId(),
                request.getQuantity(), totalPrice);
        outboxRecorder.record("ORDER_CREATED", event);

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
        order.onPaymentCompleted();
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

}
