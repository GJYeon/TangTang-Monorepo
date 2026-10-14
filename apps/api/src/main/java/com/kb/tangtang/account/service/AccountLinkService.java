package com.kb.tangtang.account.service;

import com.kb.tangtang.account.client.CodefConnectionStore;
import com.kb.tangtang.account.client.FinancialDataClient;
import com.kb.tangtang.account.client.FinancialDataClientResolver;
import com.kb.tangtang.account.client.dto.ConnectionRequest;
import com.kb.tangtang.account.client.dto.ConnectionResult;
import com.kb.tangtang.account.client.dto.CredentialDto;
import com.kb.tangtang.account.client.dto.FinancialAccountDto;
import com.kb.tangtang.account.client.dto.IdentityDto;
import com.kb.tangtang.account.client.sync.FinancialSyncClient;
import com.kb.tangtang.account.client.sync.ScenarioKeyProvider;
import com.kb.tangtang.account.client.sync.dto.CardSyncDto;
import com.kb.tangtang.account.client.sync.dto.LoanSyncDto;
import com.kb.tangtang.account.client.sync.dto.PayMoneySyncDto;
import com.kb.tangtang.account.domain.AuthMethod;
import com.kb.tangtang.account.domain.AuthStatus;
import com.kb.tangtang.account.domain.Card;
import com.kb.tangtang.account.domain.ConnectedAccount;
import com.kb.tangtang.account.domain.Loan;
import com.kb.tangtang.account.domain.ProgressStatus;
import com.kb.tangtang.account.domain.SyncStatus;
import com.kb.tangtang.account.dto.*;
import com.kb.tangtang.account.mapper.CardMapper;
import com.kb.tangtang.account.mapper.ConnectedAccountMapper;
import com.kb.tangtang.account.mapper.LoanMapper;
import com.kb.tangtang.common.exception.BusinessException;
import com.kb.tangtang.user.domain.ConsentScope;
import com.kb.tangtang.user.service.ConsentService;
import com.kb.tangtang.notification.domain.NotificationRequestedEvent;
import com.kb.tangtang.notification.domain.NotificationType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 계좌 연동 흐름 조립 (이슈 #12).
 *
 * 화면 순서와 같다: 기관 선택 → 인증 → 조회 진행 → 계좌 선택 → 완료.
 * **어느 금융 데이터 공급자가 붙었는지 이 클래스는 모른다** — FinancialDataClient 뒤에 가려져 있다.
 *
 * 트랜잭션 경계는 여기다. 외부 API 호출 구간에는 트랜잭션을 걸지 않는다 —
 * 기관 조회가 수십 초 걸릴 수 있어 커넥션을 그동안 붙잡고 있으면 풀이 마른다.
 */
@Service
public class AccountLinkService {

    /** 마이데이터 재동의 주기. 1년마다 다시 연결해야 한다. */
    private static final int CONSENT_VALID_DAYS = 365;

    /** 즉시 조회 쿨다운. 과도한 반복 호출을 막는다 (AC_02_03). */
    private static final int REFRESH_COOLDOWN_SECONDS = 150;

    /** 사용자별 계좌 연동 공급자(목서버/실 CODEF). hybrid 모드에서는 사용자마다 다르다. */
    private final FinancialDataClientResolver clients;
    /** 승인된 실 CODEF 연결(connectedId)을 기관 단위로 남기는 곳. 목서버 연결은 남길 것이 없다. */
    private final CodefConnectionStore codefConnections;
    private final ConnectedAccountMapper mapper;
    private final LoanMapper loanMapper;
    /** 관리 목록의 카드 행과 카드 연결 해제용(#467). 카드 저장 자체는 FinancialSyncServiceImpl 이 한다. */
    private final CardMapper cardMapper;
    private final LinkProgressStore progressStore;
    private final InstitutionCatalog catalog;
    private final AccountNumberPolicy accountNumbers;
    /**
     * 대출·페이머니·카드 미리보기 전용(#334) — FinancialSyncServiceImpl 의 배치 동기화와 같은 클라이언트다.
     * clients(FinancialDataClient)의 fetchAccounts() 는 은행 엔드포인트만 알아 대출·페이머니·카드를 아예
     * 못 본다. 계좌 선택 화면에 "이것도 연동됩니다"를 보여주려고 여기서만 직접 부른다 — 실제 연결
     * 저장은 이 미리보기가 아니라 최초 동기화(FinancialSyncService.sync(userId, extraCodes))가 한다.
     * 실 CODEF 모드에서는 대출·페이머니·카드 기관코드가 애초에 목록에 없어(onlySupported()) 이 경로를
     * 타지 않는다.
     */
    private final FinancialSyncClient syncClient;
    private final ScenarioKeyProvider scenarioKeyProvider;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    /**
     * 자동 동기화 배치의 실행 간격(밀리초). FinancialSyncBatchScheduler 의 fixedDelay 와 같은 값을
     * 읽는다 — 다음 자동 동기화 시각 안내를 여기서 따로 하드코딩하면(예전 "매일 18시") 배치 주기를
     * 바꾸는 순간 안내만 거짓말이 된다(이슈 #199 최종 리뷰).
     */
    private final long batchFixedDelayMs;
    /*
     * 제3자 제공(THIRD_PARTY) 동의 검사용.
     * ⚠ 화면 순서만으로는 못 막는다 — 계좌 연동 진입점이 셋이고 우회가 가능하다.
     *   동의는 화면 규칙이 아니라 서버 규칙이어야 한다. (DECISIONS.md 2026-08-11 (7))
     * ConsentService 는 의존이 가벼워 좁은 창구를 따로 만들지 않고 직접 주입한다
     * (반대 방향인 AuthService→계좌 조회는 AccountLinkService 가 무거워 ConnectedAccountQuery 를 뒀다).
     */
    private final ConsentService consentService;

    /**
     * ⚠ 생성자가 둘이라 **@Autowired 로 어느 쪽을 쓸지 명시해야 한다.**
     * 없으면 Spring 이 기본 생성자를 찾다가 `NoSuchMethodException: <init>()` 로 컨텍스트 로딩이 실패한다.
     * (2026-08-05 실제 발생 — 단위 테스트는 생성자를 직접 호출하므로 이 결함을 잡지 못한다)
     */
    @Autowired
    public AccountLinkService(FinancialDataClientResolver clients,
                              CodefConnectionStore codefConnections,
                              ConnectedAccountMapper mapper,
                              LoanMapper loanMapper,
                              CardMapper cardMapper,
                              LinkProgressStore progressStore,
                              InstitutionCatalog catalog,
                              AccountNumberPolicy accountNumbers,
                              FinancialSyncClient syncClient,
                              ScenarioKeyProvider scenarioKeyProvider,
                              ApplicationEventPublisher events,
                              ConsentService consentService,
                              @Value("${financial.sync.batch.fixed-delay-ms}") long batchFixedDelayMs) {
        this(clients, codefConnections, mapper, loanMapper, cardMapper, progressStore, catalog, accountNumbers,
                syncClient, scenarioKeyProvider, events, consentService, batchFixedDelayMs, Clock.systemDefaultZone());
    }

    /** 테스트에서 시간을 고정하기 위한 생성자. */
    AccountLinkService(FinancialDataClientResolver clients,
                       CodefConnectionStore codefConnections,
                       ConnectedAccountMapper mapper,
                       LoanMapper loanMapper,
                       CardMapper cardMapper,
                       LinkProgressStore progressStore,
                       InstitutionCatalog catalog,
                       AccountNumberPolicy accountNumbers,
                       FinancialSyncClient syncClient,
                       ScenarioKeyProvider scenarioKeyProvider,
                       ApplicationEventPublisher events,
                       ConsentService consentService,
                       long batchFixedDelayMs,
                       Clock clock) {
        this.clients = clients;
        this.codefConnections = codefConnections;
        this.mapper = mapper;
        this.loanMapper = loanMapper;
        this.cardMapper = cardMapper;
        this.progressStore = progressStore;
        this.catalog = catalog;
        this.accountNumbers = accountNumbers;
        this.syncClient = syncClient;
        this.scenarioKeyProvider = scenarioKeyProvider;
        this.events = events;
        this.consentService = consentService;
        this.batchFixedDelayMs = batchFixedDelayMs;
        this.clock = clock;
    }

    /* ── 1단계: 기관 선택 ───────────────────────────────── */

    @Transactional(readOnly = true)
    public InstitutionListDto institutions(long userId) {
        List<String> connected = mapper.findActiveByUser(userId).stream()
                .map(ConnectedAccount::getBankCode)
                .distinct()
                .toList();
        /* 공급자가 다루지 못하는 기관은 아예 내려보내지 않는다 — 인증 수단과 같은 원칙이다. */
        Set<String> supported = clients.forUser(userId).supportedOrganizations();
        /*
         * 대출·페이머니·카드도 같은 필터를 탄다. 실 CODEF 모드에서는 supportedOrganizations() 가
         * 은행 20곳만 돌려주므로 두 업권이 자동으로 빈 배열이 된다 — 의도한 동작이다.
         * 목 모드(제한 없음)에서만 카탈로그 전체가 그대로 내려간다.
         */
        return InstitutionListDto.builder()
                .banks(onlySupported(catalog.banks(connected), supported))
                .cards(onlySupported(catalog.cards(connected), supported))
                .securities(onlySupported(catalog.securities(connected), supported))
                .loans(onlySupported(catalog.loans(connected), supported))
                .payMoney(onlySupported(catalog.payMoney(connected), supported))
                .build();
    }

    /**
     * 계좌 연동 전 제3자 제공(THIRD_PARTY) 동의를 확인한다.
     *
     * ⚠ 화면 순서에만 맡기면 우회된다 — 계좌 연동 진입점이 셋(연결 계좌 관리 · 개인챌린지 CTA ·
     *   재연동)이고 전부 동의 화면을 거치지 않는 경로였다. 실제로 2026-08-11 까지
     *   **아무도 제3자 제공 동의를 하지 않은 채 계좌를 연동해 왔다.**
     *   마이데이터 동의는 화면 규칙이 아니라 서버 규칙이어야 한다. (DECISIONS.md 2026-08-11 (7))
     */
    private void requireFinancialConsent(long userId) {
        if (consentService.needsConsent(userId, ConsentScope.FINANCIAL)) {
            throw new BusinessException("CONSENT_REQUIRED",
                    "계좌를 연결하려면 제3자 제공 동의가 필요해요.");
        }
    }

    /** 빈 집합은 "제한 없음" 이다 (목서버). 그때는 목록을 그대로 둔다. */
    private static List<InstitutionDto> onlySupported(List<InstitutionDto> items,
                                                      Set<String> supported) {
        if (supported.isEmpty()) {
            return items;
        }
        return items.stream().filter(item -> supported.contains(item.getCode())).toList();
    }

    /* ── 2단계: 인증 ────────────────────────────────────── */

    /**
     * 지원 인증 수단. **프론트 분기의 유일한 근거다.**
     * 여기서 mock/codef 를 노출하면 "설정 교체만으로 전환" 이 거짓이 된다.
     */
    public AuthMethodListDto authMethods(long userId) {
        /* hybrid 모드에서는 사용자마다 공급자가 달라 인증 수단도 다르다(목: 간편인증 · CODEF: 기관 로그인). */
        FinancialDataClient client = clients.forUser(userId);
        List<AuthMethodDto> methods = new ArrayList<>();
        for (AuthMethod method : client.supportedAuthMethods()) {
            List<ProviderDto> providers = method == AuthMethod.SIMPLE_AUTH
                    ? client.simpleAuthProviders().stream()
                            .map(code -> ProviderDto.builder()
                                    .code(code)
                                    .name(providerName(code))
                                    .build())
                            .toList()
                    : List.of();
            methods.add(AuthMethodDto.builder()
                    .type(method.name())
                    .providers(providers)
                    /* 개인정보 전송 여부는 공급자가 정한다. 프론트가 판단하지 않는다. */
                    .requiresIdentity(method == AuthMethod.SIMPLE_AUTH && client.requiresIdentity())
                    .build());
        }
        return AuthMethodListDto.builder().methods(methods).build();
    }

    /** 간편인증 요청. 선택한 기관을 한 번에 처리한다. */
    public ConnectionResponseDto requestSimpleAuth(long userId, SimpleAuthRequestDto request) {
        requireFinancialConsent(userId);
        if (request.getProvider() == null || request.getProvider().isBlank()) {
            throw new BusinessException("INVALID_CREDENTIALS", "인증 수단을 선택해 주세요.");
        }
        List<String> organizations = request.getOrganizations() == null
                ? List.of()
                : request.getOrganizations();
        requireOrganizations(organizations);

        ConnectionResult result = clients.forUser(userId).createConnection(ConnectionRequest.builder()
                .authMethod(AuthMethod.SIMPLE_AUTH)
                .provider(request.getProvider())
                .organizations(organizations)
                .credentials(List.of())
                /*
                 * 본인 정보는 **전달만 한다.** DB·로그 어디에도 남기지 않는다.
                 * 목 모드에서는 프론트가 보내지 않아 전부 null 이다.
                 */
                .identity(IdentityDto.builder()
                        .userName(request.getUserName())
                        .birthDate(request.getBirthDate())
                        .carrier(request.getCarrier())
                        .phoneNo(request.getPhoneNo())
                        .build())
                .build());

        rememberProgress(userId, result, organizations);
        return toResponse(result);
    }

    /**
     * 기관 로그인 인증.
     * ⚠ 자격증명은 이 메서드 안에서만 산다. 진행 상태·로그·DB 어디에도 남기지 않는다.
     */
    public ConnectionResponseDto requestInstitutionLogin(long userId,
                                                         InstitutionLoginRequestDto request) {
        requireFinancialConsent(userId);
        List<CredentialRequestDto> rows = request.getCredentials();
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException("INVALID_CREDENTIALS", "금융기관 로그인 정보가 필요해요.");
        }
        for (CredentialRequestDto row : rows) {
            if (row.getId() == null || row.getId().isBlank()
                    || row.getPassword() == null || row.getPassword().isBlank()) {
                throw new BusinessException("INVALID_CREDENTIALS",
                        "아이디와 비밀번호를 모두 입력해 주세요.");
            }
        }

        List<String> organizations = rows.stream()
                .map(CredentialRequestDto::getOrganization)
                .toList();
        requireOrganizations(organizations);

        ConnectionResult result = clients.forUser(userId).createConnection(ConnectionRequest.builder()
                .authMethod(AuthMethod.INSTITUTION_LOGIN)
                .organizations(organizations)
                .credentials(rows.stream()
                        .map(row -> CredentialDto.builder()
                                .organization(row.getOrganization())
                                .loginType(row.getLoginType())
                                .id(row.getId())
                                .password(row.getPassword())
                                .build())
                        .toList())
                .build());

        rememberProgress(userId, result, organizations);
        return toResponse(result);
    }

    public AuthStatusDto authStatus(long userId, String connectionId) {
        progressStore.get(userId, connectionId);
        return AuthStatusDto.builder().status(clients.forUser(userId).getAuthStatus(connectionId).name()).build();
    }

    public ConnectionResponseDto submitExtraAuth(long userId, String connectionId,
                                                 ExtraAuthRequestDto request) {
        progressStore.get(userId, connectionId);
        if (request.getAuthCode() == null || request.getAuthCode().isBlank()) {
            throw new BusinessException("INVALID_CREDENTIALS", "인증번호를 입력해 주세요.");
        }
        AuthStatus status = clients.forUser(userId).submitExtraAuth(connectionId, request.getAuthCode());
        return ConnectionResponseDto.builder()
                .connectionId(connectionId)
                .status(status.name())
                .expiresInSeconds(0)
                .needsExtraAuth(false)
                .extraAuthType(null)
                .build();
    }

    /* ── 3단계: 조회 진행 ───────────────────────────────── */

    /**
     * 진행 상황 조회. **폴링될 때마다 기관을 하나씩 실제로 조회한다.**
     *
     * 별도 스레드로 미리 돌리지 않는 이유는 두 가지다.
     *   · 브로커도 비동기 인프라도 없는 단일 프로세스 구조다
     *   · 폴링 주기(1초)와 조회 속도가 자연스럽게 맞물려 화면 진행이 매끄럽다
     * 한 기관이 실패해도 FAILED 로 두고 다음 기관으로 넘어간다.
     */
    public LinkProgressDto progress(long userId, String connectionId) {
        LinkProgress progress = progressStore.get(userId, connectionId);

        FinancialDataClient client = clients.forUser(userId);
        if (client.getAuthStatus(connectionId) != AuthStatus.APPROVED) {
            /* 아직 승인 전이면 조회를 시작하지 않는다. */
            return toProgressDto(progress);
        }

        String next = progress.nextWaiting();
        if (next != null) {
            progress.mark(next, ProgressStatus.FETCHING);
            try {
                List<FinancialAccountDto> accounts = client.fetchAccounts(userId, connectionId, next);
                /*
                 * 조회까지 성공한 기관만 연결로 남긴다(실 CODEF). 계좌 선택 화면의 카드·대출 미리보기와
                 * 이후 동기화·「전체 즉시 조회」가 이 기록으로 CODEF 를 다시 부른다 — 메모리 세션은
                 * 서버 재시작과 함께 사라진다.
                 */
                client.connectedIdOf(connectionId)
                        .ifPresent(connectedId -> codefConnections.save(userId, next, connectedId));
                progress.addAccounts(accounts);
                progress.mark(next, ProgressStatus.DONE);
            } catch (RuntimeException e) {
                /* 이 기관만 실패로 두고 나머지는 계속 진행한다. */
                progress.mark(next, ProgressStatus.FAILED);
            }
        }
        return toProgressDto(progress);
    }

    /* ── 4단계: 계좌 선택 ───────────────────────────────── */

    @Transactional(readOnly = true)
    public List<LinkableGroupDto> linkableAccounts(long userId, String connectionId) {
        LinkProgress progress = progressStore.get(userId, connectionId);
        List<String> linkedHashes = mapper.findActiveHashes(userId);

        Map<String, List<LinkableAccountDto>> grouped = new LinkedHashMap<>();
        List<FinancialAccountDto> accounts = progress.getAccounts();
        for (int i = 0; i < accounts.size(); i++) {
            FinancialAccountDto account = accounts.get(i);
            String hash = accountNumbers.hash(account.getOrganization(), account.getAccountNo());
            grouped.computeIfAbsent(account.getOrganization(), key -> new ArrayList<>())
                    .add(LinkableAccountDto.builder()
                            /* 아직 DB 에 없으므로 목록 순번을 임시 식별자로 쓴다. */
                            .accountId(i + 1L)
                            .bankCode(account.getOrganization())
                            .bankName(bankName(account))
                            .accountType(accountType(account))
                            .accountName(account.getAccountName())
                            .accountNoMasked(accountNumbers.mask(account.getAccountNo()))
                            .currency(account.getCurrency() == null ? "KRW" : account.getCurrency())
                            .balance(account.getBalance())
                            .alreadyLinked(linkedHashes.contains(hash))
                            .build());
        }

        List<LinkableGroupDto> bankGroups = grouped.entrySet().stream()
                .map(entry -> LinkableGroupDto.builder()
                        .bankCode(entry.getKey())
                        .bankName(catalog.nameOf(entry.getKey()))
                        .shortLabel(catalog.shortLabelOf(entry.getKey()))
                        .accounts(entry.getValue())
                        .build())
                .toList();

        List<LinkableGroupDto> result = new ArrayList<>(bankGroups);
        result.addAll(directAssetPreviewGroups(userId, progress));
        return result;
    }

    /**
     * 대출·페이머니·카드 미리보기 그룹(#334).
     *
     * fetchAccounts() 가 다루지 못해 위 루프의 progress.getAccounts() 에는 절대 안 나오지만,
     * 사용자가 기관 선택에서 골랐다면(progress 의 대상 목록) 계좌 선택 화면에도 "연동됩니다"를
     * 보여줘야 한다 — 안 그러면 은행만 화면에 보이고 나머지는 아무 설명 없이 자산 화면에만 나타난다.
     * 여기서 반환하는 계좌는 **선택 대상이 아니다** — link() 는 이 accountId 를 쓰지 않는다.
     * 실제 저장은 계좌 연동 완료 직후 최초 동기화가 한다(LinkDoneView → syncAssets).
     */
    private List<LinkableGroupDto> directAssetPreviewGroups(long userId, LinkProgress progress) {
        List<String> selectedCodes = new ArrayList<>(progress.getStatuses().keySet());
        if (selectedCodes.isEmpty()) {
            return List.of();
        }

        String scenarioKey = scenarioKeyProvider.resolve(userId);
        List<LoanSyncDto> loans = syncClient.getLoans(scenarioKey);
        List<PayMoneySyncDto> payMoney = syncClient.getPayMoney(scenarioKey);
        List<CardSyncDto> cards = syncClient.getCards(scenarioKey);

        List<LinkableGroupDto> preview = new ArrayList<>();
        long previewAccountId = -1L;   // 음수로 둬 은행 계좌의 실 accountId(1L 부터)와 절대 안 겹치게 한다.
        for (String code : selectedCodes) {
            List<LinkableAccountDto> accounts = new ArrayList<>();
            /*
             * 미리보기 id 와 원본 키를 progress 에 함께 적어 둔다(#467). 사용자가 이 행의 체크를 풀면
             * link() 가 그 키로 제외 행을 남겨 최초 동기화·배치가 해당 상품을 건너뛴다.
             */
            for (LoanSyncDto loan : loans) {
                if (!code.equals(loan.getInstitutionCode())) {
                    continue;
                }
                long id = previewAccountId--;
                progress.registerPreview(id, new LinkProgress.PreviewEntry(
                        FinancialSyncServiceImpl.loanExclusionKey(loan.getLoanId()),
                        code, loan.getInstitutionName(), loan.getProductName(), "LOAN"));
                accounts.add(LinkableAccountDto.builder()
                        .accountId(id)
                        .bankCode(code)
                        .bankName(loan.getInstitutionName())
                        .accountType("LOAN")
                        .accountName(loan.getProductName())
                        .accountNoMasked(loan.getLoanNoMasked())
                        .currency("KRW")
                        .balance(loan.getBalance())
                        .build());
            }
            for (PayMoneySyncDto wallet : payMoney) {
                if (!code.equals(wallet.getProviderCode())) {
                    continue;
                }
                long id = previewAccountId--;
                progress.registerPreview(id, new LinkProgress.PreviewEntry(
                        FinancialSyncServiceImpl.payMoneyExclusionKey(wallet.getPayMoneyId()),
                        code, wallet.getProviderName(), wallet.getWalletName(), "PAYMONEY"));
                accounts.add(LinkableAccountDto.builder()
                        .accountId(id)
                        .bankCode(code)
                        .bankName(wallet.getProviderName())
                        .accountType("PAYMONEY")
                        .accountName(wallet.getWalletName())
                        .currency("KRW")
                        .balance(wallet.getBalance())
                        .build());
            }
            for (CardSyncDto card : cards) {
                if (!code.equals(card.getInstitutionCode())) {
                    continue;
                }
                long id = previewAccountId--;
                progress.registerPreview(id, new LinkProgress.PreviewEntry(
                        FinancialSyncServiceImpl.cardExclusionKey(card.getCardNoMasked()),
                        code, card.getInstitutionName(), card.getProductName(), "CARD"));
                accounts.add(LinkableAccountDto.builder()
                        .accountId(id)
                        .bankCode(code)
                        .bankName(card.getInstitutionName())
                        .accountType("CARD")
                        .accountName(card.getProductName())
                        .accountNoMasked(card.getCardNoMasked())
                        .currency(card.getCurrency() == null ? "KRW" : card.getCurrency())
                        .balance(BigDecimal.ZERO)
                        .build());
            }
            if (!accounts.isEmpty()) {
                preview.add(LinkableGroupDto.builder()
                        .bankCode(code)
                        .bankName(catalog.nameOf(code))
                        .shortLabel(catalog.shortLabelOf(code))
                        .accounts(accounts)
                        .autoIncluded(true)
                        .build());
            }
        }
        return preview;
    }

    /** 은행 계좌 없이도 연동을 완료할 수 있는 업권 코드. */
    private List<String> directAssetTargetCodes(LinkProgress progress) {
        return progress.getStatuses().keySet().stream()
                .filter(code -> catalog.isLoanCode(code) || catalog.isPayMoneyCode(code)
                        || catalog.isCardCode(code))
                .toList();
    }

    /* ── 5단계: 연결 저장 ───────────────────────────────── */

    @Transactional
    public LinkResultDto link(long userId, LinkRequestDto request) {
        LinkProgress progress = progressStore.get(userId, request.getConnectionId());
        List<Long> requested = request.getAccountIds();
        /*
         * 대출·페이머니·카드만 고른 경우 은행 계좌가 하나도 없어 requested 가 비어 있는 게 정상이다(#334)
         * — 그 업권은 여기서 선택할 대상 자체가 없다(계좌 선택 화면의 자동 연동 미리보기 참고).
         * 그때는 빈 선택을 오류로 보지 않는다.
         */
        boolean hasDirectAssets = !directAssetTargetCodes(progress).isEmpty();
        if ((requested == null || requested.isEmpty()) && !hasDirectAssets) {
            throw new BusinessException("EXTERNAL_API_ERROR", "연결할 계좌를 선택해 주세요.");
        }
        if (requested == null) {
            requested = List.of();
        }

        List<FinancialAccountDto> accounts = progress.getAccounts();
        /*
         * 같은 계좌를 두 번 세지 않는다.
         * 중복이 그대로 들어오면 `reactivate` 가 두 번 matched 를 돌려줘 **1개를 연결하고 "2개 연결됨"**
         * 을 응답한다(MySQL 기본값이 CLIENT_FOUND_ROWS 라 두 번째도 1이 나온다).
         */
        Set<Long> ids = new LinkedHashSet<>(requested);
        /* 이번 호출에서 이미 처리한 해시. `existing` 은 루프 밖에서 한 번 읽은 스냅샷이라 여기서 보완한다. */
        Set<String> seen = new HashSet<>(mapper.findActiveHashes(userId));
        /*
         * 체크 해제한 계좌를 기억해 둘 자리(#379). 여기서 기록하지 않으면 이 계좌는 DB 에 아무 흔적도
         * 안 남는데, FinancialSyncServiceImpl.buildSyncScope() 는 **기관 단위**로 동기화 범위를 잡는다 —
         * 같은 기관의 다른 계좌가 하나라도 연결돼 있으면 그 기관 코드가 스코프에 들어가고, collectBank() 는
         * 스코프 안의 계좌를 전부(체크 해제한 것까지) 다시 끌어와 저장한다. 그 결과 방금 연결 완료 화면
         * 직후의 최초 동기화(LinkDoneView)나 30분마다 도는 배치가 체크 해제한 계좌를 조용히 되살려
         * "필터링이 안 된다"는 증상으로 나타난다.
         * disconnect() 가 쓰는 것과 같은 is_active=0 자연키를 써서 남기면, upsertConnectedAccount() 의
         * inactiveKeys 검사가 이미 이 계좌를 걸러준다 — 별도 컬럼 없이 기존 장치에 얹는다.
         */
        Set<String> excludedSeen = new HashSet<>(mapper.findInactiveKeysByUser(userId));
        LocalDateTime now = LocalDateTime.now(clock);
        int linked = 0;

        for (int index = 0; index < accounts.size(); index++) {
            long id = index + 1L;
            FinancialAccountDto account = accounts.get(index);
            String hash = accountNumbers.hash(account.getOrganization(), account.getAccountNo());

            if (!ids.contains(id)) {
                /* 사용자가 체크 해제한 계좌. 이미 연결돼 있거나(다른 곳에서 먼저 연결) 이미 기록해 둔
                   제외 대상이면 손대지 않는다 — 특히 이미 연결된 계좌를 여기서 비활성화하면 안 된다. */
                if (seen.contains(hash) || !excludedSeen.add(hash)) {
                    continue;
                }
                ConnectedAccount excludedRow = buildRow(userId, progress, account, hash, now);
                mapper.insert(excludedRow);
                mapper.deactivateByHash(userId, hash);
                continue;
            }

            if (!seen.add(hash)) {
                /* 이미 연결됐거나 이번 요청에 또 실린 계좌는 조용히 건너뛴다. */
                continue;
            }

            ConnectedAccount row = buildRow(userId, progress, account, hash, now);

            /* 해제했던 계좌면 새 행을 만들지 않고 되살린다 (UNIQUE KEY 충돌 방지). */
            if (mapper.reactivate(row) == 0) {
                mapper.insert(row);
            }
            linked++;
        }

        /*
         * 자동 연동 미리보기에서 체크를 푼 상품(#467). 은행 계좌의 제외 행과 같은 장치다 — 키만 있는
         * is_active=0 행을 남기면 FinancialSyncServiceImpl 의 inactiveKeys 검사가 그 상품을 건너뛴다.
         * 모르는 id(화면이 지어낸 값)는 조용히 무시한다. 이미 연결돼 있던 상품(페이머니)이면 그 행이
         * 꺼진다 — 체크 해제는 곧 연결 해제다.
         */
        List<Long> excluded = request.getExcludedAccountIds() == null ? List.of() : request.getExcludedAccountIds();
        Set<Long> excludedIds = new LinkedHashSet<>(excluded);
        /*
         * 이번에 체크한 채로 둔 상품에 예전 제외 행이 남아 있으면 먼저 걷어낸다(#467 되돌리기).
         * 예전에 풀었거나 해제했던 상품은 is_active=0 행 때문에 동기화가 영원히 건너뛴다 — 다시 연동하려고
         * 체크해도 아무 일이 없는 것처럼 보인다. 페이머니는 실제 연결 행이라 되살리고(은행 계좌와 같다),
         * 대출·카드는 키만 있는 그림자 행이라 지운다(실체는 tbl_loan/tbl_card 에 동기화가 다시 만든다).
         * excludedSeen 에서도 빼야 아래 제외 루프가 "이미 기록됨"으로 오판하지 않는다.
         */
        for (Map.Entry<Long, LinkProgress.PreviewEntry> preview : progress.previewEntries().entrySet()) {
            LinkProgress.PreviewEntry entry = preview.getValue();
            if (excludedIds.contains(preview.getKey()) || !excludedSeen.contains(entry.key())) {
                continue;
            }
            if ("PAYMONEY".equals(entry.accountType())) {
                mapper.reactivate(exclusionRow(userId, progress.getConnectionId(), entry, now));
            } else {
                mapper.deleteInactiveByHash(userId, entry.key());
            }
            excludedSeen.remove(entry.key());
        }
        for (Long previewId : excludedIds) {
            LinkProgress.PreviewEntry entry = previewId == null ? null : progress.previewOf(previewId);
            if (entry == null || !excludedSeen.add(entry.key())) {
                continue;
            }
            recordExclusion(userId, progress.getConnectionId(), entry, now);
        }

        if (linked == 0 && !hasDirectAssets) {
            throw new BusinessException("EXTERNAL_API_ERROR", "연결된 계좌가 없어요.");
        }
        return LinkResultDto.builder().linkedCount(linked).directAssetsPending(hasDirectAssets).build();
    }

    /**
     * 대출·페이머니·카드 제외 행(#467). 행이 이미 있으면(이전에 연동했던 상품) 새로 만들지 않고 끈다.
     * reactivate 가 먼저 켜고 deactivateByHash 가 끄는 순서라 결과는 항상 is_active=0 이다.
     */
    private void recordExclusion(long userId, String connectionId, LinkProgress.PreviewEntry entry,
                                 LocalDateTime now) {
        ConnectedAccount row = exclusionRow(userId, connectionId, entry, now);
        if (mapper.reactivate(row) == 0) {
            mapper.insert(row);
        }
        mapper.deactivateByHash(userId, entry.key());
    }

    private ConnectedAccount exclusionRow(long userId, String connectionId, LinkProgress.PreviewEntry entry,
                                          LocalDateTime now) {
        return ConnectedAccount.builder()
                .userId(userId)
                .codefConnectedId(connectionId)
                .bankCode(entry.bankCode())
                .bankName(entry.bankName())
                .accountName(entry.accountName())
                .accountNoEncrypted(entry.key())
                .accountType(entry.accountType())
                .balance(BigDecimal.ZERO)
                .syncStatus(SyncStatus.NORMAL.name())
                .expiresAt(now.plusDays(CONSENT_VALID_DAYS))
                .build();
    }

    /**
     * link() 안에서 선택/제외 두 경로가 똑같이 쓰는 행 조립. 상태(active 여부)는 호출부가 정한다.
     *
     * ⚠ lastSyncAt 은 여기서 채우지 않는다(연동 시각으로 세팅하지 않는다). 이 값은 스키마 주석대로
     *   "마지막 동기화 *성공* 시각"이고, FinancialSyncServiceImpl.buildMonthlyFetchCursor() 가
     *   null 여부로 "거래내역을 한 번이라도 동기화했는지"를 판단한다 — 연동 직후 첫 sync() 는 이
     *   값을 읽어 null 이면 전체 이력을, null 이 아니면 그 달부터만 증분으로 받아온다. 여기서
     *   연동 시각을 미리 채우면 거래를 한 건도 가져오기 전인데도 "이미 동기화됨"으로 오판해,
     *   연동 이전 달의 거래(급여 등 은행 입금 포함)를 영원히 못 가져온다(#389). 실제 동기화 완료는
     *   FinancialSyncServiceImpl.upsertBankAccount() 가 채운다.
     */
    private ConnectedAccount buildRow(long userId, LinkProgress progress, FinancialAccountDto account,
                                      String hash, LocalDateTime now) {
        return ConnectedAccount.builder()
                .userId(userId)
                .codefConnectedId(progress.getConnectionId())
                .bankCode(account.getOrganization())
                .bankName(bankName(account))
                .accountName(account.getAccountName())
                .accountNoEncrypted(hash)
                .accountNoMasked(accountNumbers.mask(account.getAccountNo()))
                .accountType(accountType(account))
                .depositTypeCode(account.getDepositTypeCode())
                .balance(account.getBalance() == null ? BigDecimal.ZERO : account.getBalance())
                .syncStatus(SyncStatus.NORMAL.name())
                .expiresAt(now.plusDays(CONSENT_VALID_DAYS))
                .build();
    }

    /* ── 연결 계좌 관리 ─────────────────────────────────── */

    @Transactional(readOnly = true)
    public ConnectedAccountListDto connectedAccounts(long userId) {
        List<ConnectedAccount> activeAccounts = mapper.findActiveByUser(userId);
        Set<String> canonicalSecuritiesInstitutions = activeAccounts.stream()
                .filter(AccountLinkService::isCanonicalSecuritiesAccount)
                .map(ConnectedAccount::getBankName)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> canonicalDepositKeys = activeAccounts.stream()
                .filter(AccountLinkService::isCanonicalDepositAccount)
                .map(AccountLinkService::accountIdentity)
                .collect(java.util.stream.Collectors.toSet());
        List<ConnectedAccountDto> accounts = new java.util.ArrayList<>(activeAccounts.stream()
                .filter(row -> !isLegacySecuritiesShadow(row, canonicalSecuritiesInstitutions))
                .filter(row -> !isLegacyDepositShadow(row, canonicalDepositKeys))
                .map(row -> {
                    String bankCode = displayBankCode(row);
                    return ConnectedAccountDto.builder()
                        .accountId(row.getId())
                        .bankCode(bankCode)
                        .bankName(row.getBankName())
                        .shortLabel(catalog.shortLabelOf(bankCode))
                        .accountName(row.getAccountName())
                        .accountNoMasked(row.getAccountNoMasked())
                        .accountType(row.getAccountType())
                        .balance(row.getBalance())
                        .syncStatus(row.getSyncStatus())
                        .lastSyncAt(text(row.getLastSyncAt()))
                        .syncFailReason(row.getSyncFailReason())
                        .expiresAt(text(row.getExpiresAt()))
                        .build();
                })
                .toList());
        accounts.addAll(loanMapper.findByUser(userId).stream()
                .map(this::loanAsConnectedAccount)
                .toList());
        accounts.addAll(cardMapper.findByUser(userId).stream()
                .map(this::cardAsConnectedAccount)
                .toList());

        /*
         * 자동 동기화는 FinancialSyncBatchScheduler 가 fixedDelay 간격(financial.sync.batch.fixed-delay-ms,
         * 현재 30분)으로 돌린다. 예전에는 여기서 "매일 18시" 를 하드코딩했는데 그런 배치는 존재한 적이
         * 없어 안내가 통째로 거짓이었다(이슈 #199 최종 리뷰). 정확한 다음 틱 시각은 이전 실행의 종료
         * 시점에 달려 있어 알 수 없으므로, 지금부터 한 주기 뒤를 상한으로 안내한다.
         */
        LocalDateTime nextSync = LocalDateTime.now(clock).plus(Duration.ofMillis(batchFixedDelayMs));

        return ConnectedAccountListDto.builder()
                .accounts(accounts)
                .nextAutoSyncAt(text(nextSync))
                .build();
    }

    private static boolean isCanonicalSecuritiesAccount(ConnectedAccount account) {
        return "SECURITIES".equals(account.getAccountType())
                && account.getBankCode() != null && !account.getBankCode().isBlank()
                && account.getAccountName() != null && !account.getAccountName().isBlank()
                && account.getBankName() != null && !account.getBankName().isBlank();
    }

    private static boolean isCanonicalDepositAccount(ConnectedAccount account) {
        return "SAVINGS".equals(account.getAccountType())
                && !isLegacyDepositKey(account)
                && accountIdentity(account) != null;
    }

    private static boolean isLegacyDepositShadow(ConnectedAccount account, Set<String> canonicalDepositKeys) {
        return "SAVINGS".equals(account.getAccountType())
                && isLegacyDepositKey(account)
                && canonicalDepositKeys.contains(accountIdentity(account));
    }

    private static boolean isLegacyDepositKey(ConnectedAccount account) {
        return account.getAccountNoEncrypted() != null
                && account.getAccountNoEncrypted().startsWith("MOCK-DEPOSIT-");
    }

    private static String accountIdentity(ConnectedAccount account) {
        if (account.getBankCode() == null || account.getBankCode().isBlank()
                || account.getAccountNoMasked() == null || account.getAccountNoMasked().isBlank()) {
            return null;
        }
        return account.getBankCode() + "|" + account.getAccountNoMasked();
    }

    /**
     * 화면에 쓸 기관 코드. 저장된 값이 있으면 그대로, 비어 있으면 기관명으로 역추적한다.
     *
     * 목서버가 증권 동기화에 institutionCode 를 주지 않아 `tbl_connected_account.bank_code` 가
     * 빈 채로 저장되는 행이 있다(2026-08-21 확인 — 삼성증권 8행·KB Pay 8행). 코드가 비면
     * 화면이 로고 파일을 못 찾아 약칭 글자 배지로 떨어진다. 대출은 loanAsConnectedAccount()
     * 가 진작부터 같은 폴백을 써서 이 문제를 겪지 않았다 — 같은 장치를 나머지 계좌에도 건다.
     *
     * ⚠ 이건 **표시 전용**이다. 저장값을 바꾸지 않는다. 동기화 쪽에서 채우려면
     *   securitiesAccountKey() 가 institutionCode 로 자연키를 만드는 것을 먼저 봐야 한다 —
     *   거기 값이 생기면 키가 바뀌어 기존 행을 못 찾고 계좌가 중복된다(이슈 #375 와 같은 사고).
     *
     * ⚠ codeOfName 은 이름이 한 글자라도 다르면 null 을 돌려준다. 그래서 저장된 코드가 있으면
     *   언제나 그쪽이 우선이다. 폴백은 빈 값 구제용이지 이름 매칭으로 갈아타는 게 아니다.
     */
    private String displayBankCode(ConnectedAccount account) {
        String stored = account.getBankCode();
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        return catalog.codeOfName(account.getBankName());
    }

    /**
     * bank_code 는 2026-08-20 마이그레이션 이후 동기화분부터 채워진다 — 그 이전에 저장된 대출 행은
     * null 일 수 있어, 그럴 때만 기관명으로 역추적한다(catalog.codeOfName). 정상 경로는 저장된
     * 코드를 그대로 쓰는 쪽이 이름 문자열이 카탈로그와 한 글자라도 다르면 조용히 깨지는
     * codeOfName() 보다 안전하다.
     */
    private ConnectedAccountDto loanAsConnectedAccount(Loan loan) {
        String bankCode = loan.getBankCode() != null ? loan.getBankCode() : catalog.codeOfName(loan.getBankName());
        return ConnectedAccountDto.builder()
                .accountId(ManagedAccountId.ofLoan(loan.getId()))
                .bankCode(bankCode)
                .bankName(loan.getBankName())
                .shortLabel(catalog.shortLabelOf(bankCode))
                .accountName(loan.getLoanType())
                .accountNoMasked(loan.getLoanNoMasked())
                .accountType("LOAN")
                .balance(loan.getBalance())
                .syncStatus("NORMAL")
                .manageable(false)
                .build();
    }

    /**
     * 카드는 잔액이 없다 — 자산이 아니라 **거래의 출처**다(#467).
     *
     * 그래서 자산 탭에는 들어가지 않고 이 관리 목록에만 나온다. 화면 제목이 「내 계좌와 카드」이고
     * 해제 문구가 "거래 수집을 멈춰요" 인 것과 같은 뜻이다 — 장부에 찍힌 카드 승인 내역, 고정지출 탐지,
     * 미션 판정이 전부 여기서 나오므로 그 출처를 확인하고 끊을 자리가 있어야 한다.
     * 잔액 칸에는 화면이 카드 종류(신용/체크)를 대신 그린다.
     */
    private ConnectedAccountDto cardAsConnectedAccount(Card card) {
        String bankCode = card.getInstitutionCode() != null
                ? card.getInstitutionCode()
                : catalog.codeOfName(card.getInstitutionName());
        return ConnectedAccountDto.builder()
                .accountId(ManagedAccountId.ofCard(card.getId()))
                .bankCode(bankCode)
                .bankName(card.getInstitutionName())
                .shortLabel(catalog.shortLabelOf(bankCode))
                .accountName(card.getProductName())
                .accountNoMasked(card.getCardNoMasked())
                .accountType("CARD")
                .cardTypeCode(card.getCardTypeCode())
                .syncStatus("NORMAL")
                .lastSyncAt(text(card.getLastSyncAt()))
                /* 대출과 같다 — 기관 단위로만 다시 긁어오므로 개별 재조회가 없다. */
                .manageable(false)
                .build();
    }

    /** 식별자 없이 저장된 과거 MOCK-SECURITIES 행은 같은 기관의 실제 연결 행이 있을 때만 숨긴다. */
    private static boolean isLegacySecuritiesShadow(ConnectedAccount account,
                                                     Set<String> canonicalInstitutions) {
        return "SECURITIES".equals(account.getAccountType())
                && (account.getBankCode() == null || account.getBankCode().isBlank())
                && (account.getAccountName() == null || account.getAccountName().isBlank())
                && account.getBankName() != null
                && canonicalInstitutions.contains(account.getBankName());
    }

    @Transactional
    public DisconnectResultDto disconnect(long userId, long accountId) {
        switch (ManagedAccountId.kindOf(accountId)) {
            case LOAN:
                disconnectLoan(userId, ManagedAccountId.rawId(accountId));
                break;
            case CARD:
                disconnectCard(userId, ManagedAccountId.rawId(accountId));
                break;
            default:
                if (mapper.deactivate(accountId, userId) == 0) {
                    throw new BusinessException("NOT_FOUND", "연결된 계좌를 찾을 수 없어요.");
                }
        }
        return DisconnectResultDto.builder().accountId(accountId).disconnected(true).build();
    }

    /**
     * 대출 연결 해제(#467). 관리 목록의 대출 행은 tbl_loan 을 `-id` 로 꾸민 것이라(loanAsConnectedAccount)
     * tbl_connected_account 를 끌 수 없다 — 예전엔 그래서 400 이었다.
     *
     * 은행 계좌와 같은 원칙이다 — **해제 전까지 모은 이력은 남기고 수집만 멈춘다.** tbl_loan 에는 is_active 가
     * 없으니 행을 지우지 않고, 같은 키의 제외 행(is_active=0)을 "숨김 플래그"로 쓴다. LoanMapper 의 조회
     * 쿼리가 이 키를 가진 대출을 빼고 읽어(관리 목록·자산 합계·동기화 범위 전부), 동기화는 같은 키로 건너뛴다.
     * 다시 연동하면 link() 가 제외 행을 지워 행과 거래가 그대로 되살아난다 — 지웠다 다시 받으면 거래가 새
     * loan id 로 또 들어와 장부에 두 번 보였을 것이다.
     */
    private void disconnectLoan(long userId, long loanId) {
        Loan loan = loanMapper.findByUser(userId).stream()
                .filter(item -> item.getId() != null && item.getId() == loanId)
                .findFirst()
                .orElseThrow(() -> new BusinessException("NOT_FOUND", "연결된 계좌를 찾을 수 없어요."));
        String bankCode = loan.getBankCode() != null ? loan.getBankCode() : catalog.codeOfName(loan.getBankName());
        recordExclusion(userId, null,
                new LinkProgress.PreviewEntry(loan.getLoanNoEncrypted(), bankCode, loan.getBankName(),
                        loan.getLoanType(), "LOAN"),
                LocalDateTime.now(clock));
    }

    /**
     * 카드 연결 해제(#467). 대출과 같은 방식이다 — 행·거래는 그대로 두고 수집만 멈춘다.
     *
     * `tbl_card` 에도 is_active 가 없어 제외 행(is_active=0)을 숨김 플래그로 쓴다. 키를 마스킹 카드번호로
     * 만드는 이유는 FinancialSyncServiceImpl.cardExclusionKey 주석 참고 — 그 함수와 **반드시 같은 값**이어야
     * 동기화가 이 카드를 알아보고 건너뛴다.
     */
    private void disconnectCard(long userId, long cardId) {
        Card card = cardMapper.findByUser(userId).stream()
                .filter(item -> item.getId() != null && item.getId() == cardId)
                .findFirst()
                .orElseThrow(() -> new BusinessException("NOT_FOUND", "연결된 카드를 찾을 수 없어요."));
        String bankCode = card.getInstitutionCode() != null
                ? card.getInstitutionCode()
                : catalog.codeOfName(card.getInstitutionName());
        recordExclusion(userId, null,
                new LinkProgress.PreviewEntry(
                        FinancialSyncServiceImpl.cardExclusionKey(card.getCardNoMasked()),
                        bankCode, card.getInstitutionName(), card.getProductName(), "CARD"),
                LocalDateTime.now(clock));
    }

    /**
     * 계좌 하나를 다시 조회한다.
     *
     * ⚠ 트랜잭션을 걸지 않는다. 외부 API 를 부르는 동안 커넥션을 붙잡고 있으면 풀이 마른다
     * (이 클래스의 원칙). 갱신은 계좌 단위로 독립적이라 한 덩어리로 묶을 이유도 없다.
     */
    public ResyncResultDto resync(long userId, long accountId) {
        if (ManagedAccountId.kindOf(accountId) != ManagedAccountId.Kind.ACCOUNT) {
            /* 대출·카드 표시 행(#467). 기관 단위로만 다시 긁어오므로 「전체 즉시 조회」가 맞는 길이다. */
            String label = ManagedAccountId.kindOf(accountId) == ManagedAccountId.Kind.CARD ? "카드" : "대출";
            throw new BusinessException("NOT_SUPPORTED",
                    label + "는 개별로 다시 조회할 수 없어요. 「전체 즉시 조회」를 써 주세요.");
        }
        ConnectedAccount account = mapper.findByIdAndUser(accountId, userId);
        if (account == null) {
            throw new BusinessException("NOT_FOUND", "연결된 계좌를 찾을 수 없어요.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        SyncStatus status = syncInstitution(userId, account.getBankCode(), List.of(account), now);
        return ResyncResultDto.builder()
                .accountId(accountId)
                .syncStatus(status.name())
                .lastSyncAt(text(now))
                .build();
    }

    /**
     * 연결된 모든 계좌를 다시 조회한다 (AC_02_03).
     *
     * ⚠ **공급자를 실제로 부른다.** 예전에는 DB 에 `NORMAL` 과 현재시각만 써서,
     *   조회한 적이 없는데도 "방금 동기화됨" 이 뜨고 잔액은 그대로였다.
     * ⚠ 트랜잭션을 걸지 않는다 — 기관 수만큼 외부 호출이 이어지므로 커넥션을 붙잡으면 안 된다.
     */
    public RefreshResultDto refresh(long userId) {
        List<ConnectedAccount> accounts = mapper.findActiveByUser(userId);
        if (accounts.isEmpty()) {
            throw new BusinessException("NOT_FOUND", "연결된 계좌가 없어요.");
        }
        LocalDateTime now = LocalDateTime.now(clock);

        /* 기관 단위로 묶는다. 조회는 기관 한 번에 계좌 여럿을 돌려주므로 계좌마다 부를 이유가 없다. */
        Map<String, List<ConnectedAccount>> byBank = new LinkedHashMap<>();
        for (ConnectedAccount account : accounts) {
            byBank.computeIfAbsent(account.getBankCode(), code -> new ArrayList<>()).add(account);
        }

        List<RefreshInstitutionDto> rows = new ArrayList<>();
        for (Map.Entry<String, List<ConnectedAccount>> entry : byBank.entrySet()) {
            List<ConnectedAccount> owned = entry.getValue();
            SyncStatus status = syncInstitution(userId, entry.getKey(), owned, now);

            /*
             * 기관 행의 금액은 **그 기관 계좌의 합계**다.
             * 예전에는 computeIfAbsent 로 첫 계좌만 담아, 같은 은행의 두 번째 계좌부터 버려졌다.
             */
            BigDecimal total = BigDecimal.ZERO;
            for (ConnectedAccount account : owned) {
                total = total.add(account.getBalance() == null ? BigDecimal.ZERO : account.getBalance());
            }

            rows.add(RefreshInstitutionDto.builder()
                    .bankCode(entry.getKey())
                    .bankName(owned.get(0).getBankName())
                    .shortLabel(catalog.shortLabelOf(entry.getKey()))
                    /*
                     * 새 거래 건수는 거래내역 수집(후속 이슈)이 붙어야 실제 값이 나온다.
                     * 지금은 0 으로 두고 화면이 "새 거래 없음" 을 그리게 한다 — 지어내지 않는다.
                     */
                    .newTransactionCount(0)
                    .balance(total)
                    .syncStatus(status.name())
                    .build());
        }

        return RefreshResultDto.builder()
                .lastSyncAt(text(now))
                .institutionCount(rows.size())
                .cooldownSeconds(REFRESH_COOLDOWN_SECONDS)
                .institutions(List.copyOf(rows))
                .build();
    }

    /**
     * 한 기관을 조회해 그 기관 계좌들의 잔액을 갱신한다. 갱신된 잔액은 인자로 받은 객체에도 반영한다.
     *
     * 연결이 살아 있지 않으면 **거짓으로 성공을 쓰지 않는다** — 계좌를 `NEED_RECONNECT` 로 표시해
     * 사용자가 재연동할 수 있게 한다. 그 밖의 실패는 `FAILED` 와 사유를 남긴다.
     *
     * @return 이 기관의 조회 결과 상태
     */
    private SyncStatus syncInstitution(long userId, String bankCode,
                                       List<ConnectedAccount> owned, LocalDateTime now) {
        String connectionId = owned.get(0).getCodefConnectedId();
        Map<String, BigDecimal> fresh = new LinkedHashMap<>();
        try {
            for (FinancialAccountDto found : clients.forUser(userId).fetchAccounts(userId, connectionId, bankCode)) {
                fresh.put(accountNumbers.hash(bankCode, found.getAccountNo()),
                        found.getBalance() == null ? BigDecimal.ZERO : found.getBalance());
            }
        } catch (BusinessException e) {
            /*
             * 연결이 끊겼거나 만료된 경우와 일시적 실패를 구분한다.
             * 전자는 사용자가 재연동해야 풀리고, 후자는 다시 눌러보면 된다.
             */
            SyncStatus status = "CONNECTION_NOT_FOUND".equals(e.getCode())
                    || "TOKEN_EXPIRED".equals(e.getCode())
                    ? SyncStatus.NEED_RECONNECT
                    : SyncStatus.FAILED;
            for (ConnectedAccount account : owned) {
                mapper.updateSync(account.getId(), userId, status.name(), now, e.getMessage());
                if (status == SyncStatus.NEED_RECONNECT) {
                    events.publishEvent(reconnectNotification(userId, account));
                }
            }
            return status;
        }

        /*
         * ⚠ 계좌 하나라도 재연동 대상이 되면 기관 결과도 NEED_RECONNECT 다 (이슈 #70).
         *   예전에는 이 아래에서 무조건 NORMAL 을 돌려줘, DB·알림은 "재연동 필요" 인데
         *   화면만 "정상" 으로 보이는 모순이 있었다.
         */
        SyncStatus aggregate = SyncStatus.NORMAL;
        for (ConnectedAccount account : owned) {
            BigDecimal balance = fresh.get(account.getAccountNoEncrypted());
            if (balance == null) {
                /* 기관에서 사라진 계좌(해지 등). 잔액을 지어내지 않고 재연동 대상으로 둔다. */
                mapper.updateSync(account.getId(), userId, SyncStatus.NEED_RECONNECT.name(), now,
                        "금융기관에서 이 계좌를 찾지 못했어요.");
                events.publishEvent(reconnectNotification(userId, account));
                aggregate = SyncStatus.NEED_RECONNECT;
                continue;
            }
            if ("SECURITIES".equals(account.getAccountType())) {
                /* /assets/accounts 의 증권 balance 는 현금잔액뿐이다. 보유주식 평가액을 포함한
                   연결 계좌 잔액은 FinancialSyncService 가 계산하므로 여기서 덮어쓰지 않는다. */
                mapper.updateSync(account.getId(), userId, SyncStatus.NORMAL.name(), now, null);
                continue;
            }
            /* 살아 있는 계좌는 그대로 갱신한다 — 하나가 실패했다고 나머지를 버리지 않는다 */
            mapper.updateSynced(account.getId(), userId, balance, now);
            account.setBalance(balance);
        }
        return aggregate;
    }

    /**
     * 재연동이 필요하다는 알림 요청.
     *
     * 문구 자체는 {@link NotificationType} 이 소유한다 — 여기서는 은행명만 넘긴다 (이슈 #68).
     * 딥링크 경로는 계좌 화면을 아는 이 모듈이 만든다.
     */
    private static NotificationRequestedEvent reconnectNotification(long userId,
                                                                    ConnectedAccount account) {
        return new NotificationRequestedEvent(
                userId,
                NotificationType.ACCOUNT_RECONNECT,
                Map.of("bankName", account.getBankName()),
                "/asset/accounts/" + account.getId() + "/reconnect");
    }

    /** 금융정보 동의 철회 시 모든 연동을 해제한다 (이슈 #13 이 남긴 TODO(#12)). */
    @Transactional
    public int disconnectAll(long userId) {
        /* 동의를 철회했는데 connectedId 가 살아 있으면 배치 동기화가 계속 CODEF 를 부른다. */
        codefConnections.deactivateAll(userId);
        return mapper.deactivateAllByUser(userId);
    }

    /* ── 내부 ──────────────────────────────────────────── */

    /**
     * 기관코드가 비어 있으면 여기서 막는다.
     *
     * ⚠ 예전에는 리스트가 비었는지만 봤다. null 원소가 통과하면 `catalog.nameOf(null)` 이 조용히
     * null 을 돌려주고, 진행 상태에 `null` 키가 들어가 `nextWaiting()` 이 그 null 을 반환한다.
     * 호출부는 null 을 "할 일 없음" 으로 읽으므로 **폴링이 영원히 끝나지 않는다.**
     */
    private static void requireOrganizations(List<String> organizations) {
        if (organizations.isEmpty() || organizations.stream().anyMatch(
                code -> code == null || code.isBlank())) {
            throw new BusinessException("EXTERNAL_API_ERROR", "연결할 금융기관을 선택해 주세요.");
        }
    }

    private void rememberProgress(long userId, ConnectionResult result, List<String> organizations) {
        List<String> success = result.getSuccessOrganizations() == null
                ? organizations
                : result.getSuccessOrganizations();
        List<String> failed = result.getFailedOrganizations() == null
                ? List.of()
                : result.getFailedOrganizations();

        /*
         * 성공한 곳과 **실패한 곳을 모두** 목록에 올린다.
         * ⚠ 예전에는 성공한 곳만 올렸고, `mark()` 는 목록에 없는 기관을 조용히 무시한다.
         *   그래서 인증에 실패한 기관이 진행 화면에서 통째로 사라졌고,
         *   전부 실패하면 목록이 비어 `done=true` 인데 `percent=0` 인 모순 응답이 나갔다.
         * 요청한 기관 전체를 올리지는 않는다 — 간편인증은 첫 기관만 처리하므로
         *   시도하지도 않은 기관이 영영 대기 상태로 남는다.
         */
        Map<String, String> targets = new LinkedHashMap<>();
        for (String code : success) {
            targets.put(code, catalog.nameOf(code));
        }
        for (String code : failed) {
            targets.put(code, catalog.nameOf(code));
        }

        LinkProgress progress = new LinkProgress(
                userId, result.getConnectionId(), targets, clock.instant());

        /* 인증 단계에서 이미 실패한 기관은 조회를 시도하지 않고 바로 실패로 표시한다. */
        for (String code : failed) {
            progress.mark(code, ProgressStatus.FAILED);
        }
        progressStore.put(progress);
    }

    private LinkProgressDto toProgressDto(LinkProgress progress) {
        List<ProgressRowDto> rows = progress.getStatuses().entrySet().stream()
                .map(entry -> ProgressRowDto.builder()
                        .code(entry.getKey())
                        .name(progress.nameOf(entry.getKey()))
                        .status(entry.getValue().name())
                        .build())
                .toList();
        return LinkProgressDto.builder()
                .percent(progress.percent())
                .done(progress.isDone())
                .institutions(rows)
                .build();
    }

    private static ConnectionResponseDto toResponse(ConnectionResult result) {
        return ConnectionResponseDto.builder()
                .connectionId(result.getConnectionId())
                .status(result.getStatus().name())
                .expiresInSeconds(result.getExpiresInSeconds())
                .needsExtraAuth(result.isNeedsExtraAuth())
                .extraAuthType(result.getExtraAuthType())
                .build();
    }

    private String bankName(FinancialAccountDto account) {
        return account.getBankName() != null
                ? account.getBankName()
                : catalog.nameOf(account.getOrganization());
    }

    /**
     * 계좌 종류 판정.
     *
     * 공급자가 이미 판정해 줬으면(목서버) 그 값을 쓰고, 코드만 왔으면(실 CODEF) 유도한다.
     * CODEF 예금 구분 `11` = 보통예금(입출금), 그 외 적립·거치식은 예적금으로 본다.
     */
    private static String accountType(FinancialAccountDto account) {
        String given = account.getAccountType();
        if ("DEMAND_DEPOSIT".equals(given) || "SAVINGS".equals(given)) {
            return given;
        }
        return "11".equals(account.getDepositTypeCode()) || account.getDepositTypeCode() == null
                ? "DEMAND_DEPOSIT"
                : "SAVINGS";
    }

    private static String providerName(String code) {
        return switch (code) {
            case "KAKAO" -> "카카오톡";
            case "PASS" -> "PASS";
            case "NAVER" -> "네이버";
            default -> code;
        };
    }

    private static String text(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}
