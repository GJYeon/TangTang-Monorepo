package com.kb.tangtang.account.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.kb.tangtang.account.client.FinancialSourceResolver;
import com.kb.tangtang.account.mapper.ConnectedAccountMapper;

/**
 * 이슈 #199 — 계좌·카드 연동이 있는 사용자를 주기적으로 자동 동기화한다.
 * LlmCategorizationScheduler/NotificationDlqRetryScheduler 와 같은 패턴: 한 사용자가 실패해도
 * 배치 전체가 멈추지 않는다.
 *
 * ⚠ 이 클래스는 "누구를, 몇 명까지" 만 결정한다. 실제 동기화 로직은 전혀 갖지 않고
 *   FinancialSyncServiceImpl.sync() 를 그대로 재사용한다 — 수동 트리거(POST /sync)와 완전히
 *   같은 파이프라인이다. 별도 잠금·선점 테이블이 없는 이유: @Scheduled(fixedDelay) 는 이전
 *   실행이 끝나야 다음이 시작돼 이 메서드 자체는 절대 중첩 실행되지 않고, sync() 내부의 저장은
 *   자연키 upsert 라 수동 트리거와 배치가 같은 사용자를 우연히 겹쳐 돌려도 안전하다
 *   (FinancialSyncServiceImpl 클래스 Javadoc 「동시 insert 경합」 참고).
 *
 * ⚠ 이 무잠금 설계는 **단일 JVM 배포를 전제**한다(최종 리뷰 후속, PR 코멘트 P1).
 *   {@code @Scheduled(fixedDelay)} 는 JVM 하나 안에서만 중첩 실행을 막는다 — 인스턴스를 2대
 *   이상으로 스케일아웃하면 두 인스턴스의 배치가 같은 사용자를 동시에 돌릴 수 있고, 그때부터는
 *   전적으로 자연키 upsert 의 DuplicateKeyException 재시도 하나에만 기대게 된다. 지금은
 *   `docker-compose.yml`/EC2 배포가 API 컨테이너 1개뿐이라 안전하지만, 다중 인스턴스로
 *   전환하면 사용자별 DB 선점 상태나 분산 락을 다시 검토해야 한다.
 */
@Component
public class FinancialSyncBatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(FinancialSyncBatchScheduler.class);

    private final ConnectedAccountMapper connectedAccountMapper;
    private final FinancialSyncService financialSyncService;
    private final boolean enabled;
    private final int maxUsersPerTick;
    /** 재동기화 쿨다운(분). 마지막 동기화 시도가 이보다 최근인 사용자는 이번 틱에서 뺀다(이슈 #199 최종 리뷰). */
    private final int minIntervalMinutes;
    /**
     * 실 CODEF 사용자의 쿨다운(분). CODEF 는 "배치형 서비스 또는 비정상 호출로 간주될 경우 IP 가 차단될 수 있다"
     * 고 경고한다(상품 문서 Summary) — 목서버 주기(10분)로 돌리면 안 된다. 수동 새로고침은 이 값과 무관하다.
     */
    private final int codefMinIntervalMinutes;
    /** NONE · ALL · FLAGGED — financial.client 모드에서 정해진다(ConnectedAccountMapper.xml 참고). */
    private final String codefScope;

    @Autowired
    public FinancialSyncBatchScheduler(ConnectedAccountMapper connectedAccountMapper,
                                       FinancialSyncService financialSyncService,
                                       FinancialSourceResolver sources,
                                       @Value("${financial.sync.batch.enabled}") boolean enabled,
                                       @Value("${financial.sync.batch.max-users-per-tick}") int maxUsersPerTick,
                                       @Value("${financial.sync.batch.min-interval-minutes}") int minIntervalMinutes,
                                       @Value("${financial.codef.sync.min-interval-minutes:360}") int codefMinIntervalMinutes) {
        this.connectedAccountMapper = connectedAccountMapper;
        this.financialSyncService = financialSyncService;
        this.enabled = enabled;
        this.maxUsersPerTick = maxUsersPerTick;
        this.minIntervalMinutes = minIntervalMinutes;
        this.codefMinIntervalMinutes = codefMinIntervalMinutes;
        this.codefScope = switch (sources.mode()) {
            case MOCK -> "NONE";
            case CODEF -> "ALL";
            case HYBRID -> "FLAGGED";
        };
    }

    /** 목 모드 전용(테스트). CODEF 사용자가 없으므로 CODEF 쿨다운은 쓰이지 않는다. */
    FinancialSyncBatchScheduler(ConnectedAccountMapper connectedAccountMapper,
                                FinancialSyncService financialSyncService,
                                boolean enabled, int maxUsersPerTick, int minIntervalMinutes) {
        this(connectedAccountMapper, financialSyncService,
                new FinancialSourceResolver(FinancialSourceResolver.Mode.MOCK, null),
                enabled, maxUsersPerTick, minIntervalMinutes, minIntervalMinutes);
    }

    @Scheduled(fixedDelayString = "${financial.sync.batch.fixed-delay-ms}")
    public void runBatch() {
        if (!enabled) {
            return;
        }
        for (Long userId : connectedAccountMapper.findUserIdsDueForSync(
                minIntervalMinutes, codefMinIntervalMinutes, codefScope, maxUsersPerTick)) {
            try {
                financialSyncService.sync(userId);
            } catch (Exception e) {
                /* 한 사용자 때문에 배치 전체가 멈추면 안 된다 — sync() 내부에서 이미 실패 이력을 남겼다. */
                log.error("배치 동기화 실패 userId={}", userId, e);
            }
        }
    }
}
