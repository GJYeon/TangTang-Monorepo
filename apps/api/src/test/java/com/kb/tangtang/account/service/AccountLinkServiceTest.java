package com.kb.tangtang.account.service;

import com.kb.tangtang.account.client.CodefConnectionStore;
import com.kb.tangtang.account.client.FinancialDataClient;
import com.kb.tangtang.account.client.FinancialDataClientResolver;
import com.kb.tangtang.account.client.dto.ConnectionRequest;
import com.kb.tangtang.account.client.dto.ConnectionResult;
import com.kb.tangtang.account.client.dto.FinancialAccountDto;
import com.kb.tangtang.account.client.sync.FinancialSyncClient;
import com.kb.tangtang.account.client.sync.ScenarioKeyProvider;
import com.kb.tangtang.account.client.sync.dto.CardSyncDto;
import com.kb.tangtang.account.client.sync.dto.LoanSyncDto;
import com.kb.tangtang.account.client.sync.dto.PayMoneySyncDto;
import com.kb.tangtang.notification.domain.NotificationRequestedEvent;
import com.kb.tangtang.notification.domain.NotificationType;
import com.kb.tangtang.account.domain.AuthMethod;
import com.kb.tangtang.account.domain.AuthStatus;
import com.kb.tangtang.account.domain.ConnectedAccount;
import com.kb.tangtang.account.domain.Loan;
import com.kb.tangtang.account.dto.*;
import com.kb.tangtang.account.domain.Card;
import com.kb.tangtang.account.mapper.CardMapper;
import com.kb.tangtang.account.mapper.ConnectedAccountMapper;
import com.kb.tangtang.account.mapper.LoanMapper;
import com.kb.tangtang.common.exception.BusinessException;
import com.kb.tangtang.user.service.ConsentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 연동 흐름 검증.
 *
 * FinancialDataClient 를 가짜로 바꿔 목/실 어느 쪽에도 의존하지 않고 흐름만 본다 —
 * 이 경계가 제대로 서 있는지 자체가 검증 대상이다.
 */
class AccountLinkServiceTest {

    private static final long USER_ID = 1L;
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-05T09:00:00Z"), ZoneId.of("Asia/Seoul"));
    /** 배치 주기 30분 (financial.sync.batch.fixed-delay-ms 의 실제 기본값). */
    private static final long BATCH_FIXED_DELAY_MS = 1_800_000L;

    private FinancialDataClient client;
    private CodefConnectionStore codefConnections;
    private ConnectedAccountMapper mapper;
    private LoanMapper loanMapper;
    private CardMapper cardMapper;
    private LinkProgressStore store;
    private ConsentService consentService;
    private FinancialSyncClient syncClient;
    private ScenarioKeyProvider scenarioKeyProvider;
    private AccountLinkService service;

    /* 발행된 이벤트를 모아두는 퍼블리셔. 계좌별로 정확히 한 번 발행되는지 검증한다 */
    private final List<Object> publishedEvents = new ArrayList<>();
    private final ApplicationEventPublisher capturingPublisher = publishedEvents::add;

    @BeforeEach
    void setUp() {
        client = mock(FinancialDataClient.class);
        mapper = mock(ConnectedAccountMapper.class);
        loanMapper = mock(LoanMapper.class);
        cardMapper = mock(CardMapper.class);
        store = new LinkProgressStore(CLOCK);
        consentService = mock(ConsentService.class);
        syncClient = mock(FinancialSyncClient.class);
        scenarioKeyProvider = mock(ScenarioKeyProvider.class);
        codefConnections = mock(CodefConnectionStore.class);
        service = new AccountLinkService(FinancialDataClientResolver.fixed(client), codefConnections,
                mapper, loanMapper, cardMapper, store,
                new InstitutionCatalog(),
                new AccountNumberPolicy("test-secret-key-for-account-hash-0001"),
                syncClient, scenarioKeyProvider, capturingPublisher,
                consentService, BATCH_FIXED_DELAY_MS, CLOCK);
        /*
         * 기본은 "제3자 제공 동의를 마친 사용자" 다. 동의 검사는 연결 생성의 전제일 뿐이라
         * 여기 대부분의 테스트가 검증하려는 대상이 아니다. 미동의 경로는 별도 테스트에서 덮어쓴다.
         */
        when(consentService.needsConsent(anyLong(), any())).thenReturn(false);

        when(mapper.findActiveByUser(anyLong())).thenReturn(List.of());
        when(loanMapper.findByUser(anyLong())).thenReturn(List.of());
        when(cardMapper.findByUser(anyLong())).thenReturn(List.of());
        when(mapper.findActiveHashes(anyLong())).thenReturn(List.of());
        when(mapper.findInactiveKeysByUser(anyLong())).thenReturn(List.of());
        /* 기본은 "제한 없음" — 목서버가 그렇다. 제한하는 경우는 개별 테스트에서 덮어쓴다. */
        when(client.supportedOrganizations()).thenReturn(Set.of());
    }

    private ConnectionResult approvedConnection(List<String> organizations) {
        return ConnectionResult.builder()
                .connectionId("conn-1")
                .status(AuthStatus.PENDING)
                .expiresInSeconds(300)
                .needsExtraAuth(false)
                .successOrganizations(organizations)
                .failedOrganizations(List.of())
                .build();
    }

    private FinancialAccountDto account(String organization, String name, String no) {
        return FinancialAccountDto.builder()
                .organization(organization)
                .accountName(name)
                .accountNo(no)
                .depositTypeCode("11")
                .currency("KRW")
                .balance(new BigDecimal("8340000"))
                .build();
    }

    @Test
    @DisplayName("인증 수단은 공급자가 정한다 - 서비스가 목/실을 판단하지 않는다")
    void authMethodsComeFromClient() {
        when(client.supportedAuthMethods()).thenReturn(List.of(AuthMethod.SIMPLE_AUTH));
        when(client.simpleAuthProviders()).thenReturn(List.of("KAKAO"));

        AuthMethodListDto result = service.authMethods(USER_ID);

        assertEquals(1, result.getMethods().size());
        assertEquals("SIMPLE_AUTH", result.getMethods().get(0).getType());
        assertEquals("카카오톡", result.getMethods().get(0).getProviders().get(0).getName());
    }

    /*
     * 제3자 제공 동의 검사 (DECISIONS.md 2026-08-11 (7)).
     * 화면 순서에만 맡기면 우회된다 — 계좌 연동 진입점이 셋이고 전부 동의 화면을 거치지 않았다.
     */
    @Test
    @DisplayName("제3자 제공 동의가 없으면 간편인증을 시작하지 않는다 — 공급자를 부르기도 전에 막는다")
    void simpleAuthRequiresFinancialConsent() {
        when(consentService.needsConsent(anyLong(), any())).thenReturn(true);
        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("0004"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.requestSimpleAuth(USER_ID, request));

        assertEquals("CONSENT_REQUIRED", e.getCode());
        verify(client, never()).createConnection(any());
    }

    @Test
    @DisplayName("제3자 제공 동의가 없으면 기관 로그인도 막는다 — 진입점이 달라도 규칙은 같다")
    void institutionLoginRequiresFinancialConsent() {
        when(consentService.needsConsent(anyLong(), any())).thenReturn(true);
        CredentialRequestDto row = new CredentialRequestDto();
        row.setOrganization("0004");
        row.setId("tester");
        row.setPassword("pw");
        InstitutionLoginRequestDto request = new InstitutionLoginRequestDto();
        request.setCredentials(List.of(row));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.requestInstitutionLogin(USER_ID, request));

        assertEquals("CONSENT_REQUIRED", e.getCode());
        verify(client, never()).createConnection(any());
    }

    @Test
    @DisplayName("기관을 고르지 않으면 간편인증을 시작하지 않는다")
    void simpleAuthNeedsOrganizations() {
        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of());

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.requestSimpleAuth(USER_ID, request));
        assertEquals("EXTERNAL_API_ERROR", e.getCode());
    }

    @Test
    @DisplayName("승인 전에는 조회를 시작하지 않는다")
    void doesNotFetchBeforeApproval() {
        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("0004")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.PENDING);

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("0004"));
        service.requestSimpleAuth(USER_ID, request);

        LinkProgressDto progress = service.progress(USER_ID, "conn-1");

        assertEquals(0, progress.getPercent());
        assertFalse(progress.isDone());
        verify(client, never()).fetchAccounts(anyLong(), any(), any());
    }

    @Test
    @DisplayName("폴링할 때마다 기관을 하나씩 조회하고 끝나면 done 이 된다")
    void fetchesOneInstitutionPerPoll() {
        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("0004", "0088")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);
        when(client.fetchAccounts(USER_ID, "conn-1", "0004"))
                .thenReturn(List.of(account("0004", "입출금통장", "110123456723")));
        when(client.fetchAccounts(USER_ID, "conn-1", "0088"))
                .thenReturn(List.of(account("0088", "신한 주거래", "110987654321")));

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("0004", "0088"));
        service.requestSimpleAuth(USER_ID, request);

        LinkProgressDto first = service.progress(USER_ID, "conn-1");
        assertEquals(50, first.getPercent());
        assertFalse(first.isDone());

        LinkProgressDto second = service.progress(USER_ID, "conn-1");
        assertEquals(100, second.getPercent());
        assertTrue(second.isDone());
    }

    @Test
    @DisplayName("한 기관이 실패해도 나머지는 계속 진행한다")
    void oneFailureDoesNotStopTheRest() {
        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("0004", "0088")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);
        when(client.fetchAccounts(USER_ID, "conn-1", "0004"))
                .thenThrow(new BusinessException("EXTERNAL_API_UNAVAILABLE", "점검 중"));
        when(client.fetchAccounts(USER_ID, "conn-1", "0088"))
                .thenReturn(List.of(account("0088", "신한 주거래", "110987654321")));

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("0004", "0088"));
        service.requestSimpleAuth(USER_ID, request);

        service.progress(USER_ID, "conn-1");
        LinkProgressDto done = service.progress(USER_ID, "conn-1");

        assertTrue(done.isDone());
        assertEquals("FAILED", done.getInstitutions().get(0).getStatus());
        assertEquals("DONE", done.getInstitutions().get(1).getStatus());
    }

    @Test
    @DisplayName("실 CODEF 연결은 조회에 성공한 기관만 connectedId 를 남긴다 — 재시작 뒤 동기화가 쓴다")
    void savesCodefConnectionPerSucceededInstitution() {
        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("0004", "0088")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);
        when(client.connectedIdOf("conn-1")).thenReturn(java.util.Optional.of("codef-real-id"));
        when(client.fetchAccounts(USER_ID, "conn-1", "0004"))
                .thenThrow(new BusinessException("EXTERNAL_API_UNAVAILABLE", "점검 중"));
        when(client.fetchAccounts(USER_ID, "conn-1", "0088"))
                .thenReturn(List.of(account("0088", "신한 주거래", "110987654321")));

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("0004", "0088"));
        service.requestSimpleAuth(USER_ID, request);
        service.progress(USER_ID, "conn-1");
        service.progress(USER_ID, "conn-1");

        verify(codefConnections).save(USER_ID, "0088", "codef-real-id");
        verify(codefConnections, never()).save(eq(USER_ID), eq("0004"), anyString());
    }

    @Test
    @DisplayName("목서버 연결은 남길 connectedId 가 없다")
    void mockConnectionSavesNothing() {
        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("0004")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);
        when(client.connectedIdOf("conn-1")).thenReturn(java.util.Optional.empty());
        when(client.fetchAccounts(USER_ID, "conn-1", "0004"))
                .thenReturn(List.of(account("0004", "입출금통장", "110123456723")));

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("0004"));
        service.requestSimpleAuth(USER_ID, request);
        service.progress(USER_ID, "conn-1");

        verifyNoInteractions(codefConnections);
    }

    @Test
    @DisplayName("금융정보 동의 철회로 전체 해제하면 CODEF 연결도 끈다 — 배치가 계속 부르지 않게")
    void disconnectAllDeactivatesCodefConnections() {
        service.disconnectAll(USER_ID);

        verify(codefConnections).deactivateAll(USER_ID);
        verify(mapper).deactivateAllByUser(USER_ID);
    }

    @Test
    @DisplayName("계좌 목록은 마스킹된 번호만 내려보낸다")
    void linkableAccountsAreMasked() {
        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("0004")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);
        when(client.fetchAccounts(USER_ID, "conn-1", "0004"))
                .thenReturn(List.of(account("0004", "입출금통장", "110123456723")));

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("0004"));
        service.requestSimpleAuth(USER_ID, request);
        service.progress(USER_ID, "conn-1");

        List<LinkableGroupDto> groups = service.linkableAccounts(USER_ID, "conn-1");

        assertEquals(1, groups.size());
        LinkableAccountDto row = groups.get(0).getAccounts().get(0);
        assertEquals("110-***-****23", row.getAccountNoMasked());
        assertFalse(row.isAlreadyLinked());
        assertEquals("KB국민은행", groups.get(0).getBankName());
    }

    @Test
    @DisplayName("#334: 페이머니는 fetchAccounts() 로 안 잡히지만 계좌 선택 화면에 " +
            "\"자동 연동\" 미리보기 그룹으로 뜬다")
    void showsPayMoneyAsAutoIncludedPreview() {
        /* PAY_KB 는 은행 계좌 엔드포인트에 없다 — 목서버 실제 동작과 같다(빈 목록). */
        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("PAY_KB")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);
        when(client.fetchAccounts(USER_ID, "conn-1", "PAY_KB")).thenReturn(List.of());
        when(scenarioKeyProvider.resolve(USER_ID)).thenReturn("1");
        when(syncClient.getPayMoney("1")).thenReturn(List.of(
                PayMoneySyncDto.builder().payMoneyId(3L).providerCode("PAY_KB")
                        .providerName("KB Pay").walletName("KB Pay 챌린지 지갑")
                        .balance(new BigDecimal("130000")).build()));
        when(syncClient.getLoans("1")).thenReturn(List.of());

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("PAY_KB"));
        service.requestSimpleAuth(USER_ID, request);
        service.progress(USER_ID, "conn-1");

        List<LinkableGroupDto> groups = service.linkableAccounts(USER_ID, "conn-1");

        assertEquals(1, groups.size());
        LinkableGroupDto group = groups.get(0);
        assertTrue(group.isAutoIncluded());
        assertEquals("PAY_KB", group.getBankCode());
        LinkableAccountDto row = group.getAccounts().get(0);
        assertEquals("KB Pay 챌린지 지갑", row.getAccountName());
        assertEquals(0, new BigDecimal("130000").compareTo(row.getBalance()));
        /* 미리보기 accountId 는 link() 이 절대 인덱스로 못 쓰게 음수여야 한다. */
        assertTrue(row.getAccountId() < 0);
    }

    @Test
    @DisplayName("은행에서 내려온 대출과 카드도 계좌 선택 화면에 자동 연동으로 보인다")
    void showsBankLoanAndCardAsAutoIncludedPreview() {
        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("0004", "0381")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);
        when(client.fetchAccounts(USER_ID, "conn-1", "0004"))
                .thenReturn(List.of(account("0004", "KB 입출금통장", "110123456723")));
        when(client.fetchAccounts(USER_ID, "conn-1", "0381")).thenReturn(List.of());
        when(scenarioKeyProvider.resolve(USER_ID)).thenReturn("1");
        when(syncClient.getLoans("1")).thenReturn(List.of(
                LoanSyncDto.builder().loanId(11L).institutionCode("0004")
                        .institutionName("KB국민은행").productName("KB 신용대출")
                        .loanNoMasked("LN-****-0001").balance(new BigDecimal("14200000")).build()));
        when(syncClient.getPayMoney("1")).thenReturn(List.of());
        when(syncClient.getCards("1")).thenReturn(List.of(
                CardSyncDto.builder().cardId(21L).institutionCode("0381")
                        .institutionName("KB국민카드").productName("KB My WE:SH 카드")
                        .cardNoMasked("1234-****-****-5678").currency("KRW").build()));

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("0004", "0381"));
        service.requestSimpleAuth(USER_ID, request);
        service.progress(USER_ID, "conn-1");

        List<LinkableGroupDto> groups = service.linkableAccounts(USER_ID, "conn-1");

        assertEquals(3, groups.size());
        assertEquals("KB 입출금통장", groups.get(0).getAccounts().get(0).getAccountName());
        assertTrue(groups.get(1).isAutoIncluded());
        assertEquals("KB 신용대출", groups.get(1).getAccounts().get(0).getAccountName());
        assertTrue(groups.get(2).isAutoIncluded());
        assertEquals("KB My WE:SH 카드", groups.get(2).getAccounts().get(0).getAccountName());
    }

    @Test
    @DisplayName("#334: 은행 없이 페이머니만 골랐으면 빈 accountIds 로도 link() 가 성공하고 " +
            "directAssetsPending 을 true 로 돌려준다")
    void linkSucceedsWithNoBankAccountsWhenDirectAssetsSelected() {
        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("PAY_KB")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);
        when(client.fetchAccounts(USER_ID, "conn-1", "PAY_KB")).thenReturn(List.of());

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("PAY_KB"));
        service.requestSimpleAuth(USER_ID, request);
        service.progress(USER_ID, "conn-1");

        LinkRequestDto linkRequest = new LinkRequestDto();
        linkRequest.setConnectionId("conn-1");
        linkRequest.setAccountIds(List.of());   // 계좌 선택 화면에 체크할 게 없다

        LinkResultDto result = service.link(USER_ID, linkRequest);

        assertEquals(0, result.getLinkedCount());
        assertTrue(result.isDirectAssetsPending());
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("#467: 자동 연동 미리보기에서 체크를 푼 상품은 원본 키로 제외 행(is_active=0)을 남긴다")
    void linkRecordsExclusionForUncheckedAutoIncludedPreview() {
        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("PAY_KB", "CP_KB")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);
        when(client.fetchAccounts(USER_ID, "conn-1", "PAY_KB")).thenReturn(List.of());
        when(client.fetchAccounts(USER_ID, "conn-1", "CP_KB")).thenReturn(List.of());
        when(scenarioKeyProvider.resolve(USER_ID)).thenReturn("1");
        when(syncClient.getPayMoney("1")).thenReturn(List.of(
                PayMoneySyncDto.builder().payMoneyId(3L).providerCode("PAY_KB")
                        .providerName("KB Pay").walletName("KB Pay 지갑")
                        .balance(new BigDecimal("130000")).build()));
        when(syncClient.getLoans("1")).thenReturn(List.of(
                LoanSyncDto.builder().loanId(11L).institutionCode("CP_KB")
                        .institutionName("KB캐피탈").productName("KB 신용대출")
                        .loanNoMasked("LN-****-0001").balance(new BigDecimal("14200000")).build()));

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("PAY_KB", "CP_KB"));
        service.requestSimpleAuth(USER_ID, request);
        service.progress(USER_ID, "conn-1");
        List<LinkableGroupDto> groups = service.linkableAccounts(USER_ID, "conn-1");
        long loanPreviewId = groups.stream()
                .flatMap(group -> group.getAccounts().stream())
                .filter(row -> "LOAN".equals(row.getAccountType()))
                .findFirst().orElseThrow().getAccountId();

        LinkRequestDto linkRequest = new LinkRequestDto();
        linkRequest.setConnectionId("conn-1");
        linkRequest.setAccountIds(List.of());
        linkRequest.setExcludedAccountIds(List.of(loanPreviewId, 999L));   // 999 는 화면이 지어낸 id

        LinkResultDto result = service.link(USER_ID, linkRequest);

        assertTrue(result.isDirectAssetsPending());   // 페이머니는 그대로 연동된다
        /* 키는 FinancialSyncServiceImpl 이 저장할 때 쓰는 것과 같아야 건너뛴다. */
        verify(mapper).insert(argThat(row ->
                "MOCK-LOAN-11".equals(row.getAccountNoEncrypted()) && "LOAN".equals(row.getAccountType())
                        && "CP_KB".equals(row.getBankCode())));
        verify(mapper).deactivateByHash(USER_ID, "MOCK-LOAN-11");
        verify(mapper, never()).deactivateByHash(eq(USER_ID), argThat(key -> key.startsWith("MOCK-PAYMONEY")));
    }

    @Test
    @DisplayName("#467: 관리 목록의 대출 행을 해제하면 행은 두고 제외 키(숨김)만 남긴다 — 예전엔 400")
    void disconnectLoanRecordsExclusionWithoutDeleting() {
        when(loanMapper.findByUser(USER_ID)).thenReturn(List.of(
                Loan.builder().id(7L).userId(USER_ID).loanNoEncrypted("MOCK-LOAN-301")
                        .bankCode("CP_KB").bankName("KB캐피탈").loanType("KB 신용대출").build()));

        DisconnectResultDto result = service.disconnect(USER_ID, ManagedAccountId.ofLoan(7L));

        assertTrue(result.isDisconnected());
        verify(mapper).insert(argThat(row -> "MOCK-LOAN-301".equals(row.getAccountNoEncrypted())));
        verify(mapper).deactivateByHash(USER_ID, "MOCK-LOAN-301");
        /* 은행 계좌와 같은 원칙 — 해제 전까지 모은 이력은 남긴다. 조회 말고는 tbl_loan 을 건드리지 않는다. */
        verify(loanMapper).findByUser(USER_ID);
        verifyNoMoreInteractions(loanMapper);
        verify(mapper, never()).deactivate(anyLong(), anyLong());
    }

    @Test
    @DisplayName("#467: 연결 계좌 관리 목록에 카드도 나온다 — 잔액 없이 거래 출처로")
    void connectedAccountsIncludeCards() {
        when(cardMapper.findByUser(USER_ID)).thenReturn(List.of(
                Card.builder().id(4L).userId(USER_ID).institutionCode("0381").institutionName("KB국민카드")
                        .productName("KB My WE:SH 카드").cardNoMasked("1234-****-****-5678")
                        .cardTypeCode("01").build()));

        ConnectedAccountListDto result = service.connectedAccounts(USER_ID);

        assertEquals(1, result.getAccounts().size());
        ConnectedAccountDto row = result.getAccounts().get(0);
        assertEquals("CARD", row.getAccountType());
        assertEquals("KB My WE:SH 카드", row.getAccountName());
        assertEquals("01", row.getCardTypeCode());
        /* 카드는 자산이 아니다 — 잔액을 채우지 않는다. 개별 재조회도 없다(기관 단위로만 긁어온다). */
        assertNull(row.getBalance());
        assertFalse(row.isManageable());
        /* 대출 행과 id 가 겹치면 안 된다 — 화면 키가 깨지고 해제가 엉뚱한 테이블로 간다. */
        assertEquals(ManagedAccountId.Kind.CARD, ManagedAccountId.kindOf(row.getAccountId()));
        assertEquals(4L, ManagedAccountId.rawId(row.getAccountId()));
        assertNotEquals(ManagedAccountId.ofLoan(4L), row.getAccountId());
    }

    @Test
    @DisplayName("#467: 카드 해제는 행·거래를 두고 제외 키(마스킹 번호 기반)만 남긴다")
    void disconnectCardRecordsExclusionWithoutDeleting() {
        when(cardMapper.findByUser(USER_ID)).thenReturn(List.of(
                Card.builder().id(4L).userId(USER_ID).institutionCode("0381").institutionName("KB국민카드")
                        .productName("KB My WE:SH 카드").cardNoMasked("1234-****-****-5678")
                        .cardTypeCode("01").build()));

        DisconnectResultDto result = service.disconnect(USER_ID, ManagedAccountId.ofCard(4L));

        assertTrue(result.isDisconnected());
        /* 키는 FinancialSyncServiceImpl 이 건너뛸 때 쓰는 것과 같아야 한다. */
        verify(mapper).insert(argThat(row ->
                "MOCK-CARD-1234-****-****-5678".equals(row.getAccountNoEncrypted())
                        && "CARD".equals(row.getAccountType())));
        verify(mapper).deactivateByHash(USER_ID, "MOCK-CARD-1234-****-****-5678");
        /* 조회 말고는 tbl_card 를 건드리지 않는다 — 해제 전 이력은 남긴다. */
        verify(cardMapper).findByUser(USER_ID);
        verifyNoMoreInteractions(cardMapper);
    }

    @Test
    @DisplayName("#467: 카드 재조회는 NOT_SUPPORTED, 없는 카드 해제는 NOT_FOUND")
    void cardRowsRejectUnknownDisconnectAndResync() {
        when(cardMapper.findByUser(USER_ID)).thenReturn(List.of());

        BusinessException notFound = assertThrows(BusinessException.class,
                () -> service.disconnect(USER_ID, ManagedAccountId.ofCard(99L)));
        assertEquals("NOT_FOUND", notFound.getCode());

        BusinessException notSupported = assertThrows(BusinessException.class,
                () -> service.resync(USER_ID, ManagedAccountId.ofCard(4L)));
        assertEquals("NOT_SUPPORTED", notSupported.getCode());
        assertTrue(notSupported.getMessage().startsWith("카드"));
        verify(mapper, never()).findByIdAndUser(anyLong(), anyLong());
    }

    @Test
    @DisplayName("#467 되돌리기: 예전에 풀었던 대출을 다시 체크하면 제외 그림자 행을 지우고, 페이머니는 되살린다")
    void linkClearsOldExclusionWhenAutoIncludedRowIsCheckedAgain() {
        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("PAY_KB", "CP_KB")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);
        when(client.fetchAccounts(USER_ID, "conn-1", "PAY_KB")).thenReturn(List.of());
        when(client.fetchAccounts(USER_ID, "conn-1", "CP_KB")).thenReturn(List.of());
        when(scenarioKeyProvider.resolve(USER_ID)).thenReturn("1");
        when(syncClient.getPayMoney("1")).thenReturn(List.of(
                PayMoneySyncDto.builder().payMoneyId(3L).providerCode("PAY_KB")
                        .providerName("KB Pay").walletName("KB Pay 지갑")
                        .balance(new BigDecimal("130000")).build()));
        when(syncClient.getLoans("1")).thenReturn(List.of(
                LoanSyncDto.builder().loanId(11L).institutionCode("CP_KB")
                        .institutionName("KB캐피탈").productName("KB 신용대출")
                        .loanNoMasked("LN-****-0001").balance(new BigDecimal("14200000")).build()));
        /* 둘 다 예전에 풀었거나 해제해 둔 상태 — is_active=0 행이 남아 있다. */
        when(mapper.findInactiveKeysByUser(USER_ID)).thenReturn(List.of("MOCK-LOAN-11", "MOCK-PAYMONEY-3"));

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("PAY_KB", "CP_KB"));
        service.requestSimpleAuth(USER_ID, request);
        service.progress(USER_ID, "conn-1");
        service.linkableAccounts(USER_ID, "conn-1");

        LinkRequestDto linkRequest = new LinkRequestDto();
        linkRequest.setConnectionId("conn-1");
        linkRequest.setAccountIds(List.of());
        linkRequest.setExcludedAccountIds(List.of());   // 이번엔 둘 다 체크한 채로 둔다

        service.link(USER_ID, linkRequest);

        verify(mapper).deleteInactiveByHash(USER_ID, "MOCK-LOAN-11");
        verify(mapper).reactivate(argThat(row -> "MOCK-PAYMONEY-3".equals(row.getAccountNoEncrypted())));
        verify(mapper, never()).deactivateByHash(anyLong(), anyString());
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("#467: 없는 대출을 해제하면 NOT_FOUND, 대출 행 재조회는 NOT_SUPPORTED")
    void loanRowsRejectUnknownDisconnectAndResync() {
        when(loanMapper.findByUser(USER_ID)).thenReturn(List.of());

        BusinessException notFound = assertThrows(BusinessException.class,
                () -> service.disconnect(USER_ID, -99L));
        assertEquals("NOT_FOUND", notFound.getCode());

        BusinessException notSupported = assertThrows(BusinessException.class,
                () -> service.resync(USER_ID, -7L));
        assertEquals("NOT_SUPPORTED", notSupported.getCode());
        verify(mapper, never()).findByIdAndUser(anyLong(), anyLong());
    }

    @Test
    @DisplayName("대출·페이머니가 하나도 없는데 빈 accountIds 를 보내면 여전히 오류다 — 회귀 방지")
    void linkStillRejectsEmptySelectionWithoutDirectAssets() {
        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("0004")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);
        when(client.fetchAccounts(USER_ID, "conn-1", "0004"))
                .thenReturn(List.of(account("0004", "입출금통장", "110123456723")));

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("0004"));
        service.requestSimpleAuth(USER_ID, request);
        service.progress(USER_ID, "conn-1");

        LinkRequestDto linkRequest = new LinkRequestDto();
        linkRequest.setConnectionId("conn-1");
        linkRequest.setAccountIds(List.of());

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.link(USER_ID, linkRequest));
        assertEquals("EXTERNAL_API_ERROR", e.getCode());
    }

    @Test
    @DisplayName("선택한 계좌를 저장한다 - 원본 계좌번호는 남기지 않는다")
    void savesSelectedAccounts() {
        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("0004")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);
        when(client.fetchAccounts(USER_ID, "conn-1", "0004"))
                .thenReturn(List.of(account("0004", "입출금통장", "110123456723")));
        when(mapper.reactivate(any())).thenReturn(0);

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("0004"));
        service.requestSimpleAuth(USER_ID, request);
        service.progress(USER_ID, "conn-1");

        LinkRequestDto linkRequest = new LinkRequestDto();
        linkRequest.setConnectionId("conn-1");
        linkRequest.setAccountIds(List.of(1L));

        LinkResultDto result = service.link(USER_ID, linkRequest);

        assertEquals(1, result.getLinkedCount());

        List<ConnectedAccount> saved = new ArrayList<>();
        verify(mapper).insert(argThat(row -> {
            saved.add(row);
            return true;
        }));
        ConnectedAccount row = saved.get(0);
        assertEquals("입출금통장", row.getAccountName());
        assertEquals("110-***-****23", row.getAccountNoMasked());
        assertNotEquals("110123456723", row.getAccountNoEncrypted());
        assertEquals(64, row.getAccountNoEncrypted().length()); // HMAC-SHA256 hex
    }

    @Test
    @DisplayName("연동 직후에는 lastSyncAt 을 비워 둔다 — 거래내역을 아직 한 번도 동기화하지 않았다")
    void linkedAccountStartsWithoutLastSyncAt() {
        /*
         * #389: 여기서 lastSyncAt 을 연동 시각으로 채우면, FinancialSyncServiceImpl.
         * buildMonthlyFetchCursor() 가 "이미 동기화된 계좌"로 오판해 첫 거래 동기화부터 연동한
         * 달 이전 거래(급여 등 은행 입금 포함)를 영원히 못 가져온다. 실제 동기화 완료 시점은
         * FinancialSyncServiceImpl.upsertBankAccount() 가 채운다 — 여기서는 null 이어야 한다.
         */
        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("0004")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);
        when(client.fetchAccounts(USER_ID, "conn-1", "0004"))
                .thenReturn(List.of(account("0004", "입출금통장", "110123456723")));
        when(mapper.reactivate(any())).thenReturn(0);

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("0004"));
        service.requestSimpleAuth(USER_ID, request);
        service.progress(USER_ID, "conn-1");

        LinkRequestDto linkRequest = new LinkRequestDto();
        linkRequest.setConnectionId("conn-1");
        linkRequest.setAccountIds(List.of(1L));
        service.link(USER_ID, linkRequest);

        List<ConnectedAccount> saved = new ArrayList<>();
        verify(mapper).insert(argThat(row -> {
            saved.add(row);
            return true;
        }));
        assertNull(saved.get(0).getLastSyncAt());
    }

    @Test
    @DisplayName("공급자가 판정해 준 계좌 종류를 그대로 쓰고 코드 컬럼을 오염시키지 않는다")
    void doesNotPutLongValueIntoDepositTypeCode() {
        /*
         * 목서버는 accountType 을 'DEMAND_DEPOSIT' 로 내려준다.
         * 이 값을 deposit_type_code(VARCHAR(5)) 에 넣으면 저장이 깨진다 — 2026-08-05 실제 발생.
         */
        FinancialAccountDto fromMock = FinancialAccountDto.builder()
                .organization("0004")
                .accountName("KB Star 입출금통장")
                .accountNo("123456******7890")
                .accountType("DEMAND_DEPOSIT")
                .depositTypeCode(null)
                .currency("KRW")
                .balance(new BigDecimal("1244200"))
                .build();

        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("0004")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);
        when(client.fetchAccounts(USER_ID, "conn-1", "0004")).thenReturn(List.of(fromMock));
        when(mapper.reactivate(any())).thenReturn(0);

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("0004"));
        service.requestSimpleAuth(USER_ID, request);
        service.progress(USER_ID, "conn-1");

        LinkRequestDto linkRequest = new LinkRequestDto();
        linkRequest.setConnectionId("conn-1");
        linkRequest.setAccountIds(List.of(1L));
        service.link(USER_ID, linkRequest);

        List<ConnectedAccount> saved = new ArrayList<>();
        verify(mapper).insert(argThat(row -> {
            saved.add(row);
            return true;
        }));
        ConnectedAccount row = saved.get(0);
        assertEquals("DEMAND_DEPOSIT", row.getAccountType());
        assertTrue(row.getDepositTypeCode() == null || row.getDepositTypeCode().length() <= 5,
                "deposit_type_code 는 VARCHAR(5) 다. 코드값만 담아야 한다: " + row.getDepositTypeCode());
    }

    @Test
    @DisplayName("코드만 온 경우(실 CODEF)에는 종류를 유도한다")
    void derivesAccountTypeFromCode() {
        FinancialAccountDto savings = FinancialAccountDto.builder()
                .organization("0004")
                .accountName("KB 정기적금")
                .accountNo("210123456707")
                .accountType(null)
                .depositTypeCode("12")
                .currency("KRW")
                .balance(new BigDecimal("1200000"))
                .build();

        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("0004")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);
        when(client.fetchAccounts(USER_ID, "conn-1", "0004")).thenReturn(List.of(savings));
        when(mapper.reactivate(any())).thenReturn(0);

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("0004"));
        service.requestSimpleAuth(USER_ID, request);
        service.progress(USER_ID, "conn-1");

        LinkRequestDto linkRequest = new LinkRequestDto();
        linkRequest.setConnectionId("conn-1");
        linkRequest.setAccountIds(List.of(1L));
        service.link(USER_ID, linkRequest);

        List<ConnectedAccount> saved = new ArrayList<>();
        verify(mapper).insert(argThat(row -> {
            saved.add(row);
            return true;
        }));
        assertEquals("SAVINGS", saved.get(0).getAccountType());
        assertEquals("12", saved.get(0).getDepositTypeCode());
    }

    @Test
    @DisplayName("이미 연결된 계좌만 골랐다면 연결하지 않는다")
    void skipsAlreadyLinked() {
        AccountNumberPolicy policy = new AccountNumberPolicy("test-secret-key-for-account-hash-0001");
        when(mapper.findActiveHashes(anyLong()))
                .thenReturn(List.of(policy.hash("0004", "110123456723")));
        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("0004")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);
        when(client.fetchAccounts(USER_ID, "conn-1", "0004"))
                .thenReturn(List.of(account("0004", "입출금통장", "110123456723")));

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("0004"));
        service.requestSimpleAuth(USER_ID, request);
        service.progress(USER_ID, "conn-1");

        LinkRequestDto linkRequest = new LinkRequestDto();
        linkRequest.setConnectionId("conn-1");
        linkRequest.setAccountIds(List.of(1L));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.link(USER_ID, linkRequest));
        assertEquals("EXTERNAL_API_ERROR", e.getCode());
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("공급자가 다루지 못하는 기관은 목록에서 뺀다")
    void hidesUnsupportedInstitutions() {
        /* 실 CODEF 는 카카오뱅크(0090)·토스뱅크(0092)를 제공하지 않는다. */
        when(client.supportedOrganizations()).thenReturn(Set.of("0004", "0003"));

        InstitutionListDto result = service.institutions(USER_ID);

        List<String> codes = result.getBanks().stream().map(InstitutionDto::getCode).toList();
        assertEquals(List.of("0004", "0003"), codes);
        /* 카드·증권·대출·페이머니도 같은 기준으로 걸린다 — 지금 구현은 은행 계좌조회뿐이다. */
        assertTrue(result.getCards().isEmpty());
        assertTrue(result.getSecurities().isEmpty());
        assertTrue(result.getLoans().isEmpty(), "실 CODEF 모드에서는 대출이 빈 배열이어야 한다");
        assertTrue(result.getPayMoney().isEmpty(), "실 CODEF 모드에서는 페이머니가 빈 배열이어야 한다");
    }

    @Test
    @DisplayName("제한이 없으면 목록을 그대로 내려준다 (목서버)")
    void keepsAllInstitutionsWhenUnrestricted() {
        InstitutionListDto result = service.institutions(USER_ID);

        List<String> codes = result.getBanks().stream().map(InstitutionDto::getCode).toList();
        assertTrue(codes.contains("0090"), "목 모드에는 카카오뱅크가 남아야 한다");
        assertFalse(result.getCards().isEmpty());
        assertFalse(result.getSecurities().isEmpty());
        assertFalse(result.getLoans().isEmpty(), "목 모드에는 대출 업권이 내려가야 한다");
        assertFalse(result.getPayMoney().isEmpty(), "목 모드에는 페이머니 업권이 내려가야 한다");
        assertTrue(result.getLoans().stream().map(InstitutionDto::getCode).toList().contains("CP_KB"));
        assertTrue(result.getPayMoney().stream().map(InstitutionDto::getCode).toList()
                .contains("PAY_KAKAO"));
    }

    @Test
    @DisplayName("같은 계좌를 두 번 보내도 한 번만 센다")
    void countsDuplicateIdsOnce() {
        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("0004")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);
        when(client.fetchAccounts(USER_ID, "conn-1", "0004"))
                .thenReturn(List.of(account("0004", "입출금통장", "110123456723")));

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("0004"));
        service.requestSimpleAuth(USER_ID, request);
        service.progress(USER_ID, "conn-1");

        LinkRequestDto linkRequest = new LinkRequestDto();
        linkRequest.setConnectionId("conn-1");
        linkRequest.setAccountIds(List.of(1L, 1L));

        /* 중복을 그대로 두면 reactivate 가 두 번 matched 를 돌려줘 "2개 연결됨" 이 된다. */
        assertEquals(1, service.link(USER_ID, linkRequest).getLinkedCount());
    }

    @Test
    @DisplayName("#379: 체크 해제한 계좌는 연결하지 않고, 이후 동기화가 되살리지 못하게 비활성으로 남긴다")
    void marksUnselectedAccountsInactiveSoSyncCannotRevive() {
        /*
         * FinancialSyncServiceImpl.buildSyncScope() 는 기관 단위로 동기화 범위를 잡는다 — 같은 기관의
         * 다른 계좌가 연결돼 있으면 이 계좌도 다시 끌려온다. is_active=0 으로 남겨야
         * upsertConnectedAccount() 의 inactiveKeys 검사가 걸러준다.
         */
        when(client.createConnection(any())).thenReturn(approvedConnection(List.of("0004")));
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);
        when(client.fetchAccounts(USER_ID, "conn-1", "0004")).thenReturn(List.of(
                account("0004", "입출금통장", "110123456723"),
                account("0004", "저축예금", "110987654321")));
        when(mapper.reactivate(any())).thenReturn(0);

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("0004"));
        service.requestSimpleAuth(USER_ID, request);
        service.progress(USER_ID, "conn-1");

        LinkRequestDto linkRequest = new LinkRequestDto();
        linkRequest.setConnectionId("conn-1");
        linkRequest.setAccountIds(List.of(1L));   // 두 번째 계좌(2L, 저축예금)는 체크 해제

        LinkResultDto result = service.link(USER_ID, linkRequest);

        assertEquals(1, result.getLinkedCount());

        List<ConnectedAccount> inserted = new ArrayList<>();
        verify(mapper, times(2)).insert(argThat(row -> {
            inserted.add(row);
            return true;
        }));
        ConnectedAccount excludedRow = inserted.stream()
                .filter(row -> "저축예금".equals(row.getAccountName()))
                .findFirst().orElseThrow();

        AccountNumberPolicy policy = new AccountNumberPolicy("test-secret-key-for-account-hash-0001");
        String excludedHash = policy.hash("0004", "110987654321");
        assertEquals(excludedHash, excludedRow.getAccountNoEncrypted());
        verify(mapper).deactivateByHash(USER_ID, excludedHash);
        /* 선택한 계좌는 비활성화하지 않는다. */
        verify(mapper, never()).deactivateByHash(eq(USER_ID),
                eq(policy.hash("0004", "110123456723")));
    }

    @Test
    @DisplayName("기관코드가 비어 있으면 요청을 시작하지 않는다")
    void rejectsBlankOrganization() {
        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        /* null 이 통과하면 진행 상태에 null 키가 들어가 폴링이 영원히 끝나지 않는다. */
        request.setOrganizations(java.util.Collections.singletonList(null));

        assertThrows(BusinessException.class, () -> service.requestSimpleAuth(USER_ID, request));
        verify(client, never()).createConnection(any());
    }

    @Test
    @DisplayName("인증에 실패한 기관도 진행 목록에 남는다")
    void keepsFailedInstitutionsVisible() {
        when(client.createConnection(any())).thenReturn(ConnectionResult.builder()
                .connectionId("conn-1")
                .status(AuthStatus.PENDING)
                .expiresInSeconds(300)
                .needsExtraAuth(false)
                .successOrganizations(List.of())
                .failedOrganizations(List.of("0004"))
                .build());
        when(client.getAuthStatus("conn-1")).thenReturn(AuthStatus.APPROVED);

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("0004"));
        service.requestSimpleAuth(USER_ID, request);

        LinkProgressDto progress = service.progress(USER_ID, "conn-1");

        /* 예전에는 실패 기관이 목록에서 사라지고 done=true, percent=0 인 모순 응답이 나갔다. */
        assertEquals(1, progress.getInstitutions().size());
        assertEquals("FAILED", progress.getInstitutions().get(0).getStatus());
        assertTrue(progress.isDone());
        assertEquals(100, progress.getPercent());
    }

    private ConnectedAccount linked(long id, String bankCode, String hash, String balance) {
        return ConnectedAccount.builder()
                .id(id)
                .userId(USER_ID)
                .codefConnectedId("conn-1")
                .bankCode(bankCode)
                .bankName("IBK기업은행")
                .accountNoEncrypted(hash)
                .balance(new BigDecimal(balance))
                .build();
    }

    @Test
    @DisplayName("즉시 조회는 공급자를 실제로 부르고 잔액을 갱신한다")
    void refreshCallsProvider() {
        AccountNumberPolicy policy = new AccountNumberPolicy("test-secret-key-for-account-hash-0001");
        String hash = policy.hash("0003", "110123456723");
        when(mapper.findActiveByUser(USER_ID)).thenReturn(List.of(linked(7L, "0003", hash, "100")));
        when(client.fetchAccounts(USER_ID, "conn-1", "0003"))
                .thenReturn(List.of(account("0003", "입출금통장", "110123456723")));

        RefreshResultDto result = service.refresh(USER_ID);

        /* 예전에는 공급자를 부르지 않고 DB 에 NORMAL 만 써서 "방금 동기화됨" 이 거짓이었다. */
        verify(client).fetchAccounts(USER_ID, "conn-1", "0003");
        verify(mapper).updateSynced(eq(7L), eq(USER_ID), eq(new BigDecimal("8340000")), any());
        assertEquals("NORMAL", result.getInstitutions().get(0).getSyncStatus());
    }

    @Test
    @DisplayName("증권 계좌 수동 새로고침은 현금잔액으로 보유주식 평가액을 덮어쓰지 않는다")
    void refreshPreservesSecuritiesTotalBalance() {
        AccountNumberPolicy policy = new AccountNumberPolicy("test-secret-key-for-account-hash-0001");
        String hash = policy.hash("0218", "301-***-INV2002");
        ConnectedAccount securities = ConnectedAccount.builder()
                .id(17L).userId(USER_ID).codefConnectedId("conn-1")
                .bankCode("0218").bankName("KB증권").accountName("KB 증권 투자계좌")
                .accountNoEncrypted(hash).accountNoMasked("301-***-INV2002")
                .accountType("SECURITIES").balance(new BigDecimal("1303600")).build();
        when(mapper.findActiveByUser(USER_ID)).thenReturn(List.of(securities));
        when(client.fetchAccounts(USER_ID, "conn-1", "0218"))
                .thenReturn(List.of(balanced("0218", "301-***-INV2002", "0")));

        service.refresh(USER_ID);

        verify(mapper).updateSync(eq(17L), eq(USER_ID), eq("NORMAL"), any(), isNull());
        verify(mapper, never()).updateSynced(eq(17L), eq(USER_ID), any(), any());
    }

    @Test
    @DisplayName("기관 행의 금액은 그 기관 계좌의 합계다")
    void sumsBalancePerInstitution() {
        AccountNumberPolicy policy = new AccountNumberPolicy("test-secret-key-for-account-hash-0001");
        when(mapper.findActiveByUser(USER_ID)).thenReturn(List.of(
                linked(1L, "0003", policy.hash("0003", "111"), "0"),
                linked(2L, "0003", policy.hash("0003", "222"), "0")));
        when(client.fetchAccounts(USER_ID, "conn-1", "0003")).thenReturn(List.of(
                balanced("0003", "111", "500000"),
                balanced("0003", "222", "1500000")));

        RefreshResultDto result = service.refresh(USER_ID);

        /* 예전에는 computeIfAbsent 로 첫 계좌만 담아 두 번째 계좌가 통째로 버려졌다. */
        assertEquals(1, result.getInstitutions().size());
        assertEquals(0, new BigDecimal("2000000").compareTo(result.getInstitutions().get(0).getBalance()));
    }

    @Test
    @DisplayName("연결이 끊겼으면 성공으로 쓰지 않고 재연동 대상으로 표시한다")
    void marksNeedReconnectWhenConnectionGone() {
        AccountNumberPolicy policy = new AccountNumberPolicy("test-secret-key-for-account-hash-0001");
        when(mapper.findActiveByUser(USER_ID))
                .thenReturn(List.of(linked(9L, "0003", policy.hash("0003", "111"), "100")));
        when(client.fetchAccounts(USER_ID, "conn-1", "0003"))
                .thenThrow(new BusinessException("CONNECTION_NOT_FOUND", "연결 정보를 찾을 수 없어요."));

        RefreshResultDto result = service.refresh(USER_ID);

        assertEquals("NEED_RECONNECT", result.getInstitutions().get(0).getSyncStatus());
        verify(mapper).updateSync(eq(9L), eq(USER_ID), eq("NEED_RECONNECT"), any(), any());
        verify(mapper, never()).updateSynced(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("[예외 분기] 연결이 끊긴 기관의 계좌마다 재연동 이벤트가 하나씩 발행된다")
    void publishesReconnectEventPerAccountWhenConnectionGone() {
        AccountNumberPolicy policy = new AccountNumberPolicy("test-secret-key-for-account-hash-0001");
        when(mapper.findActiveByUser(USER_ID)).thenReturn(List.of(
                linked(9L, "0003", policy.hash("0003", "111"), "100"),
                linked(10L, "0003", policy.hash("0003", "222"), "200")));
        when(client.fetchAccounts(USER_ID, "conn-1", "0003"))
                .thenThrow(new BusinessException("CONNECTION_NOT_FOUND", "연결 정보를 찾을 수 없어요."));

        RefreshResultDto result = service.refresh(USER_ID);

        /*
         * owned 두 계좌가 모두 NEED_RECONNECT 로 확정되므로, 반환값(SyncStatus) 하나가 아니라
         * 계좌마다 이벤트가 나가야 한다 — "sync 실행당 한 번"이 아니라 "계좌마다 한 번".
         */
        assertEquals("NEED_RECONNECT", result.getInstitutions().get(0).getSyncStatus());
        assertEquals(2, publishedEvents.size());
        List<NotificationRequestedEvent> events = publishedEvents.stream()
                .map(NotificationRequestedEvent.class::cast)
                .toList();
        assertEquals(List.of("/asset/accounts/9/reconnect", "/asset/accounts/10/reconnect"),
                events.stream().map(NotificationRequestedEvent::deepLinkUrl).toList());
        assertTrue(events.stream().allMatch(e ->
                e.userId().equals(USER_ID)
                        && e.type() == NotificationType.ACCOUNT_RECONNECT
                        && "IBK기업은행".equals(e.params().get("bankName"))));
    }

    @Test
    @DisplayName("[잔액 누락 분기] 응답에 없는 계좌는 그 계좌 하나만 재연동 이벤트가 발행된다")
    void publishesReconnectEventWhenAccountMissingFromResponse() {
        AccountNumberPolicy policy = new AccountNumberPolicy("test-secret-key-for-account-hash-0001");
        ConnectedAccount account = linked(11L, "0003", policy.hash("0003", "111"), "100");
        when(mapper.findByIdAndUser(11L, USER_ID)).thenReturn(account);
        /* 기관 응답에 이 계좌가 없다 — 해지 등으로 사라진 경우 */
        when(client.fetchAccounts(USER_ID, "conn-1", "0003")).thenReturn(List.of());

        ResyncResultDto result = service.resync(USER_ID, 11L);

        /*
         * ⚠ 예전에는 이 분기에서도 응답이 NORMAL 이었다 — DB 와 알림은 "재연동 필요" 인데
         * 화면만 "정상" 으로 보이는 모순이었다 (이슈 #70).
         */
        assertEquals("NEED_RECONNECT", result.getSyncStatus());

        verify(mapper).updateSync(eq(11L), eq(USER_ID), eq("NEED_RECONNECT"), any(),
                eq("금융기관에서 이 계좌를 찾지 못했어요."));
        verify(mapper, never()).updateSynced(eq(11L), anyLong(), any(), any());
        assertEquals(1, publishedEvents.size());
        NotificationRequestedEvent event = (NotificationRequestedEvent) publishedEvents.get(0);
        assertEquals(USER_ID, event.userId().longValue());
        assertEquals(NotificationType.ACCOUNT_RECONNECT, event.type());
        assertEquals("IBK기업은행", event.params().get("bankName"));
        assertEquals("/asset/accounts/11/reconnect", event.deepLinkUrl());
    }

    @Test
    @DisplayName("[집계] 한 계좌라도 재연동이 필요하면 기관 결과도 NEED_RECONNECT 다")
    void institutionStatusReflectsWorstAccount() {
        /*
         * 같은 기관에 계좌가 둘인데 하나만 응답에서 사라진 경우다.
         * 예전에는 성공 경로 끝에서 무조건 NORMAL 을 돌려줘, 한 계좌가 재연동 대상이 돼도
         * 즉시 조회 화면은 "정상" 으로 보였다 (이슈 #70).
         */
        AccountNumberPolicy policy = new AccountNumberPolicy("test-secret-key-for-account-hash-0001");
        String presentHash = policy.hash("0003", "110123456723");
        when(mapper.findActiveByUser(USER_ID)).thenReturn(List.of(
                linked(7L, "0003", presentHash, "100"),
                linked(8L, "0003", policy.hash("0003", "999"), "200")));
        when(client.fetchAccounts(USER_ID, "conn-1", "0003"))
                .thenReturn(List.of(account("0003", "입출금통장", "110123456723")));

        RefreshResultDto result = service.refresh(USER_ID);

        assertEquals("NEED_RECONNECT", result.getInstitutions().get(0).getSyncStatus());
        /* 살아 있는 계좌는 그대로 갱신된다 — 하나가 실패했다고 나머지를 버리지 않는다 */
        verify(mapper).updateSynced(eq(7L), eq(USER_ID), any(), any());
        verify(mapper).updateSync(eq(8L), eq(USER_ID), eq("NEED_RECONNECT"), any(), any());
    }

    @Test
    @DisplayName("[정상 동기화] NORMAL 로 끝나면 재연동 이벤트를 발행하지 않는다")
    void doesNotPublishReconnectEventWhenSyncNormal() {
        /* 거짓 알림 방지 가드 — 가장 중요한 케이스다. refreshCallsProvider 와 같은 픽스처를 쓴다. */
        AccountNumberPolicy policy = new AccountNumberPolicy("test-secret-key-for-account-hash-0001");
        String hash = policy.hash("0003", "110123456723");
        when(mapper.findActiveByUser(USER_ID)).thenReturn(List.of(linked(7L, "0003", hash, "100")));
        when(client.fetchAccounts(USER_ID, "conn-1", "0003"))
                .thenReturn(List.of(account("0003", "입출금통장", "110123456723")));

        RefreshResultDto result = service.refresh(USER_ID);

        assertEquals("NORMAL", result.getInstitutions().get(0).getSyncStatus());
        assertTrue(publishedEvents.isEmpty());
    }

    @Test
    @DisplayName("[예외 분기 - FAILED] 일시적 실패는 재연동 이벤트를 발행하지 않는다")
    void doesNotPublishReconnectEventOnTransientFailure() {
        /*
         * CONNECTION_NOT_FOUND·TOKEN_EXPIRED 가 아닌 코드는 FAILED 로 갈린다 (oneFailureDoesNotStopTheRest 와 같은 픽스처).
         * FAILED 는 "다시 눌러보면 되는" 일시적 실패라 재연동 알림 대상이 아니다.
         */
        AccountNumberPolicy policy = new AccountNumberPolicy("test-secret-key-for-account-hash-0001");
        when(mapper.findActiveByUser(USER_ID)).thenReturn(List.of(
                linked(12L, "0003", policy.hash("0003", "111"), "100")));
        when(client.fetchAccounts(USER_ID, "conn-1", "0003"))
                .thenThrow(new BusinessException("EXTERNAL_API_UNAVAILABLE", "점검 중"));

        RefreshResultDto result = service.refresh(USER_ID);

        assertEquals("FAILED", result.getInstitutions().get(0).getSyncStatus());
        assertTrue(publishedEvents.isEmpty());
    }

    @Test
    @DisplayName("다음 자동 동기화 시각은 배치 주기(fixed-delay)로 계산한다 — '매일 18시' 하드코딩이 아니다")
    void nextAutoSyncAtFollowsBatchInterval() {
        /*
         * 이슈 #199 최종 리뷰 — 예전에는 "매일 18시" 를 하드코딩해 놨는데 그런 배치는 존재한 적이 없다.
         * 실제 배치는 financial.sync.batch.fixed-delay-ms(30분)마다 돈다.
         * CLOCK 은 KST 2026-08-05 18:00 에 고정돼 있다 — 옛 코드였다면 하루 뒤 18:00 이 나온다.
         */
        ConnectedAccountListDto result = service.connectedAccounts(USER_ID);

        assertEquals(LocalDateTime.of(2026, 8, 5, 18, 30).toString(), result.getNextAutoSyncAt());
    }

    @Test
    @DisplayName("bank_code 가 비어 있으면 기관명으로 코드를 역추적해 로고가 살아난다")
    void fillsMissingBankCodeFromInstitutionName() {
        /*
         * 목서버가 증권 동기화에 institutionCode 를 주지 않아 bank_code 가 빈 채로 저장된
         * 행이 실제로 있다(2026-08-21 운영 확인 — 삼성증권 8행·KB Pay 8행).
         * 코드가 비면 화면이 로고 파일을 못 찾아 약칭 글자 배지로 떨어진다.
         */
        ConnectedAccount securities = ConnectedAccount.builder()
                .id(1L).userId(USER_ID).bankName("삼성증권")
                .accountName("삼성증권 종합계좌").accountNoEncrypted("MOCK-SECURITIES-1")
                .accountType("SECURITIES").balance(new BigDecimal("1000"))
                .syncStatus("NORMAL").build();
        ConnectedAccount payMoney = ConnectedAccount.builder()
                .id(2L).userId(USER_ID).bankCode("").bankName("KB Pay")
                .accountName("KB Pay 머니").accountNoEncrypted("MOCK-PAYMONEY-1")
                .accountType("PAYMONEY").balance(new BigDecimal("2000"))
                .syncStatus("NORMAL").build();
        when(mapper.findActiveByUser(USER_ID)).thenReturn(List.of(securities, payMoney));

        ConnectedAccountListDto result = service.connectedAccounts(USER_ID);

        // null 인 경우와 빈 문자열인 경우를 모두 구제한다
        assertEquals("0240", result.getAccounts().get(0).getBankCode());
        assertEquals("PAY_KB", result.getAccounts().get(1).getBankCode());
        // 약칭도 같은 코드로 뽑아야 배지 글자가 빈칸이 되지 않는다
        assertEquals("삼성", result.getAccounts().get(0).getShortLabel());
        assertEquals("KB", result.getAccounts().get(1).getShortLabel());
    }

    @Test
    @DisplayName("저장된 bank_code 가 있으면 기관명 역추적보다 항상 우선한다")
    void storedBankCodeWinsOverNameLookup() {
        /*
         * codeOfName 은 이름이 한 글자라도 다르면 null 이라 폴백에 의존하면 조용히 깨진다.
         * 저장값이 있으면 이름이 카탈로그와 달라도 저장값을 그대로 써야 한다.
         */
        ConnectedAccount account = ConnectedAccount.builder()
                .id(1L).userId(USER_ID).bankCode("0240").bankName("삼성증권(구)")
                .accountName("종합계좌").accountNoEncrypted("MOCK-SECURITIES-9")
                .accountType("SECURITIES").balance(new BigDecimal("1000"))
                .syncStatus("NORMAL").build();
        when(mapper.findActiveByUser(USER_ID)).thenReturn(List.of(account));

        ConnectedAccountListDto result = service.connectedAccounts(USER_ID);

        assertEquals("0240", result.getAccounts().get(0).getBankCode());
    }

    @Test
    @DisplayName("같은 증권 계좌의 이전 동기화 그림자 행은 연결 계좌 관리에서 숨긴다")
    void hidesLegacySecuritiesShadowWhenCanonicalAccountExists() {
        ConnectedAccount canonical = ConnectedAccount.builder()
                .id(1L).userId(USER_ID).bankCode("0218").bankName("KB증권")
                .accountName("KB 증권 투자계좌").accountNoEncrypted("canonical")
                .accountType("SECURITIES").balance(new BigDecimal("1303600"))
                .syncStatus("NORMAL").build();
        ConnectedAccount legacyShadow = ConnectedAccount.builder()
                .id(2L).userId(USER_ID).bankName("KB증권")
                .accountNoEncrypted("MOCK-SECURITIES-301").accountType("SECURITIES")
                .balance(BigDecimal.ZERO).syncStatus("NORMAL").build();
        when(mapper.findActiveByUser(USER_ID)).thenReturn(List.of(canonical, legacyShadow));

        ConnectedAccountListDto result = service.connectedAccounts(USER_ID);

        assertEquals(1, result.getAccounts().size());
        assertEquals(1L, result.getAccounts().get(0).getAccountId());
        assertEquals(0, new BigDecimal("1303600").compareTo(result.getAccounts().get(0).getBalance()));
    }

    @Test
    @DisplayName("첫 동기화가 만든 이전 적금 그림자 행은 연결 계좌 관리에서 숨긴다")
    void hidesLegacyDepositShadowWhenCanonicalAccountExists() {
        ConnectedAccount canonical = ConnectedAccount.builder()
                .id(1L).userId(USER_ID).bankCode("0004").bankName("KB국민은행")
                .accountName("KB 매월 챌린지 적금").accountNoEncrypted("canonical")
                .accountNoMasked("110-***-SAVING2").accountType("SAVINGS")
                .balance(new BigDecimal("1500000")).syncStatus("NORMAL").build();
        ConnectedAccount legacyShadow = ConnectedAccount.builder()
                .id(2L).userId(USER_ID).bankCode("0004").bankName("KB국민은행")
                .accountName("KB 매월 챌린지 적금").accountNoEncrypted("MOCK-DEPOSIT-201")
                .accountNoMasked("110-***-SAVING2").accountType("SAVINGS")
                .balance(new BigDecimal("1500000")).syncStatus("NORMAL").build();
        when(mapper.findActiveByUser(USER_ID)).thenReturn(List.of(canonical, legacyShadow));

        ConnectedAccountListDto result = service.connectedAccounts(USER_ID);

        assertEquals(1, result.getAccounts().size());
        assertEquals(1L, result.getAccounts().get(0).getAccountId());
    }

    @Test
    @DisplayName("동기화된 대출은 연결 계좌 관리에 읽기 전용 항목으로 보인다 (bank_code 가 저장돼 있으면 그대로 쓴다)")
    void includesSyncedLoansInConnectedAccounts() {
        when(loanMapper.findByUser(USER_ID)).thenReturn(List.of(Loan.builder()
                .id(31L).userId(USER_ID).bankName("KB캐피탈").bankCode("CP_KB").loanType("KB 신용대출")
                .loanNoMasked("LN-2025-****-0001")
                .balance(new BigDecimal("14200000")).build()));

        ConnectedAccountListDto result = service.connectedAccounts(USER_ID);

        assertEquals(1, result.getAccounts().size());
        /* 카드 행과 겹치지 않게 종류를 id 에 실어 보낸다(#467). 예전엔 그냥 `-31` 이었다. */
        assertEquals(ManagedAccountId.ofLoan(31L), result.getAccounts().get(0).getAccountId());
        assertEquals("CP_KB", result.getAccounts().get(0).getBankCode());
        assertEquals("LN-2025-****-0001", result.getAccounts().get(0).getAccountNoMasked());
        assertFalse(result.getAccounts().get(0).isManageable());
    }

    @Test
    @DisplayName("2026-08-20 마이그레이션 이전에 저장돼 bank_code 가 없는 옛 대출 행은 기관명으로 역추적한다")
    void fallsBackToNameLookupWhenLoanHasNoBankCode() {
        when(loanMapper.findByUser(USER_ID)).thenReturn(List.of(Loan.builder()
                .id(32L).userId(USER_ID).bankName("KB캐피탈").loanType("KB 신용대출")
                .loanNoMasked("LN-2025-****-0002")
                .balance(new BigDecimal("5000000")).build()));

        ConnectedAccountListDto result = service.connectedAccounts(USER_ID);

        assertEquals(1, result.getAccounts().size());
        assertEquals("CP_KB", result.getAccounts().get(0).getBankCode());
    }

    private FinancialAccountDto balanced(String organization, String no, String balance) {
        return FinancialAccountDto.builder()
                .organization(organization)
                .accountName("통장")
                .accountNo(no)
                .depositTypeCode("11")
                .currency("KRW")
                .balance(new BigDecimal(balance))
                .build();
    }
}
