package com.kb.tangtang.account.mapper;

import com.kb.tangtang.account.domain.ConnectedAccount;
import com.kb.tangtang.account.domain.FinancialSyncHistory;
import com.kb.tangtang.user.dto.UserDto;
import com.kb.tangtang.user.mapper.UserMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 진짜 DB 를 쓰므로 테스트 메서드마다 스프링 테스트의 트랜잭션 롤백에 기댄다 — TransactionMapperTest 참고. */
@Disabled("실 DB 연결 필요 — 로컬 수동 검증용")
@SpringJUnitConfig
@ContextConfiguration(classes = com.kb.tangtang.config.RootConfig.class)
@Transactional
class ConnectedAccountMapperTest {

    @Autowired
    private ConnectedAccountMapper connectedAccountMapper;

    @Autowired
    private UserMapper userMapper;

    /** 대상 선정이 이제 이력 테이블을 근거로 하므로 여기서도 이력을 직접 넣는다(이슈 #199 최종 리뷰). */
    @Autowired
    private FinancialSyncHistoryMapper syncHistoryMapper;

    /**
     * 이슈 #147 리뷰에서 발견 — reactivate() 의 SET 절이 bank_code·provider 를 빼먹어서,
     * 최초 insert 이후로는 이 두 컬럼이 재동기화에서 절대 갱신되지 않았다. 계좌를 되살릴 때
     * 새 bankCode/provider 값이 실제로 반영되는지 확인한다.
     */
    @Test
    void reactivateUpdatesBankCodeAndProvider() {
        String accountNoEncrypted = "MOCK-PAYMONEY-TEST-001";
        ConnectedAccount original = ConnectedAccount.builder()
                .userId(1L)
                .bankCode("OLD_CODE")
                .bankName("옛날제공자")
                .accountNoEncrypted(accountNoEncrypted)
                .accountType("PAYMONEY")
                .provider("OLD_PROVIDER")
                .balance(new BigDecimal("10000"))
                .syncStatus("NORMAL")
                .lastSyncAt(LocalDateTime.now())
                .build();
        connectedAccountMapper.insert(original);

        ConnectedAccount reconnected = ConnectedAccount.builder()
                .userId(1L)
                .bankCode("NEW_CODE")
                .bankName("새제공자")
                .accountNoEncrypted(accountNoEncrypted)
                .accountType("PAYMONEY")
                .provider("NEW_PROVIDER")
                .balance(new BigDecimal("20000"))
                .syncStatus("NORMAL")
                .lastSyncAt(LocalDateTime.now())
                .build();
        int updated = connectedAccountMapper.reactivate(reconnected);
        assertEquals(1, updated);

        ConnectedAccount found = connectedAccountMapper.findByIdAndUser(original.getId(), 1L);
        assertEquals("NEW_CODE", found.getBankCode(),
                "reactivate 가 bank_code 를 갱신하지 않으면 최초 insert 값이 영원히 고정된다");
        assertEquals("NEW_PROVIDER", found.getProvider(),
                "reactivate 가 provider 를 갱신하지 않으면 최초 insert 값이 영원히 고정된다");
    }

    /**
     * 이슈 #199 최종 리뷰 — 대상 선정 기준이 tbl_connected_account.last_sync_at 에서
     * tbl_financial_sync_history.finished_at 으로 바뀌었다. sync() 는 자기가 만든 MOCK-* 계좌의
     * last_sync_at 만 갱신해서, CODEF 연동 계좌만 가진 사용자가 영원히 "최우선" 으로 남아 큐를 독점했다.
     *
     * 그래서 이 테스트는 **세 사용자의 계좌 last_sync_at 을 일부러 전부 같게** 두고 이력만 다르게 넣는다 —
     * 옛 SQL 이라면 순서가 결정되지 않아 이 단언이 통과할 수 없다.
     */
    @Test
    @org.junit.jupiter.api.DisplayName("동기화 이력(finished_at)이 가장 오래된 사용자부터 limit 만큼, 쿨다운 안쪽은 제외한다")
    void findUserIdsDueForSyncOrdersByOldestSyncHistoryFirst() {
        // 테스트용 사용자 생성 (transactional rollback 이므로 DB에 영구 기록 안 됨)
        // UserMapper.insert 는 useGeneratedKeys="true" + keyProperty="id" 라서 id 는 insert SQL에
        // 포함되지 않는다 — 빌더에 .id(...)를 미리 넣어도 무시된다. 로컬 변수에 담아 insert 후
        // getId() 로 실제 생성된 PK를 읽어야 tbl_connected_account.fk_ca_user 를 만족한다.
        UserDto user901 = UserDto.builder()
                .socialProvider("TEST")
                .providerUserId("test-user-901")
                .email("test901@test.com")
                .status("ACTIVE")
                .difficultyId(1L)
                .build();
        userMapper.insert(user901);

        UserDto user902 = UserDto.builder()
                .socialProvider("TEST")
                .providerUserId("test-user-902")
                .email("test902@test.com")
                .status("ACTIVE")
                .difficultyId(1L)
                .build();
        userMapper.insert(user902);

        UserDto user903 = UserDto.builder()
                .socialProvider("TEST")
                .providerUserId("test-user-903")
                .email("test903@test.com")
                .status("ACTIVE")
                .difficultyId(1L)
                .build();
        userMapper.insert(user903);

        // is_active 는 tbl_connected_account 에 NOT NULL DEFAULT 1 이지만, insert 문이 항상
        // #{isActive} 를 명시적으로 채워 넣으므로 애플리케이션이 값을 안 주면 DEFAULT 가 아니라
        // 명시적 NULL 이 들어가 NOT NULL 제약을 그대로 위반한다 — 반드시 명시한다.
        // findUserIdsDueForSync 도 WHERE is_active = 1 로 걸러내므로 빠지면 결과에서도 안 잡힌다.
        UserDto user904 = UserDto.builder()
                .socialProvider("TEST")
                .providerUserId("test-user-904")
                .email("test904@test.com")
                .status("ACTIVE")
                .difficultyId(1L)
                .build();
        userMapper.insert(user904);

        // last_sync_at 은 넷 다 "방금" 으로 통일한다 — 정렬이 여기서 오지 않는다는 걸 못박기 위함.
        LocalDateTime sameForEveryone = LocalDateTime.now();
        connectedAccountMapper.insert(ConnectedAccount.builder()
                .userId(user901.getId()).accountNoEncrypted("MOCK-BANK-T901")
                .accountType("DEMAND_DEPOSIT").syncStatus("NORMAL").isActive(true)
                .lastSyncAt(sameForEveryone)
                .build());
        connectedAccountMapper.insert(ConnectedAccount.builder()
                .userId(user902.getId()).accountNoEncrypted("MOCK-BANK-T902")
                .accountType("DEMAND_DEPOSIT").syncStatus("NORMAL").isActive(true)
                .lastSyncAt(sameForEveryone)
                .build());
        connectedAccountMapper.insert(ConnectedAccount.builder()
                .userId(user903.getId()).accountNoEncrypted("MOCK-BANK-T903")
                .accountType("DEMAND_DEPOSIT").syncStatus("NORMAL").isActive(true)
                .lastSyncAt(sameForEveryone)
                .build());
        connectedAccountMapper.insert(ConnectedAccount.builder()
                .userId(user904.getId()).accountNoEncrypted("MOCK-BANK-T904")
                .accountType("DEMAND_DEPOSIT").syncStatus("NORMAL").isActive(true)
                .lastSyncAt(sameForEveryone)
                .build());

        /*
         * 이력만으로 순서·자격이 갈린다.
         *  901: 3시간 전 성공  → 두 번째로 오래됨
         *  902: 이력 없음      → NULL 이라 맨 앞
         *  903: 10시간 전 실패 → FAILED 도 "시도" 다. 가장 오래됐지만 902 다음
         *  904: 5분 전 성공    → 쿨다운(30분) 안쪽이라 아예 제외돼야 한다
         */
        syncHistoryMapper.insert(FinancialSyncHistory.builder()
                .userId(user901.getId()).status("COMPLETED").syncedSourcesJson("[\"BANK\"]")
                .startedAt(LocalDateTime.now().minusHours(3))
                .finishedAt(LocalDateTime.now().minusHours(3))
                .build());
        syncHistoryMapper.insert(FinancialSyncHistory.builder()
                .userId(user903.getId()).status("FAILED").failedSource("BANK").failReason("목서버 응답 없음")
                .startedAt(LocalDateTime.now().minusHours(10))
                .finishedAt(LocalDateTime.now().minusHours(10))
                .build());
        syncHistoryMapper.insert(FinancialSyncHistory.builder()
                .userId(user904.getId()).status("COMPLETED").syncedSourcesJson("[\"BANK\"]")
                .startedAt(LocalDateTime.now().minusMinutes(5))
                .finishedAt(LocalDateTime.now().minusMinutes(5))
                .build());

        List<Long> due = connectedAccountMapper.findUserIdsDueForSync(30, 30, "NONE", 10);

        /* 904 는 쿨다운 안쪽이라 빠지고, 나머지는 마지막 시도가 오래된 순이다. */
        assertEquals(List.of(user902.getId(), user903.getId(), user901.getId()),
                due.stream().filter(List.of(user901.getId(), user902.getId(),
                                user903.getId(), user904.getId())::contains)
                        .collect(java.util.stream.Collectors.toList()));
    }
}
