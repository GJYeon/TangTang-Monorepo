package com.kb.tangtang.account.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kb.tangtang.account.client.sync.FinancialSyncClient;
import com.kb.tangtang.account.client.sync.ScenarioKeyProvider;
import com.kb.tangtang.account.client.sync.dto.BankAccountSyncDto;
import com.kb.tangtang.account.client.sync.dto.BankTransactionSyncDto;
import com.kb.tangtang.account.client.sync.dto.CardApprovalSyncDto;
import com.kb.tangtang.account.client.sync.dto.CardBillSyncDto;
import com.kb.tangtang.account.client.sync.dto.CardSyncDto;
import com.kb.tangtang.account.client.sync.dto.DepositSyncDto;
import com.kb.tangtang.account.client.sync.dto.DepositTransactionSyncDto;
import com.kb.tangtang.account.client.sync.dto.LoanSyncDto;
import com.kb.tangtang.account.client.sync.dto.LoanTransactionSyncDto;
import com.kb.tangtang.account.client.sync.dto.PayMoneySyncDto;
import com.kb.tangtang.account.client.sync.dto.PayMoneyTransactionSyncDto;
import com.kb.tangtang.account.client.sync.dto.SecuritiesTransactionSyncDto;
import com.kb.tangtang.account.client.sync.dto.StockAssetSyncDto;
import com.kb.tangtang.account.client.sync.dto.StockHoldingSyncDto;
import com.kb.tangtang.account.domain.Card;
import com.kb.tangtang.account.domain.CardBill;
import com.kb.tangtang.account.domain.ConnectedAccount;
import com.kb.tangtang.account.domain.FinancialSyncHistory;
import com.kb.tangtang.account.domain.InvestmentHolding;
import com.kb.tangtang.account.domain.Loan;
import com.kb.tangtang.account.domain.LlmCategorizationRequestedEvent;
import com.kb.tangtang.account.dto.FinancialSyncResultDto;
import com.kb.tangtang.account.mapper.CardBillMapper;
import com.kb.tangtang.account.mapper.CardMapper;
import com.kb.tangtang.account.mapper.ConnectedAccountMapper;
import com.kb.tangtang.account.mapper.FinancialSyncHistoryMapper;
import com.kb.tangtang.account.mapper.InvestmentHoldingMapper;
import com.kb.tangtang.account.mapper.LoanMapper;
import com.kb.tangtang.common.exception.BusinessException;
import com.kb.tangtang.transaction.domain.Transaction;
import com.kb.tangtang.transaction.dto.RuleCategorizationResultDto;
import com.kb.tangtang.transaction.mapper.TransactionMapper;
import com.kb.tangtang.transaction.service.TransactionCategorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * 이슈 #147 — 금융 동기화 오케스트레이터.
 *
 * 외부 목서버 호출(collectAll)은 전부 트랜잭션 밖에서 수행한다 — AccountLinkService.refresh() 와
 * 같은 이유로, 여러 건의 외부 HTTP 호출 동안 DB 커넥션을 붙잡으면 커넥션 풀이 마른다.
 * 전부 성공한 뒤에만 saveAll() 로 DB 에 반영한다. 하나라도 실패하면 DB 는 손대지 않고
 * 실패 이력만 남긴 뒤 BusinessException 을 그대로 올린다(설계 문서 §8).
 *
 * 저장은 전부 "자연키로 update 시도 → 0행이면 insert" 패턴이라 몇 번을 다시 돌려도 결과가 같다(§9).
 *
 * <h3>동시 insert 경합</h3>
 * 위 패턴은 원자적이지 않다. 같은 사용자의 동기화 요청이 겹치면 둘 다 update 0행을 보고 둘 다 insert 를
 * 시도해, 늦은 쪽이 UNIQUE 제약에 걸려 500 이 난다. 매퍼 XML 6개를 전부
 * `INSERT ... ON DUPLICATE KEY UPDATE` 로 고치는 건 이 시점에 너무 큰 변경이라,
 * **각 insert 를 {@link DuplicateKeyException} 으로 감싸 한 번만 update 로 되돌리는** 방식으로 막는다.
 * (MySQL/InnoDB 는 insert 하나가 제약에 걸려도 트랜잭션이 죽지 않아 같은 트랜잭션 안에서 이어갈 수 있다.
 *  PostgreSQL 이라면 이 방식이 통하지 않는다.)
 */
@Service
public class FinancialSyncServiceImpl implements FinancialSyncService {

    private static final Logger log = LoggerFactory.getLogger(FinancialSyncServiceImpl.class);

    private static final List<String> SOURCE_ORDER =
            List.of("BANK", "DEPOSIT", "SECURITIES", "LOAN", "PAY_MONEY", "CARD");

    /**
     * 수집이 아니라 **저장 단계**에서 실패했다는 표시. 이력의 failed_source 에 들어간다 —
     * 소스 이름(BANK 등)과 섞이지 않게 별도 값을 쓴다. (컬럼은 VARCHAR(20))
     */
    private static final String SAVE_PHASE = "SAVE";

    /** 저장은 성공했지만 그 뒤 규칙 카테고리화 단계에서 실패했다는 표시. failed_source 에 들어간다. */
    private static final String CATEGORIZATION_PHASE = "CATEGORIZATION";

    /** tbl_financial_sync_history.fail_reason 은 VARCHAR(500) 이다. DB 예외 메시지는 이보다 길 수 있다. */
    private static final int FAIL_REASON_MAX = 500;

    /**
     * 목서버 거래유형 코드 (`trans_type_code`). 시드(`seed-v2.sql`)에서 확인한 값이다 —
     * 은행 `01`=입금 / `02`=출금. 공식 enum 이 없어 시드 관례를 상수로 못박는다.
     */
    private static final String TRANS_TYPE_IN = "01";
    private static final String TRANS_TYPE_OUT = "02";

    /**
     * 페이머니 거래유형 코드. **같은 `trans_type_code` 라도 소스마다 뜻이 다르다** —
     * 은행 `02`=출금이지만 페이머니 `02`=결제, `03`=환불이다. 그래서 상수를 따로 둔다.
     * (`seed-v2.sql`: `01`=충전 양수, `02`=결제 **음수**, `03`=환불 양수)
     */
    private static final String PAY_TYPE_TOPUP = "01";
    private static final String PAY_TYPE_PAYMENT = "02";
    private static final String PAY_TYPE_REFUND = "03";

    /**
     * 목서버 카드 승인유형 코드 (`approval_type_code`). `01`=승인, `03`=취소다
     * (`seed-v2.sql` 의 취소 시드 `N2-C-0814-C` 가 `'03'` + 음수 금액 + raw_json.originalApprovalNo).
     */
    private static final String APPROVAL_TYPE_CANCEL = "03";

    /** rawJson 파싱 전용. ObjectMapper 는 스레드 안전하므로 한 번만 만든다. */
    private static final ObjectMapper RAW_JSON_READER = new ObjectMapper();

    private final FinancialSyncClient client;
    private final ScenarioKeyProvider scenarioKeyProvider;
    private final AccountNumberPolicy accountNumbers;
    private final ConnectedAccountMapper connectedAccountMapper;
    private final LoanMapper loanMapper;
    private final InvestmentHoldingMapper investmentHoldingMapper;
    private final CardMapper cardMapper;
    private final CardBillMapper cardBillMapper;
    private final TransactionMapper transactionMapper;
    private final FinancialSyncHistoryMapper syncHistoryMapper;
    private final TransactionCategorizationService transactionCategorizationService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final Executor syncExecutor;

    /**
     * ⚠ 생성자가 둘이라 **@Autowired 로 어느 쪽을 쓸지 명시해야 한다** (AccountLinkService 와 같은 이유).
     *   컨텍스트에 Clock 빈이 없으므로 Spring 이 쓰는 쪽은 이 생성자다 — Clock 을 파라미터로 남겨 두면
     *   `NoSuchBeanDefinitionException: java.time.Clock` 으로 기동이 실패한다.
     *   단위 테스트는 아래 패키지 전용 생성자로 시간을 고정한다.
     */
    @Autowired
    public FinancialSyncServiceImpl(FinancialSyncClient client,
                                    ScenarioKeyProvider scenarioKeyProvider,
                                    AccountNumberPolicy accountNumbers,
                                    ConnectedAccountMapper connectedAccountMapper,
                                    LoanMapper loanMapper,
                                    InvestmentHoldingMapper investmentHoldingMapper,
                                    CardMapper cardMapper,
                                    CardBillMapper cardBillMapper,
                                    TransactionMapper transactionMapper,
                                    FinancialSyncHistoryMapper syncHistoryMapper,
                                    TransactionCategorizationService transactionCategorizationService,
                                    ApplicationEventPublisher eventPublisher,
                                    TransactionTemplate transactionTemplate,
                                    @Qualifier("financialSyncExecutor") Executor syncExecutor) {
        this(client, scenarioKeyProvider, accountNumbers, connectedAccountMapper, loanMapper,
                investmentHoldingMapper, cardMapper, cardBillMapper, transactionMapper, syncHistoryMapper,
                transactionCategorizationService, eventPublisher,
                Clock.systemDefaultZone(), transactionTemplate, syncExecutor);
    }

    /** 테스트에서 시간을 고정하기 위한 생성자. */
    FinancialSyncServiceImpl(FinancialSyncClient client,
                             ScenarioKeyProvider scenarioKeyProvider,
                             AccountNumberPolicy accountNumbers,
                             ConnectedAccountMapper connectedAccountMapper,
                             LoanMapper loanMapper,
                             InvestmentHoldingMapper investmentHoldingMapper,
                             CardMapper cardMapper,
                             CardBillMapper cardBillMapper,
                             TransactionMapper transactionMapper,
                             FinancialSyncHistoryMapper syncHistoryMapper,
                             TransactionCategorizationService transactionCategorizationService,
                             ApplicationEventPublisher eventPublisher,
                             Clock clock,
                             TransactionTemplate transactionTemplate,
                             Executor syncExecutor) {
        this.client = client;
        this.scenarioKeyProvider = scenarioKeyProvider;
        this.accountNumbers = accountNumbers;
        this.connectedAccountMapper = connectedAccountMapper;
        this.loanMapper = loanMapper;
        this.investmentHoldingMapper = investmentHoldingMapper;
        this.cardMapper = cardMapper;
        this.cardBillMapper = cardBillMapper;
        this.transactionMapper = transactionMapper;
        this.syncHistoryMapper = syncHistoryMapper;
        this.transactionCategorizationService = transactionCategorizationService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
        this.syncExecutor = syncExecutor;
    }

    @Override
    public FinancialSyncResultDto sync(long userId) {
        return sync(userId, Set.of());
    }

    @Override
    public FinancialSyncResultDto sync(long userId, Set<String> extraInstitutionCodes) {
        LocalDateTime startedAt = LocalDateTime.now(clock);
        String scenarioKey = scenarioKeyProvider.resolve(userId);
        SyncScope scope = buildSyncScope(userId, extraInstitutionCodes);
        MonthlyFetchCursor monthlyCursor = buildMonthlyFetchCursor(userId);
        /*
         * 사용자가 직접 해제한(is_active=0) 계좌는 동기화가 되살리면 안 된다(이슈 #199 최종 리뷰).
         * upsertConnectedAccount 가 무조건 reactivate 를 부르는데, 배치가 30분마다 도는 지금은
         * disconnect() 한 계좌가 한 틱 안에 조용히 되살아난다. 여기서 한 번만 읽어 아래로 넘긴다
         * (monthlyCursor 와 같은 이유 — 저장 루프 안에서 매번 DB 를 때리지 않기 위함).
         */
        Set<String> inactiveAccountKeys = new HashSet<>(connectedAccountMapper.findInactiveKeysByUser(userId));

        SyncBundle bundle;
        try {
            bundle = collectAll(scenarioKey, monthlyCursor, scope);
        } catch (SourceCollectionException e) {
            recordFailure(userId, e.source, e.getCause().getMessage(), startedAt);
            throw (BusinessException) e.getCause();
        }

        /*
         * 쓰기 실패도 이력을 남긴다. 트랜잭션은 이미 롤백된 뒤라(saveAll 이 경계를 닫고 나온다)
         * 이 insert 는 별도 트랜잭션으로 커밋된다 — 실패 이력이 롤백에 휩쓸리지 않는다.
         * 이게 없으면 저장 단계 실패는 이력 한 줄 없이 500 으로만 남는다.
         */
        List<Long> upsertedTransactionIds;
        try {
            upsertedTransactionIds = saveAll(userId, bundle, startedAt, inactiveAccountKeys);
        } catch (RuntimeException e) {
            recordFailure(userId, SAVE_PHASE, e.getMessage(), startedAt);
            throw e;
        }

        /*
         * 카테고리화는 saveAll() 이 커밋된 뒤에 돈다(계획 문서 참고). 규칙 1~4단계(동기)는 모듈 경계
         * 원칙의 명시적 예외로 transaction 모듈을 직접 호출한다 — ruleCategorizedCount 를 이 응답에
         * 담아야 해서 이벤트로는 불가능하다. 5단계(LLM 작업 등록)는 응답을 기다릴 이유가 없어 이벤트로
         * 분리한다.
         */
        RuleCategorizationResultDto categorization;
        try {
            categorization = transactionCategorizationService.categorizeRuleBased(userId, upsertedTransactionIds);
        } catch (RuntimeException e) {
            /*
             * 저장은 이미 커밋된 뒤라(writeAll 이 COMPLETED 이력을 남기고 트랜잭션을 닫았다) 여기서
             * 터지면 이력엔 COMPLETED, 응답은 500 — 실패 흔적이 없는 채로 둘이 어긋난다. 이 단계도
             * 실패 이력을 남긴다(collectAll/saveAll 과 같은 패턴).
             */
            recordFailure(userId, CATEGORIZATION_PHASE, e.getMessage(), startedAt);
            throw e;
        }

        String llmCategorizationStatus = "NOT_REQUIRED";
        if (!categorization.getLlmEligibleTransactionIds().isEmpty()) {
            eventPublisher.publishEvent(new LlmCategorizationRequestedEvent(
                    userId, categorization.getLlmEligibleTransactionIds()));
            llmCategorizationStatus = "PENDING";
        }

        return FinancialSyncResultDto.builder()
                .status("COMPLETED")
                .syncedSources(SOURCE_ORDER)
                .syncedAt(LocalDateTime.now(clock).atZone(clock.getZone())
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .collectedTransactionCount(upsertedTransactionIds.size())
                .ruleCategorizedCount(categorization.getRuleCategorizedCount())
                .llmPendingTransactionCount(categorization.getLlmEligibleTransactionIds().size())
                .llmCategorizationStatus(llmCategorizationStatus)
                .build();
    }

    /* ── 수집 (트랜잭션 밖) ─────────────────────────────── */

    /**
     * BANK/CARD 만 대상이다(이슈 #199 1차 범위). lastSyncAt 이 null 인 행(한 번도 동기화 안 됨)은
     * 맵에 넣지 않는다 — "모른다" 취급돼 전체 이력을 받게 하기 위함.
     */
    private MonthlyFetchCursor buildMonthlyFetchCursor(long userId) {
        Map<String, LocalDateTime> accounts = new HashMap<>();
        for (ConnectedAccount account : connectedAccountMapper.findActiveByUser(userId)) {
            /*
             * accountNoMasked 로 키를 만든다 — accountNoEncrypted 가 아니다.
             * 은행 계좌는 이제 AccountLinkService 와 같은 해시 키를 쓰는데(#334, upsertBankAccount
             * 문서 참고), 이 커서는 "목서버 계좌 ID" → "마지막 동기화 시각"을 이어줘야 하고 목서버
             * 계좌 ID 는 해시에 안 들어간다. accountNoMasked 는 목서버 응답과 저장된 행 양쪽에
             * 그대로 남는 유일한 값이라 여기서 조인 키로 쓴다.
             */
            if (account.getLastSyncAt() != null && account.getAccountNoMasked() != null) {
                accounts.put(account.getAccountNoMasked(), account.getLastSyncAt());
            }
        }
        Map<String, LocalDateTime> cards = new HashMap<>();
        for (Card card : cardMapper.findByUser(userId)) {
            if (card.getLastSyncAt() != null) {
                cards.put(card.getCardNoMasked(), card.getLastSyncAt());
            }
        }
        return new MonthlyFetchCursor(accounts, cards);
    }

    private SyncScope buildSyncScope(long userId, Set<String> extraInstitutionCodes) {
        Set<String> institutionCodes = new HashSet<>();
        Set<String> institutionNames = new HashSet<>();
        for (ConnectedAccount account : connectedAccountMapper.findActiveByUser(userId)) {
            if (account.getBankCode() != null) {
                institutionCodes.add(account.getBankCode());
            }
            if (account.getBankName() != null) {
                institutionNames.add(account.getBankName());
            }
        }
        /*
         * 대출·카드는 tbl_connected_account 를 쓰지 않는다 — saveLoans()/upsertCard() 참고, 각자
         * tbl_loan/tbl_card 에만 남는다. 위 루프만으로 스코프를 만들면 최초 동기화(extraInstitutionCodes
         * 로 부트스트랩) 이후로는 흔적이 tbl_connected_account 밖에 있다는 이유로 두 업권이 매번 다시
         * 빠진다(#334 리뷰 지적 — 대출은 첫 동기화만 되고 이후 배치에서 사라짐). 각자의 테이블에서
         * 직접 채워 "한 번이라도 연동됐던 기관"을 스코프가 기억하게 한다.
         *
         * 대출은 2026-08-20 이전엔 기관코드 컬럼이 없어 이름으로만 매칭했다(tbl_loan.bank_code
         * 추가로 지금은 코드도 쓸 수 있다). bank_code 가 비어 있는 옛 행(마이그레이션 이전 동기화분)
         * 도 있을 수 있어 이름 매칭은 계속 남겨 둔다 — collectLoan() 이 코드·이름 둘 중 하나만
         * 맞아도 포함시키는 이유다.
         */
        for (Loan loan : loanMapper.findByUser(userId)) {
            if (loan.getBankCode() != null) {
                institutionCodes.add(loan.getBankCode());
            }
            if (loan.getBankName() != null) {
                institutionNames.add(loan.getBankName());
            }
        }
        for (Card card : cardMapper.findByUser(userId)) {
            if (card.getInstitutionCode() != null) {
                institutionCodes.add(card.getInstitutionCode());
            }
        }
        /*
         * 대출·페이머니 최초 연동용(#334) — 클래스 상단 FinancialSyncService.sync(userId, Set) 참고.
         * null 코드가 섞여 들어오면 여기서 걸러야 한다. isDone() 이 null 을 "할 일 없음"으로 읽는
         * AccountLinkService.requireOrganizations() 와 같은 이유다.
         */
        for (String code : extraInstitutionCodes) {
            if (code != null && !code.isBlank()) {
                institutionCodes.add(code);
            }
        }
        return new SyncScope(institutionCodes, institutionNames);
    }

    /** lastSyncAt 이 속한 달부터 오늘(clock 기준)이 속한 달까지, 오름차순으로. */
    private List<YearMonth> monthsSince(LocalDateTime lastSyncAt) {
        YearMonth start = YearMonth.from(lastSyncAt);
        YearMonth end = YearMonth.from(LocalDate.now(clock));
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth m = start; !m.isAfter(end); m = m.plusMonths(1)) {
            months.add(m);
        }
        return months;
    }

    private List<BankTransactionSyncDto> fetchBankTransactions(String scenarioKey, BankAccountSyncDto account,
                                                                MonthlyFetchCursor cursor) {
        long accountId = account.getAccountId();
        LocalDateTime lastSyncAt = cursor.accountLastSyncAt(account.getAccountNoMasked());
        if (lastSyncAt == null) {
            return client.getBankTransactions(scenarioKey, accountId, null);
        }
        List<BankTransactionSyncDto> merged = new ArrayList<>();
        for (YearMonth month : monthsSince(lastSyncAt)) {
            merged.addAll(client.getBankTransactions(scenarioKey, accountId, month.toString()));
        }
        return merged;
    }

    private List<CardApprovalSyncDto> fetchCardApprovals(String scenarioKey, long cardId, String cardNoMasked,
                                                          MonthlyFetchCursor cursor) {
        LocalDateTime lastSyncAt = cursor.cardLastSyncAt(cardNoMasked);
        if (lastSyncAt == null) {
            return client.getCardApprovals(scenarioKey, cardId, null);
        }
        List<CardApprovalSyncDto> merged = new ArrayList<>();
        for (YearMonth month : monthsSince(lastSyncAt)) {
            merged.addAll(client.getCardApprovals(scenarioKey, cardId, month.toString()));
        }
        return merged;
    }

    /**
     * 전부 트랜잭션 밖. BANK/DEPOSIT/SECURITIES/LOAN/PAY_MONEY/CARD 6개 소스는 서로 결과를
     * 참조하지 않고 {@link SyncBundle} 의 서로 다른 필드에만 쓰므로 병렬로 돌린다 — 목서버
     * 왕복이 순차로 쌓이던 게 가장 느린 소스 하나 수준으로 줄어든다.
     *
     * ⚠ 각 소스는 자기 필드만 쓰고 다른 소스의 필드는 절대 건드리지 않는다. 여러 스레드가 SyncBundle
     *   하나를 같이 채워도 안전한 건 이 "쓰기 대상 분리" 때문이다 — 필드가 겹치면 별도 동기화가
     *   필요해진다.
     *
     * 하나라도 실패하면 {@link SourceCollectionException} 으로 감싸 올라간다(fail-fast였던 예전과
     * 달리, 병렬이라 실패한 소스 하나 때문에 다른 소스 호출이 취소되지는 않는다 — 이미 동시에
     * 날아간 뒤라 취소할 수 없고, 실패든 성공이든 결과는 collectAll() 을 벗어나지 못하므로 실질적인
     * 차이는 없다).
     */
    private SyncBundle collectAll(String scenarioKey, MonthlyFetchCursor monthlyCursor, SyncScope scope) {
        SyncBundle bundle = new SyncBundle();

        CompletableFuture<Void> bank = collectAsync("BANK",
                () -> collectBank(scenarioKey, bundle, monthlyCursor, scope));
        CompletableFuture<Void> deposit = collectAsync("DEPOSIT",
                () -> collectDeposit(scenarioKey, bundle, scope));
        CompletableFuture<Void> securities = collectAsync("SECURITIES",
                () -> collectSecurities(scenarioKey, bundle, scope));
        CompletableFuture<Void> loan = collectAsync("LOAN",
                () -> collectLoan(scenarioKey, bundle, scope));
        CompletableFuture<Void> payMoney = collectAsync("PAY_MONEY",
                () -> collectPayMoney(scenarioKey, bundle, scope));
        CompletableFuture<Void> card = collectAsync("CARD",
                () -> collectCard(scenarioKey, bundle, monthlyCursor, scope));

        try {
            CompletableFuture.allOf(bank, deposit, securities, loan, payMoney, card).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof SourceCollectionException) {
                throw (SourceCollectionException) e.getCause();
            }
            throw e;
        }

        return bundle;
    }

    /** action 이 던지는 BusinessException 에 source 이름을 붙여 감싼다 — 실패 이력에 남길 소스명이다. */
    private CompletableFuture<Void> collectAsync(String source, Runnable action) {
        return CompletableFuture.runAsync(() -> {
            try {
                action.run();
            } catch (BusinessException e) {
                throw new SourceCollectionException(source, e);
            }
        }, syncExecutor);
    }

    private void collectBank(String scenarioKey, SyncBundle bundle, MonthlyFetchCursor monthlyCursor,
                             SyncScope scope) {
        for (BankAccountSyncDto account : client.getBankAccounts(scenarioKey)) {
            if (!scope.includesCode(account.getInstitutionCode())) {
                continue;
            }
            bundle.bankAccounts.add(account);
            bundle.bankTransactions.put(account.getAccountId(),
                    fetchBankTransactions(scenarioKey, account, monthlyCursor));
        }
    }

    private void collectDeposit(String scenarioKey, SyncBundle bundle, SyncScope scope) {
        for (DepositSyncDto deposit : client.getDeposits(scenarioKey)) {
            if (!scope.includesCode(deposit.getInstitutionCode())) {
                continue;
            }
            bundle.deposits.add(deposit);
            bundle.depositTransactions.put(deposit.getDepositAccountId(),
                    client.getDepositTransactions(scenarioKey, deposit.getDepositAccountId()));
        }
    }

    private void collectSecurities(String scenarioKey, SyncBundle bundle, SyncScope scope) {
        StockAssetSyncDto stock = client.getStockAsset(scenarioKey);
        if (stock != null && scope.includesName(stock.getInstitutionName())) {
            bundle.stock = stock;
            bundle.securitiesTransactions.put(stock.getAccountId(),
                    client.getSecuritiesTransactions(scenarioKey, stock.getAccountId()));
        }
    }

    private void collectLoan(String scenarioKey, SyncBundle bundle, SyncScope scope) {
        for (LoanSyncDto loan : client.getLoans(scenarioKey)) {
            /* tbl_loan 은 기관코드를 저장하지 않아 재동기화 스코프는 이름으로만 재구성된다 —
               buildSyncScope() 참고. 그래서 코드·이름 둘 중 하나만 맞아도 포함시킨다. */
            if (!scope.includesCode(loan.getInstitutionCode())
                    && !scope.includesName(loan.getInstitutionName())) {
                continue;
            }
            bundle.loans.add(loan);
            bundle.loanTransactions.put(loan.getLoanId(),
                    client.getLoanTransactions(scenarioKey, loan.getLoanId()));
        }
    }

    private void collectPayMoney(String scenarioKey, SyncBundle bundle, SyncScope scope) {
        for (PayMoneySyncDto payMoney : client.getPayMoney(scenarioKey)) {
            if (!scope.includesCode(payMoney.getProviderCode())) {
                continue;
            }
            bundle.payMoney.add(payMoney);
            bundle.payMoneyTransactions.put(payMoney.getPayMoneyId(),
                    client.getPayMoneyTransactions(scenarioKey, payMoney.getPayMoneyId()));
        }
    }

    private void collectCard(String scenarioKey, SyncBundle bundle, MonthlyFetchCursor monthlyCursor,
                             SyncScope scope) {
        for (CardSyncDto card : client.getCards(scenarioKey)) {
            if (!scope.includesCode(card.getInstitutionCode())) {
                continue;
            }
            bundle.cards.add(card);
            bundle.cardApprovals.put(card.getCardId(),
                    fetchCardApprovals(scenarioKey, card.getCardId(), card.getCardNoMasked(), monthlyCursor));
            bundle.cardBills.put(card.getCardId(), client.getCardBills(scenarioKey, card.getCardId()));
        }
    }

    private void recordFailure(long userId, String failedSource, String failReason,
                               LocalDateTime startedAt) {
        syncHistoryMapper.insert(FinancialSyncHistory.builder()
                .userId(userId)
                .status("FAILED")
                .failedSource(failedSource)
                .failReason(truncate(failReason))
                .startedAt(startedAt)
                .finishedAt(LocalDateTime.now(clock))
                .build());
    }

    /* ── 저장 (하나의 트랜잭션) ─────────────────────────── */

    /**
     * 저장 구간의 트랜잭션 경계.
     *
     * ⚠ 여기서 @Transactional 을 쓰면 **아무 일도 일어나지 않는다.** 애너테이션은 프록시를 통해 들어온
     *    호출에만 걸리는데 이 메서드는 같은 객체의 sync() 가 직접 부르기 때문이다(자기호출).
     *    설계 §8 이 요구하는 "전부 성공한 뒤 하나의 커밋" 을 실제로 보장하려면 경계를 코드로 명시해야
     *    해서 TransactionTemplate 을 쓴다. 콜백 안에서 예외가 나면 그때까지의 쓰기가 전부 롤백된다.
     */
    private List<Long> saveAll(long userId, SyncBundle bundle, LocalDateTime startedAt,
                               Set<String> inactiveKeys) {
        return transactionTemplate.execute(status -> writeAll(userId, bundle, startedAt, inactiveKeys));
    }

    /** 실제 쓰기. 순서: 상품 upsert → 거래 upsert → correlation 연결 → 성공 이력. 이번 호출에서 upsert 된 거래 id 목록을 돌려준다. */
    private List<Long> writeAll(long userId, SyncBundle bundle, LocalDateTime startedAt,
                                Set<String> inactiveKeys) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Transaction> upserted = new ArrayList<>();

        /* BANK: 계좌 upsert + 거래 upsert. 계좌 타입은 목서버 accountTypeCode 를 그대로 안 쓰고
           우리 도메인 값(DEMAND_DEPOSIT)으로 저장한다 — AccountLinkService 관례와 동일. */
        for (BankAccountSyncDto account : bundle.bankAccounts) {
            Long connectedAccountId = upsertBankAccount(userId, account, now, inactiveKeys);
            if (connectedAccountId == null) {
                continue;   // 사용자가 해제한 계좌 — 계좌도 거래도 되살리지 않는다
            }
            for (BankTransactionSyncDto tx :
                    bundle.bankTransactions.getOrDefault(account.getAccountId(), List.of())) {
                /*
                 * 은행 출금 쪽에도 correlationId 를 채운다. linkByCorrelation() 이 BANK ↔ CARD_CHECK 를
                 * correlation_id 로 잇는데(설계 §7), 여기서 비워 두면 연결이 한 건도 성립하지 않는다.
                 */
                RawJsonFields fields = RawJsonFields.parse(tx.getRawJson());
                upserted.add(saveTransaction(userId, connectedAccountId, null, "BANK",
                        "BANK-" + connectedAccountId + "-" + tx.getTransactionId(),
                        bankKind(tx.getTransTypeCode()), tx.getAmount(), false,
                        tx.getTransactedAt(), tx.getDescription(),
                        null, null, fields.correlationId, null, tx.getRawJson()));
            }
        }

        /* CARD: 카드 upsert -> 승인건은 거래로, 청구서는 별도 테이블로. */
        for (CardSyncDto card : bundle.cards) {
            if (inactiveKeys.contains(cardExclusionKey(card.getCardNoMasked()))) {
                continue;   // 계좌 선택에서 체크를 푼 카드(#467) — 카드도 승인건도 저장하지 않는다
            }
            Long cardId = upsertCard(userId, card, now);
            /* 01=신용, 02=체크. 목서버에 공식 enum 이 없어 시드 관례를 따른다(설계 「미해결」 항목). */
            String sourceType = "01".equals(card.getCardTypeCode()) ? "CARD_CREDIT" : "CARD_CHECK";
            for (CardApprovalSyncDto approval :
                    bundle.cardApprovals.getOrDefault(card.getCardId(), List.of())) {
                RawJsonFields fields = RawJsonFields.parse(approval.getRawJson());
                /*
                 * 취소(승인유형 03)는 목서버가 **음수** 금액으로 준다. tbl_transaction.amount 는
                 * "항상 양수 + is_refund=1" 이 규약이라(스키마 주석) saveTransaction 이 절댓값으로
                 * 저장하고 여기서 환불 플래그만 세운다.
                 */
                boolean cancelled = APPROVAL_TYPE_CANCEL.equals(approval.getApprovalTypeCode());
                /* 원거래 승인번호는 취소·환불 후속 처리가 참조한다(설계 §7). 컬럼에도 남긴다. */
                upserted.add(saveTransaction(userId, null, cardId, sourceType,
                        sourceType + "-" + cardId + "-" + approval.getApprovalNo(),
                        TxKind.CARD_SPEND, approval.getApprovedAmount(), cancelled,
                        approval.getApprovedAt(), approval.getMerchantName(),
                        approval.getMerchantCategoryCode(), approval.getMerchantCategoryName(),
                        fields.correlationId, fields.originalApprovalNo, approval.getRawJson()));
            }
            for (CardBillSyncDto bill : bundle.cardBills.getOrDefault(card.getCardId(), List.of())) {
                upsertCardBill(cardId, bill);
            }
        }

        /* 나머지 소스(DEPOSIT/SECURITIES/LOAN/PAY_MONEY)도 같은 upsert-then-transaction 패턴. */
        saveDeposits(userId, bundle, now, upserted, inactiveKeys);
        saveSecurities(userId, bundle, now, upserted, inactiveKeys);
        saveLoans(userId, bundle, upserted, inactiveKeys);
        savePayMoney(userId, bundle, now, upserted, inactiveKeys);

        /* 반환값은 연결된 쌍의 수가 아니라 갱신된 행 수(쌍당 2)다 — 다중 테이블 UPDATE 라서.
           로그를 남기는 이유: correlationId 추출이 조용히 깨져도(RawJsonFields.parse 참고) 이 값이
           0 으로 떨어지는 것 말고는 아무 신호가 없다 — sync() 는 여전히 COMPLETED 를 반환한다. */
        int linkedRows = transactionMapper.linkByCorrelation(userId);
        log.info("correlation 연결 완료 userId={} linkedRows={}", userId, linkedRows);

        syncHistoryMapper.insert(FinancialSyncHistory.builder()
                .userId(userId)
                .status("COMPLETED")
                .syncedSourcesJson(toJsonArray(SOURCE_ORDER))
                .startedAt(startedAt)
                .finishedAt(LocalDateTime.now(clock))
                .build());

        return upserted.stream().map(Transaction::getId).collect(java.util.stream.Collectors.toList());
    }

    private void saveDeposits(long userId, SyncBundle bundle, LocalDateTime now,
                              List<Transaction> upserted, Set<String> inactiveKeys) {
        for (DepositSyncDto deposit : bundle.deposits) {
            String accountNoEncrypted = depositAccountKey(deposit);
            ConnectedAccount row = ConnectedAccount.builder()
                    .userId(userId)
                    .bankCode(deposit.getInstitutionCode())
                    .bankName(deposit.getInstitutionName())
                    .accountName(deposit.getProductName())
                    .accountNoEncrypted(accountNoEncrypted)
                    .accountNoMasked(deposit.getAccountNoMasked())
                    .accountType("SAVINGS")
                    .balance(deposit.getBalance())
                    .syncStatus("NORMAL")
                    .lastSyncAt(now)
                    .build();
            Long accountId = upsertConnectedAccount(userId, row, accountNoEncrypted, inactiveKeys);
            if (accountId == null) {
                continue;   // 사용자가 해제한 계좌 — 계좌도 거래도 되살리지 않는다
            }
            for (DepositTransactionSyncDto tx :
                    bundle.depositTransactions.getOrDefault(deposit.getDepositAccountId(), List.of())) {
                /* 예적금 납입은 소비가 아니라 상품으로의 자금 이동이다 — 소비 집계에 섞이면 안 된다. */
                upserted.add(saveTransaction(userId, accountId, null, "DEPOSIT",
                        "DEPOSIT-" + accountId + "-" + tx.getTransactionId(),
                        TxKind.TRANSFER, tx.getAmount(), false,
                        tx.getTransactedAt(), tx.getDescription(),
                        null, null, null, null, tx.getRawJson()));
            }
        }
    }

    /**
     * 예적금도 계좌 선택 단계와 같은 자연키를 쓴다.
     * 과거에는 여기만 MOCK-DEPOSIT-id 를 써서, 선택 저장 뒤 첫 동기화가 같은 적금을 한 번 더 만들었다.
     */
    private String depositAccountKey(DepositSyncDto deposit) {
        if (deposit.getInstitutionCode() != null && !deposit.getInstitutionCode().isBlank()
                && deposit.getAccountNoMasked() != null && !deposit.getAccountNoMasked().isBlank()) {
            return accountNumbers.hash(deposit.getInstitutionCode(), deposit.getAccountNoMasked());
        }
        return "MOCK-DEPOSIT-" + deposit.getDepositAccountId();
    }

    private void saveSecurities(long userId, SyncBundle bundle, LocalDateTime now,
                                List<Transaction> upserted, Set<String> inactiveKeys) {
        if (bundle.stock == null) {
            return;
        }
        String accountNoEncrypted = securitiesAccountKey(bundle.stock);
        ConnectedAccount row = ConnectedAccount.builder()
                .userId(userId)
                .bankCode(bundle.stock.getInstitutionCode())
                .bankName(bundle.stock.getInstitutionName())
                .accountName(bundle.stock.getAccountName())
                .accountNoEncrypted(accountNoEncrypted)
                .accountNoMasked(bundle.stock.getAccountNoMasked())
                .accountType("SECURITIES")
                /* 연결 계좌 화면은 현금과 보유주식 평가액을 합쳐 현재 증권자산을 보여준다. */
                .balance(securitiesBalance(bundle.stock))
                .syncStatus("NORMAL")
                .lastSyncAt(now)
                .build();
        Long accountId = upsertConnectedAccount(userId, row, accountNoEncrypted, inactiveKeys);
        if (accountId == null) {
            return;     // 사용자가 해제한 계좌 — 보유종목·거래까지 통째로 건너뛴다
        }

        for (StockHoldingSyncDto holding : holdings(bundle.stock)) {
            InvestmentHolding ih = InvestmentHolding.builder()
                    .userId(userId)
                    .accountId(accountId)
                    .symbol(holding.getProductCode())
                    .name(holding.getProductName())
                    /* 목서버에 국가 정보가 없다. 시드된 종목이 전부 국내라 고정값을 쓴다(도메인 주석 참고). */
                    .marketCountry("KR")
                    .currency(bundle.stock.getCurrency())
                    .quantity(holding.getQuantity())
                    .averagePurchasePrice(holding.getAveragePurchasePrice())
                    .lastPrice(holding.getLastPrice())
                    .purchaseAmount(holding.getPurchaseAmount())
                    .marketValue(holding.getMarketValue())
                    .profitLossAmount(holding.getProfitLossAmount())
                    .profitLossRate(holding.getProfitLossRate())
                    .build();
            /*
             * updatePosition 만 쓴다 — 가격 4컬럼(last_price 등)은 이제 InvestmentPriceRefresher(토스
             * 실시간 시세)가 소유한다. 여기서 목서버 값으로 같이 덮으면 배치가 돌 때마다 실시간
             * 가격이 가짜 값으로 되돌아간다(InvestmentHoldingMapper.java 참고).
             */
            if (investmentHoldingMapper.updatePosition(ih) == 0) {
                try {
                    investmentHoldingMapper.insert(ih);
                } catch (DuplicateKeyException e) {
                    investmentHoldingMapper.updatePosition(ih);
                }
            }
        }
        for (SecuritiesTransactionSyncDto tx :
                bundle.securitiesTransactions.getOrDefault(bundle.stock.getAccountId(), List.of())) {
            /* 매수·매도 모두 소비가 아니다(매도 대금을 소비로 잡으면 지출이 부풀려진다). */
            upserted.add(saveTransaction(userId, accountId, null, "SECURITIES",
                    "SECURITIES-" + accountId + "-" + tx.getTransactionId(),
                    TxKind.TRANSFER, tx.getTransactionAmount(), false,
                    tx.getTransactedAt(), tx.getSecurityProductName(),
                    null, null, null, null, null));
        }
    }

    /** 새 목서버 응답은 연결 단계와 같은 기관·마스킹 계좌번호로 자연키를 만든다. */
    private String securitiesAccountKey(StockAssetSyncDto stock) {
        if (stock.getInstitutionCode() != null && !stock.getInstitutionCode().isBlank()
                && stock.getAccountNoMasked() != null && !stock.getAccountNoMasked().isBlank()) {
            return accountNumbers.hash(stock.getInstitutionCode(), stock.getAccountNoMasked());
        }
        /* 이전 응답 또는 실 데이터가 식별자를 아직 주지 않는 경우의 호환 경로. */
        return "MOCK-SECURITIES-" + stock.getAccountId();
    }

    private static BigDecimal securitiesBalance(StockAssetSyncDto stock) {
        BigDecimal cash = stock.getCashBalance() == null ? BigDecimal.ZERO : stock.getCashBalance();
        BigDecimal marketValue = stock.getTotalMarketValue() == null
                ? BigDecimal.ZERO : stock.getTotalMarketValue();
        return cash.add(marketValue);
    }

    /**
     * 대출·카드 제외 키(#467). tbl_loan/tbl_card 에는 is_active 가 없어, 사용자가 체크를 풀거나 해제한 상품은
     * tbl_connected_account 에 이 키로 is_active=0 행을 남겨 두고 여기서 건너뛴다 — 페이머니가 이미 쓰는 방식이다.
     * AccountLinkService 가 제외 행을 만들 때 같은 함수를 쓴다. 형식을 한쪽만 바꾸면 조용히 되살아난다.
     */
    public static String loanExclusionKey(long mockLoanId) {
        return "MOCK-LOAN-" + mockLoanId;
    }

    /**
     * ⚠ 카드만 목서버 id 가 아니라 **마스킹 카드번호**로 키를 만든다(#467).
     * 해제는 `tbl_card` 행에서 키를 되찾아야 하는데 그 테이블에 목서버 cardId 가 없다.
     * `card_no_masked` 는 `UNIQUE(user_id, card_no_masked)` 이고 `CardMapper.update` 의 자연키이며
     * 동기화 응답(CardSyncDto)에도 그대로 있어, 양쪽에서 같은 키가 나오는 유일한 값이다.
     * (대출은 `tbl_loan.loan_no_encrypted` 에 목서버 id 기반 키가 저장돼 있어 이 문제가 없다)
     */
    public static String cardExclusionKey(String cardNoMasked) {
        return "MOCK-CARD-" + cardNoMasked;
    }

    public static String payMoneyExclusionKey(long mockPayMoneyId) {
        return "MOCK-PAYMONEY-" + mockPayMoneyId;
    }

    private void saveLoans(long userId, SyncBundle bundle, List<Transaction> upserted,
                           Set<String> inactiveKeys) {
        for (LoanSyncDto loan : bundle.loans) {
            if (inactiveKeys.contains(loanExclusionKey(loan.getLoanId()))) {
                continue;   // 해제했거나 체크를 푼 대출(#467) — 되살리지 않는다
            }
            Loan row = Loan.builder()
                    .userId(userId)
                    /* 목서버는 마스킹된 대출번호만 준다 — 해시할 원본이 없어 소스 식별자를 그대로 쓴다. */
                    .loanNoEncrypted(loanExclusionKey(loan.getLoanId()))
                    .loanNoMasked(loan.getLoanNoMasked())
                    .bankName(loan.getInstitutionName())
                    .bankCode(loan.getInstitutionCode())
                    .loanType(loan.getProductName())
                    .loanAmount(loan.getPrincipal())
                    .balance(loan.getBalance())
                    .interestRate(loan.getInterestRate())
                    .startDate(date(loan.getStartDate()))
                    .maturityDate(date(loan.getMaturityDate()))
                    .monthlyPayment(loan.getMonthlyPayment())
                    .nextPaymentDate(date(loan.getNextPaymentDate()))
                    .build();
            Long loanId = upsertLoan(userId, row);

            for (LoanTransactionSyncDto tx :
                    bundle.loanTransactions.getOrDefault(loan.getLoanId(), List.of())) {
                /* 대출 실행(입금)·상환(출금) 모두 소비가 아니다 → TRANSFER. 방향은 단정하지 않는다. */
                Transaction txRow = Transaction.builder()
                        .userId(userId)
                        .loanId(loanId)
                        .codefTrKey("LOAN-" + loanId + "-" + tx.getTransactionId())
                        .amount(tx.getAmount() == null ? null : tx.getAmount().abs())
                        .direction(TxKind.TRANSFER.direction)
                        .trDate(OffsetDateTime.parse(tx.getTransactedAt()).toLocalDate())
                        .classification(TxKind.TRANSFER.classification)
                        .isExcludedFromSummary(false)
                        .sourceType("LOAN")
                        .rawJson(tx.getRawJson())
                        .build();
                upserted.add(upsertTransaction(txRow));
            }
        }
    }

    private void savePayMoney(long userId, SyncBundle bundle, LocalDateTime now,
                              List<Transaction> upserted, Set<String> inactiveKeys) {
        for (PayMoneySyncDto payMoney : bundle.payMoney) {
            String accountNoEncrypted = payMoneyExclusionKey(payMoney.getPayMoneyId());
            ConnectedAccount row = ConnectedAccount.builder()
                    .userId(userId)
                    .bankCode(payMoney.getProviderCode())
                    .bankName(payMoney.getProviderName())
                    .accountName(payMoney.getWalletName())
                    .accountNoEncrypted(accountNoEncrypted)
                    .accountType("PAYMONEY")
                    .provider(payMoney.getProviderCode())
                    .balance(payMoney.getBalance())
                    .syncStatus("NORMAL")
                    .lastSyncAt(now)
                    .build();
            Long accountId = upsertConnectedAccount(userId, row, accountNoEncrypted, inactiveKeys);
            if (accountId == null) {
                continue;   // 사용자가 해제한 계좌 — 계좌도 거래도 되살리지 않는다
            }
            for (PayMoneyTransactionSyncDto tx :
                    bundle.payMoneyTransactions.getOrDefault(payMoney.getPayMoneyId(), List.of())) {
                /*
                 * 충전·결제·환불이 한 테이블에 섞여 있고 amount 부호도 제각각이다(결제만 음수).
                 * saveTransaction 이 절댓값을 취하므로 부호가 사라진다 — 유형 코드로 판정해야 한다.
                 */
                upserted.add(saveTransaction(userId, accountId, null, "PAYMONEY",
                        "PAYMONEY-" + accountId + "-" + tx.getTransactionId(),
                        payMoneyKind(tx.getTransTypeCode()), tx.getAmount(),
                        PAY_TYPE_REFUND.equals(tx.getTransTypeCode()),
                        tx.getTransactedAt(), tx.getMerchantName(),
                        tx.getMerchantCategoryCode(), tx.getMerchantCategoryName(),
                        null, null, tx.getRawJson()));
            }
        }
    }

    /* ── upsert 헬퍼 ───────────────────────────────────── */

    /**
     * ⚠ 은행 계좌만 예외적으로 AccountLinkService 와 **같은 해시 키**를 쓴다(#334).
     *
     * 은행(BANK/DEMAND_DEPOSIT)은 목서버의 `/api/v1/assets/accounts` 를 계좌 연동
     * (AccountLinkService.link(), accountNumbers.hash(bankCode, accountNoMasked)) 과 이 배치 동기화가
     * **둘 다** 부르는 유일한 자산 종류다 — 예적금·증권·대출·페이머니·카드는 연동 흐름이 아예 모르는
     * 별도 엔드포인트라 이 배치만 만든다. 그래서 은행만 "MOCK-BANK-{id}" 같은 소스전용 키를 쓰면
     * 두 흐름이 **같은 실계좌**를 서로 다른 행으로 각각 만든다 — 실제로 재현됐다: 계좌 연동 직후
     * 화면에 같은 계좌(같은 마스킹 번호·같은 잔액)가 두 번 떴다. 목서버는 마스킹된 계좌번호만 주므로
     * (AccountLinkService 문서 참고) accountNoMasked 를 그대로 해시 입력에 써도 두 쪽이 같은 값을 낸다.
     */
    private Long upsertBankAccount(long userId, BankAccountSyncDto account, LocalDateTime now,
                                   Set<String> inactiveKeys) {
        String accountType = "SAVINGS".equalsIgnoreCase(account.getAccountTypeCode())
                ? "SAVINGS" : "DEMAND_DEPOSIT";
        /*
         * 실 CODEF 는 전체 계좌번호를 준다(accountNoRaw). 계좌 연동도 전체 번호로 해시하므로 그걸 써야
         * 같은 행으로 모인다 — 마스킹 번호로 해시하면 같은 실계좌가 두 행이 된다. 목서버는 null 이다.
         */
        String hashInput = account.getAccountNoRaw() != null ? account.getAccountNoRaw() : account.getAccountNoMasked();
        String accountNoEncrypted = accountNumbers.hash(account.getInstitutionCode(), hashInput);
        ConnectedAccount row = ConnectedAccount.builder()
                .userId(userId)
                .bankCode(account.getInstitutionCode())
                .bankName(account.getInstitutionName())
                .accountName(account.getProductName())
                .accountNoEncrypted(accountNoEncrypted)
                .accountNoMasked(account.getAccountNoMasked())
                .accountType(accountType)
                .balance(account.getBalance())
                .syncStatus("NORMAL")
                .lastSyncAt(now)
                .build();
        return upsertConnectedAccount(userId, row, accountNoEncrypted, inactiveKeys);
    }

    /**
     * BANK/DEPOSIT/SECURITIES/PAYMONEY 공통 upsert (reactivate 시도 → 0행이면 insert). PK 를 돌려준다.
     *
     * ⚠ DEPOSIT/SECURITIES/PAYMONEY 는 account_no_encrypted 에 "MOCK-{소스}-{목서버ID}" 를 넣는다.
     *   AccountLinkService 의 연동 흐름이 이 세 종류를 아예 다루지 않아(별도 엔드포인트) 겹칠 행이
     *   없기 때문이다. **BANK 는 예외다** — upsertBankAccount() 의 문서 참고. 그쪽은 연동 흐름과
     *   같은 해시 키를 써서 일부러 겹치게 한다.
     *
     * ⚠ 사용자가 해제한 계좌면 **아무것도 하지 않고 null 을 돌려준다**(이슈 #199 최종 리뷰).
     *   reactivate 의 SQL 은 is_active=1 을 무조건 세우는데, 그 동작 자체는 AccountLinkService 의
     *   재연결 흐름이 의존하므로 손대지 않는다 — 되살리면 안 되는 상황을 여기서 거른다.
     *   null 을 받은 호출부는 그 계좌의 거래까지 통째로 건너뛴다.
     */
    private Long upsertConnectedAccount(long userId, ConnectedAccount row, String accountNoEncrypted,
                                        Set<String> inactiveKeys) {
        if (inactiveKeys.contains(accountNoEncrypted)) {
            log.debug("사용자가 해제한 계좌라 동기화에서 제외한다 userId={} key={}", userId, accountNoEncrypted);
            return null;
        }
        if (connectedAccountMapper.reactivate(row) > 0) {
            return findConnectedAccountId(userId, accountNoEncrypted);
        }
        try {
            connectedAccountMapper.insert(row);
        } catch (DuplicateKeyException e) {
            /* 경합에서 졌다(클래스 주석 「동시 insert 경합」). 상대 행에 우리 값을 덮고 그 PK 를 쓴다. */
            connectedAccountMapper.reactivate(row);
            return findConnectedAccountId(userId, accountNoEncrypted);
        }
        return row.getId();     // useGeneratedKeys 로 insert 직후 채워진다
    }

    /** update/경합 경로는 PK 를 돌려주지 않는다. 자연키로 다시 찾는다. */
    private Long findConnectedAccountId(long userId, String accountNoEncrypted) {
        return connectedAccountMapper.findActiveByUser(userId).stream()
                .filter(a -> accountNoEncrypted.equals(a.getAccountNoEncrypted()))
                .findFirst()
                .map(ConnectedAccount::getId)
                .orElseThrow(() -> new BusinessException("EXTERNAL_API_ERROR",
                        "동기화한 계좌를 다시 찾지 못했어요."));
    }

    /**
     * 대출 upsert (update 시도 → 0행이면 insert). PK 를 돌려준다.
     *
     * 방금 갱신한 행을 못 찾는 건 이상 상황이다. null 로 두면 account_id·loan_id 가 둘 다 빈
     * 고아 거래가 생기므로 다른 upsert 헬퍼들과 똑같이 예외로 끊는다.
     */
    private Long upsertLoan(long userId, Loan row) {
        if (loanMapper.update(row) > 0) {
            return findLoanId(userId, row.getLoanNoEncrypted());
        }
        try {
            loanMapper.insert(row);
        } catch (DuplicateKeyException e) {
            loanMapper.update(row);
            return findLoanId(userId, row.getLoanNoEncrypted());
        }
        return row.getId();
    }

    private Long findLoanId(long userId, String loanNoEncrypted) {
        return loanMapper.findByUser(userId).stream()
                .filter(l -> loanNoEncrypted.equals(l.getLoanNoEncrypted()))
                .findFirst()
                .map(Loan::getId)
                .orElseThrow(() -> new BusinessException("EXTERNAL_API_ERROR",
                        "동기화한 대출을 다시 찾지 못했어요."));
    }

    private Long upsertCard(long userId, CardSyncDto card, LocalDateTime now) {
        Card row = Card.builder()
                .userId(userId)
                .institutionCode(card.getInstitutionCode())
                .institutionName(card.getInstitutionName())
                .cardNoMasked(card.getCardNoMasked())
                .productName(card.getProductName())
                .cardProductCode(card.getCardProductCode())
                .cardTypeCode(card.getCardTypeCode())
                .cardStatusCode(card.getCardStatusCode())
                .currency(card.getCurrency())
                .issuedAt(date(card.getIssuedAt()))
                .lastSyncAt(now)
                .build();
        if (cardMapper.update(row) > 0) {
            return findCardId(userId, card.getCardNoMasked());
        }
        try {
            cardMapper.insert(row);
        } catch (DuplicateKeyException e) {
            cardMapper.update(row);
            return findCardId(userId, card.getCardNoMasked());
        }
        return row.getId();
    }

    private Long findCardId(long userId, String cardNoMasked) {
        return cardMapper.findByUser(userId).stream()
                .filter(c -> cardNoMasked.equals(c.getCardNoMasked()))
                .findFirst()
                .map(Card::getId)
                .orElseThrow(() -> new BusinessException("EXTERNAL_API_ERROR",
                        "동기화한 카드를 다시 찾지 못했어요."));
    }

    /** 카드 청구서는 거래가 아니다 — tbl_card_bill 에만 넣는다(설계 §7). */
    private void upsertCardBill(Long cardId, CardBillSyncDto bill) {
        CardBill row = CardBill.builder()
                .cardId(cardId)
                .billingMonth(bill.getBillingMonth())
                .dueDate(date(bill.getDueDate()))
                .billStatusCode(bill.getBillStatusCode())
                .billStatusName(bill.getBillStatusName())
                .totalAmount(bill.getTotalAmount())
                .paidAmount(bill.getPaidAmount())
                .build();
        if (cardBillMapper.update(row) == 0) {
            try {
                cardBillMapper.insert(row);
            } catch (DuplicateKeyException e) {
                cardBillMapper.update(row);
            }
        }
    }

    /**
     * 거래 한 건 upsert. 멱등키(codef_tr_key) 로 update 를 먼저 시도하고 0행이면 insert 한다 —
     * 재동기화로 같은 거래가 다시 들어와도 행이 늘지 않는다(설계 §9).
     *
     * ⚠ codefTrKey 의 상품 ID 자리에는 **반드시 우리 DB 의 PK** 를 넣는다(목서버 ID 금지).
     *   `uk_tx_codef_key` 는 codef_tr_key 단독 UNIQUE 이고 update 의 WHERE 에도 user_id 가 없다.
     *   목서버 ID 를 쓰면 시나리오 키를 공유하는 사용자들이 같은 키를 만들어, 두 번째 사용자의 동기화가
     *   **첫 번째 사용자의 거래를 덮어쓰고 자기 행은 만들지 못한다**(6명이 같은 목서버로 데모한다).
     *   우리 PK 는 상품 자연키 upsert 로 사용자마다 다르고 재동기화 사이에는 안 변한다.
     */
    private Transaction saveTransaction(long userId, Long accountId, Long cardId, String sourceType,
                                 String codefTrKey, TxKind kind, BigDecimal amount, boolean refund,
                                 String transactedAt, String merchantName,
                                 String merchantCategoryCode, String merchantCategoryName,
                                 String correlationId, String originalApprovalNo, String rawJson) {
        /* tbl_transaction.amount 규약: "항상 양수. 환불도 양수 + is_refund=1". 여기 한곳에서 강제한다. */
        BigDecimal positiveAmount = amount == null ? null : amount.abs();
        Transaction row = Transaction.builder()
                .userId(userId)
                .accountId(accountId)
                .cardId(cardId)
                .codefTrKey(codefTrKey)
                .merchantName(merchantName)
                .merchantNameNormalized(merchantName)
                .amount(positiveAmount)
                /* 방향·3분류는 소스마다 규칙이 달라 호출부가 TxKind 로 정해 넘긴다(아래 enum 참고). */
                .direction(kind.direction)
                .trDate(OffsetDateTime.parse(transactedAt).toLocalDate())
                .classification(kind.classification)
                .isExcludedFromSummary(false)
                .isRefund(refund)
                .refundedAmount(refund ? positiveAmount : null)
                .sourceType(sourceType)
                .correlationId(correlationId)
                .originalApprovalNo(originalApprovalNo)
                .merchantCategoryCode(merchantCategoryCode)
                .merchantCategoryName(merchantCategoryName)
                .rawJson(rawJson)
                .build();
        return upsertTransaction(row);
    }

    /**
     * 거래 upsert 한 곳. 대출 거래처럼 Transaction 을 직접 조립하는 경로도 여기를 탄다.
     *
     * update 경로(재동기화)는 PK 를 돌려주지 않아 row.getId() 가 계속 null 이다. 카테고리화 파이프라인이
     * "이번 호출에서 upsert 된 거래의 id" 를 알아야 해서, id 가 비어 있으면 멱등키로 한 번 더 조회해 채운다.
     */
    private Transaction upsertTransaction(Transaction row) {
        if (transactionMapper.update(row) == 0) {
            try {
                transactionMapper.insert(row);
            } catch (DuplicateKeyException e) {
                /* 동시 동기화가 같은 멱등키를 먼저 넣었다. 그 행을 우리 값으로 갱신하고 넘어간다. */
                transactionMapper.update(row);
            }
        }
        if (row.getId() == null) {
            row.setId(transactionMapper.findIdByCodefTrKey(row.getCodefTrKey()));
        }
        return row;
    }

    /**
     * 목서버 은행 거래유형 코드 → 방향·3분류.
     *
     * 급여·이자 입금을 CONSUMPTION 으로 저장하면 미션·리포트의 소비 집계
     * (`MissionCategoryAnalysisMapper`: `classification='CONSUMPTION'`)가 통째로 오염된다.
     * 아는 코드만 단정하고, 모르는 코드는 지어내지 않고 소비도 수입도 아닌 TRANSFER 로 둔다
     * (classification 은 NOT NULL + CHECK 라 비울 수 없다).
     */
    private static TxKind bankKind(String transTypeCode) {
        if (TRANS_TYPE_IN.equals(transTypeCode)) {
            return TxKind.BANK_IN;
        }
        if (TRANS_TYPE_OUT.equals(transTypeCode)) {
            return TxKind.BANK_OUT;
        }
        return TxKind.TRANSFER;
    }

    /**
     * 목서버 페이머니 거래유형 코드 → 방향·3분류. `bankKind` 와 같은 모양이지만 코드 의미가 달라
     * 별도 헬퍼다.
     *
     * - `01` 충전: 내 돈을 지갑으로 옮기는 것뿐이라 소비가 아니다 → 예적금·증권과 같은 TRANSFER.
     *   (충전의 짝이 되는 은행 출금이 따로 잡히면 이중계상이 되지만, 그건 체크카드↔은행 연결과 같은
     *    성격의 별개 과제다 — 여기서는 페이머니 자신의 분류만 바로잡는다.)
     * - `02` 결제: 진짜 소비 → CONSUMPTION.
     * - `03` 환불: 소비의 정정 → CONSUMPTION + is_refund (호출부가 refund 플래그를 세운다).
     *   미션 집계가 `is_refund=1 이면 -refunded_amount` 로 상계하므로 이 조합이어야 맞는다.
     * - 그 외: bankKind 와 같은 이유로 지어내지 않고 TRANSFER 로 둔다.
     */
    private static TxKind payMoneyKind(String transTypeCode) {
        if (PAY_TYPE_TOPUP.equals(transTypeCode)) {
            return TxKind.TRANSFER;
        }
        if (PAY_TYPE_PAYMENT.equals(transTypeCode) || PAY_TYPE_REFUND.equals(transTypeCode)) {
            return TxKind.PAY_MONEY;
        }
        return TxKind.TRANSFER;     // 모르는 코드 — bankKind 와 같은 이유
    }

    /* ── 잡동사니 ──────────────────────────────────────── */

    private static List<StockHoldingSyncDto> holdings(StockAssetSyncDto stock) {
        return stock.getHoldings() == null ? List.of() : stock.getHoldings();
    }

    /** 실패 사유를 컬럼 길이에 맞춘다 — 이력 남기려다 이력 insert 가 터지면 본말전도다. */
    private static String truncate(String reason) {
        if (reason == null || reason.length() <= FAIL_REASON_MAX) {
            return reason;
        }
        return reason.substring(0, FAIL_REASON_MAX);
    }

    /** 목서버 날짜는 전부 yyyy-MM-dd 다. 빈 값은 그대로 비워 둔다. */
    private static LocalDate date(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append('"').append(values.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * 거래 한 건의 방향(`direction`)과 3분류(`classification`) 조합.
     *
     * `classification` 은 NOT NULL + CHECK(CONSUMPTION/TRANSFER/INCOME) 이므로 **절대 null 이 될 수
     * 없다.** 반대로 `direction` 은 nullable 이라 확신이 없으면 비운다 — 금액 부호로는 판정할 수 없다
     * (amount 는 항상 양수). 소스별로 규칙이 다르므로 호출부가 골라서 넘긴다.
     */
    private enum TxKind {
        /** 은행 입금(`trans_type_code=01`) — 급여·이자 등 유입. 소비가 아니라 수입이다. */
        BANK_IN("IN", "INCOME"),
        /** 은행 출금(`trans_type_code=02`) — 실제 지출. */
        BANK_OUT("OUT", "CONSUMPTION"),
        /** 카드 승인 — 가맹점 결제라 성격상 무조건 출금·소비다(취소도 소비 거래의 정정으로 남긴다). */
        CARD_SPEND("OUT", "CONSUMPTION"),
        /** 페이머니 — 충전·결제가 섞여 있으나 소비 지갑으로 본다. 방향은 단정하지 않는다. */
        PAY_MONEY(null, "CONSUMPTION"),
        /** 예적금 납입·증권 매매·대출 실행/상환 — 돈이 상품으로 옮겨갈 뿐 소비가 아니다. */
        TRANSFER(null, "TRANSFER");

        private final String direction;
        private final String classification;

        TxKind(String direction, String classification) {
            this.direction = direction;
            this.classification = classification;
        }
    }

    /**
     * collectAll() 의 병렬 소스 중 하나가 실패했을 때 어느 소스였는지 실어 올리는 래퍼.
     * 스레드마다 자기가 맡은 소스 이름을 아는 채로 던지므로 SourceCursor 같은 공유 상태가 필요 없다.
     */
    private static final class SourceCollectionException extends RuntimeException {
        private final String source;

        private SourceCollectionException(String source, BusinessException cause) {
            super(cause);
            this.source = source;
        }
    }

    private static final class SyncScope {
        private final Set<String> institutionCodes;
        private final Set<String> institutionNames;

        private SyncScope(Set<String> institutionCodes, Set<String> institutionNames) {
            this.institutionCodes = institutionCodes;
            this.institutionNames = institutionNames;
        }

        private boolean includesCode(String code) {
            return institutionCodes.isEmpty() || institutionCodes.contains(code);
        }

        private boolean includesName(String name) {
            return institutionNames.isEmpty() || institutionNames.contains(name);
        }
    }

    /**
     * BANK/CARD 증분 수집 커서(이슈 #199). 계좌/카드별 마지막 동기화 시각을 안다 —
     * 이미 아는 계좌/카드는 마지막 동기화 달부터만, 처음 보는 계좌/카드는 전체 이력을 받게 하는
     * 근거다. DB 조회는 sync() 시작 시 한 번만 하고 collectAll() 안에서는 이 맵만 읽는다 —
     * collectAll() 이 "DB 안 읽는 순수 외부호출" 이라는 설계(클래스 Javadoc)를 유지하기 위함.
     */
    private static final class MonthlyFetchCursor {
        private final Map<String, LocalDateTime> accountLastSyncByKey;
        private final Map<String, LocalDateTime> cardLastSyncByMaskedNo;

        MonthlyFetchCursor(Map<String, LocalDateTime> accountLastSyncByKey,
                           Map<String, LocalDateTime> cardLastSyncByMaskedNo) {
            this.accountLastSyncByKey = accountLastSyncByKey;
            this.cardLastSyncByMaskedNo = cardLastSyncByMaskedNo;
        }

        LocalDateTime accountLastSyncAt(String accountNoMasked) {
            return accountLastSyncByKey.get(accountNoMasked);
        }

        LocalDateTime cardLastSyncAt(String cardNoMasked) {
            return cardLastSyncByMaskedNo.get(cardNoMasked);
        }
    }

    /** 목서버 rawJson 문자열에서 correlationId/originalApprovalNo 만 뽑는다. 나머지 키는 무시한다. */
    private static final class RawJsonFields {
        private final String correlationId;
        private final String originalApprovalNo;

        private RawJsonFields(String correlationId, String originalApprovalNo) {
            this.correlationId = correlationId;
            this.originalApprovalNo = originalApprovalNo;
        }

        static RawJsonFields parse(String rawJson) {
            if (rawJson == null || rawJson.isBlank()) {
                return new RawJsonFields(null, null);
            }
            try {
                JsonNode node = RAW_JSON_READER.readTree(rawJson);
                return new RawJsonFields(
                        node.hasNonNull("correlationId") ? node.get("correlationId").asText() : null,
                        node.hasNonNull("originalApprovalNo")
                                ? node.get("originalApprovalNo").asText() : null);
            } catch (Exception e) {
                /*
                 * raw_json 파싱 실패는 동기화 전체를 막을 이유가 아니다 — 연결 정보 없이 저장한다.
                 * 다만 조용히 넘어가면 이 catch 가 "정상적인 형식 오류"와 "추출 로직 자체의 버그"를
                 * 구분 못 해 후자를 영원히 숨긴다 — 최소한 로그는 남긴다.
                 */
                log.warn("raw_json correlationId 추출 실패, 연결 정보 없이 저장한다: {}", e.getMessage());
                return new RawJsonFields(null, null);
            }
        }
    }

    /**
     * 수집 결과 묶음. 거래는 **상품별 Map** 으로 담는다 — 하나의 평면 리스트에 모으면 계좌가 둘 이상일 때
     * 어느 계좌의 거래인지 잃어버려 서로 섞인다.
     */
    private static final class SyncBundle {
        final List<BankAccountSyncDto> bankAccounts = new ArrayList<>();
        final Map<Long, List<BankTransactionSyncDto>> bankTransactions = new HashMap<>();
        final List<DepositSyncDto> deposits = new ArrayList<>();
        final Map<Long, List<DepositTransactionSyncDto>> depositTransactions = new HashMap<>();
        StockAssetSyncDto stock;
        final Map<Long, List<SecuritiesTransactionSyncDto>> securitiesTransactions = new HashMap<>();
        final List<LoanSyncDto> loans = new ArrayList<>();
        final Map<Long, List<LoanTransactionSyncDto>> loanTransactions = new HashMap<>();
        final List<PayMoneySyncDto> payMoney = new ArrayList<>();
        final Map<Long, List<PayMoneyTransactionSyncDto>> payMoneyTransactions = new HashMap<>();
        final List<CardSyncDto> cards = new ArrayList<>();
        final Map<Long, List<CardApprovalSyncDto>> cardApprovals = new HashMap<>();
        final Map<Long, List<CardBillSyncDto>> cardBills = new HashMap<>();
    }
}
