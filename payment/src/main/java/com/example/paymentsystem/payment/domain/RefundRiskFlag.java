package com.example.paymentsystem.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "refund_risk_flag")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundRiskFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false, length = 100)
    private String merchantId;

    @Column(name = "refund_tx_id", nullable = false)
    private Long refundTxId;

    @Column(name = "pending_balance", nullable = false)
    private Long pendingBalance;

    @Column(name = "available_balance", nullable = false)
    private Long availableBalance;

    @Column(name = "refund_amount", nullable = false)
    private Long refundAmount;

    @Column(name = "net_position", nullable = false)
    private Long netPosition;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public RefundRiskFlag(String merchantId, Long refundTxId, Long pendingBalance,
                          Long availableBalance, Long refundAmount, Long netPosition) {
        this.merchantId = merchantId;
        this.refundTxId = refundTxId;
        this.pendingBalance = pendingBalance;
        this.availableBalance = availableBalance;
        this.refundAmount = refundAmount;
        this.netPosition = netPosition;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }
}
