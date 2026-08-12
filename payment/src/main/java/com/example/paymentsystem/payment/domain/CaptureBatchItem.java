package com.example.paymentsystem.payment.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "capture_batch_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CaptureBatchItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private CaptureBatch batch;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tx_id", nullable = false, unique = true)
    private PaymentTransaction transaction;

    @Column(nullable = false)
    private Long amount;

    public CaptureBatchItem(CaptureBatch batch, PaymentTransaction transaction, Long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        this.batch = batch;
        this.transaction = transaction;
        this.amount = amount;
    }
}
