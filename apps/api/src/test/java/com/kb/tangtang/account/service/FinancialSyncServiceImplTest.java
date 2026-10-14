package com.kb.tangtang.account.service;

import com.kb.tangtang.account.client.sync.FinancialSyncClient;
import com.kb.tangtang.account.client.sync.ScenarioKeyProvider;
import com.kb.tangtang.account.client.sync.dto.BankAccountSyncDto;
import com.kb.tangtang.account.client.sync.dto.BankTransactionSyncDto;
import com.kb.tangtang.account.client.sync.dto.CardApprovalSyncDto;
import com.kb.tangtang.account.client.sync.dto.CardSyncDto;
import com.kb.tangtang.account.client.sync.dto.DepositSyncDto;
import com.kb.tangtang.account.client.sync.dto.DepositTransactionSyncDto;
import com.kb.tangtang.account.client.sync.dto.LoanSyncDto;
import com.kb.tangtang.account.client.sync.dto.PayMoneySyncDto;
import com.kb.tangtang.account.client.sync.dto.PayMoneyTransactionSyncDto;
import com.kb.tangtang.account.client.sync.dto.SecuritiesTransactionSyncDto;
import com.kb.tangtang.account.client.sync.dto.StockAssetSyncDto;
import com.kb.tangtang.account.domain.Card;
import com.kb.tangtang.account.domain.ConnectedAccount;
import com.kb.tangtang.account.domain.LlmCategorizationRequestedEvent;
import com.kb.tangtang.account.domain.Loan;
import com.kb.tangtang.account.dto.FinancialSyncResultDto;
import com.kb.tangtang.account.mapper.CardBillMapper;
import com.kb.tangtang.account.mapper.CardMapper;
import com.kb.tangtang.account.mapper.ConnectedAccountMapper;
import com.kb.tangtang.account.mapper.FinancialSyncHistoryMapper;
import com.kb.tangtang.account.mapper.InvestmentHoldingMapper;
import com.kb.tangtang.account.mapper.LoanMapper;
import com.kb.tangtang.common.exception.BusinessException;
import com.kb.tangtang.transaction.dto.RuleCategorizationResultDto;
import com.kb.tangtang.transaction.mapper.TransactionMapper;
import com.kb.tangtang.transaction.service.TransactionCategorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 이슈 #147 — 금융 동기화 오케스트레이터 단위 테스트.
 * 매퍼·클라이언트는 전부 목이라 DB·목서버가 없어도 돈다 (AccountLinkServiceTest 와 같은 방식).
 */
class FinancialSyncServiceImplTest {

    /**
     * 트랜잭션 개시·커밋과 DB 쓰기를 **한 줄에** 기록한다. 순서를 검증하려면 둘을 같은 타임라인에
     * 올려야 한다 — 트랜잭션 쪽은 목이 아니라 실제 구현이라 Mockito InOrder 하나로는 못 묶는다.
     */
    private List<String> timeline;

    /**
     * 커넥션 없이 콜백만 그대로 실행하는 트랜잭션 매니저.
     *
     * TransactionTemplate 은 매니저를 목으로 줘도 콜백을 부르긴 하지만, 그건 목의 기본 반환값
     * (getTransaction → null)에 기대는 동작이라 의도가 드러나지 않는다. 여기서는 개시·커밋 시점을
     * 직접 기록해 경계 순서까지 검증한다. 실제 경계는 RootConfig 의 빈이 담당한다.
     */
    private PlatformTransactionManager recordingTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                timeline.add("BEGIN");
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                timeline.add("COMMIT");
            }

            @Override
            public void rollback(TransactionStatus status) {
                timeline.add("ROLLBACK");
            }
        };
    }

    private FinancialSyncClient client;
    private ScenarioKeyProvider scenarioKeyProvider;
    /* AccountLinkServiceTest 와 같은 테스트 전용 시크릿 — 값 자체는 해시 알고리즘 검증에 관여하지 않는다. */
    private final AccountNumberPolicy accountNumbers =
            new AccountNumberPolicy("test-secret-key-for-account-hash-0001");
    private ConnectedAccountMapper connectedAccountMapper;
    private LoanMapper loanMapper;
    private InvestmentHoldingMapper investmentHoldingMapper;
    private CardMapper cardMapper;
    private CardBillMapper cardBillMapper;
    private TransactionMapper transactionMapper;
    private FinancialSyncHistoryMapper syncHistoryMapper;
    private TransactionCategorizationService transactionCategorizationService;
    private ApplicationEventPublisher eventPublisher;
    private FinancialSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        client = mock(FinancialSyncClient.class);
        scenarioKeyProvider = mock(ScenarioKeyProvider.class);
        connectedAccountMapper = mock(ConnectedAccountMapper.class);
        loanMapper = mock(LoanMapper.class);
        investmentHoldingMapper = mock(InvestmentHoldingMapper.class);
        cardMapper = mock(CardMapper.class);
        cardBillMapper = mock(CardBillMapper.class);
        transactionMapper = mock(TransactionMapper.class);
        syncHistoryMapper = mock(FinancialSyncHistoryMapper.class);
        timeline = new ArrayList<>();

        Clock clock = Clock.fixed(Instant.parse("2026-08-12T01:00:00Z"), ZoneId.of("Asia/Seoul"));

        transactionCategorizationService = mock(TransactionCategorizationService.class);
        when(transactionCategorizationService.categorizeRuleBased(anyLong(), any()))
                .thenReturn(RuleCategorizationResultDto.builder()
                        .ruleCategorizedCount(0)
                        .llmEligibleTransactionIds(List.of())
                        .build());
        eventPublisher = mock(ApplicationEventPublisher.class);

        /*
         * collectAll() 이 이제 6개 소스를 병렬로 모은다(성능 개선). 대부분의 테스트는 순서·타임라인을
         * 검증하므로 여기서는 호출 스레드에서 그대로 실행하는 직렬 실행기를 쓴다 — 결과는 실제 병렬
         * 실행과 같고(각 소스가 서로 다른 SyncBundle 필드만 쓴다) 테스트는 결정적으로 유지된다.
         * 병렬 실행 자체(스레드 안전성)는 아래 collectAllRunsSourcesInParallelWithoutDataCorruption 이
         * 진짜 스레드풀로 따로 검증한다.
         */
        service = new FinancialSyncServiceImpl(
                client, scenarioKeyProvider, accountNumbers, connectedAccountMapper, loanMapper,
                investmentHoldingMapper, cardMapper, cardBillMapper, transactionMapper,
                syncHistoryMapper, transactionCategorizationService, eventPublisher,
                clock, new TransactionTemplate(recordingTransactionManager()), Runnable::run);

        when(scenarioKeyProvider.resolve(1L)).thenReturn("1");
        // 이번 테스트는 BANK 만 데이터가 있고 나머지 5개 소스는 빈 목록으로 둔다.
        when(client.getBankAccounts("1")).thenReturn(List.of(
                BankAccountSyncDto.builder()
                        .accountId(101L).institutionCode("0004").institutionName("KB국민은행")
                        .accountTypeCode("1001").productName("KB Star Checking")
                        .accountNoMasked("110-***-120045").currency("KRW")
                        .balance(new BigDecimal("1244200")).availableAmount(new BigDecimal("1244200"))
                        .build()));
        when(client.getBankTransactions(eq("1"), eq(101L), isNull())).thenReturn(List.of(
                BankTransactionSyncDto.builder()
                        .transactionId(9001L).transactedAt("2026-08-10T09:05:01+09:00")
                        .transTypeCode("01").amount(new BigDecimal("50000"))
                        .balanceAfter(new BigDecimal("1194200")).description("이체")
                        .rawJson(null)
                        .build()));
        when(client.getDeposits("1")).thenReturn(List.of());
        when(client.getStockAsset("1")).thenReturn(null);
        when(client.getLoans("1")).thenReturn(List.of());
        when(client.getPayMoney("1")).thenReturn(List.of());
        when(client.getCards("1")).thenReturn(List.of());

        /* reactivate 가 0 을 돌려주므로 기본 시나리오는 insert 경로다. update 경로는
           resyncReusesExistingRowOnUpdatePath 가 따로 검증한다.

           ⚠ 반드시 PK 를 채워야 한다. XML 의 insert 는 useGeneratedKeys="true" 라 실제로는 항상
             id 가 돌아오는데, 예전 스텁은 그걸 흉내내지 않아 계좌 PK 가 null 인 채로(현실에 없는 상태)
             테스트가 돌았다 — 그래서 "계좌 PK 가 null" 을 "이 계좌는 건너뛴다" 신호로 쓸 수가 없었다
             (이슈 #199 최종 리뷰). */
        when(connectedAccountMapper.insert(any())).thenAnswer(inv -> {
            /* null 체크 필수: 개별 테스트가 이 스텁을 덮어쓸 때 when(mock.insert(any())) 호출 자체가
               여기 답변을 인자 null 로 한 번 실행시킨다. */
            ConnectedAccount saved = inv.getArgument(0);
            if (saved != null) {
                saved.setId(77L);
            }
            return 1;
        });
        when(transactionMapper.update(any())).thenReturn(0);
        when(transactionMapper.insert(any())).thenReturn(1);
    }

    @Test
    @DisplayName("모든 소스가 성공하면 COMPLETED 를 반환하고 이력을 성공으로 남긴다")
    void syncSucceeds() {
        FinancialSyncResultDto result = service.sync(1L);

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(List.of("BANK", "DEPOSIT", "SECURITIES", "LOAN", "PAY_MONEY", "CARD"),
                result.getSyncedSources());
        verify(syncHistoryMapper).insert(argThat(h -> "COMPLETED".equals(h.getStatus())));
        verify(transactionMapper).linkByCorrelation(1L);
    }

    @Test
    @DisplayName("증권 연결 계좌에는 현금과 보유주식 평가액을 합친 금액을 저장한다")
    void storesSecuritiesCashAndMarketValueTogether() {
        when(client.getStockAsset("1")).thenReturn(
                StockAssetSyncDto.builder().accountId(301L).institutionCode("0218")
                        .institutionName("KB증권").accountName("KB 증권 투자계좌")
                        .accountNoMasked("301-***-INV2002")
                        .currency("KRW").cashBalance(BigDecimal.ZERO)
                        .totalMarketValue(new BigDecimal("1303600")).build());
        when(client.getSecuritiesTransactions(eq("1"), eq(301L))).thenReturn(List.of());

        service.sync(1L);

        verify(connectedAccountMapper).insert(argThat(account ->
                "SECURITIES".equals(account.getAccountType())
                        && "0218".equals(account.getBankCode())
                        && accountNumbers.hash("0218", "301-***-INV2002")
                        .equals(account.getAccountNoEncrypted())
                        && new BigDecimal("1303600").compareTo(account.getBalance()) == 0));
    }

    @Test
    @DisplayName("한 소스라도 실패하면 DB 를 건드리지 않고 실패 이력만 남긴다")
    void oneSourceFailsAbortsWholeSync() {
        when(client.getCards("1")).thenThrow(
                new BusinessException("EXTERNAL_API_UNAVAILABLE", "목서버 응답 없음"));

        BusinessException e = assertThrows(BusinessException.class, () -> service.sync(1L));

        assertEquals("EXTERNAL_API_UNAVAILABLE", e.getCode());
        verify(connectedAccountMapper, never()).insert(any());
        verify(transactionMapper, never()).insert(any());
        verify(syncHistoryMapper).insert(argThat(h ->
                "FAILED".equals(h.getStatus()) && "CARD".equals(h.getFailedSource())));
    }

    @Test
    @DisplayName("같은 데이터로 두 번 동기화해도 거래를 두 번 insert 하지 않는다")
    void resyncIsIdempotent() {
        when(transactionMapper.update(any())).thenReturn(0).thenReturn(1); // 1차 insert, 2차 update
        when(transactionMapper.insert(any())).thenReturn(1);

        service.sync(1L);
        service.sync(1L);

        verify(transactionMapper, times(1)).insert(any());
        verify(transactionMapper, times(2)).update(any());
    }

    @Test
    @DisplayName("두 번째 동기화는 update 경로를 타고 첫 동기화가 만든 행의 PK 를 그대로 재사용한다")
    void resyncReusesExistingRowOnUpdatePath() {
        /*
         * 1차: reactivate 0행 → insert (PK 77 부여). 2차: reactivate 1행 → 자연키 재조회로 같은 77.
         * 이 스텁이 없으면 두 번째 sync 도 insert 분기를 타 update 경로(재조회 포함)가 통째로
         * 검증되지 않는다 — 예전 테스트가 그랬다.
         */
        when(connectedAccountMapper.insert(any())).thenAnswer(inv -> {
            ConnectedAccount saved = inv.getArgument(0);
            saved.setId(77L);   // useGeneratedKeys 흉내
            return 1;
        });
        when(connectedAccountMapper.reactivate(any())).thenReturn(0).thenReturn(1);
        /* #334: 은행 계좌는 AccountLinkService 와 같은 해시 키를 쓴다(upsertBankAccount 문서 참고) —
           setUp() 의 getBankAccounts 스텁(institutionCode=0004, accountNoMasked=110-***-120045)과
           같은 입력으로 재계산해야 findConnectedAccountId() 의 자연키 재조회가 이 행을 찾는다. */
        when(connectedAccountMapper.findActiveByUser(1L)).thenReturn(List.of(
                ConnectedAccount.builder().id(77L).userId(1L)
                        .accountNoEncrypted(accountNumbers.hash("0004", "110-***-120045")).build()));
        when(transactionMapper.update(any())).thenReturn(0).thenReturn(1);

        service.sync(1L);
        service.sync(1L);

        /* 계좌는 한 번만 만들어진다 — 2차는 되살리기(update) 경로다. */
        verify(connectedAccountMapper, times(1)).insert(any());
        verify(connectedAccountMapper, times(2)).reactivate(any());
        /* 핵심: 2차의 멱등키가 1차와 같은 내부 PK(77)를 쓴다. 목서버 ID(101)를 쓰거나 재조회가
           엉뚱한 행을 집으면 새 행이 하나 더 생긴다. */
        verify(transactionMapper, times(2)).update(argThat(t -> "BANK-77-9001".equals(t.getCodefTrKey())));
        verify(transactionMapper, times(1)).insert(argThat(t -> "BANK-77-9001".equals(t.getCodefTrKey())));
    }

    @Test
    @DisplayName("#334 리뷰 지적: 계좌 연동(AccountLinkService.link())이 이미 만든 은행 계좌 행을 " +
            "배치 동기화가 중복으로 또 만들지 않는다 — 같은 은행코드·마스킹번호는 같은 자연키를 낸다")
    void bankSyncReusesRowAlreadyCreatedByLinkFlow() {
        /*
         * AccountLinkService.link() 는 accountNumbers.hash(bankCode, accountNoMasked) 를 자연키로
         * 쓴다. 이 배치도 은행 계좌만큼은 같은 해시를 써야(upsertBankAccount 문서 참고) 두 흐름이
         * 같은 실계좌를 서로 다른 행으로 중복 생성하지 않는다 — 실제로 화면에 같은 계좌(같은 마스킹
         * 번호·같은 잔액)가 두 번 뜨는 것으로 재현됐다.
         */
        String linkFlowKey = accountNumbers.hash("0004", "110-***-120045");
        when(connectedAccountMapper.findActiveByUser(1L)).thenReturn(List.of(
                ConnectedAccount.builder().id(55L).userId(1L)
                        .accountNoEncrypted(linkFlowKey).accountNoMasked("110-***-120045").build()));
        when(connectedAccountMapper.reactivate(any())).thenReturn(1);

        service.sync(1L);

        /* 계좌 연동이 만든 자연키와 정확히 같은 키로 재활성화(update)만 일어나야 한다 — 새 행을
           만들면 안 된다. */
        verify(connectedAccountMapper).reactivate(argThat(a -> linkFlowKey.equals(a.getAccountNoEncrypted())));
        verify(connectedAccountMapper, never()).insert(any());
    }

    @Test
    @DisplayName("실 CODEF 계좌는 전체 번호(accountNoRaw)로 해시한다 — 연동 흐름이 만든 행과 같은 키가 된다")
    void codefBankSyncHashesRawAccountNumber() {
        /* 실 CODEF 연동은 hash(기관, 전체번호) 로 행을 만든다. 마스킹 번호로 해시하면 같은 계좌가 두 행이 된다. */
        String linkFlowKey = accountNumbers.hash("0004", "02004130000001");
        when(client.getBankAccounts("1")).thenReturn(List.of(
                BankAccountSyncDto.builder()
                        .accountId(101L).institutionCode("0004").institutionName("KB국민은행")
                        .accountTypeCode("DEMAND_DEPOSIT").productName("KB국민ONE통장")
                        .accountNoMasked(accountNumbers.mask("02004130000001"))
                        .accountNoRaw("02004130000001")
                        .currency("KRW").balance(new BigDecimal("546000"))
                        .build()));
        when(connectedAccountMapper.reactivate(any())).thenReturn(1);
        when(connectedAccountMapper.findActiveByUser(1L)).thenReturn(List.of(
                ConnectedAccount.builder().id(55L).userId(1L).accountNoEncrypted(linkFlowKey).build()));

        service.sync(1L);

        verify(connectedAccountMapper).reactivate(argThat(a -> linkFlowKey.equals(a.getAccountNoEncrypted())
                && accountNumbers.mask("02004130000001").equals(a.getAccountNoMasked())));
        verify(connectedAccountMapper, never()).insert(any());
    }

    @Test
    @DisplayName("card_type_code 01=CARD_CREDIT, 02=CARD_CHECK 으로 분류하고, 동기화 후 연결 SQL 을 부른다")
    void classifiesCardTypesAndLinksCorrelation() {
        when(client.getCards("1")).thenReturn(List.of(
                CardSyncDto.builder().cardId(1L).cardNoMasked("9490-****-****-2201")
                        .cardTypeCode("01").currency("KRW").build(),
                CardSyncDto.builder().cardId(2L).cardNoMasked("5210-****-****-7714")
                        .cardTypeCode("02").currency("KRW").build()));
        when(client.getCardApprovals(eq("1"), eq(1L), isNull())).thenReturn(List.of(
                CardApprovalSyncDto.builder().approvalId(1L).approvalNo("APV-CREDIT-1")
                        .approvedAt("2026-08-10T12:00:00+09:00").approvedAmount(new BigDecimal("30000"))
                        .rawJson(null).build()));
        when(client.getCardApprovals(eq("1"), eq(2L), isNull())).thenReturn(List.of(
                CardApprovalSyncDto.builder().approvalId(2L).approvalNo("APV-CHECK-1")
                        .approvedAt("2026-08-10T13:00:00+09:00").approvedAmount(new BigDecimal("15000"))
                        .rawJson("{\"correlationId\":\"N2-CHK-0617\"}").build()));
        when(client.getCardBills(anyString(), anyLong())).thenReturn(List.of());
        when(cardMapper.update(any())).thenReturn(0);
        doAnswer(inv -> {
            Card c = inv.getArgument(0);
            c.setId(c.getCardNoMasked().equals("9490-****-****-2201") ? 100L : 200L);
            return 1;
        }).when(cardMapper).insert(any());

        service.sync(1L);

        verify(transactionMapper, atLeastOnce()).insert(argThat(t -> "CARD_CREDIT".equals(t.getSourceType())));
        verify(transactionMapper, atLeastOnce()).insert(argThat(t ->
                "CARD_CHECK".equals(t.getSourceType()) && "N2-CHK-0617".equals(t.getCorrelationId())));
        verify(transactionMapper).linkByCorrelation(1L);
    }

    @Test
    @DisplayName("은행 거래의 raw_json 에 있는 correlationId 를 채운다 — 체크카드 연결의 전제다")
    void bankTransactionCarriesCorrelationId() {
        when(client.getBankTransactions(eq("1"), eq(101L), isNull())).thenReturn(List.of(
                BankTransactionSyncDto.builder()
                        .transactionId(9002L).transactedAt("2026-08-10T13:00:02+09:00")
                        .transTypeCode("02").amount(new BigDecimal("15000"))
                        .balanceAfter(new BigDecimal("1179200")).description("체크카드 출금")
                        .rawJson("{\"correlationId\":\"N2-CHK-0617\",\"challengeEligible\":true}")
                        .build()));

        service.sync(1L);

        verify(transactionMapper).insert(argThat(t ->
                "BANK".equals(t.getSourceType()) && "N2-CHK-0617".equals(t.getCorrelationId())));
    }

    @Test
    @DisplayName("멱등키에 목서버 ID 가 아니라 우리 DB PK 를 쓴다 — 사용자끼리 키가 충돌하면 안 된다")
    void codefTrKeyIsScopedByInternalId() {
        when(connectedAccountMapper.insert(any())).thenAnswer(inv -> {
            ConnectedAccount saved = inv.getArgument(0);
            saved.setId(77L);   // useGeneratedKeys 흉내
            return 1;
        });

        service.sync(1L);

        /* 목서버 계좌 ID(101)가 아니라 우리 PK(77)가 들어가야 한다. 101 이면 시나리오 키를 공유하는
           다른 사용자와 같은 키가 만들어져 서로의 거래를 덮어쓴다. */
        verify(transactionMapper).insert(argThat(t -> "BANK-77-9001".equals(t.getCodefTrKey())));
    }

    @Test
    @DisplayName("모든 DB 쓰기가 트랜잭션 개시 후 · 커밋 전에 일어난다 (이력 insert·연결 SQL 포함)")
    void allWritesHappenInsideOneTransaction() {
        when(connectedAccountMapper.insert(any())).thenAnswer(inv -> {
            timeline.add("WRITE:account");
            ((ConnectedAccount) inv.getArgument(0)).setId(77L);  // useGeneratedKeys 흉내
            return 1;
        });
        when(transactionMapper.insert(any())).thenAnswer(inv -> {
            timeline.add("WRITE:transaction");
            return 1;
        });
        when(transactionMapper.linkByCorrelation(1L)).thenAnswer(inv -> {
            timeline.add("WRITE:link");
            return 0;
        });
        when(syncHistoryMapper.insert(any())).thenAnswer(inv -> {
            timeline.add("WRITE:history");
            return 1;
        });

        service.sync(1L);

        /*
         * 순서를 통째로 못박는다. 예전 @Transactional 자기호출 버전이었다면 BEGIN/COMMIT 자체가 없어
         * 이 단언이 깨진다. 연결 SQL 이나 이력 insert 를 나중에 트랜잭션 밖으로 빼도 마찬가지다.
         */
        assertEquals(
                List.of("BEGIN", "WRITE:account", "WRITE:transaction", "WRITE:link", "WRITE:history",
                        "COMMIT"),
                timeline);
    }

    @Test
    @DisplayName("저장 단계에서 터져도 실패 이력을 남긴다 — 500 만 뜨고 흔적이 없으면 안 된다")
    void writeFailureIsRecordedInHistory() {
        when(transactionMapper.insert(any())).thenThrow(
                new IllegalStateException("Duplicate entry 'BANK-1-9001' for key 'uk_tx_codef_key'"));

        assertThrows(IllegalStateException.class, () -> service.sync(1L));

        verify(syncHistoryMapper).insert(argThat(h ->
                "FAILED".equals(h.getStatus())
                        && "SAVE".equals(h.getFailedSource())
                        && h.getFailReason() != null && h.getFailReason().contains("Duplicate entry")));
        /* 실패 이력은 롤백된 트랜잭션 밖에서 남겨야 한다. */
        assertEquals(List.of("BEGIN", "ROLLBACK"), timeline);
    }

    @Test
    @DisplayName("은행 입금(01)은 IN/INCOME, 출금(02)은 OUT/CONSUMPTION 으로 저장한다")
    void bankTransTypeCodeDrivesDirectionAndClassification() {
        when(client.getBankTransactions(eq("1"), eq(101L), isNull())).thenReturn(List.of(
                BankTransactionSyncDto.builder()
                        .transactionId(9001L).transactedAt("2026-08-10T09:05:01+09:00")
                        .transTypeCode("01").amount(new BigDecimal("2500000")).description("급여")
                        .build(),
                BankTransactionSyncDto.builder()
                        .transactionId(9002L).transactedAt("2026-08-11T09:05:01+09:00")
                        .transTypeCode("02").amount(new BigDecimal("15000")).description("체크카드 출금")
                        .build()));

        service.sync(1L);

        /* 급여 입금을 CONSUMPTION 으로 넣으면 미션·리포트의 소비 집계가 통째로 오염된다. */
        verify(transactionMapper).insert(argThat(t ->
                "급여".equals(t.getMerchantName())
                        && "IN".equals(t.getDirection())
                        && "INCOME".equals(t.getClassification())));
        verify(transactionMapper).insert(argThat(t ->
                "체크카드 출금".equals(t.getMerchantName())
                        && "OUT".equals(t.getDirection())
                        && "CONSUMPTION".equals(t.getClassification())));
    }

    @Test
    @DisplayName("모르는 거래유형 코드는 지어내지 않는다 — direction 은 비우고 TRANSFER 로 둔다")
    void unknownBankTransTypeCodeFallsBackToTransfer() {
        when(client.getBankTransactions(eq("1"), eq(101L), isNull())).thenReturn(List.of(
                BankTransactionSyncDto.builder()
                        .transactionId(9003L).transactedAt("2026-08-10T09:05:01+09:00")
                        .transTypeCode("99").amount(new BigDecimal("1000")).description("정체불명")
                        .build()));

        service.sync(1L);

        /* classification 은 NOT NULL + CHECK 라 비울 수 없다 — 소비도 수입도 아닌 값을 고른다. */
        verify(transactionMapper).insert(argThat(t ->
                t.getDirection() == null && "TRANSFER".equals(t.getClassification())));
    }

    @Test
    @DisplayName("예적금·증권 거래는 소비가 아니라 TRANSFER 다")
    void depositAndSecuritiesAreTransfer() {
        when(client.getDeposits("1")).thenReturn(List.of(
                DepositSyncDto.builder().depositAccountId(201L).institutionCode("0004")
                        .institutionName("KB국민은행").productName("KB 적금")
                        .accountNoMasked("110-***-999999").balance(new BigDecimal("1000000")).build()));
        when(client.getDepositTransactions(eq("1"), eq(201L))).thenReturn(List.of(
                DepositTransactionSyncDto.builder()
                        .transactionId(7001L).transactedAt("2026-08-05T09:00:00+09:00")
                        .transTypeCode("01").amount(new BigDecimal("300000")).description("납입")
                        .build()));
        when(client.getStockAsset("1")).thenReturn(
                StockAssetSyncDto.builder().accountId(301L).institutionName("KB증권")
                        .currency("KRW").cashBalance(new BigDecimal("50000")).build());
        when(client.getSecuritiesTransactions(eq("1"), eq(301L))).thenReturn(List.of(
                SecuritiesTransactionSyncDto.builder()
                        .transactionId(8001L).transactedAt("2026-08-06T10:00:00+09:00")
                        .transTypeCode("02").securityProductName("삼성전자")
                        .transactionAmount(new BigDecimal("700000")).build()));

        service.sync(1L);

        /* 적금 납입·주식 매도 대금을 CONSUMPTION 으로 잡으면 지출이 없는 돈까지 부풀려진다. */
        verify(transactionMapper).insert(argThat(t ->
                "DEPOSIT".equals(t.getSourceType()) && "TRANSFER".equals(t.getClassification())));
        verify(transactionMapper).insert(argThat(t ->
                "SECURITIES".equals(t.getSourceType()) && "TRANSFER".equals(t.getClassification())));
    }

    @Test
    @DisplayName("적금 동기화는 계좌 선택 저장과 같은 마스킹 번호 해시를 쓴다")
    void depositSyncUsesSameAccountKeyAsLinkSelection() {
        when(client.getDeposits("1")).thenReturn(List.of(
                DepositSyncDto.builder().depositAccountId(201L).institutionCode("0004")
                        .institutionName("KB국민은행").productName("KB 매월 챌린지 적금")
                        .accountNoMasked("110-***-SAVING2").balance(new BigDecimal("1500000")).build()));
        when(client.getDepositTransactions(eq("1"), eq(201L))).thenReturn(List.of());

        service.sync(1L);

        verify(connectedAccountMapper).insert(argThat(account ->
                accountNumbers.hash("0004", "110-***-SAVING2").equals(account.getAccountNoEncrypted())));
    }

    @Test
    @DisplayName("대출 동기화는 목서버의 마스킹 대출번호를 보존한다")
    void loanSyncPreservesMaskedLoanNumber() {
        when(client.getLoans("1")).thenReturn(List.of(
                LoanSyncDto.builder().loanId(301L).institutionName("KB국민은행")
                        .productName("KB 신용대출").loanNoMasked("LN-2025-****-0001")
                        .principal(new BigDecimal("15000000")).balance(new BigDecimal("14200000")).build()));
        when(client.getLoanTransactions(eq("1"), eq(301L))).thenReturn(List.of());

        service.sync(1L);

        verify(loanMapper).insert(argThat(loan ->
                "LN-2025-****-0001".equals(loan.getLoanNoMasked())));
    }

    @Test
    @DisplayName("#467: 제외 키(is_active=0)가 남아 있는 대출·카드는 동기화가 되살리지 않는다")
    void excludedLoanAndCardAreSkipped() {
        when(connectedAccountMapper.findInactiveKeysByUser(1L))
                /* 카드 키는 목서버 id 가 아니라 마스킹 번호다(#467) — tbl_card 에서 되찾을 수 있는 유일한 값. */
                .thenReturn(List.of("MOCK-LOAN-301", "MOCK-CARD-1234-****-****-0021"));
        when(client.getLoans("1")).thenReturn(List.of(
                LoanSyncDto.builder().loanId(301L).institutionCode("CP_KB").institutionName("KB캐피탈")
                        .productName("KB 신용대출").loanNoMasked("LN-2025-****-0001")
                        .principal(new BigDecimal("15000000")).balance(new BigDecimal("14200000")).build(),
                LoanSyncDto.builder().loanId(302L).institutionCode("CP_KB").institutionName("KB캐피탈")
                        .productName("KB 전세대출").loanNoMasked("LN-2025-****-0002")
                        .principal(new BigDecimal("50000000")).balance(new BigDecimal("48000000")).build()));
        when(client.getLoanTransactions(eq("1"), anyLong())).thenReturn(List.of());
        when(client.getCards("1")).thenReturn(List.of(
                CardSyncDto.builder().cardId(21L).institutionCode("0381").institutionName("KB국민카드")
                        .productName("KB 신용카드").cardNoMasked("1234-****-****-0021").cardTypeCode("01").build()));

        service.sync(1L);

        verify(loanMapper, never()).insert(argThat(loan -> "MOCK-LOAN-301".equals(loan.getLoanNoEncrypted())));
        verify(loanMapper, never()).update(argThat(loan -> "MOCK-LOAN-301".equals(loan.getLoanNoEncrypted())));
        verify(loanMapper).insert(argThat(loan -> "MOCK-LOAN-302".equals(loan.getLoanNoEncrypted())));
        verify(cardMapper, never()).insert(any());
        verify(cardMapper, never()).update(any());
    }

    @Test
    @DisplayName("대출 동기화는 목서버의 기관코드를 tbl_loan.bank_code 에 저장한다 (아이콘 매칭용)")
    void loanSyncPersistsInstitutionCode() {
        when(client.getLoans("1")).thenReturn(List.of(
                LoanSyncDto.builder().loanId(301L).institutionCode("CP_KB").institutionName("KB캐피탈")
                        .productName("KB 신용대출").loanNoMasked("LN-2025-****-0001")
                        .principal(new BigDecimal("15000000")).balance(new BigDecimal("14200000")).build()));
        when(client.getLoanTransactions(eq("1"), eq(301L))).thenReturn(List.of());

        service.sync(1L);

        verify(loanMapper).insert(argThat(loan -> "CP_KB".equals(loan.getBankCode())));
    }

    @Test
    @DisplayName("페이머니는 충전(01)=TRANSFER, 결제(02)=CONSUMPTION, 환불(03)=is_refund 로 가른다")
    void payMoneyTransTypeCodeDrivesClassification() {
        when(client.getPayMoney("1")).thenReturn(List.of(
                PayMoneySyncDto.builder().payMoneyId(401L).providerCode("KAKAO")
                        .providerName("카카오페이").walletName("카카오페이 머니")
                        .balance(new BigDecimal("130000")).build()));
        when(client.getPayMoneyTransactions(eq("1"), eq(401L))).thenReturn(List.of(
                /* 충전은 양수, 결제는 **음수**, 환불은 양수로 내려온다(seed-v2.sql). */
                PayMoneyTransactionSyncDto.builder()
                        .transactionId(6001L).transactedAt("2026-06-12T18:00:01+09:00")
                        .transTypeCode("01").amount(new BigDecimal("150000")).build(),
                PayMoneyTransactionSyncDto.builder()
                        .transactionId(6002L).transactedAt("2026-06-15T12:30:00+09:00")
                        .transTypeCode("02").amount(new BigDecimal("-70000"))
                        .merchantName("배달의민족").build(),
                PayMoneyTransactionSyncDto.builder()
                        .transactionId(6003L).transactedAt("2026-06-27T10:00:00+09:00")
                        .transTypeCode("03").amount(new BigDecimal("10000"))
                        .merchantName("배달의민족").build()));

        service.sync(1L);

        /* 충전은 내 돈을 지갑으로 옮긴 것뿐이다 — 소비로 잡으면 지출이 통째로 부풀려진다. */
        verify(transactionMapper).insert(argThat(t ->
                t.getCodefTrKey().endsWith("-6001")
                        && "TRANSFER".equals(t.getClassification())
                        && !t.isRefund()));
        /* 결제는 음수로 오지만 저장은 양수 + CONSUMPTION 이다. */
        verify(transactionMapper).insert(argThat(t ->
                t.getCodefTrKey().endsWith("-6002")
                        && "CONSUMPTION".equals(t.getClassification())
                        && new BigDecimal("70000").compareTo(t.getAmount()) == 0
                        && !t.isRefund()));
        /* 환불은 CONSUMPTION + is_refund — 미션 집계가 -refunded_amount 로 상계한다. */
        verify(transactionMapper).insert(argThat(t ->
                t.getCodefTrKey().endsWith("-6003")
                        && "CONSUMPTION".equals(t.getClassification())
                        && t.isRefund()
                        && new BigDecimal("10000").compareTo(t.getRefundedAmount()) == 0));
    }

    @Test
    @DisplayName("선택한 PAY_KB 기관 코드와 같은 페이머니 응답만 동기화한다")
    void syncsPayMoneyWhenResponseUsesSelectedInstitutionCode() {
        when(connectedAccountMapper.findActiveByUser(1L)).thenReturn(List.of(
                ConnectedAccount.builder().bankCode("PAY_KB").bankName("KB Pay").build()));
        when(client.getPayMoney("1")).thenReturn(List.of(
                PayMoneySyncDto.builder().payMoneyId(401L).providerCode("PAY_KB")
                        .providerName("KB Pay").walletName("KB Pay Money")
                        .balance(new BigDecimal("130000")).build()));
        when(client.getPayMoneyTransactions(eq("1"), eq(401L))).thenReturn(List.of());

        service.sync(1L);

        verify(connectedAccountMapper).insert(argThat(account ->
                "PAYMONEY".equals(account.getAccountType())
                        && "PAY_KB".equals(account.getBankCode())));
    }

    @Test
    @DisplayName("#334: 계좌 연동이 만든 ConnectedAccount 가 없어도 extraInstitutionCodes 로 넘긴 " +
            "페이머니 기관은 최초 동기화에서 저장된다")
    void syncsExtraInstitutionEvenWithoutExistingConnectedAccount() {
        /* 대출·페이머니는 fetchAccounts() 가 다루지 못해 ConnectedAccount 가 아예 없는 상태에서 시작한다.
           은행(0004)은 이미 연동돼 있다고 가정한다 — 실제로 이런 조합이 흔하다. */
        when(connectedAccountMapper.findActiveByUser(1L)).thenReturn(List.of(
                ConnectedAccount.builder().bankCode("0004").bankName("KB국민은행").build()));
        when(client.getPayMoney("1")).thenReturn(List.of(
                PayMoneySyncDto.builder().payMoneyId(401L).providerCode("PAY_KB")
                        .providerName("KB Pay").walletName("KB Pay Money")
                        .balance(new BigDecimal("130000")).build()));
        when(client.getPayMoneyTransactions(eq("1"), eq(401L))).thenReturn(List.of());

        service.sync(1L, Set.of("PAY_KB"));

        verify(connectedAccountMapper).insert(argThat(account ->
                "PAYMONEY".equals(account.getAccountType())
                        && "PAY_KB".equals(account.getBankCode())));
    }

    @Test
    @DisplayName("#334: extraInstitutionCodes 없이 부르는 단일 인자 sync(userId) 는 여전히 " +
            "연동 흔적이 없는 기관을 건너뛴다 — 회귀 방지")
    void singleArgSyncStillExcludesUnlinkedInstitutions() {
        when(connectedAccountMapper.findActiveByUser(1L)).thenReturn(List.of(
                ConnectedAccount.builder().bankCode("0004").bankName("KB국민은행").build()));
        when(client.getPayMoney("1")).thenReturn(List.of(
                PayMoneySyncDto.builder().payMoneyId(401L).providerCode("PAY_KB")
                        .providerName("KB Pay").walletName("KB Pay Money")
                        .balance(new BigDecimal("130000")).build()));

        service.sync(1L);

        verify(connectedAccountMapper, never()).insert(
                argThat(account -> "PAY_KB".equals(account.getBankCode())));
    }

    @Test
    @DisplayName("#334 리뷰 지적: 대출은 tbl_connected_account 가 아니라 tbl_loan 에만 남기 때문에, " +
            "extraInstitutionCodes 없이 부르는 평소 동기화에서도 이전에 만든 대출 이름 기록으로 스코프에 남아야 한다")
    void loanStaysInScopeOnSubsequentSyncWithoutExtraCodes() {
        /* 은행(0004) 하나는 이미 연동돼 있어 스코프가 "무제한(빈 스코프)"이 아니라 실제로 제한되는
           상황을 만든다 — 대출 하나만 연동한 사용자였다면 이 결함이 가려진다. */
        when(connectedAccountMapper.findActiveByUser(1L)).thenReturn(List.of(
                ConnectedAccount.builder().bankCode("0004").bankName("KB국민은행").build()));
        /* 이전 동기화가 이미 만들어 둔 대출 행. 2026-08-20 마이그레이션 이전에 저장된 옛 행을
           흉내내 bankCode 는 비워 두고 이름만 남긴다 — buildSyncScope() 의 이름 폴백 경로를 검증한다. */
        when(loanMapper.findByUser(1L)).thenReturn(List.of(
                Loan.builder().id(55L).userId(1L).loanNoEncrypted("MOCK-LOAN-301")
                        .bankName("KB캐피탈").build()));
        when(client.getLoans("1")).thenReturn(List.of(
                LoanSyncDto.builder().loanId(301L).institutionCode("CP_KB")
                        .institutionName("KB캐피탈").principal(new BigDecimal("5000000"))
                        .balance(new BigDecimal("4200000")).interestRate(new BigDecimal("4.5"))
                        .startDate("2026-01-01").maturityDate("2029-01-01")
                        .monthlyPayment(new BigDecimal("150000")).build()));
        when(client.getLoanTransactions(eq("1"), eq(301L))).thenReturn(List.of());

        /* extraInstitutionCodes 없이 부르는 평소 동기화 — 배치 스케줄러·즉시 조회와 같은 경로다. */
        service.sync(1L);

        verify(loanMapper).insert(argThat(loan -> "KB캐피탈".equals(loan.getBankName())));
    }

    @Test
    @DisplayName("카드 취소(03)는 음수 금액을 양수 + is_refund=1 로 저장한다")
    void cardCancellationIsStoredAsPositiveRefund() {
        when(client.getCards("1")).thenReturn(List.of(
                CardSyncDto.builder().cardId(1L).cardNoMasked("9490-****-****-2201")
                        .cardTypeCode("01").currency("KRW").build()));
        when(client.getCardApprovals(eq("1"), eq(1L), isNull())).thenReturn(List.of(
                CardApprovalSyncDto.builder().approvalId(1L).approvalNo("N2-C-0814")
                        .approvedAt("2026-08-14T18:10:00+09:00").approvalTypeCode("01")
                        .merchantName("이마트 역삼점").approvedAmount(new BigDecimal("100000"))
                        .rawJson(null).build(),
                CardApprovalSyncDto.builder().approvalId(2L).approvalNo("N2-C-0814-C")
                        .approvedAt("2026-08-15T09:00:00+09:00").approvalTypeCode("03")
                        .merchantName("이마트 역삼점").approvedAmount(new BigDecimal("-20000"))
                        .rawJson("{\"originalApprovalNo\":\"N2-C-0814\"}").build()));
        when(client.getCardBills(anyString(), anyLong())).thenReturn(List.of());
        when(cardMapper.update(any())).thenReturn(0);

        service.sync(1L);

        /* amount 는 "항상 양수. 환불도 양수 + is_refund=1" 이 스키마 규약이다. */
        verify(transactionMapper).insert(argThat(t ->
                t.getCodefTrKey().endsWith("N2-C-0814-C")
                        && new BigDecimal("20000").compareTo(t.getAmount()) == 0
                        && t.isRefund()
                        && new BigDecimal("20000").compareTo(t.getRefundedAmount()) == 0
                        && "N2-C-0814".equals(t.getOriginalApprovalNo())));
        /* 정상 승인은 환불 플래그가 서면 안 된다. */
        verify(transactionMapper).insert(argThat(t ->
                t.getCodefTrKey().endsWith("N2-C-0814")
                        && !t.isRefund() && t.getRefundedAmount() == null
                        && "OUT".equals(t.getDirection())
                        && "CONSUMPTION".equals(t.getClassification())));
    }

    @Test
    @DisplayName("동시 동기화가 같은 행을 먼저 넣어도 500 이 아니라 update 로 흡수한다")
    void concurrentInsertRaceFallsBackToUpdate() {
        /* update 0행 → insert 시도 → 상대가 이미 넣어 UNIQUE 위반. 여기서 터지면 사용자에게 500 이다. */
        when(transactionMapper.update(any())).thenReturn(0).thenReturn(1);
        when(transactionMapper.insert(any())).thenThrow(
                new DuplicateKeyException("Duplicate entry 'BANK-1-9001' for key 'uk_tx_codef_key'"));

        FinancialSyncResultDto result = service.sync(1L);

        assertEquals("COMPLETED", result.getStatus());
        /* 경합에서 진 쪽은 상대 행을 우리 값으로 갱신하고 넘어간다 (update 2회: 최초 시도 + 재시도). */
        verify(transactionMapper, times(2)).update(any());
        assertEquals(List.of("BEGIN", "COMMIT"), timeline);
    }

    @Test
    @DisplayName("저장 후 upsert 된 거래 id 로 규칙 카테고리화를 호출하고, 결과를 응답에 담는다")
    void callsRuleCategorizationWithUpsertedIdsAndReflectsResultInResponse() {
        when(transactionCategorizationService.categorizeRuleBased(eq(1L), anyList()))
                .thenReturn(RuleCategorizationResultDto.builder()
                        .ruleCategorizedCount(1)
                        .llmEligibleTransactionIds(List.of())
                        .build());

        FinancialSyncResultDto result = service.sync(1L);

        assertEquals(1, result.getCollectedTransactionCount());
        assertEquals(1, result.getRuleCategorizedCount());
        assertEquals(0, result.getLlmPendingTransactionCount());
        assertEquals("NOT_REQUIRED", result.getLlmCategorizationStatus());
        verify(transactionCategorizationService).categorizeRuleBased(eq(1L), anyList());
    }

    @Test
    @DisplayName("규칙으로 못 채운 거래가 있으면 LlmCategorizationRequestedEvent 를 발행하고 상태를 PENDING 으로 둔다")
    void publishesLlmEventWhenEligibleTransactionsRemain() {
        when(transactionCategorizationService.categorizeRuleBased(eq(1L), anyList()))
                .thenReturn(RuleCategorizationResultDto.builder()
                        .ruleCategorizedCount(0)
                        .llmEligibleTransactionIds(List.of(999L))
                        .build());

        FinancialSyncResultDto result = service.sync(1L);

        assertEquals(1, result.getLlmPendingTransactionCount());
        assertEquals("PENDING", result.getLlmCategorizationStatus());
        /*
         * ApplicationEventPublisher 는 publishEvent(ApplicationEvent)/publishEvent(Object) 오버로드가
         * 둘 다 있다. argThat(람다) 를 타입 힌트 없이 쓰면 javac 가 더 구체적인 ApplicationEvent 오버로드로
         * 고정해버려, 그와 무관한 record 타입(LlmCategorizationRequestedEvent) 로의 instanceof/캐스팅이
         * 컴파일 에러가 된다. Object 로 타입 힌트를 줘 Object 오버로드를 타게 한다.
         */
        verify(eventPublisher).publishEvent(Mockito.<Object>argThat(event ->
                event instanceof LlmCategorizationRequestedEvent
                        && ((LlmCategorizationRequestedEvent) event).userId().equals(1L)
                        && ((LlmCategorizationRequestedEvent) event).transactionIds().equals(List.of(999L))));
    }

    @Test
    @DisplayName("LLM 대상이 없으면 이벤트를 발행하지 않는다")
    void doesNotPublishEventWhenNoLlmEligibleTransactions() {
        service.sync(1L);

        verify(eventPublisher, never()).publishEvent(any(LlmCategorizationRequestedEvent.class));
    }

    @Test
    @DisplayName("카테고리화 단계에서 터져도 실패 이력을 남긴다 — 저장은 이미 커밋됐어도 흔적 없이 500 만 뜨면 안 된다")
    void categorizationFailureIsRecordedInHistory() {
        when(transactionCategorizationService.categorizeRuleBased(anyLong(), any()))
                .thenThrow(new IllegalStateException("알 수 없는 tbl_merchant_category_map.source: WEIRD"));

        assertThrows(IllegalStateException.class, () -> service.sync(1L));

        verify(syncHistoryMapper).insert(argThat(h ->
                "FAILED".equals(h.getStatus())
                        && "CATEGORIZATION".equals(h.getFailedSource())
                        && h.getFailReason() != null
                        && h.getFailReason().contains("tbl_merchant_category_map")));
    }

    @Test
    @DisplayName("재동기화(update 경로)에서도 재조회로 채운 id 가 규칙 카테고리화 호출에 그대로 전달된다")
    void resyncPathBackfillsIdBeforeCallingRuleCategorization() {
        /* codefTrKeyIsScopedByInternalId 와 같은 방식으로 계좌 PK 를 77 로 고정한다
           (codefTrKey 는 "BANK-{내부 계좌 PK}-{목서버 거래ID}"). */
        when(connectedAccountMapper.insert(any())).thenAnswer(inv -> {
            ConnectedAccount saved = inv.getArgument(0);
            saved.setId(77L);
            return 1;
        });
        /* update 가 1행을 돌려주는 재동기화 경로. 이때는 useGeneratedKeys 가 없어 id 가 비어 있으므로
           findIdByCodefTrKey 로 재조회해 채워야 한다 — 그 채워진 id 가 실제로 카테고리화 호출까지
           전달되는지가 이 테스트의 핵심이다. */
        when(transactionMapper.update(any())).thenReturn(1);
        when(transactionMapper.findIdByCodefTrKey("BANK-77-9001")).thenReturn(99L);

        service.sync(1L);

        verify(transactionCategorizationService).categorizeRuleBased(eq(1L), eq(List.of(99L)));
    }

    @Test
    @DisplayName("이미 동기화한 계좌는 마지막 동기화 달부터 이번 달까지만 월별로 받아온다")
    void reSyncedAccountFetchesOnlyMonthsSinceLastSync() {
        /* #334: 월별 증분 커서는 accountNoMasked 로 조인한다(MonthlyFetchCursor 참고) —
           setUp() 의 getBankAccounts 스텁과 같은 110-***-120045 를 써야 커서가 이 행을 찾는다. */
        when(connectedAccountMapper.findActiveByUser(1L)).thenReturn(List.of(
                ConnectedAccount.builder().id(77L).userId(1L)
                        .accountNoEncrypted(accountNumbers.hash("0004", "110-***-120045"))
                        .accountNoMasked("110-***-120045")
                        .lastSyncAt(LocalDateTime.of(2026, 6, 20, 10, 0))
                        .build()));
        when(client.getBankTransactions(eq("1"), eq(101L), anyString())).thenReturn(List.of());

        service.sync(1L);

        /* clock 이 2026-08-12 로 고정돼 있다 — 6월부터 8월까지 3번 호출돼야 한다. */
        verify(client).getBankTransactions("1", 101L, "2026-06");
        verify(client).getBankTransactions("1", 101L, "2026-07");
        verify(client).getBankTransactions("1", 101L, "2026-08");
        verify(client, never()).getBankTransactions(eq("1"), eq(101L), isNull());
    }

    @Test
    @DisplayName("사용자가 해제한 계좌는 동기화가 되살리지 않는다 — 계좌도 거래도 손대지 않는다")
    void userDisconnectedAccountIsNotResurrected() {
        /*
         * 이슈 #199 최종 리뷰(Critical). upsertConnectedAccount 가 무조건 reactivate(is_active=1) 를
         * 부르는 탓에, 사용자가 disconnect() 로 끊은 계좌가 30분 배치 한 틱이면 조용히 되살아났다.
         * reactivate 의 SQL 자체는 AccountLinkService 의 정당한 재연결이 의존하므로 손대지 않고,
         * 되살리면 안 되는 계좌를 여기(동기화 파이프라인)에서 거른다.
         */
        /* #334: 은행 계좌의 자연키는 이제 AccountLinkService 와 같은 해시다(upsertBankAccount 참고) —
           setUp() 의 getBankAccounts 스텁(0004, 110-***-120045)과 같은 값으로 재계산해야 한다. */
        when(connectedAccountMapper.findInactiveKeysByUser(1L))
                .thenReturn(List.of(accountNumbers.hash("0004", "110-***-120045")));

        FinancialSyncResultDto result = service.sync(1L);

        assertEquals("COMPLETED", result.getStatus());
        /* 계좌를 되살리지도, 새로 만들지도 않는다. */
        verify(connectedAccountMapper, never()).reactivate(any());
        verify(connectedAccountMapper, never()).insert(any());
        /* 그 계좌의 거래도 들어오면 안 된다 — 계좌만 막고 거래를 넣으면 고아 거래가 된다. */
        verify(transactionMapper, never()).insert(argThat(t ->
                t.getCodefTrKey() != null && t.getCodefTrKey().startsWith("BANK-")));
        verify(transactionMapper, never()).update(argThat(t ->
                t.getCodefTrKey() != null && t.getCodefTrKey().startsWith("BANK-")));
        assertEquals(0, result.getCollectedTransactionCount());
    }

    @Test
    @DisplayName("해제되지 않은 계좌는 평소대로 동기화된다 — 제외 목록이 과잉 차단하면 안 된다")
    void activeAccountIsStillSyncedWhenAnotherAccountIsDisconnected() {
        when(connectedAccountMapper.findInactiveKeysByUser(1L))
                .thenReturn(List.of(accountNumbers.hash("0004", "999-***-999999")));   // 다른 계좌만 해제된 상태

        service.sync(1L);

        verify(connectedAccountMapper).reactivate(argThat(a ->
                accountNumbers.hash("0004", "110-***-120045").equals(a.getAccountNoEncrypted())));
        verify(transactionMapper).insert(argThat(t -> "BANK".equals(t.getSourceType())));
    }

    @Test
    @DisplayName("처음 연결한 계좌는 지금처럼 yearMonth 없이 전체 이력을 받아온다")
    void newAccountStillFetchesFullHistory() {
        /* setUp() 이 findActiveByUser 를 스텁하지 않으므로 기본값(빈 리스트) — "아는 계좌 없음". */
        service.sync(1L);

        verify(client).getBankTransactions("1", 101L, null);
    }

    @Test
    @DisplayName("이미 동기화한 카드는 마지막 동기화 달부터 이번 달까지만 월별로 받아온다")
    void reSyncedCardFetchesOnlyMonthsSinceLastSync() {
        when(client.getCards("1")).thenReturn(List.of(
                CardSyncDto.builder().cardId(1L).cardNoMasked("9490-****-****-2201")
                        .cardTypeCode("01").currency("KRW").build()));
        when(client.getCardApprovals(eq("1"), eq(1L), anyString())).thenReturn(List.of());
        when(client.getCardBills(anyString(), anyLong())).thenReturn(List.of());
        when(cardMapper.update(any())).thenReturn(0);
        when(cardMapper.findByUser(1L)).thenReturn(List.of(
                Card.builder().id(100L).userId(1L).cardNoMasked("9490-****-****-2201")
                        .lastSyncAt(LocalDateTime.of(2026, 8, 1, 0, 0))
                        .build()));

        service.sync(1L);

        verify(client).getCardApprovals("1", 1L, "2026-08");
        verify(client, never()).getCardApprovals(eq("1"), eq(1L), isNull());
    }

    /** setUp() 의 직렬 실행기(Runnable::run)와 달리 여기서만 진짜 스레드풀을 쓴다 — 병렬 실행 자체를 검증한다. */
    private FinancialSyncServiceImpl serviceWithRealExecutor(ExecutorService executor) {
        return new FinancialSyncServiceImpl(
                client, scenarioKeyProvider, accountNumbers, connectedAccountMapper, loanMapper,
                investmentHoldingMapper, cardMapper, cardBillMapper, transactionMapper,
                syncHistoryMapper, transactionCategorizationService, eventPublisher,
                Clock.fixed(Instant.parse("2026-08-12T01:00:00Z"), ZoneId.of("Asia/Seoul")),
                new TransactionTemplate(recordingTransactionManager()), executor);
    }

    @Test
    @DisplayName("6개 소스를 진짜 스레드풀로 병렬 수집해도 소스별 데이터가 서로 섞이거나 유실되지 않는다")
    void collectAllRunsSourcesInParallelWithoutDataCorruption() throws InterruptedException {
        when(client.getDeposits("1")).thenReturn(List.of(
                DepositSyncDto.builder().depositAccountId(201L).institutionCode("0004")
                        .institutionName("KB국민은행").productName("KB 적금")
                        .accountNoMasked("110-***-999999").balance(new BigDecimal("1000000")).build()));
        when(client.getDepositTransactions(eq("1"), eq(201L))).thenReturn(List.of(
                DepositTransactionSyncDto.builder()
                        .transactionId(7001L).transactedAt("2026-08-05T09:00:00+09:00")
                        .transTypeCode("01").amount(new BigDecimal("300000")).description("납입")
                        .build()));
        when(client.getStockAsset("1")).thenReturn(
                StockAssetSyncDto.builder().accountId(301L).institutionName("KB증권")
                        .currency("KRW").cashBalance(new BigDecimal("50000")).build());
        when(client.getSecuritiesTransactions(eq("1"), eq(301L))).thenReturn(List.of(
                SecuritiesTransactionSyncDto.builder()
                        .transactionId(8001L).transactedAt("2026-08-06T10:00:00+09:00")
                        .transTypeCode("02").securityProductName("삼성전자")
                        .transactionAmount(new BigDecimal("700000")).build()));
        when(client.getPayMoney("1")).thenReturn(List.of(
                PayMoneySyncDto.builder().payMoneyId(401L).providerCode("KAKAO")
                        .providerName("카카오페이").walletName("카카오페이 머니")
                        .balance(new BigDecimal("130000")).build()));
        when(client.getPayMoneyTransactions(eq("1"), eq(401L))).thenReturn(List.of(
                PayMoneyTransactionSyncDto.builder()
                        .transactionId(6001L).transactedAt("2026-06-12T18:00:01+09:00")
                        .transTypeCode("01").amount(new BigDecimal("150000")).build()));
        when(client.getCards("1")).thenReturn(List.of(
                CardSyncDto.builder().cardId(1L).cardNoMasked("9490-****-****-2201")
                        .cardTypeCode("01").currency("KRW").build()));
        when(client.getCardApprovals(eq("1"), eq(1L), isNull())).thenReturn(List.of(
                CardApprovalSyncDto.builder().approvalId(1L).approvalNo("APV-CREDIT-1")
                        .approvedAt("2026-08-10T12:00:00+09:00").approvedAmount(new BigDecimal("30000"))
                        .rawJson(null).build()));
        when(client.getCardBills(anyString(), anyLong())).thenReturn(List.of());
        when(cardMapper.update(any())).thenReturn(0);
        doAnswer(inv -> {
            Card c = inv.getArgument(0);
            c.setId(100L);
            return 1;
        }).when(cardMapper).insert(any());

        ExecutorService realExecutor = Executors.newFixedThreadPool(6);
        FinancialSyncResultDto result;
        try {
            result = serviceWithRealExecutor(realExecutor).sync(1L);
        } finally {
            realExecutor.shutdown();
            realExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertEquals("COMPLETED", result.getStatus());
        /* 소스마다 결과가 5건(BANK/DEPOSIT/SECURITIES/PAYMONEY/CARD_CREDIT) — 경합으로 유실되거나
           다른 스레드가 쓴 값이 섞였다면 이 개수나 아래 소스별 단언 중 하나가 깨진다. */
        verify(transactionMapper, times(5)).insert(any());
        verify(transactionMapper).insert(argThat(t ->
                "BANK".equals(t.getSourceType()) && t.getCodefTrKey().endsWith("-9001")));
        verify(transactionMapper).insert(argThat(t ->
                "DEPOSIT".equals(t.getSourceType()) && t.getCodefTrKey().endsWith("-7001")));
        verify(transactionMapper).insert(argThat(t ->
                "SECURITIES".equals(t.getSourceType()) && t.getCodefTrKey().endsWith("-8001")));
        verify(transactionMapper).insert(argThat(t ->
                "PAYMONEY".equals(t.getSourceType()) && t.getCodefTrKey().endsWith("-6001")));
        verify(transactionMapper).insert(argThat(t ->
                "CARD_CREDIT".equals(t.getSourceType()) && t.getCodefTrKey().endsWith("APV-CREDIT-1")));
    }

    @Test
    @DisplayName("진짜 스레드풀로 병렬 수집해도 실패한 소스 이름이 실패 이력에 정확히 남는다")
    void sourceFailureIsAttributedCorrectlyUnderRealParallelExecution() throws InterruptedException {
        when(client.getCards("1")).thenThrow(
                new BusinessException("EXTERNAL_API_UNAVAILABLE", "목서버 응답 없음"));

        ExecutorService realExecutor = Executors.newFixedThreadPool(6);
        BusinessException thrown;
        try {
            FinancialSyncServiceImpl parallelService = serviceWithRealExecutor(realExecutor);
            thrown = assertThrows(BusinessException.class, () -> parallelService.sync(1L));
        } finally {
            realExecutor.shutdown();
            realExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertEquals("EXTERNAL_API_UNAVAILABLE", thrown.getCode());
        verify(connectedAccountMapper, never()).insert(any());
        verify(transactionMapper, never()).insert(any());
        verify(syncHistoryMapper).insert(argThat(h ->
                "FAILED".equals(h.getStatus()) && "CARD".equals(h.getFailedSource())));
    }
}
