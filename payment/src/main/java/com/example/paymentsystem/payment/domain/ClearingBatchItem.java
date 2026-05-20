package com.example.paymentsystem.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "clearing_batch_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClearingBatchItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private ClearingBatch batch;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tx_id", nullable = false, unique = true)
    private PaymentTransaction transaction;

    @Column(nullable = false)
    private Long amount;

    public ClearingBatchItem(ClearingBatch batch, PaymentTransaction transaction, Long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        this.batch = batch;
        this.transaction = transaction;
        this.amount = amount;
    }
}
