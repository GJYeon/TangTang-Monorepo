package com.kb.tangtang.account.client.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kb.tangtang.account.client.CodefConnectionStore;
import com.kb.tangtang.account.client.CodefHttpClient;
import com.kb.tangtang.account.client.CodefOrganizations;
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
import com.kb.tangtang.common.exception.BusinessException;
import lombok.extern.log4j.Log4j2;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 실 CODEF 거래내역 동기화 클라이언트.
 *
 * {@link FinancialSyncClient} 는 목서버 모양(시나리오 키 + 목서버 숫자 ID)으로 설계돼 있다. 서비스를 고치지 않고
 * CODEF 를 붙이기 위해 이 클래스가 그 모양을 흉내낸다.
 *   · 키 — `codef:{userId}`. 사용자의 기관별 connectedId 는 {@link CodefConnectionStore} 에서 찾는다.
 *   · ID — CODEF 에는 계좌 ID 가 없다. (기관·계좌번호)로 **결정적** long 을 만들고, 목록 조회 때
 *          ID → 계좌번호 매핑을 기억해 둔다. 이어지는 거래내역 조회가 그 ID 로 계좌번호를 되찾는다.
 *
 * 다루는 상품 (CODEF 개발가이드 2026-10-14 확인):
 *   · 은행 보유계좌   /v1/kr/bank/p/account/account-list
 *   · 수시입출 거래   /v1/kr/bank/p/account/transaction-list
 *   · 적금 거래       /v1/kr/bank/p/installment-savings/transaction-list
 *   · 대출 거래       /v1/kr/bank/p/loan/transaction-list
 *   · 보유카드        /v1/kr/card/p/account/card-list
 *   · 카드 승인내역   /v1/kr/card/p/account/approval-list
 *   · 카드 청구내역   /v1/kr/card/p/account/billing-list
 * 증권은 범위 밖으로 결정했고(2026-10-14), 페이머니(카카오페이·네이버페이 잔액)는 CODEF 개인 상품이 없다 —
 * 둘 다 **빈 결과**를 준다. 실데이터 사용자에게 목서버 가짜 데이터를 섞지 않는다.
 *
 * 예적금(정기/적금·신탁)은 getDeposits 가 아니라 **은행 계좌로** 돌려준다. 계좌 연동 흐름이 예적금을
 * 은행 계좌(account_type=SAVINGS)로 저장하므로, 여기서 deposits 로 돌리면 같은 계좌가 두 행이 된다.
 *
 * ⚠ 계좌번호·카드번호 원본은 메모리 매핑에만 둔다. 로그로 찍지 않는다.
 */
@Log4j2
public class CodefFinancialSyncClient implements FinancialSyncClient {

    static final String KEY_PREFIX = "codef:";

    /** 처음 동기화(마지막 동기화 시각을 모를 때) 거슬러 올라가는 기간. 고정지출 탐지에 몇 달치가 필요하다. */
    static final int INITIAL_MONTHS = 6;

    /** 대출 거래는 월 단위 커서가 없어 매번 이 기간을 다시 받는다(멱등키라 중복 저장은 없다). */
    static final int LOAN_HISTORY_MONTHS = 12;

    /**
     * 같은 CODEF 응답을 재사용하는 시간. 한 번의 동기화가 은행 보유계좌를 BANK·LOAN 두 소스에서 동시에
     * 부르고, 카드 승인내역을 카드마다 부른다 — 기관 단위 응답을 한 번만 받아 나눠 쓴다.
     * CODEF 는 과다 호출 시 IP 를 차단한다고 경고한다.
     */
    private static final Duration MEMO_TTL = Duration.ofMinutes(2);

    /** 은행 예금구분(resAccountDeposit): 11 수시입출 · 10 미분류(수시입출 구분 안 됨). 나머지는 저축성. */
    private static final String DEPOSIT_DEMAND = "11";
    private static final String DEPOSIT_UNCLASSIFIED = "10";
    /** 12 정기/적금 · 14 미분류(적금 or 신탁) — 적금 거래내역 API 로 조회한다. 13 신탁은 거래내역 상품이 없다. */
    private static final String DEPOSIT_SAVINGS = "12";
    private static final String DEPOSIT_SAVINGS_OR_TRUST = "14";

    /** 서비스가 아는 은행 거래유형(FinancialSyncServiceImpl.bankKind). 모르는 코드는 TRANSFER 로 저장된다. */
    private static final String TRANS_IN = "01";
    private static final String TRANS_OUT = "02";
    /** 적금 입금은 내 돈을 옮긴 것이지 수입이 아니다 — 일부러 모르는 코드를 줘 TRANSFER 로 저장되게 한다. */
    private static final String TRANS_SAVINGS = "09";

    /** 서비스가 아는 카드 승인유형. 03 이면 환불(is_refund)로 저장한다. */
    private static final String APPROVAL_NORMAL = "01";
    private static final String APPROVAL_CANCEL = "03";
    /** resCancelYN: 0 정상 · 1 취소 · 2 부분취소 · 3 거절 */
    private static final String CANCEL_FULL = "1";
    private static final String CANCEL_PARTIAL = "2";
    private static final String CANCEL_REJECTED = "3";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CodefHttpClient http;
    private final String baseUrl;
    private final CodefConnectionStore connections;
    /** 표시용 마스킹. AccountNumberPolicy::mask 를 받는다 — 연동 화면과 같은 형식이어야 커서 매칭이 된다. */
    private final Function<String, String> masker;
    /** 우리 기관코드 → 기관명. InstitutionCatalog::nameOf 를 받는다. */
    private final Function<String, String> institutionNames;
    private final Clock clock;

    /** (키|종류|ID) → 원본 식별 정보. 목록 조회가 채우고 상세 조회가 읽는다. */
    private final Map<String, Object> handles = new ConcurrentHashMap<>();
    private final Map<String, Memo> memo = new ConcurrentHashMap<>();

    public CodefFinancialSyncClient(CodefHttpClient http, String baseUrl, CodefConnectionStore connections,
                                    Function<String, String> masker, Function<String, String> institutionNames,
                                    Clock clock) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.connections = connections;
        this.masker = masker;
        this.institutionNames = institutionNames;
        this.clock = clock;
    }

    public static String keyOf(long userId) {
        return KEY_PREFIX + userId;
    }

    public static boolean isCodefKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX);
    }

    /* ── 은행: 수시입출·예적금 ─────────────────────────── */

    @Override
    public List<BankAccountSyncDto> getBankAccounts(String key) {
        List<BankAccountSyncDto> result = new ArrayList<>();
        forEachConnection(key, false, connection -> {
            for (Object item : CodefHttpClient.asList(accountList(key, connection).get("resDepositTrust"))) {
                Map<String, Object> row = CodefHttpClient.asMap(item);
                String accountNo = digits(text(row, "resAccount"));
                if (accountNo.isEmpty()) {
                    continue;
                }
                String depositCode = text(row, "resAccountDeposit");
                boolean demand = DEPOSIT_DEMAND.equals(depositCode) || DEPOSIT_UNCLASSIFIED.equals(depositCode)
                        || depositCode == null;
                long id = stableId("BANK", connection.organization(), accountNo);
                handles.put(handleKey(key, "BANK", id),
                        new AccountHandle(connection, accountNo, depositCode, null));
                result.add(BankAccountSyncDto.builder()
                        .accountId(id)
                        .institutionCode(connection.organization())
                        .institutionName(institutionNames.apply(connection.organization()))
                        .accountTypeCode(demand ? "DEMAND_DEPOSIT" : "SAVINGS")
                        .productName(text(row, "resAccountName"))
                        .accountNoMasked(masker.apply(accountNo))
                        .accountNoRaw(accountNo)
                        .currency(orDefault(text(row, "resAccountCurrency"), "KRW"))
                        .balance(decimal(row.get("resAccountBalance")))
                        .availableAmount(null)
                        .build());
            }
        });
        return result;
    }

    @Override
    public List<BankTransactionSyncDto> getBankTransactions(String key, long accountId) {
        return getBankTransactions(key, accountId, null);
    }

    @Override
    public List<BankTransactionSyncDto> getBankTransactions(String key, long accountId, String yearMonth) {
        AccountHandle account = handle(key, "BANK", accountId, AccountHandle.class);
        String depositCode = account.depositCode;
        boolean savings = DEPOSIT_SAVINGS.equals(depositCode) || DEPOSIT_SAVINGS_OR_TRUST.equals(depositCode);
        if (!savings && depositCode != null && !DEPOSIT_DEMAND.equals(depositCode)
                && !DEPOSIT_UNCLASSIFIED.equals(depositCode)) {
            return List.of();   // 신탁(13) — CODEF 개인 거래내역 상품이 없다
        }
        String path = savings
                ? "/v1/kr/bank/p/installment-savings/transaction-list"
                : "/v1/kr/bank/p/account/transaction-list";

        Range range = range(yearMonth, INITIAL_MONTHS);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organization", CodefOrganizations.toCodef(account.connection.organization()));
        body.put("connectedId", account.connection.connectedId());
        body.put("account", account.accountNo);
        body.put("startDate", range.start);
        body.put("endDate", range.end);
        body.put("orderBy", "0");
        /* 계좌 상세는 이미 보유계좌 조회로 받았다. 거래내역만 받는다. */
        body.put("inquiryType", "0");
        Map<String, Object> data = data(http.post(baseUrl + path, body));

        List<BankTransactionSyncDto> result = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (Object item : CodefHttpClient.asList(data.get("resTrHistoryList"))) {
            Map<String, Object> row = CodefHttpClient.asMap(item);
            BigDecimal in = decimal(row.get("resAccountIn"));
            BigDecimal out = decimal(row.get("resAccountOut"));
            boolean incoming = in.signum() > 0;
            String typeCode = savings ? TRANS_SAVINGS : (incoming ? TRANS_IN : TRANS_OUT);
            String date = text(row, "resAccountTrDate");
            String time = text(row, "resAccountTrTime");
            result.add(BankTransactionSyncDto.builder()
                    .transactionId(occurrenceId(seen, "BANKTX", account.accountNo, date, time,
                            text(row, "resAccountIn"), text(row, "resAccountOut"),
                            text(row, "resAfterTranBalance"), text(row, "resAccountDesc1"),
                            text(row, "resAccountDesc2"), text(row, "resAccountDesc3")))
                    .transactedAt(isoDateTime(date, time))
                    .transTypeCode(typeCode)
                    .amount(incoming ? in : out.abs())
                    .balanceAfter(nullableDecimal(row.get("resAfterTranBalance")))
                    .description(bankDescription(row))
                    .rawJson(json(row))
                    .build());
        }
        return result;
    }

    /** 예적금은 은행 계좌로 돌려준다(클래스 문서 참고). */
    @Override
    public List<DepositSyncDto> getDeposits(String key) {
        return List.of();
    }

    @Override
    public List<DepositTransactionSyncDto> getDepositTransactions(String key, long depositAccountId) {
        return List.of();
    }

    /* ── 증권·페이머니: 다루지 않는다 ──────────────────── */

    /** 증권은 범위 밖(2026-10-14 결정). null 은 "증권 자산 없음" 이라는 정상 상태다(MockFinancialSyncClient 와 같다). */
    @Override
    public StockAssetSyncDto getStockAsset(String key) {
        return null;
    }

    @Override
    public List<SecuritiesTransactionSyncDto> getSecuritiesTransactions(String key, long accountId) {
        return List.of();
    }

    /** CODEF 에는 간편결제 잔액·거래 개인 상품이 없다. */
    @Override
    public List<PayMoneySyncDto> getPayMoney(String key) {
        return List.of();
    }

    @Override
    public List<PayMoneyTransactionSyncDto> getPayMoneyTransactions(String key, long payMoneyId) {
        return List.of();
    }

    /* ── 대출: 은행 보유계좌의 resLoan ─────────────────── */

    @Override
    public List<LoanSyncDto> getLoans(String key) {
        List<LoanSyncDto> result = new ArrayList<>();
        forEachConnection(key, false, connection -> {
            for (Object item : CodefHttpClient.asList(accountList(key, connection).get("resLoan"))) {
                Map<String, Object> row = CodefHttpClient.asMap(item);
                String accountNo = digits(text(row, "resAccount"));
                if (accountNo.isEmpty()) {
                    continue;
                }
                String execNo = text(row, "resAccountLoanExecNo");
                long id = stableId("LOAN", connection.organization(), accountNo, orDefault(execNo, ""));
                AccountHandle loan = new AccountHandle(connection, accountNo, null, execNo);
                handles.put(handleKey(key, "LOAN", id), loan);

                /* 원금·이율은 보유계좌에 없다. 대출 거래내역의 계좌 상세에서 가져온다(곧 getLoanTransactions 가
                   같은 응답을 재사용한다 — memo). 그 조회가 실패해도 대출 자체는 보여준다. */
                Map<String, Object> detail = Map.of();
                try {
                    detail = loanHistory(key, loan);
                } catch (BusinessException e) {
                    log.warn("CODEF 대출 상세를 받지 못해 원금·이율 없이 둔다 organization={} code={}",
                            connection.organization(), e.getCode());
                }
                result.add(LoanSyncDto.builder()
                        .loanId(id)
                        .institutionCode(connection.organization())
                        .institutionName(institutionNames.apply(connection.organization()))
                        .productName(orDefault(text(detail, "resLoanKind"), text(row, "resAccountName")))
                        .loanNoMasked(masker.apply(accountNo))
                        .principal(nullableDecimal(detail.get("resPrincipal")))
                        .balance(decimal(row.get("resAccountBalance")).abs())
                        .interestRate(nullableDecimal(detail.get("resRate")))
                        .startDate(isoDate(text(row, "resAccountStartDate")))
                        .maturityDate(isoDate(text(row, "resAccountEndDate")))
                        .monthlyPayment(null)
                        .nextPaymentDate(isoDate(text(detail, "resDatePayment")))
                        .build());
            }
        });
        return result;
    }

    @Override
    public List<LoanTransactionSyncDto> getLoanTransactions(String key, long loanId) {
        AccountHandle loan = handle(key, "LOAN", loanId, AccountHandle.class);
        List<LoanTransactionSyncDto> result = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (Object item : CodefHttpClient.asList(loanHistory(key, loan).get("resTrHistoryList"))) {
            Map<String, Object> row = CodefHttpClient.asMap(item);
            BigDecimal principal = nullableDecimal(row.get("resPrincipal"));
            BigDecimal interest = nullableDecimal(row.get("resInterest"));
            /* 거래금액 = 원금 + 이자. 기관에 따라 거래금액만 오거나 원금·이자만 온다(문서 「출력부 특이사항」). */
            BigDecimal amount = nullableDecimal(row.get("resTranAmount"));
            if (amount == null) {
                amount = (principal == null ? BigDecimal.ZERO : principal)
                        .add(interest == null ? BigDecimal.ZERO : interest);
            }
            String date = text(row, "resAccountTrDate");
            result.add(LoanTransactionSyncDto.builder()
                    .transactionId(occurrenceId(seen, "LOANTX", loan.accountNo, date,
                            text(row, "resTransTypeNm"), text(row, "resTranAmount"),
                            text(row, "resPrincipal"), text(row, "resInterest"), text(row, "resLoanBalance")))
                    .transactedAt(isoDateTime(date, null))
                    .transTypeCode(text(row, "resTransTypeNm"))
                    .amount(amount)
                    .principalAmount(principal)
                    .interestAmount(interest)
                    .balanceAfter(nullableDecimal(row.get("resLoanBalance")))
                    .description(text(row, "resTransTypeNm"))
                    .rawJson(json(row))
                    .build());
        }
        return result;
    }

    /* ── 카드 ─────────────────────────────────────────── */

    @Override
    public List<CardSyncDto> getCards(String key) {
        List<CardSyncDto> result = new ArrayList<>();
        forEachConnection(key, true, connection -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("organization", CodefOrganizations.toCodef(connection.organization()));
            body.put("connectedId", connection.connectedId());
            body.put("inquiryType", "0");   // 카드 이미지 미포함
            Object list = memoized(key + "|cards|" + connection.organization(),
                    () -> http.post(baseUrl + "/v1/kr/card/p/account/card-list", body)).get("data");

            boolean firstOfOrganization = true;
            for (Object item : CodefHttpClient.asList(list)) {
                Map<String, Object> row = CodefHttpClient.asMap(item);
                String cardNo = text(row, "resCardNo");
                if (cardNo == null || cardNo.isBlank()) {
                    continue;
                }
                String cardName = text(row, "resCardName");
                long id = stableId("CARD", connection.organization(), cardNo, orDefault(cardName, ""));
                handles.put(handleKey(key, "CARD", id), new CardHandle(connection, cardNo, firstOfOrganization));
                firstOfOrganization = false;
                result.add(CardSyncDto.builder()
                        .cardId(id)
                        .institutionCode(connection.organization())
                        .institutionName(institutionNames.apply(connection.organization()))
                        .cardNoMasked(cardNo)
                        .productName(productName(cardName))
                        .cardProductCode(null)
                        .cardTypeCode(cardTypeCode(text(row, "resCardType"), cardName))
                        .cardStatusCode(text(row, "resState"))
                        .currency("KRW")
                        .issuedAt(isoDate(text(row, "resIssueDate")))
                        .build());
            }
        });
        return result;
    }

    @Override
    public List<CardApprovalSyncDto> getCardApprovals(String key, long cardId) {
        return getCardApprovals(key, cardId, null);
    }

    /**
     * 카드 승인내역. **카드사 단위로 한 번(전체조회)** 받아 카드번호로 나눈다 — 카드별 조회는 카드번호 전체·
     * 카드명·중복카드 순번까지 요구해(문서 「카드별 조회 시 카드번호 비교 로직」) 보유카드의 마스킹 번호로는
     * 맞출 수 없다. 같은 카드사의 다른 카드는 memo 된 응답을 재사용한다.
     */
    @Override
    public List<CardApprovalSyncDto> getCardApprovals(String key, long cardId, String yearMonth) {
        CardHandle card = handle(key, "CARD", cardId, CardHandle.class);
        Range range = range(yearMonth, INITIAL_MONTHS);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organization", CodefOrganizations.toCodef(card.connection.organization()));
        body.put("connectedId", card.connection.connectedId());
        body.put("startDate", range.start);
        body.put("endDate", range.end);
        body.put("orderBy", "0");
        body.put("inquiryType", "1");           // 전체조회
        body.put("memberStoreInfoType", "1");   // 가맹점 업종 — 카테고리화 단서
        Object list = memoized(key + "|approvals|" + card.connection.organization() + "|" + range,
                () -> http.post(baseUrl + "/v1/kr/card/p/account/approval-list", body)).get("data");

        List<CardApprovalSyncDto> result = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (Object item : CodefHttpClient.asList(list)) {
            Map<String, Object> row = CodefHttpClient.asMap(item);
            if (!card.cardNo.equals(text(row, "resCardNo"))) {
                continue;
            }
            String cancelYn = text(row, "resCancelYN");
            if (CANCEL_REJECTED.equals(cancelYn)) {
                continue;   // 거절 — 돈이 나가지 않았다
            }
            BigDecimal used = approvedAmount(row);
            /* KB 「취소전표매입」은 취소여부 대신 이용금액을 음수로 준다(문서 「출력부 특이사항」). */
            boolean cancelled = CANCEL_FULL.equals(cancelYn) || CANCEL_PARTIAL.equals(cancelYn) || used.signum() < 0;
            BigDecimal amount = cancelled && CANCEL_PARTIAL.equals(cancelYn)
                    ? orDefault(nullableDecimal(row.get("resCancelAmount")), used)
                    : used;
            String date = text(row, "resUsedDate");
            String time = text(row, "resUsedTime");
            String approvalNo = text(row, "resApprovalNo");
            long approvalId = occurrenceId(seen, "APPROVAL", card.cardNo, date, time, approvalNo,
                    text(row, "resUsedAmount"), cancelYn, text(row, "resMemberStoreName"));
            /*
             * 거래 멱등키가 승인번호로 만들어진다(FinancialSyncServiceImpl.writeAll). 취소건은 원승인과 승인번호가
             * 같아 그대로 쓰면 **취소가 원거래를 덮어쓴다** — 접미사로 가른다. 승인번호가 비는 기관(NH 해외건)은
             * 결정적 ID 로 대신한다.
             */
            String key0 = approvalNo == null || approvalNo.isBlank() ? "H" + approvalId : approvalNo;
            result.add(CardApprovalSyncDto.builder()
                    .approvalId(approvalId)
                    .approvalNo(cancelled ? key0 + "-C" + approvalId : key0)
                    .approvedAt(isoDateTime(date, time))
                    .approvalTypeCode(cancelled ? APPROVAL_CANCEL : APPROVAL_NORMAL)
                    .merchantName(text(row, "resMemberStoreName"))
                    .merchantCategoryCode(null)
                    .merchantCategoryName(text(row, "resMemberStoreType"))
                    .approvedAmount(amount.abs())
                    .rawJson(json(withOriginalApprovalNo(row, cancelled ? approvalNo : null)))
                    .build());
        }
        return result;
    }

    /**
     * 카드 청구서. **카드사 명세서 단위**로 오므로 그 카드사의 첫 번째 카드에만 붙인다 —
     * 카드마다 붙이면 같은 청구액이 카드 수만큼 합산된다.
     */
    @Override
    public List<CardBillSyncDto> getCardBills(String key, long cardId) {
        CardHandle card = handle(key, "CARD", cardId, CardHandle.class);
        if (!card.firstOfOrganization) {
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organization", CodefOrganizations.toCodef(card.connection.organization()));
        body.put("connectedId", card.connection.connectedId());
        /* startDate 를 비우면 최근 명세서를 준다. */
        Object list = memoized(key + "|bills|" + card.connection.organization(),
                () -> http.post(baseUrl + "/v1/kr/card/p/account/billing-list", body)).get("data");

        List<CardBillSyncDto> result = new ArrayList<>();
        for (Object item : CodefHttpClient.asList(list)) {
            Map<String, Object> row = CodefHttpClient.asMap(item);
            String dueDate = isoDate(orDefault(text(row, "resPaymentDueDate"), text(row, "resWithdrawalDueDate")));
            if (dueDate == null) {
                continue;   // 청구월을 정할 수 없다(billing_month NOT NULL)
            }
            result.add(CardBillSyncDto.builder()
                    .billId(stableId("BILL", card.connection.organization(), dueDate))
                    .billingMonth(dueDate.substring(0, 7))
                    .dueDate(dueDate)
                    .billStatusCode(null)
                    .billStatusName(text(row, "resBillType"))
                    .totalAmount(decimal(row.get("resTotalAmount")))
                    .paidAmount(BigDecimal.ZERO)
                    .build());
        }
        return result;
    }

    /* ── CODEF 호출 공용 ──────────────────────────────── */

    /** 은행 보유계좌. BANK·LOAN 두 소스가 동시에 부르므로 memo 로 한 번만 보낸다. */
    private Map<String, Object> accountList(String key, CodefConnectionStore.Connection connection) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organization", CodefOrganizations.toCodef(connection.organization()));
        body.put("connectedId", connection.connectedId());
        return data(memoized(key + "|accounts|" + connection.organization(),
                () -> http.post(baseUrl + "/v1/kr/bank/p/account/account-list", body)));
    }

    /** 대출 거래내역(계좌 상세 포함). getLoans 와 getLoanTransactions 가 같은 응답을 나눠 쓴다. */
    private Map<String, Object> loanHistory(String key, AccountHandle loan) {
        Range range = range(null, LOAN_HISTORY_MONTHS);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organization", CodefOrganizations.toCodef(loan.connection.organization()));
        body.put("connectedId", loan.connection.connectedId());
        body.put("account", loan.accountNo);
        body.put("startDate", range.start);
        body.put("endDate", range.end);
        body.put("orderBy", "0");
        if (loan.loanExecNo != null && !loan.loanExecNo.isBlank()) {
            /* 보유계좌에 대출실행번호가 있으면 필수다(문서 입력부). */
            body.put("accountLoanExecNo", loan.loanExecNo);
        }
        return data(memoized(key + "|loan|" + loan.connection.organization() + "|" + loan.accountNo
                        + "|" + loan.loanExecNo + "|" + range,
                () -> http.post(baseUrl + "/v1/kr/bank/p/loan/transaction-list", body)));
    }

    /**
     * 사용자의 연결을 업권별로 돈다.
     *
     * **기관 하나의 실패로 전체 동기화를 버리지 않는다.** 은행마다 점검 시간이 달라(예: 심야) 한 곳이 막히는
     * 일이 잦은데, 예전 방식(소스 하나 실패 → 동기화 전체 실패)이면 멀쩡한 카드 승인내역까지 못 들어온다.
     * 그 기관만 건너뛰고 로그를 남긴다. **모든 기관이 실패하면** 그때는 실패로 올린다 — 조용히 빈 결과를
     * 주면 "동기화됨" 으로 보이면서 아무것도 갱신되지 않는다.
     */
    private void forEachConnection(String key, boolean cards,
                                   Consumer<CodefConnectionStore.Connection> action) {
        List<CodefConnectionStore.Connection> all = connections.activeConnections(userIdOf(key));
        if (all.isEmpty()) {
            /*
             * 쓸 수 있는 연결이 하나도 없다(해제·키 교체로 복호화 실패 등). 빈 결과로 넘기면 동기화가
             * COMPLETED 로 남아 "동기화됨" 으로 보이면서 아무것도 갱신되지 않는다 — 2026-10-15 도커 검증에서
             * 실제로 재현됐다. 재연결이 필요하다고 알린다. 한 업권만 없는 경우(카드만 연결)는 아래에서 빈 결과다.
             */
            throw new BusinessException("CONNECTION_NOT_FOUND", "금융기관 연결 정보가 없어요. 다시 연결해 주세요.");
        }
        BusinessException lastFailure = null;
        int attempted = 0;
        int failed = 0;
        for (CodefConnectionStore.Connection connection : all) {
            if (connection.isCard() != cards) {
                continue;
            }
            attempted++;
            try {
                action.accept(connection);
            } catch (BusinessException e) {
                failed++;
                lastFailure = e;
                log.warn("CODEF 기관 조회 실패, 이 기관만 건너뛴다 organization={} code={}",
                        connection.organization(), e.getCode());
            }
        }
        if (attempted > 0 && failed == attempted) {
            throw lastFailure;
        }
    }

    /**
     * 같은 키의 동시 호출은 먼저 온 쪽의 결과를 기다린다. HTTP 호출은 맵 잠금 밖에서 한다 —
     * computeIfAbsent 안에서 부르면 CODEF 응답(최대 수십 초)을 기다리는 동안 다른 키까지 막힌다.
     */
    private Map<String, Object> memoized(String memoKey, Supplier<Map<String, Object>> call) {
        Instant now = clock.instant();
        memo.values().removeIf(m -> m.at.plus(MEMO_TTL).isBefore(now));

        Memo mine = new Memo(now, new CompletableFuture<>());
        Memo existing = memo.putIfAbsent(memoKey, mine);
        Memo entry = existing == null ? mine : existing;
        if (existing == null) {
            try {
                mine.response.complete(call.get());
            } catch (RuntimeException e) {
                mine.response.completeExceptionally(e);
            }
        }
        try {
            return entry.response.join();
        } catch (CompletionException e) {
            memo.remove(memoKey, entry);    // 실패는 기억하지 않는다 — 다음 호출이 다시 시도한다
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            }
            throw e;
        }
    }

    private static Map<String, Object> data(Map<String, Object> response) {
        return CodefHttpClient.asMap(response.get("data"));
    }

    private <T> T handle(String key, String kind, long id, Class<T> type) {
        Object found = handles.get(handleKey(key, kind, id));
        if (!type.isInstance(found)) {
            /* 목록 조회 없이 상세를 부른 경우. 서비스는 항상 목록 → 상세 순서로 부르므로 정상 흐름에서는 없다. */
            throw new BusinessException("EXTERNAL_API_ERROR", "동기화할 계좌 정보를 찾지 못했어요. 다시 시도해 주세요.");
        }
        return type.cast(found);
    }

    private static String handleKey(String key, String kind, long id) {
        return key + "|" + kind + "|" + id;
    }

    static long userIdOf(String key) {
        if (!isCodefKey(key)) {
            throw new IllegalArgumentException("CODEF 동기화 키가 아닙니다.");
        }
        return Long.parseLong(key.substring(KEY_PREFIX.length()));
    }

    /* ── 값 변환 ──────────────────────────────────────── */

    /** 조회 기간(yyyyMMdd). yearMonth 가 있으면 그 달(오늘을 넘지 않게), 없으면 최근 months 개월. */
    private Range range(String yearMonth, int months) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        if (yearMonth == null || yearMonth.isBlank()) {
            return new Range(today.minusMonths(months).format(YMD), today.format(YMD));
        }
        YearMonth month = YearMonth.parse(yearMonth);
        LocalDate end = month.atEndOfMonth().isAfter(today) ? today : month.atEndOfMonth();
        return new Range(month.atDay(1).format(YMD), end.format(YMD));
    }

    /**
     * 같은 내용의 거래가 한 응답에 여러 번 나올 수 있다(같은 시각 같은 금액 이체 두 번).
     * 내용으로만 ID 를 만들면 둘째가 첫째를 덮으므로, 응답 안에서 몇 번째 같은 거래인지를 함께 넣는다.
     * CODEF 응답 순서는 조회마다 같으므로(일자 정렬) 재동기화해도 같은 ID 가 나온다.
     */
    private static long occurrenceId(Map<String, Integer> seen, String kind, String... parts) {
        String base = String.join("|", nullSafe(parts));
        int occurrence = seen.merge(base, 1, Integer::sum);
        return stableId(kind, base, String.valueOf(occurrence));
    }

    /** 내용 기반의 결정적 양수 long. 재동기화해도 같은 값이 나와야 멱등키가 유지된다. */
    static long stableId(String kind, String... parts) {
        String material = kind + "|" + String.join("|", nullSafe(parts));
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).getMostSignificantBits()
                & Long.MAX_VALUE;
    }

    private static String[] nullSafe(String... parts) {
        String[] safe = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            safe[i] = parts[i] == null ? "" : parts[i];
        }
        return safe;
    }

    /**
     * 은행 거래 설명. 비고 1(보낸분/받는분) → 3(적요) → 2(거래구분/메모) 순으로 처음 값이 있는 것.
     * 카테고리화가 이 값을 가맹점명처럼 쓰므로, 상대방 이름이 있으면 그게 가장 쓸모 있다.
     */
    private static String bankDescription(Map<String, Object> row) {
        for (String field : new String[]{"resAccountDesc1", "resAccountDesc3", "resAccountDesc2"}) {
            String value = text(row, field);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /** 해외건이면 원화금액을 쓴다(통화가 KRW 가 아니고 원화금액이 있을 때). */
    private static BigDecimal approvedAmount(Map<String, Object> row) {
        String currency = text(row, "resAccountCurrency");
        BigDecimal krw = nullableDecimal(row.get("resKRWAmt"));
        if (currency != null && !"KRW".equalsIgnoreCase(currency) && krw != null) {
            return krw;
        }
        return decimal(row.get("resUsedAmount"));
    }

    /**
     * 01=신용 · 02=체크 (tbl_card.card_type_code 관례). CODEF 카드구분은 "체크/본인"·"본인신용" 같은
     * 자유 문구라 "체크" 가 들어 있는지로만 가른다. 카드구분이 비면 카드명을 본다.
     */
    static String cardTypeCode(String cardType, String cardName) {
        String hint = (cardType == null ? "" : cardType) + "|" + (cardName == null ? "" : cardName);
        return hint.contains("체크") ? "02" : "01";
    }

    /** "참! 좋은친구카드_체크|IBK기업은행" → "참! 좋은친구카드_체크". 발급사 꼬리를 뗀다. */
    private static String productName(String cardName) {
        if (cardName == null) {
            return null;
        }
        int bar = cardName.indexOf('|');
        return bar < 0 ? cardName : cardName.substring(0, bar);
    }

    /** 원거래 승인번호를 raw_json 에 남긴다 — 서비스(RawJsonFields)가 original_approval_no 로 옮긴다. */
    private static Map<String, Object> withOriginalApprovalNo(Map<String, Object> row, String originalApprovalNo) {
        if (originalApprovalNo == null || originalApprovalNo.isBlank()) {
            return row;
        }
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.put("originalApprovalNo", originalApprovalNo);
        return copy;
    }

    /** yyyyMMdd + hhmmss → ISO-8601 오프셋 시각(서비스가 OffsetDateTime.parse 로 읽는다). */
    static String isoDateTime(String yyyymmdd, String hhmmss) {
        String date = digits(yyyymmdd);
        if (date.length() != 8) {
            throw new BusinessException("EXTERNAL_API_ERROR", "금융기관 거래일자를 읽지 못했어요.");
        }
        String time = digits(hhmmss);
        if (time.length() < 6) {
            time = (time + "000000").substring(0, 6);
        } else {
            time = time.substring(0, 6);
        }
        return date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6, 8)
                + "T" + time.substring(0, 2) + ":" + time.substring(2, 4) + ":" + time.substring(4, 6)
                + "+09:00";
    }

    /** yyyyMMdd → yyyy-MM-dd (서비스가 LocalDate.parse 로 읽는다). 형식이 다르면 null — 지어내지 않는다. */
    static String isoDate(String yyyymmdd) {
        String date = digits(yyyymmdd);
        if (date.length() != 8) {
            return null;
        }
        return date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6, 8);
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }

    private static String text(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private static <T> T orDefault(T value, T fallback) {
        if (value == null || (value instanceof String && ((String) value).isBlank())) {
            return fallback;
        }
        return value;
    }

    private static BigDecimal decimal(Object value) {
        BigDecimal parsed = nullableDecimal(value);
        return parsed == null ? BigDecimal.ZERO : parsed;
    }

    /** "1,234" · " 500 " 같은 표기도 읽는다. 빈 값은 null. */
    private static BigDecimal nullableDecimal(Object value) {
        if (value == null) {
            return null;
        }
        String cleaned = String.valueOf(value).replace(",", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String json(Map<String, Object> row) {
        try {
            return JSON.writeValueAsString(row);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /* ── 내부 타입 ────────────────────────────────────── */

    private record Range(String start, String end) {
        @Override
        public String toString() {
            return start + "-" + end;
        }
    }

    private record Memo(Instant at, CompletableFuture<Map<String, Object>> response) {
    }

    /** 계좌·대출 한 건의 원본 식별 정보. 계좌번호는 여기 밖으로 나가지 않는다. */
    private record AccountHandle(CodefConnectionStore.Connection connection, String accountNo,
                                 String depositCode, String loanExecNo) {
        @Override
        public String toString() {
            return "AccountHandle[" + connection.organization() + "]";
        }
    }

    private record CardHandle(CodefConnectionStore.Connection connection, String cardNo,
                              boolean firstOfOrganization) {
    }
}
