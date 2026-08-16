package com.example.paymentsystem.payment.repository;

import com.example.paymentsystem.payment.domain.Payout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayoutRepository extends JpaRepository<Payout, Long> {

    /**
     * 오늘치 지급코드의 마지막 일련번호. <b>문자열이 아니라 수로 비교해야 한다.</b>
     *
     * <p>예전에는 {@code ORDER BY payout_code DESC LIMIT 1}로 마지막 코드를 집었는데,
     * 코드가 {@code %03d}로 패딩돼 있어 1000번째부터 자릿수가 늘어난다. 문자열 정렬에서는
     * {@code "999" > "1000"}이라 그 시점부터 최댓값이 999로 고정되고, 다음 번호를 영원히
     * 1000으로 계산해 {@code uk_payout_payout_code}에 부딪힌다 — 하루 지급이 1,000건을
     * 넘는 순간 지급이 통째로 멈춘다. 150 TPS 실측(가맹점 1,200곳)에서 실제로 재현됐다.
     *
     * <p>동시 호출에서는 같은 번호를 둘이 집을 수 있지만, 그때는 UNIQUE 제약이 잡아 한쪽이
     * 롤백된다. 지급은 관리자가 가맹점별로 순차 호출하는 저빈도 경로라 여기까지만 방어한다.
     */
    @Query(value = """
    SELECT COALESCE(MAX(CAST(SUBSTRING(p.payout_code, CHAR_LENGTH(:prefix) + 1) AS UNSIGNED)), 0)
      FROM payout p
     WHERE p.payout_code LIKE CONCAT(:prefix, '%')
    """, nativeQuery = true)
    long findMaxSequenceByPrefix(@Param("prefix") String prefix);
}
