package com.kb.tangtang.account.client;

import com.kb.tangtang.account.client.dto.ConnectionRequest;
import com.kb.tangtang.account.client.dto.ConnectionResult;
import com.kb.tangtang.account.client.dto.CredentialDto;
import com.kb.tangtang.account.client.dto.FinancialAccountDto;
import com.kb.tangtang.account.client.dto.IdentityDto;
import com.kb.tangtang.account.client.dto.TwoWayInfo;
import com.kb.tangtang.account.domain.AuthMethod;
import com.kb.tangtang.account.domain.AuthStatus;
import com.kb.tangtang.common.exception.BusinessException;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실 CODEF 구현. 발표의 라이브 데모 1컷이 이 구현으로 돈다.
 *
 * 인증 경로는 **기관 로그인(아이디·비밀번호)** 하나다. CODEF 간편인증은 2-way 추가 통신
 * (인증 요청 → 사용자 승인 대기 → 재요청)이 필요해 데모 리스크가 크다고 판단했다
 * (DECISIONS.md 2026-08-05 결정 3번).
 *
 * 확인된 응답 구조 (2026-08-05 실계좌 테스트):
 *   · POST /v1/account/create      → data.connectedId + successList / errorList (기관 단위)
 *   · POST .../account/account-list → data.resDepositTrust[] 등 5분류
 *   · resAccountDisplay 는 **마스킹되지 않은 전체 계좌번호**다
 *   · CODEF 는 응답 본문을 URL 인코딩해 내려준다 (SDK 가 디코딩해 준다)
 *
 * ⚠ 자격증명은 이 클래스 밖으로 나가지 않는다. 로그로 찍지 않고 필드로 보관하지 않는다.
 *   비밀번호는 CodefPasswordCipher 로 RSA 암호화한 뒤에만 전송한다.
 *
 * 카드사(03xx)도 같은 통로로 계정을 등록한다(businessType=CD). 카드는 "계좌" 가 아니라
 * {@link #fetchAccounts} 가 빈 목록을 주고, 보유카드·승인내역은 동기화(CodefFinancialSyncClient)가 가져온다.
 */
@Log4j2
public class CodefFinancialDataClient implements FinancialDataClient {

    /** 추가 인증이 필요하다는 CODEF 응답 코드. 이게 오면 사용자의 앱 승인을 기다린다. */
    private static final String CONTINUE_TWO_WAY = CodefHttpClient.CONTINUE_TWO_WAY;

    /** 간편인증 승인 제한 시간. 화면의 남은 시간 표시와 맞춘 값이다. */
    private static final int SIMPLE_AUTH_EXPIRES_SECONDS = 300;

    private final CodefHttpClient http;
    private final String baseUrl;
    private final CodefPasswordCipher passwordCipher;
    /**
     * 메모리 세션이 없을 때(서버 재시작 뒤 「전체 즉시 조회」) connectedId 를 찾는 곳.
     * null 이면 세션만 본다 — 저장소 없이 만든 테스트용 인스턴스.
     */
    private final CodefConnectionStore connectionStore;

    /** 저장소 없이 쓰는 생성자(테스트). */
    public CodefFinancialDataClient(RestTemplate restTemplate, String baseUrl,
                                    String clientId, String clientSecret,
                                    CodefPasswordCipher passwordCipher) {
        this(new CodefHttpClient(restTemplate, clientId, clientSecret), baseUrl, passwordCipher, null);
    }

    public CodefFinancialDataClient(CodefHttpClient http, String baseUrl,
                                    CodefPasswordCipher passwordCipher,
                                    CodefConnectionStore connectionStore) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.passwordCipher = passwordCipher;
        this.connectionStore = connectionStore;
    }

    /**
     * 간편인증 수단 → CODEF `loginTypeLevel`.
     * 1 카카오톡 · 5 통신사(PASS) · 6 네이버 (CODEF 개발가이드)
     */
    private static final Map<String, String> LOGIN_TYPE_LEVELS =
            Map.of("KAKAO", "1", "PASS", "5", "NAVER", "6");

    /**
     * 진행 중인 연결. **간편인증은 1차 요청 시점에 connectedId 가 없다** —
     * 사용자가 앱에서 승인한 뒤 2차 요청에서야 발급된다. 그래서 우리 식별자를 먼저 만들고
     * 그 아래에 2-way 세션과 나중에 받은 connectedId 를 묶어둔다.
     *
     * 기관 로그인도 같은 통로를 쓴다 — 바깥(서비스·프론트)에서 두 경로가 구분되지 않아야 한다.
     */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 세션 보관 기한. 인증 제한 시간(5분)에 조회 시간을 얹은 값이다. */
    private static final long SESSION_TTL_SECONDS = 30 * 60L;

    /**
     * 한 번의 연결 시도. 승인 전에는 connectedId 가 null 이다.
     *
     * ⚠ **본인 정보(이름·생년월일·통신사·휴대폰)는 여기 두지 않는다.** 2차 요청에 필요한 것은
     *   `twoWayInfo` 와 `organization` 뿐이라 보관할 이유가 없고, 힙에 남기면 덤프 한 번에 새어 나간다.
     *   (2026-08-05 리뷰 지적 — 쓰이지도 않는 PII 를 무기한 들고 있었다)
     */
    private static final class Session {
        private String connectedId;
        private TwoWayInfo twoWayInfo;
        private String organization;
        private AuthStatus status = AuthStatus.PENDING;
        private final Instant createdAt = Instant.now();
    }

    /**
     * 실 CODEF 로 제공할 인증 수단.
     *
     * **은행권은 간편인증을 쓸 수 없다.** 2026-08-05 실호출로 확인했다 —
     * IBK(0003)·카카오뱅크(0090) 모두 `CF-11021`("기관코드와 로그인 방식을 확인하세요")로 거부됐다.
     * CODEF 계정등록은 기관마다 허용하는 로그인 방식이 정해져 있고, 이 두 곳은 간편인증이 아니다.
     *
     * 그래서 **되는 것만 알린다.** 화면에 띄워놓고 누르면 실패하는 선택지를 두지 않는다.
     * 간편인증 2-way 구현(`startSimpleAuth`·`continueBody`·`getAuthStatus`)은 지우지 않았다 —
     * CODEF 가 지원 기관을 넓히면 이 목록에 `SIMPLE_AUTH` 를 되돌리는 것만으로 켜진다.
     * 목 모드는 그대로 간편인증을 제공한다(시연 기준).
     */
    @Override
    public List<AuthMethod> supportedAuthMethods() {
        return List.of(AuthMethod.INSTITUTION_LOGIN);
    }

    @Override
    public List<String> simpleAuthProviders() {
        return List.of("KAKAO", "PASS", "NAVER");
    }

    /**
     * 은행 20곳 + 우리 카탈로그의 카드사 6곳(CodefOrganizations 참고).
     * 증권·보험은 상품 경로가 달라 다루지 않는다(증권은 범위 밖으로 결정, 2026-10-14).
     */
    private static final Set<String> CODEF_ORGANIZATIONS = CodefOrganizations.supported();

    @Override
    public Set<String> supportedOrganizations() {
        return CODEF_ORGANIZATIONS;
    }

    /**
     * 승인된 연결의 실제 CODEF connectedId. AccountLinkService 가 이 값을 DB 에 (암호화해) 남긴다.
     * 승인 전이거나 모르는 연결이면 비어 있다.
     */
    @Override
    public Optional<String> connectedIdOf(String connectionId) {
        Session session = connectionId == null ? null : sessions.get(connectionId);
        return session == null ? Optional.empty() : Optional.ofNullable(session.connectedId);
    }

    @Override
    public boolean requiresIdentity() {
        /* 실 CODEF 간편인증은 "누구에게 푸시를 보낼지" 를 알아야 한다. */
        return true;
    }

    @Override
    public ConnectionResult createConnection(ConnectionRequest request) {
        if (request.getAuthMethod() == AuthMethod.SIMPLE_AUTH) {
            return startSimpleAuth(request);
        }
        return loginWithCredentials(request);
    }

    /**
     * 간편인증 1차 요청.
     *
     * 정상 흐름은 **여기서 끝나지 않는다** — CODEF 가 `CF-03002`(추가 인증 필요)를 돌려주고,
     * 사용자가 인증 앱에서 승인한 뒤 2차 요청(`continueSimpleAuth`)에서 connectedId 가 나온다.
     * 그래서 우리 식별자만 먼저 발급하고 PENDING 으로 돌려준다.
     *
     * ⚠ 기관을 여러 곳 고르면 CODEF 는 기관마다 별도 인증을 요구한다.
     *   지금은 **첫 기관 하나만** 진행한다. 여러 기관 동시 간편인증은 실호출로 동작을 확인한 뒤 넓힌다.
     */
    private ConnectionResult startSimpleAuth(ConnectionRequest request) {
        List<String> organizations = request.getOrganizations();
        if (organizations == null || organizations.isEmpty()) {
            throw new BusinessException("EXTERNAL_API_ERROR", "연결할 금융기관을 선택해 주세요.");
        }
        String level = LOGIN_TYPE_LEVELS.get(request.getProvider());
        if (level == null) {
            throw new BusinessException("INVALID_CREDENTIALS", "지원하지 않는 인증 수단이에요.");
        }
        IdentityDto identity = request.getIdentity();
        if (identity == null || identity.getUserName() == null || identity.getPhoneNo() == null) {
            throw new BusinessException("INVALID_CREDENTIALS", "본인 정보를 입력해 주세요.");
        }

        String organization = organizations.get(0);
        Map<String, Object> response = post(baseUrl + "/v1/account/create",
                Map.of("accountList", List.of(simpleAuthRow(organization, level, identity))));

        String connectionId = "codef-" + UUID.randomUUID();
        Session session = new Session();
        session.organization = organization;
        evictExpiredSessions();
        sessions.put(connectionId, session);

        applyResponse(session, response);

        return ConnectionResult.builder()
                .connectionId(connectionId)
                .status(session.status)
                .expiresInSeconds(SIMPLE_AUTH_EXPIRES_SECONDS)
                .needsExtraAuth(false)
                .extraAuthType(null)
                .successOrganizations(List.of(organization))
                .failedOrganizations(List.of())
                .build();
    }

    /** 기관 로그인(아이디·비밀번호). create 응답이 곧 결과라 승인 대기가 없다. */
    private ConnectionResult loginWithCredentials(ConnectionRequest request) {
        List<CredentialDto> credentials = request.getCredentials();
        if (credentials == null || credentials.isEmpty()) {
            throw new BusinessException("INVALID_CREDENTIALS", "금융기관 로그인 정보가 필요해요.");
        }

        List<Map<String, String>> accountList = new ArrayList<>();
        for (CredentialDto credential : credentials) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("countryCode", "KR");
            /* 은행 BK · 카드 CD. 한 요청에 섞여도 CODEF 는 하나의 connectedId 아래 묶는다. */
            row.put("businessType", CodefOrganizations.businessTypeOf(credential.getOrganization()));
            row.put("clientType", "P");
            /* 카드사는 우리 코드와 CODEF 코드가 다르다 — 나가는 순간 바꾼다(CodefOrganizations). */
            row.put("organization", CodefOrganizations.toCodef(credential.getOrganization()));
            row.put("loginType", "1");
            row.put("id", credential.getId());
            /*
             * ⚠ CODEF 는 평문 비밀번호를 받지 않는다. 공개키로 암호화한 값만 유효하다.
             * 여기서 암호화하고, 평문은 이 줄 밖으로 나가지 않는다.
             */
            row.put("password", passwordCipher.encrypt(credential.getPassword()));
            accountList.add(row);
        }

        Map<String, Object> response =
                post(baseUrl + "/v1/account/create", Map.of("accountList", accountList));
        Map<String, Object> data = asMap(response.get("data"));

        String connectedId = string(data.get("connectedId"));
        if (connectedId == null || connectedId.isBlank()) {
            throw new BusinessException("INVALID_CREDENTIALS",
                    "아이디 또는 비밀번호가 일치하지 않아요.");
        }

        /* 간편인증과 같은 통로를 쓴다 — 바깥에서 두 경로가 구분되지 않아야 한다. */
        String connectionId = "codef-" + UUID.randomUUID();
        Session session = new Session();
        session.connectedId = connectedId;
        session.status = AuthStatus.APPROVED;
        session.organization = credentials.get(0).getOrganization();
        evictExpiredSessions();
        sessions.put(connectionId, session);

        /* 기관 단위 성공·실패가 배열로 온다. 진행 화면의 기관별 상태가 여기서 나온다. */
        return ConnectionResult.builder()
                .connectionId(connectionId)
                .status(AuthStatus.APPROVED)
                .expiresInSeconds(0)
                .needsExtraAuth(false)
                .extraAuthType(null)
                .successOrganizations(organizations(data.get("successList")))
                .failedOrganizations(organizations(data.get("errorList")))
                .build();
    }

    /**
     * 승인 여부 확인. 프론트가 1초 간격으로 부른다.
     *
     * 간편인증은 여기서 **2차 요청을 시도한다.** 사용자가 아직 앱에서 승인하지 않았으면
     * CODEF 가 다시 `CF-03002` 를 돌려주므로 PENDING 을 유지하고, 승인했으면 connectedId 가 온다.
     * 폴링이 곧 재시도인 셈이라 별도 대기 스레드가 필요 없다.
     */
    @Override
    public AuthStatus getAuthStatus(String connectionId) {
        Session session = session(connectionId);
        if (session.status != AuthStatus.PENDING || session.twoWayInfo == null) {
            return session.status;
        }

        try {
            applyResponse(session, post(baseUrl + "/v1/account/create", continueBody(session)));
        } catch (BusinessException e) {
            /*
             * 폴링 중 CODEF 가 오류를 주면 인증이 끝난 것이다 — 사용자가 거절했거나 세션이 만료됐다.
             * 예외를 그대로 올리면 화면이 "다시 시도" 대신 오류 배너만 띄우고 상태가 멈춘다.
             */
            session.status = AuthStatus.FAILED;
        }
        return session.status;
    }

    /** 2차 요청 본문. 1차에서 받은 twoWayInfo 를 **그대로** 실어야 같은 세션으로 이어진다. */
    private Map<String, Object> continueBody(Session session) {
        TwoWayInfo info = session.twoWayInfo;
        Map<String, Object> twoWay = new LinkedHashMap<>();
        twoWay.put("jobIndex", info.getJobIndex());
        twoWay.put("threadIndex", info.getThreadIndex());
        twoWay.put("jti", info.getJti());
        twoWay.put("twoWayTimestamp", info.getTwoWayTimestamp());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("organization", CodefOrganizations.toCodef(session.organization));
        row.put("simpleAuth", "1");
        row.put("is2Way", true);
        row.put("twoWayInfo", twoWay);

        return Map.of("accountList", List.of(row));
    }

    /**
     * 1차·2차 응답을 세션에 반영한다.
     *
     * `CF-03002` + `continue2Way` 면 아직 승인 전이다 — 2-way 세션을 갱신하고 PENDING 을 유지한다.
     * connectedId 가 오면 승인이다. 둘 다 아니면 실패로 본다.
     */
    private void applyResponse(Session session, Map<String, Object> response) {
        Map<String, Object> result = asMap(response.get("result"));
        Map<String, Object> data = asMap(response.get("data"));
        String code = string(result.get("code"));

        if (CONTINUE_TWO_WAY.equals(code) || Boolean.TRUE.equals(data.get("continue2Way"))) {
            session.twoWayInfo = TwoWayInfo.builder()
                    .jobIndex(number(data.get("jobIndex")))
                    .threadIndex(number(data.get("threadIndex")))
                    .jti(string(data.get("jti")))
                    .twoWayTimestamp(number(data.get("twoWayTimestamp")))
                    .build();
            session.status = AuthStatus.PENDING;
            return;
        }

        String connectedId = string(data.get("connectedId"));
        if (connectedId != null && !connectedId.isBlank()) {
            session.connectedId = connectedId;
            session.status = AuthStatus.APPROVED;
            return;
        }

        /* 사용자가 거절했거나 세션이 만료된 경우. 화면은 "다시 시도" 를 보여준다. */
        session.status = AuthStatus.FAILED;
    }

    /**
     * 화면에서 인증번호를 입력해 제출하는 경로(SMS 등).
     *
     * 간편인증은 사용자가 **앱에서** 승인하므로 이 통로를 쓰지 않는다 — 폴링이 재시도를 겸한다.
     * 기관이 화면 입력형 추가인증을 요구하는 경우는 실호출로 확인한 뒤 붙인다.
     */
    @Override
    public AuthStatus submitExtraAuth(String connectionId, String authCode) {
        return getAuthStatus(connectionId);
    }

    /**
     * 지난 세션을 버린다.
     *
     * 이 빈은 싱글턴이라 지우지 않으면 맵이 WAS 수명 내내 자란다 —
     * 연결 시도 한 번마다 엔트리 하나가 영구히 남았다(2026-08-05 리뷰 지적).
     * 별도 스케줄러를 두지 않고 새 세션을 만들 때 함께 치운다. 진행 상태 저장소와 같은 방식이다.
     */
    private void evictExpiredSessions() {
        Instant deadline = Instant.now().minusSeconds(SESSION_TTL_SECONDS);
        sessions.values().removeIf(session -> session.createdAt.isBefore(deadline));
    }

    private Session session(String connectionId) {
        Session session = connectionId == null ? null : sessions.get(connectionId);
        if (session == null) {
            throw new BusinessException("CONNECTION_NOT_FOUND",
                    "연결 정보를 찾을 수 없어요. 처음부터 다시 시도해 주세요.");
        }
        return session;
    }

    @Override
    public List<FinancialAccountDto> fetchAccounts(long userId, String connectionId, String organization) {
        String connectedId = resolveConnectedId(userId, connectionId, organization);

        /*
         * 카드사는 "계좌" 가 없다. 연결 진행 화면에서 그 기관을 성공으로 넘기기 위해 빈 목록을 준다 —
         * 보유카드는 계좌 선택 화면의 자동 연동 미리보기와 최초 동기화가 CodefFinancialSyncClient 로 가져온다.
         */
        if (CodefOrganizations.isCard(organization)) {
            return List.of();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        /* 바깥은 우리 식별자를 쓴다. 실제 CODEF connectedId 로 바꾸는 곳은 여기 한 곳이다. */
        body.put("connectedId", connectedId);
        body.put("organization", CodefOrganizations.toCodef(organization));

        Map<String, Object> response = post(baseUrl + "/v1/kr/bank/p/account/account-list", body);
        Map<String, Object> data = asMap(response.get("data"));

        List<FinancialAccountDto> accounts = new ArrayList<>();
        /* 5분류 중 예적금·입출금만 계좌 연동 대상이다. 대출·보험·펀드는 별도 이슈다. */
        for (Object item : asList(data.get("resDepositTrust"))) {
            Map<String, Object> row = asMap(item);
            accounts.add(FinancialAccountDto.builder()
                    .organization(organization)
                    .bankName(null)
                    .accountName(string(row.get("resAccountName")))
                    /* ⚠ 마스킹되지 않은 전체 계좌번호다. 저장·표시 전에 반드시 가공한다. */
                    .accountNo(string(row.get("resAccountDisplay")))
                    /* 실 CODEF 는 종류를 판정해 주지 않는다. 숫자 코드만 오므로 서비스가 유도한다. */
                    .accountType(null)
                    .depositTypeCode(string(row.get("resAccountDeposit")))
                    .currency(string(row.get("resAccountCurrency")))
                    .balance(decimal(row.get("resAccountBalance")))
                    .build());
        }
        return accounts;
    }

    /**
     * 우리 연결 식별자 → 실제 CODEF connectedId.
     *
     * 연결 직후에는 메모리 세션에 있다. 서버를 재시작했거나 세션이 만료된 뒤(「전체 즉시 조회」·재조회)에는
     * 세션이 없으므로 **DB 에 남긴 연결(userId × 기관)** 에서 찾는다. 둘 다 없으면 재연결이 필요하다 —
     * 호출부(AccountLinkService.syncInstitution)가 CONNECTION_NOT_FOUND 를 NEED_RECONNECT 로 바꾼다.
     */
    private String resolveConnectedId(long userId, String connectionId, String organization) {
        Session session = connectionId == null ? null : sessions.get(connectionId);
        if (session != null) {
            if (session.connectedId == null) {
                throw new BusinessException("TOKEN_EXPIRED", "인증이 만료됐어요. 다시 연결해 주세요.");
            }
            return session.connectedId;
        }
        if (connectionStore != null) {
            Optional<String> stored = connectionStore.connectedId(userId, organization);
            if (stored.isPresent()) {
                return stored.get();
            }
        }
        return session(connectionId).connectedId;   // 없으면 CONNECTION_NOT_FOUND 를 던진다
    }

    /** CODEF 호출. 토큰·URL 디코딩·결과코드 판정은 {@link CodefHttpClient} 가 한다. */
    private Map<String, Object> post(String url, Map<String, Object> body) {
        return http.post(url, body);
    }

    private static List<String> organizations(Object listValue) {
        List<String> result = new ArrayList<>();
        for (Object item : asList(listValue)) {
            String organization = string(asMap(item).get("organization"));
            if (organization != null) {
                result.add(CodefOrganizations.fromCodef(organization));
            }
        }
        return result;
    }

    private static Map<String, Object> asMap(Object value) {
        return CodefHttpClient.asMap(value);
    }

    private static List<?> asList(Object value) {
        return CodefHttpClient.asList(value);
    }

    /**
     * 간편인증 1차 요청의 계정 항목.
     *
     * ⚠ **확실한 것과 확인이 필요한 것이 섞여 있다.**
     *   확실: `loginType=5`(간편인증) · `loginTypeLevel`(1 카카오톡 · 5 PASS · 6 네이버)
     *   미확인: 본인 정보 필드명과 형식(`userName`·`birthDate`·`phoneNo`·`telecom`).
     *           공개 문서에서 확정하지 못해 일반적인 이름으로 넣었다.
     *           **첫 실호출에서 CODEF 가 누락·형식 오류를 코드로 알려주므로 그때 확정한다.**
     *           지어낸 값이 남지 않도록 이 주석을 지우지 말 것.
     */
    private Map<String, String> simpleAuthRow(String organization, String loginTypeLevel,
                                              IdentityDto identity) {
        String birthDate = toEightDigitBirthDate(identity.getBirthDate());

        Map<String, String> row = new LinkedHashMap<>();
        row.put("countryCode", "KR");
        row.put("businessType", CodefOrganizations.businessTypeOf(organization));
        row.put("clientType", "P");
        row.put("organization", CodefOrganizations.toCodef(organization));
        row.put("loginType", "5");
        row.put("loginTypeLevel", loginTypeLevel);
        row.put("userName", identity.getUserName());
        /*
         * 생년월일을 두 이름으로 함께 보낸다. CODEF 문서마다 `identity` 와 `birthDate` 가 섞여 나오고
         * 어느 쪽이 필수인지 공개 문서로 확정하지 못했다. 값은 같으므로 둘 다 실어 한 번의 실호출로
         * 판정한다 — 응답의 errorList 가 어느 쪽을 요구하는지 알려주면 남는 하나를 지운다.
         */
        row.put("identity", birthDate);
        row.put("birthDate", birthDate);
        row.put("phoneNo", digitsOnly(identity.getPhoneNo()));
        row.put("telecom", telecomCode(identity.getCarrier()));
        return row;
    }

    /**
     * 통신사 → CODEF `telecom` 코드.
     *
     * CODEF 는 통신사를 이름이 아니라 숫자로 받는다. 화면이 보내는 값(`SKT`·`KT_MVNO` …)을
     * 여기서만 바꾼다 — 프론트는 CODEF 를 모른 채로 남아야 한다.
     * 0 SKT · 1 KT · 2 LG U+ · 3 SKT 알뜰폰 · 4 KT 알뜰폰 · 5 LG U+ 알뜰폰
     */
    private static final Map<String, String> TELECOM_CODES = Map.of(
            "SKT", "0",
            "KT", "1",
            "LGU", "2",
            "SKT_MVNO", "3",
            "KT_MVNO", "4",
            "LGU_MVNO", "5");

    static String telecomCode(String carrier) {
        String code = carrier == null ? null : TELECOM_CODES.get(carrier.trim().toUpperCase());
        if (code == null) {
            throw new BusinessException("INVALID_CREDENTIALS", "통신사를 선택해 주세요.");
        }
        return code;
    }

    /**
     * 생년월일 6자리 → 8자리(yyyyMMdd).
     *
     * 화면은 6자리로 받는다(사용자에게 익숙한 형식). 세기는 앞 두 자리로 가른다 —
     * `00~29` 는 2000년대, 그 외는 1900년대. 통상적인 관례이며,
     * 이 서비스 사용자층(MZ)에서 오판정이 날 구간은 사실상 없다.
     * 이미 8자리로 오면 그대로 둔다.
     */
    static String toEightDigitBirthDate(String birthDate) {
        String digits = digitsOnly(birthDate);
        if (digits.length() == 8) {
            return digits;
        }
        if (digits.length() != 6) {
            throw new BusinessException("INVALID_CREDENTIALS", "생년월일 6자리를 입력해 주세요.");
        }
        int year = Integer.parseInt(digits.substring(0, 2));
        String century = year <= 29 ? "20" : "19";
        return century + digits;
    }

    private static String digitsOnly(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }

    private static Long number(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
