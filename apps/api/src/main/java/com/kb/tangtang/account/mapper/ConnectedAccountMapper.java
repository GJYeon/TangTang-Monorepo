package com.kb.tangtang.account.mapper;

import com.kb.tangtang.account.domain.ConnectedAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * tbl_connected_account 접근.
 *
 * ⚠ @Mapper 가 없으면 등록되지 않는다 (@MapperScan(annotationClass = Mapper.class)).
 * SQL 은 mapper/account/ConnectedAccountMapper.xml 에 있다.
 */
@Mapper
public interface ConnectedAccountMapper {

    /** 연결 계좌 목록 (해제되지 않은 것만) */
    List<ConnectedAccount> findActiveByUser(@Param("userId") long userId);

    /** 이미 연결된 계좌의 해시 목록. 계좌 선택 화면의 alreadyLinked 판정에 쓴다 */
    List<String> findActiveHashes(@Param("userId") long userId);

    ConnectedAccount findByIdAndUser(@Param("id") long id, @Param("userId") long userId);

    /**
     * 연결 저장.
     * UNIQUE KEY (user_id, account_no_encrypted) 가 중복을 막는다 —
     * 같은 계좌를 두 번 연결하면 해제 후 재연결로 본다(is_active 를 되살린다).
     */
    int insert(ConnectedAccount account);

    /** 해제했던 계좌를 다시 연결할 때. 새 행을 만들지 않고 되살린다 */
    int reactivate(ConnectedAccount account);

    /** 연결 해제 (soft delete) */
    int deactivate(@Param("id") long id, @Param("userId") long userId);

    /**
     * 자연키로 비활성화한다(이슈 #379). 계좌 선택 화면에서 체크 해제한 계좌는 insert 직후라
     * PK 를 다시 조회하지 않고도 끌 수 있어야 해서 {@link #deactivate} 와 별도로 둔다.
     */
    int deactivateByHash(@Param("userId") long userId, @Param("accountNoEncrypted") String accountNoEncrypted);

    /**
     * 대출·카드 제외 그림자 행 제거(#467). 체크를 풀었던 상품을 다시 연동할 때 부른다 —
     * 이 행이 남아 있으면 동기화가 그 상품을 영원히 건너뛴다. is_active=0 인 행만 지우므로
     * 실제 연결 행(페이머니 등)은 건드리지 않는다.
     */
    int deleteInactiveByHash(@Param("userId") long userId, @Param("accountNoEncrypted") String accountNoEncrypted);

    /** 사용자의 모든 연결 해제. 금융정보 동의 철회 시 쓴다 (이슈 #13 TODO(#12)) */
    int deactivateAllByUser(@Param("userId") long userId);

    /** 동기화 결과 반영 */
    int updateSync(@Param("id") long id,
                   @Param("userId") long userId,
                   @Param("syncStatus") String syncStatus,
                   @Param("lastSyncAt") LocalDateTime lastSyncAt,
                   @Param("syncFailReason") String syncFailReason);

    /**
     * 조회 성공 반영. **잔액까지 갱신한다.**
     * 예전에는 상태·시각만 바꿔서 "방금 동기화됨" 이라면서 금액은 그대로였다.
     */
    int updateSynced(@Param("id") long id,
                     @Param("userId") long userId,
                     @Param("balance") java.math.BigDecimal balance,
                     @Param("lastSyncAt") LocalDateTime lastSyncAt);

    /** 사용자가 명시적으로 해제한(is_active=0) 계좌의 자연키 목록. 동기화가 이 계좌들을 되살리면 안 된다(이슈 #199 최종 리뷰). */
    List<String> findInactiveKeysByUser(@Param("userId") long userId);

    /**
     * 배치 스케줄러 대상 사용자 선정(이슈 #199). is_active 계좌가 있는 사용자만, 마지막 동기화
     * **시도**가 가장 오래된 사용자부터 최대 limit 명. 별도 잠금·작업 테이블 없이 라운드로빈 효과를 낸다.
     *
     * ⚠ 정렬 기준은 tbl_connected_account.last_sync_at 이 아니라 tbl_financial_sync_history.finished_at 이다
     *   (이슈 #199 최종 리뷰). sync() 는 자기가 만든 MOCK-* 계좌의 last_sync_at 만 갱신하므로,
     *   CODEF 연동(AccountLinkService)으로만 계좌를 만든 사용자는 last_sync_at 이 영원히 갱신되지 않아
     *   "무한 연체" 상태로 매 틱 큐 맨 앞을 차지하며 다른 사용자를 굶긴다. 반면 이력은 성공(COMPLETED)이든
     *   실패(FAILED)든 매 시도마다 finished_at 이 찍히므로 실제 시도 이력을 정직하게 반영한다.
     *
     * @param thresholdMinutes      쿨다운(분). 마지막 시도가 이보다 최근이면 대상에서 제외한다.
     * @param codefThresholdMinutes 실 CODEF 사용자의 쿨다운(분). CODEF 는 과다 호출 시 IP 를 차단한다.
     * @param codefScope            NONE · ALL · FLAGGED — 누구를 CODEF 사용자로 볼지(XML 주석 참고)
     */
    List<Long> findUserIdsDueForSync(@Param("thresholdMinutes") int thresholdMinutes,
                                     @Param("codefThresholdMinutes") int codefThresholdMinutes,
                                     @Param("codefScope") String codefScope,
                                     @Param("limit") int limit);
}
