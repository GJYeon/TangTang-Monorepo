package com.kb.tangtang.account.client.sync;

import com.kb.tangtang.account.client.CodefConnectionStore;
import com.kb.tangtang.account.client.CodefHttpClient;
import com.kb.tangtang.account.client.sync.dto.BankAccountSyncDto;
import com.kb.tangtang.account.client.sync.dto.BankTransactionSyncDto;
import com.kb.tangtang.account.client.sync.dto.CardApprovalSyncDto;
import com.kb.tangtang.account.client.sync.dto.CardBillSyncDto;
import com.kb.tangtang.account.client.sync.dto.CardSyncDto;
import com.kb.tangtang.account.client.sync.dto.LoanSyncDto;
import com.kb.tangtang.account.client.sync.dto.LoanTransactionSyncDto;
import com.kb.tangtang.account.service.AccountNumberPolicy;
import com.kb.tangtang.account.service.InstitutionCatalog;
import com.kb.tangtang.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 실 CODEF 응답 → 동기화 DTO 매핑 검증.
 *
 * 응답 모양은 CODEF 개발가이드의 출력부 예시(2026-10-14 확인)를 따른다. 실호출이 아니므로
 * "필드 이름·형식이 문서대로일 때 서비스가 읽을 수 있는 값이 나오는가" 를 고정한다.
 */
class CodefFinancialSyncClientTest {

    private static final String BASE = "https://development.codef.io";
    private static final long USER_ID = 7L;
    private static final String KEY = CodefFinancialSyncClient.keyOf(USER_ID);
    /** 2026-10-14 12:00 KST */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), ZoneOffset.UTC);

    private static final CodefConnectionStore.Connection KB_BANK =
            new CodefConnectionStore.Connection("0004", "BK", "conn-bank");
    /** 우리 카탈로그 0301 = 신한카드 → CODEF 0306 */
    private static final CodefConnectionStore.Connection SHINHAN_CARD =
            new CodefConnectionStore.Connection("0301", "CD", "conn-card");

    private MockRestServiceServer server;
    private CodefConnectionStore connections;
    private CodefFinancialSyncClient client;
    private final AccountNumberPolicy accountNumbers = new AccountNumberPolicy("test-secret");

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        connections = mock(CodefConnectionStore.class);
        client = new CodefFinancialSyncClient(
                new CodefHttpClient(restTemplate, "id", "secret"), BASE, connections,
                accountNumbers::mask, new InstitutionCatalog()::nameOf, CLOCK);
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("https://oauth.codef.io/oauth/token")))
                .andRespond(withSuccess("{\"access_token\":\"t\"}", MediaType.APPLICATION_JSON));
    }

    private static String ok(String data) {
        return "{\"result\":{\"code\":\"CF-00000\",\"message\":\"성공\"},\"data\":" + data + "}";
    }

    private void expect(String path, String data) {
        server.expect(once(), requestTo(BASE + path))
                .andRespond(withSuccess(ok(data), MediaType.APPLICATION_JSON));
    }

    private static final String ACCOUNT_LIST = """
            {"resDepositTrust":[
               {"resAccount":"02004130000001","resAccountDisplay":"020-0413-0000-001","resAccountName":"KB국민ONE통장",
                "resAccountDeposit":"11","resAccountBalance":"546000","resAccountCurrency":"KRW"},
               {"resAccount":"03204080000000","resAccountDisplay":"032-0408-0000-000","resAccountName":"자유적금",
                "resAccountDeposit":"12","resAccountBalance":"1600000","resAccountCurrency":"KRW"}],
             "resLoan":{"resAccount":"75260900000000","resAccountDisplay":"752609-00-000000","resAccountName":"전세자금",
                "resAccountBalance":"-190000000","resAccountStartDate":"20190629","resAccountEndDate":"20210629",
                "resAccountLoanExecNo":"","resAccountDeposit":"40"}}
            """;

    @Test
    @DisplayName("보유계좌: 수시입출·적금을 은행 계좌로 주고, 전체 번호는 해시 입력으로만 남긴다")
    void mapsBankAccounts() {
        when(connections.activeConnections(USER_ID)).thenReturn(List.of(KB_BANK));
        server.expect(once(), requestTo(BASE + "/v1/kr/bank/p/account/account-list"))
                .andExpect(jsonPath("$.organization").value("0004"))
                .andExpect(jsonPath("$.connectedId").value("conn-bank"))
                .andRespond(withSuccess(ok(ACCOUNT_LIST), MediaType.APPLICATION_JSON));

        List<BankAccountSyncDto> accounts = client.getBankAccounts(KEY);

        assertEquals(2, accounts.size());
        BankAccountSyncDto demand = accounts.get(0);
        assertEquals("DEMAND_DEPOSIT", demand.getAccountTypeCode());
        assertEquals("0004", demand.getInstitutionCode());
        assertEquals("KB국민은행", demand.getInstitutionName());
        assertEquals("02004130000001", demand.getAccountNoRaw());
        /* 마스킹은 연동 화면과 같은 규칙이어야 동기화 커서가 맞는다. */
        assertEquals(accountNumbers.mask("02004130000001"), demand.getAccountNoMasked());
        assertEquals(0, new BigDecimal("546000").compareTo(demand.getBalance()));
        assertEquals("SAVINGS", accounts.get(1).getAccountTypeCode());
        /* 같은 계좌는 언제나 같은 ID — 재동기화해도 멱등키가 유지된다. */
        assertEquals(demand.getAccountId(), CodefFinancialSyncClient.stableId("BANK", "0004", "02004130000001"));
        server.verify();
    }

    @Test
    @DisplayName("수시입출 거래: 입금은 01·출금은 02, 시각은 KST 오프셋 ISO 로 준다")
    void mapsDemandTransactions() {
        when(connections.activeConnections(USER_ID)).thenReturn(List.of(KB_BANK));
        expect("/v1/kr/bank/p/account/account-list", ACCOUNT_LIST);
        server.expect(once(), requestTo(BASE + "/v1/kr/bank/p/account/transaction-list"))
                .andExpect(jsonPath("$.account").value("02004130000001"))
                /* 월 커서: 2026-10 이면 1일부터 오늘까지 */
                .andExpect(jsonPath("$.startDate").value("20261001"))
                .andExpect(jsonPath("$.endDate").value("20261014"))
                .andRespond(withSuccess(ok("""
                        {"resTrHistoryList":[
                          {"resAccountTrDate":"20261002","resAccountTrTime":"004219","resAccountOut":"0",
                           "resAccountIn":"3000000","resAfterTranBalance":"3546000",
                           "resAccountDesc1":"(주)탕탕","resAccountDesc2":"급여","resAccountDesc3":"","resAccountDesc4":""},
                          {"resAccountTrDate":"20261003","resAccountTrTime":"120000","resAccountOut":"17000",
                           "resAccountIn":"0","resAfterTranBalance":"3529000",
                           "resAccountDesc1":"","resAccountDesc2":"체크","resAccountDesc3":"넷플릭스","resAccountDesc4":""}]}
                        """), MediaType.APPLICATION_JSON));

        long accountId = client.getBankAccounts(KEY).get(0).getAccountId();
        List<BankTransactionSyncDto> txs = client.getBankTransactions(KEY, accountId, "2026-10");

        assertEquals(2, txs.size());
        assertEquals("01", txs.get(0).getTransTypeCode());
        assertEquals(0, new BigDecimal("3000000").compareTo(txs.get(0).getAmount()));
        assertEquals("2026-10-02T00:42:19+09:00", txs.get(0).getTransactedAt());
        assertEquals("(주)탕탕", txs.get(0).getDescription());
        assertEquals("02", txs.get(1).getTransTypeCode());
        assertEquals(0, new BigDecimal("17000").compareTo(txs.get(1).getAmount()));
        /* 비고1 이 비면 적요(비고3)를 쓴다 — 카테고리화가 가맹점명처럼 읽는다. */
        assertEquals("넷플릭스", txs.get(1).getDescription());
        server.verify();
    }

    @Test
    @DisplayName("적금 거래: 적금 API 로 조회하고, 입금은 수입이 아니므로 모르는 유형(→TRANSFER)으로 준다")
    void savingsTransactionsAreTransfers() {
        when(connections.activeConnections(USER_ID)).thenReturn(List.of(KB_BANK));
        expect("/v1/kr/bank/p/account/account-list", ACCOUNT_LIST);
        expect("/v1/kr/bank/p/installment-savings/transaction-list", """
                {"resTrHistoryList":{"resAccountTrDate":"20261005","resAccountIn":"100000",
                  "resAccountDesc2":"월불입금","resAfterTranBalance":"1700000"}}
                """);

        long savingsId = client.getBankAccounts(KEY).get(1).getAccountId();
        List<BankTransactionSyncDto> txs = client.getBankTransactions(KEY, savingsId, null);

        /* 단건이면 CODEF 는 배열 대신 객체를 준다 — 한 건짜리 목록으로 읽어야 한다. */
        assertEquals(1, txs.size());
        assertNotEquals("01", txs.get(0).getTransTypeCode());
        assertNotEquals("02", txs.get(0).getTransTypeCode());
        assertEquals("2026-10-05T00:00:00+09:00", txs.get(0).getTransactedAt());
        server.verify();
    }

    @Test
    @DisplayName("같은 시각·같은 금액 거래 두 건은 서로 다른 ID 를 받는다(둘째가 첫째를 덮지 않는다)")
    void identicalTransactionsGetDistinctIds() {
        when(connections.activeConnections(USER_ID)).thenReturn(List.of(KB_BANK));
        expect("/v1/kr/bank/p/account/account-list", ACCOUNT_LIST);
        String row = """
                {"resAccountTrDate":"20261003","resAccountTrTime":"120000","resAccountOut":"5000",
                 "resAccountIn":"0","resAfterTranBalance":"","resAccountDesc1":"홍길동"}
                """;
        expect("/v1/kr/bank/p/account/transaction-list", "{\"resTrHistoryList\":[" + row + "," + row + "]}");

        long accountId = client.getBankAccounts(KEY).get(0).getAccountId();
        List<BankTransactionSyncDto> txs = client.getBankTransactions(KEY, accountId, "2026-10");

        assertNotEquals(txs.get(0).getTransactionId(), txs.get(1).getTransactionId());
    }

    @Test
    @DisplayName("대출: 보유계좌 resLoan + 대출 거래내역 상세를 합치고, 거래내역 응답은 한 번만 받는다")
    void mapsLoansWithSingleHistoryCall() {
        when(connections.activeConnections(USER_ID)).thenReturn(List.of(KB_BANK));
        expect("/v1/kr/bank/p/account/account-list", ACCOUNT_LIST);
        /* once() — getLoans 와 getLoanTransactions 가 같은 응답을 나눠 써야 한다. */
        expect("/v1/kr/bank/p/loan/transaction-list", """
                {"resLoanKind":"신혼부부 임차","resPrincipal":"120000000","resRate":"1.44",
                 "resDatePayment":"20261028",
                 "resTrHistoryList":[{"resAccountTrDate":"20260929","resTransTypeNm":"이자납입",
                   "resTranAmount":"","resPrincipal":"0","resInterest":"144000","resLoanBalance":"190000000"}]}
                """);

        List<LoanSyncDto> loans = client.getLoans(KEY);
        List<LoanTransactionSyncDto> txs = client.getLoanTransactions(KEY, loans.get(0).getLoanId());

        LoanSyncDto loan = loans.get(0);
        assertEquals("신혼부부 임차", loan.getProductName());
        assertEquals(0, new BigDecimal("190000000").compareTo(loan.getBalance()));
        assertEquals(0, new BigDecimal("120000000").compareTo(loan.getPrincipal()));
        assertEquals("2019-06-29", loan.getStartDate());
        assertEquals("2026-10-28", loan.getNextPaymentDate());
        /* 거래금액이 비면 원금 + 이자 */
        assertEquals(0, new BigDecimal("144000").compareTo(txs.get(0).getAmount()));
        server.verify();
    }

    private static final String CARD_LIST = """
            [{"resCardNo":"1234********5678","resCardName":"Deep Dream 체크|신한카드","resCardType":"체크/본인",
              "resIssueDate":"20240105","resState":"정상"},
             {"resCardNo":"9876********4321","resCardName":"Mr.Life","resCardType":"본인신용"}]
            """;

    @Test
    @DisplayName("보유카드: 우리 코드를 CODEF 코드로 바꿔 부르고, 체크/신용을 01·02 로 가른다")
    void mapsCards() {
        when(connections.activeConnections(USER_ID)).thenReturn(List.of(SHINHAN_CARD));
        server.expect(once(), requestTo(BASE + "/v1/kr/card/p/account/card-list"))
                .andExpect(jsonPath("$.organization").value("0306"))
                .andRespond(withSuccess(ok(CARD_LIST), MediaType.APPLICATION_JSON));

        List<CardSyncDto> cards = client.getCards(KEY);

        assertEquals(2, cards.size());
        assertEquals("0301", cards.get(0).getInstitutionCode());
        assertEquals("신한카드", cards.get(0).getInstitutionName());
        assertEquals("Deep Dream 체크", cards.get(0).getProductName());
        assertEquals("02", cards.get(0).getCardTypeCode());
        assertEquals("01", cards.get(1).getCardTypeCode());
        assertEquals("2024-01-05", cards.get(0).getIssuedAt());
        server.verify();
    }

    @Test
    @DisplayName("승인내역: 카드사 단위로 한 번 받아 카드번호로 나누고, 취소는 원승인을 덮지 않게 키를 가른다")
    void mapsApprovals() {
        when(connections.activeConnections(USER_ID)).thenReturn(List.of(SHINHAN_CARD));
        expect("/v1/kr/card/p/account/card-list", CARD_LIST);
        server.expect(once(), requestTo(BASE + "/v1/kr/card/p/account/approval-list"))
                .andExpect(jsonPath("$.inquiryType").value("1"))
                .andRespond(withSuccess(ok("""
                        [{"resUsedDate":"20261010","resUsedTime":"093000","resCardNo":"1234********5678",
                          "resMemberStoreName":"스타벅스","resMemberStoreType":"커피전문점","resUsedAmount":"5500",
                          "resApprovalNo":"11112222","resCancelYN":"0","resAccountCurrency":"KRW"},
                         {"resUsedDate":"20261011","resUsedTime":"101500","resCardNo":"1234********5678",
                          "resMemberStoreName":"스타벅스","resUsedAmount":"5500",
                          "resApprovalNo":"11112222","resCancelYN":"1","resAccountCurrency":"KRW"},
                         {"resUsedDate":"20261012","resUsedTime":"120000","resCardNo":"1234********5678",
                          "resMemberStoreName":"거절가맹점","resUsedAmount":"9900",
                          "resApprovalNo":"33334444","resCancelYN":"3"},
                         {"resUsedDate":"20261012","resUsedTime":"130000","resCardNo":"9876********4321",
                          "resMemberStoreName":"이마트","resUsedAmount":"42000",
                          "resApprovalNo":"55556666","resCancelYN":"0"}]
                        """), MediaType.APPLICATION_JSON));

        List<CardSyncDto> cards = client.getCards(KEY);
        List<CardApprovalSyncDto> first = client.getCardApprovals(KEY, cards.get(0).getCardId(), "2026-10");
        /* 같은 카드사의 둘째 카드는 memo 된 응답을 쓴다 — approval-list 는 once() 다. */
        List<CardApprovalSyncDto> second = client.getCardApprovals(KEY, cards.get(1).getCardId(), "2026-10");

        assertEquals(2, first.size(), "거절건은 빠진다");
        assertEquals("01", first.get(0).getApprovalTypeCode());
        assertEquals("커피전문점", first.get(0).getMerchantCategoryName());
        assertEquals("2026-10-10T09:30:00+09:00", first.get(0).getApprovedAt());
        assertEquals("03", first.get(1).getApprovalTypeCode());
        assertNotEquals(first.get(0).getApprovalNo(), first.get(1).getApprovalNo(),
                "취소건이 원승인과 같은 멱등키를 쓰면 원거래를 덮어쓴다");
        assertTrue(first.get(1).getRawJson().contains("\"originalApprovalNo\":\"11112222\""));
        assertEquals(1, second.size());
        assertEquals("이마트", second.get(0).getMerchantName());
        server.verify();
    }

    @Test
    @DisplayName("청구서는 카드사 명세서라 그 카드사의 첫 카드에만 붙는다")
    void billsAttachToFirstCardOnly() {
        when(connections.activeConnections(USER_ID)).thenReturn(List.of(SHINHAN_CARD));
        expect("/v1/kr/card/p/account/card-list", CARD_LIST);
        expect("/v1/kr/card/p/account/billing-list", """
                {"resTotalAmount":"342000","resBillType":"신한카드","resPaymentDueDate":"20261025"}
                """);

        List<CardSyncDto> cards = client.getCards(KEY);
        List<CardBillSyncDto> bills = client.getCardBills(KEY, cards.get(0).getCardId());

        assertEquals(1, bills.size());
        assertEquals("2026-10", bills.get(0).getBillingMonth());
        assertEquals("2026-10-25", bills.get(0).getDueDate());
        assertEquals(0, BigDecimal.ZERO.compareTo(bills.get(0).getPaidAmount()));
        assertTrue(client.getCardBills(KEY, cards.get(1).getCardId()).isEmpty());
        server.verify();
    }

    @Test
    @DisplayName("기관 하나가 실패해도 나머지 기관 결과는 살린다")
    void skipsFailedOrganization() {
        CodefConnectionStore.Connection shinhanBank = new CodefConnectionStore.Connection("0088", "BK", "conn-2");
        when(connections.activeConnections(USER_ID)).thenReturn(List.of(KB_BANK, shinhanBank));
        server.expect(once(), requestTo(BASE + "/v1/kr/bank/p/account/account-list"))
                .andRespond(withSuccess("{\"result\":{\"code\":\"CF-12100\",\"message\":\"점검 중\"},\"data\":{}}",
                        MediaType.APPLICATION_JSON));
        expect("/v1/kr/bank/p/account/account-list", ACCOUNT_LIST);

        List<BankAccountSyncDto> accounts = client.getBankAccounts(KEY);

        assertEquals(2, accounts.size());
        assertTrue(accounts.stream().allMatch(a -> "0088".equals(a.getInstitutionCode())));
    }

    @Test
    @DisplayName("모든 기관이 실패하면 빈 결과가 아니라 실패로 올린다(조용히 '동기화됨' 이 되지 않게)")
    void throwsWhenEveryOrganizationFails() {
        when(connections.activeConnections(USER_ID)).thenReturn(List.of(KB_BANK));
        server.expect(once(), requestTo(BASE + "/v1/kr/bank/p/account/account-list"))
                .andRespond(withSuccess("{\"result\":{\"code\":\"CF-12100\",\"message\":\"점검 중\"},\"data\":{}}",
                        MediaType.APPLICATION_JSON));

        assertThrows(BusinessException.class, () -> client.getBankAccounts(KEY));
    }

    @Test
    @DisplayName("쓸 수 있는 연결이 하나도 없으면 '동기화됨' 대신 재연결 필요로 실패한다")
    void failsWhenNoUsableConnection() {
        when(connections.activeConnections(USER_ID)).thenReturn(List.of());

        BusinessException e = assertThrows(BusinessException.class, () -> client.getBankAccounts(KEY));
        assertEquals("CONNECTION_NOT_FOUND", e.getCode());
    }

    @Test
    @DisplayName("카드만 연결한 사용자는 은행 결과가 비어 있을 뿐 실패가 아니다")
    void cardOnlyUserHasNoBankAccounts() {
        when(connections.activeConnections(USER_ID)).thenReturn(List.of(SHINHAN_CARD));

        assertTrue(client.getBankAccounts(KEY).isEmpty());
        assertTrue(client.getLoans(KEY).isEmpty());
    }

    @Test
    @DisplayName("증권·페이머니는 CODEF 에서 가져오지 않는다 — 목서버 데이터도 섞지 않는다")
    void securitiesAndPayMoneyAreEmpty() {
        assertNull(client.getStockAsset(KEY));
        assertTrue(client.getPayMoney(KEY).isEmpty());
        assertTrue(client.getDeposits(KEY).isEmpty());
    }

    @Test
    @DisplayName("CODEF 키는 codef:{userId} 다")
    void keyFormat() {
        assertTrue(CodefFinancialSyncClient.isCodefKey("codef:7"));
        assertFalse(CodefFinancialSyncClient.isCodefKey("3"));
        assertFalse(CodefFinancialSyncClient.isCodefKey(null));
        assertEquals(7L, CodefFinancialSyncClient.userIdOf("codef:7"));
    }
}
