package com.example.paymentsystem.payment.service;


import com.example.paymentsystem.payment.domain.*;
import com.example.paymentsystem.payment.repository.AccountRepository;
import com.example.paymentsystem.payment.repository.LedgerPostingRepository;
import com.example.paymentsystem.payment.repository.LedgerRepository;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private static final String GLOBAL = "GLOBAL";

    private final LedgerRepository ledgerRepository;
    private final LedgerPostingRepository ledgerPostingRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final AccountRepository accountRepository;

    /** 매입 기표가 잔액을 언제 반영할지. 측정용 스위치이고 운영 기본값은 SNAPSHOT이다. */
    @Value("${payment.ledger.balance-mode:SNAPSHOT}")
    private LedgerBalanceMode balanceMode;

    @Value("${payment.ledger.shard-count:16}")
    private int shardCount;

    /**
     * 매입 기표.
     *
     * <p>인라인 모드의 락 처리가 지금 모양이 되기까지의 순서는 이랬다. 각 단계가 다음 단계를
     * 불러왔으므로 셋을 따로 떼서 이해할 수 없다.
     *
     * <ol>
     *   <li><b>데드락</b> — 계정을 잠그는 순서가 트랜잭션마다 달라 서로를 기다리는 사이클이 생겼다.</li>
     *   <li><b>lost update</b> — 계정 UPDATE를 원장 INSERT보다 앞으로 당겨 순서를 고정했더니
     *       데드락은 사라졌지만, 락 없이 먼저 읽어둔 잔액에 더하는 바람에 그 사이 커밋된
     *       다른 매입의 갱신을 덮어썼다.</li>
     *   <li><b>FOR UPDATE 직렬화</b> — 조회 시점부터 X-lock을 걸어 읽기와 잠그기를 한 번에 했다.
     *       그제서야 정합성이 맞았고, <b>그때 비로소 락 대기 병목이 관측 가능한 형태로 드러났다.</b></li>
     * </ol>
     *
     * <p>그래서 이 측정의 대조군인 INLINE은 "고장난 구현"이 아니라 정합성이 맞는 구현이다.
     * 깨진 구현과 비교하면 지금 방식의 개선폭이 부풀려진다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void postCapture(Long approveTransactionId, LedgerSourceType sourceType) {
        PaymentTransaction transaction = paymentTransactionRepository.findById(approveTransactionId)
                .orElseThrow();
        String merchantId = transaction.getPaymentIntent().getMerchantId();

        boolean inline = balanceMode != LedgerBalanceMode.SNAPSHOT;

        // 인라인 모드는 **조회 시점에 곧바로 FOR UPDATE로 집는다.** 락 없이 먼저 읽으면 그
        // 인스턴스가 영속성 컨텍스트에 남아, 나중에 잠가도 잔액은 잠그기 전 값이라 lost update가 난다.
        //
        // 잠그는 순서는 매입회수 -> 가맹점 -> 수수료로 **항상 고정**이다. 모든 매입이 같은 순서로
        // 집으므로 서로를 기다리는 사이클이 만들어질 수 없다.
        // 샤딩 모드에서만 결제별로 버킷이 갈린다 — 그래도 타입 순서는 그대로다.
        int bucket = bucketOf(transaction);
        Account cardReceivable = hotAccount(AccountType.CARD_NETWORK_RECEIVABLE, bucket, inline);
        Account merchantPending = merchantAccount(AccountType.MERCHANT_PENDING, merchantId, inline);
        Account feeRevenue = hotAccount(AccountType.FEE_REVENUE, bucket, inline);

        long amount = transaction.getAmount();
        long fee = calculateFee(amount);

        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(new LedgerEntry(cardReceivable, LedgerDirection.DEBIT, amount, LedgerEntryType.CAPTURE));
        entries.add(new LedgerEntry(merchantPending, LedgerDirection.CREDIT, amount - fee, LedgerEntryType.CAPTURE));
        if (fee > 0) {
            entries.add(new LedgerEntry(feeRevenue, LedgerDirection.CREDIT, fee, LedgerEntryType.CAPTURE));
        }

        // SNAPSHOT이면 기표만 하고 끝이다 — 잔액은 AccountBalanceFlusher가 나중에 맞춘다.
        if (inline) {
            applyInline(entries);
        }

        post(LedgerPostingType.CAPTURE, sourceType, approveTransactionId.toString(), entries);
    }

    /**
     * 이미 X-lock을 쥔 계정에 금액을 더한다. 잠그는 일은 조회가 이미 했다.
     *
     * <p>{@code SELECT ... FOR UPDATE} 시점부터 커밋까지 락을 쥐므로, 글로벌 계정처럼 전 결제가
     * 공유하는 한 행에서는 <b>매입 처리량의 상한이 그 행의 락 보유 시간으로 정해진다</b> —
     * 이 측정이 보려는 것이 정확히 그 지점이다.
     */
    private void applyInline(List<LedgerEntry> entries) {
        for (LedgerEntry entry : entries) {
            entry.getAccount().apply(entry.getDirection(), entry.getAmount());
            // 표시하지 않으면 플러셔가 같은 금액을 한 번 더 더한다.
            entry.markApplied();
        }
    }

    private int bucketOf(PaymentTransaction transaction) {
        if (balanceMode != LedgerBalanceMode.SHARDED) {
            return -1;
        }
        return Math.floorMod(transaction.getPaymentIntent().getPaymentKey().hashCode(), shardCount);
    }

    /** 전 결제가 공유하는 계정. 샤딩 모드에서만 버킷 행으로 갈라진다. */
    private Account hotAccount(AccountType type, int bucket, boolean forUpdate) {
        String merchantId = bucket < 0 ? GLOBAL : shardMerchantId(bucket);
        return findAccount(type, merchantId, forUpdate)
                .orElseThrow(() -> new IllegalStateException(
                        "account missing: " + type + " / " + merchantId));
    }

    private Optional<Account> findAccount(AccountType type, String merchantId, boolean forUpdate) {
        return forUpdate
                ? accountRepository.findByAccountTypeAndMerchantIdForUpdate(type, merchantId)
                : accountRepository.findByAccountTypeAndMerchantId(type, merchantId);
    }

    /** V36이 만들어 둔 샤드 행의 merchantId. {@code GLOBAL#00} 꼴이다. */
    public static String shardMerchantId(int bucket) {
        return String.format("GLOBAL#%02d", bucket);
    }

    // CLEARING: 카드사와 대사(reconciliation)가 끝난 net 금액에서 카드사 매입 수수료만 확정 반영.
    // 은행 계좌 입금은 SETTLEMENT의 몫이라 여기서는 CARD_NETWORK_RECEIVABLE을 건드리지 않는다.
    @Transactional(propagation = Propagation.MANDATORY)
    public void postClearing(Long batchId, Long fileNet) {
        Account cardNetworkFee = globalAccount(AccountType.CARD_NETWORK_FEE);
        Account cardReceivable = globalAccount(AccountType.CARD_NETWORK_RECEIVABLE);

        long fee = calculateCardNetworkFee(fileNet);

        List<LedgerEntry> entries = new ArrayList<>();
        if (fee > 0) {
            entries.add(new LedgerEntry(cardNetworkFee, LedgerDirection.DEBIT, fee, LedgerEntryType.CLEARING));
            entries.add(new LedgerEntry(cardReceivable, LedgerDirection.CREDIT, fee, LedgerEntryType.CLEARING));
        }

        post(LedgerPostingType.CLEARING, LedgerSourceType.CLEARING_BATCH, batchId.toString(), entries);
    }

    // SETTLEMENT: 카드사 → PG 실제 입금. CLEARED된 배치별로 (총액 - 카드사수수료) 순액이 은행 계좌에 반영된다.
    @Transactional(propagation = Propagation.MANDATORY)
    public void postSettlement(Long runId, List<ClearingBatch> batches) {
        Account cardReceivable = globalAccount(AccountType.CARD_NETWORK_RECEIVABLE);
        Account bank = globalAccount(AccountType.BANK_ACCOUNT);

        List<LedgerEntry> entries = new ArrayList<>();
        for (ClearingBatch batch : batches) {
            long fee = calculateCardNetworkFee(batch.getTotalAmount());
            long net = batch.getTotalAmount() - fee;

            entries.add(new LedgerEntry(bank, LedgerDirection.DEBIT, net, LedgerEntryType.SETTLEMENT));
            entries.add(new LedgerEntry(cardReceivable, LedgerDirection.CREDIT, net, LedgerEntryType.SETTLEMENT));
        }

        post(LedgerPostingType.SETTLEMENT, LedgerSourceType.SETTLEMENT_RUN, runId.toString(), entries);
    }

    // PAYOUT: PG → 가맹점 실제 송금. 가맹점 계좌는 PENDING/AVAILABLE 구분 없이 단일 계좌.
    @Transactional(propagation = Propagation.MANDATORY)
    public void postPayout(Long payoutId, String merchantId, Long amount) {
        Account merchantPending = merchantAccount(AccountType.MERCHANT_PENDING, merchantId);
        Account bank = globalAccount(AccountType.BANK_ACCOUNT);

        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(new LedgerEntry(merchantPending, LedgerDirection.DEBIT, amount, LedgerEntryType.PAYOUT));
        entries.add(new LedgerEntry(bank, LedgerDirection.CREDIT, amount, LedgerEntryType.PAYOUT));

        post(LedgerPostingType.PAYOUT, LedgerSourceType.PAYOUT_REQUEST, payoutId.toString(), entries);
    }

    private void post(
            LedgerPostingType postingType,
            LedgerSourceType sourceType,
            String sourceId,
            List<LedgerEntry> entries
    ) {
        LedgerPosting posting = ledgerPostingRepository.save(
                new LedgerPosting(
                        postingType,
                        sourceType,
                        sourceId,
                        total(entries, LedgerDirection.DEBIT),
                        total(entries, LedgerDirection.CREDIT)
                )
        );

        for (LedgerEntry entry : entries) {
            entry.assignPosting(posting);
            // account.apply() 호출 없음 — AccountBalanceFlusher가 주기적으로 갱신
        }

        ledgerRepository.saveAll(entries);
    }

    private Account globalAccount(AccountType type) {
        return accountRepository.findByAccountTypeAndMerchantId(type, GLOBAL).orElseThrow();
    }

    private Account merchantAccount(AccountType type, String merchantId) {
        return merchantAccount(type, merchantId, false);
    }

    /**
     * 가맹점 계정. 없으면 만든다 — 방금 INSERT한 행은 이 트랜잭션이 이미 X-lock을 쥐고 있으므로
     * {@code forUpdate}여도 따로 잠글 필요가 없다.
     */
    private Account merchantAccount(AccountType type, String merchantId, boolean forUpdate) {
        return findAccount(type, merchantId, forUpdate)
                .orElseGet(() -> accountRepository.save(new Account(type, AccountClass.LIABILITY, merchantId)));
    }

    private long total(List<LedgerEntry> entries, LedgerDirection direction) {
        return entries.stream()
                .filter(entry -> entry.getDirection() == direction)
                .mapToLong(LedgerEntry::getAmount)
                .sum();
    }

    private long calculateFee(long amount) {
        long numerator = Math.multiplyExact(amount, 3L);
        return Math.addExact(numerator, 50L) / 100L;
    }

    // 카드사가 정산 시 떼어가는 매입 수수료율(임의값 1%) — PG-가맹점 수수료(calculateFee)와는 별개 축.
    private long calculateCardNetworkFee(long amount) {
        long numerator = Math.multiplyExact(amount, 1L);
        return Math.addExact(numerator, 50L) / 100L;
    }
}
