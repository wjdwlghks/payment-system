package com.example.paymentsystem.payment.controller;

import com.example.paymentsystem.payment.domain.TransactionStatus;
import com.example.paymentsystem.payment.domain.TransactionType;
import com.example.paymentsystem.payment.repository.PaymentTransactionRepository;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 단계별 통과 현황을 <b>유입 경로까지 갈라서</b> 보여준다.
 *
 * <p>{@code /admin/metrics/recovery}가 "조회를 몇 번 했고 결과가 뭐였나"라는 복구 장치 자신의
 * 활동량이라면, 여기는 그 활동이 결제 흐름에 남긴 결과다. 두 질문에 답한다.
 *
 * <ol>
 *   <li>단계마다 몇 건이 들어와 몇 건이 성공했나 — 그중 몇 건이 UNKNOWN을 거쳐 확정됐나</li>
 *   <li>이 단계에 들어온 건은 직전 단계가 <b>바로</b> 성공해서 온 건가, UNKNOWN이었다가
 *       조회로 확정된 뒤에 온 건가</li>
 * </ol>
 *
 * <p>두 번째가 이 엔드포인트의 존재 이유다. 확정되고 나면 트랜잭션 상태는 SUCCEEDED 하나뿐이라
 * "장애를 맞고도 복구돼서 통과한 결제"가 무장애 결제와 구분되지 않는다. 복구 경로가 실제로
 * 결제를 끝까지 밀어줬다는 것은 이 분해가 있어야 보인다.
 *
 * <p>부하가 도는 중에도 읽을 수 있지만, 판정에 쓸 값은 <b>수렴 이후</b>에 읽어야 한다.
 * 진행 중에는 {@code unresolved}/{@code inFlight}가 0이 아닌 게 정상이다.
 */
@RestController
@RequestMapping("/admin/metrics/funnel")
@RequiredArgsConstructor
public class FunnelMetricsController {

    /** 각 단계의 직전 단계. AUTH는 가맹점 요청이 곧 유입이라 직전이 없고, CANCEL은 흐름 밖이다. */
    private static final Map<TransactionType, TransactionType> PREVIOUS_STAGE = Map.of(
            TransactionType.FDS, TransactionType.AUTH,
            TransactionType.APPROVE, TransactionType.FDS,
            TransactionType.CAPTURE, TransactionType.APPROVE
    );

    private final PaymentTransactionRepository txRepository;

    @GetMapping
    public FunnelResult get() {
        Map<TransactionType, StageAccumulator> outcomes = new EnumMap<>(TransactionType.class);
        for (Object[] row : txRepository.aggregateStageOutcomes()) {
            outcomes.computeIfAbsent(type(row[0]), t -> new StageAccumulator())
                    .add(TransactionStatus.valueOf(text(row[1])), number(row[2]) == 1L, number(row[3]));
        }

        Map<TransactionType, EntryAccumulator> entries = new EnumMap<>(TransactionType.class);
        for (Object[] row : txRepository.aggregateStageEntryPaths()) {
            entries.computeIfAbsent(type(row[0]), t -> new EntryAccumulator())
                    .add(row[1] == null ? null : number(row[1]) == 1L, number(row[2]));
        }

        List<StageFunnel> stages = new ArrayList<>();
        boolean entryPathsIntact = true;
        for (TransactionType stage : TransactionType.values()) {
            StageAccumulator outcome = outcomes.getOrDefault(stage, new StageAccumulator());
            TransactionType previous = PREVIOUS_STAGE.get(stage);
            StageEntry entry = previous == null
                    ? null
                    : entries.getOrDefault(stage, new EntryAccumulator()).toRecord(previous);
            if (entry != null && entry.orphan() != 0) {
                entryPathsIntact = false;
            }
            stages.add(outcome.toRecord(stage, entry));
        }

        return new FunnelResult(stages, entryPathsIntact);
    }

    // 네이티브 쿼리의 반환 타입은 드라이버/방언에 따라 갈린다. 여기서 한 번만 좁힌다.
    private static TransactionType type(Object value) {
        return TransactionType.valueOf(text(value));
    }

    private static String text(Object value) {
        return String.valueOf(value);
    }

    private static long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static final class StageAccumulator {
        private long okDirect;
        private long okViaInquiry;
        private long failDirect;
        private long failViaInquiry;
        private long unresolved;
        private long inFlight;

        void add(TransactionStatus status, boolean viaInquiry, long count) {
            switch (status) {
                case SUCCEEDED -> {
                    if (viaInquiry) okViaInquiry += count; else okDirect += count;
                }
                case FAIL -> {
                    if (viaInquiry) failViaInquiry += count; else failDirect += count;
                }
                case UNKNOWN -> unresolved += count;
                case REQUESTED -> inFlight += count;
            }
        }

        StageFunnel toRecord(TransactionType stage, StageEntry entry) {
            long ok = okDirect + okViaInquiry;
            long fail = failDirect + failViaInquiry;
            return new StageFunnel(
                    stage.name(), ok + fail + unresolved + inFlight,
                    ok, okDirect, okViaInquiry,
                    fail, failDirect, failViaInquiry,
                    unresolved, inFlight, entry);
        }
    }

    private static final class EntryAccumulator {
        private long afterDirectSuccess;
        private long afterInquiryRecovered;
        private long orphan;

        /** {@code previousViaInquiry}가 null이면 직전 단계의 성공을 못 찾았다는 뜻이다. */
        void add(Boolean previousViaInquiry, long count) {
            if (previousViaInquiry == null) {
                orphan += count;
            } else if (previousViaInquiry) {
                afterInquiryRecovered += count;
            } else {
                afterDirectSuccess += count;
            }
        }

        StageEntry toRecord(TransactionType previous) {
            return new StageEntry(previous.name(), afterDirectSuccess, afterInquiryRecovered, orphan);
        }
    }

    /**
     * @param entryPathsIntact 모든 단계의 {@code orphan}이 0인가. false면 유입 분해가 아니라
     *                         "직전 단계 성공 없이 다음 단계가 존재한다"는 불변식 위반이다.
     */
    public record FunnelResult(
            List<StageFunnel> stages,
            boolean entryPathsIntact
    ) {}

    /**
     * @param unresolved 아직 UNKNOWN인 건. 수렴 후에는 0이어야 한다.
     * @param inFlight   아직 REQUESTED인 건. 수렴 후에는 0이어야 한다.
     */
    public record StageFunnel(
            String stage,
            long total,
            long ok,
            long okDirect,
            long okViaInquiry,
            long fail,
            long failDirect,
            long failViaInquiry,
            long unresolved,
            long inFlight,
            StageEntry enteredFrom
    ) {}

    /**
     * @param afterDirectSuccess    직전 단계가 동기 호출 한 번에 성공해서 넘어온 건
     * @param afterInquiryRecovered 직전 단계가 UNKNOWN이었다가 조회로 성공 확정된 뒤 넘어온 건
     * @param orphan                직전 단계의 성공이 없는데 존재하는 건 — 항상 0
     */
    public record StageEntry(
            String previousStage,
            long afterDirectSuccess,
            long afterInquiryRecovered,
            long orphan
    ) {}
}
