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
@Table(name = "settlement_run_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementRunItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "settlement_run_id", nullable = false)
    private SettlementRun run;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "capture_tx_id", nullable = false, unique = true)
    private PaymentTransaction captureTransaction;

    @Column(name = "merchant_id", nullable = false, length = 100)
    private String merchantId;

    @Column(nullable = false)
    private Long amount;

    public SettlementRunItem(
            SettlementRun run,
            PaymentTransaction captureTransaction,
            String merchantId,
            Long amount
    ) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        this.run = run;
        this.captureTransaction = captureTransaction;
        this.merchantId = merchantId;
        this.amount = amount;
    }
}
