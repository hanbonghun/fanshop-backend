package com.fanshop.order.api;

import com.fanshop.order.service.OrderService;
import com.fanshop.support.response.ApiResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ApiResponse<OrderResponse> createOrder(@AuthenticationPrincipal String memberId,
            @RequestBody CreateOrderRequest request) {
        return ApiResponse.success(orderService.createOrder(Long.valueOf(memberId), request));
    }

}
