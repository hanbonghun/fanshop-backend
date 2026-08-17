package com.fanshop.product.service;

import com.fanshop.product.api.CreateProductRequest;
import com.fanshop.product.api.ProductResponse;
import com.fanshop.product.domain.InventoryReservation;
import com.fanshop.product.domain.Product;
import com.fanshop.product.domain.ProductRepository;
import com.fanshop.product.domain.ReservationRepository;
import com.fanshop.support.error.CoreException;
import com.fanshop.support.error.ErrorType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    private final ReservationRepository reservationRepository;

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        Product saved = productRepository.save(request.toEntity());
        return ProductResponse.from(saved);
    }

    public ProductResponse getProduct(Long productId) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND, productId));
        return ProductResponse.from(product);
    }

    @Transactional
    public ReservationResult softReserveStock(Long orderId, Long productId, int quantity) {
        if (reservationRepository.findByOrderId(orderId).isPresent()) {
            // order.created 재수신. 예약은 이미 만들어져 있다.
            return ReservationResult.reserved();
        }
        return productRepository.findByIdWithLock(productId).map(product -> {
            if (product.availableQuantity() < quantity) {
                return ReservationResult.rejected(ErrorType.INSUFFICIENT_STOCK.getMessage());
            }
            product.softReserve(quantity);
            reservationRepository.save(new InventoryReservation(orderId, productId, quantity));
            return ReservationResult.reserved();
        }).orElseGet(() -> ReservationResult.rejected(ErrorType.PRODUCT_NOT_FOUND.getMessage()));
    }

    /**
     * 예약이 아직 살아 있을 때만 확정한다. 만료로 이미 해제된 뒤 늦게 도착한 결제 성공은 여기서 걸러진다. order-service는 그 주문을
     * {@code REFUND_REQUIRED}로 두므로, 재고를 되잡지 않는 쪽이 그 결정과 맞는다.
     */
    @Transactional
    public void confirmReservation(Long orderId, Long productId, int quantity) {
        transitReservation(orderId, InventoryReservation::confirm, product -> product.confirmReservation(quantity),
                "확정");
    }

    /** 예약이 아직 살아 있을 때만 해제한다. 이미 확정된 예약에 늦은 만료가 도착해도 팔린 재고가 되살아나지 않는다. */
    @Transactional
    public void releaseReservation(Long orderId, Long productId, int quantity) {
        transitReservation(orderId, InventoryReservation::release, product -> product.releaseReservation(quantity),
                "해제");
    }

    private void transitReservation(Long orderId, java.util.function.Predicate<InventoryReservation> transit,
            java.util.function.Consumer<Product> applyToProduct, String action) {
        InventoryReservation reservation = reservationRepository.findByOrderIdWithLock(orderId).orElse(null);
        if (reservation == null) {
            log.warn("예약 없음 — {} 무시, orderId={}", action, orderId);
            return;
        }
        if (!transit.test(reservation)) {
            log.warn("전이 불가 — {} 무시, orderId={}, status={}", action, orderId, reservation.getStatus());
            return;
        }
        Product product = productRepository.findByIdWithLock(reservation.getProductId())
            .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND, reservation.getProductId()));
        applyToProduct.accept(product);
    }

    // HTTP 직접 호출용 (관리자 재고 조정 등)
    @Transactional
    public void decreaseStock(Long productId, int quantity) {
        Product product = productRepository.findByIdWithLock(productId)
            .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND, productId));
        if (product.getStockQuantity() < quantity) {
            throw new CoreException(ErrorType.INSUFFICIENT_STOCK, productId);
        }
        product.decreaseStock(quantity);
    }

}
