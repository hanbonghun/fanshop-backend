package com.fanshop.product.domain;

import com.fanshop.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "products")
public class Product extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private long price;

    @Column(nullable = false)
    private int stockQuantity;

    @Column(nullable = false)
    private int reservedQuantity;

    public Product(String name, long price, int stockQuantity) {
        this.name = name;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.reservedQuantity = 0;
    }

    public int availableQuantity() {
        return stockQuantity - reservedQuantity;
    }

    public void softReserve(int quantity) {
        if (availableQuantity() < quantity) {
            throw new IllegalStateException("재고가 부족합니다.");
        }
        this.reservedQuantity += quantity;
    }

    public void confirmReservation(int quantity) {
        this.stockQuantity -= quantity;
        this.reservedQuantity -= quantity;
    }

    public void releaseReservation(int quantity) {
        this.reservedQuantity -= quantity;
    }

    // HTTP 직접 호출용 (관리자 재고 조정 등)
    public void decreaseStock(int quantity) {
        if (this.stockQuantity < quantity) {
            throw new IllegalStateException("재고가 부족합니다.");
        }
        this.stockQuantity -= quantity;
    }

}
