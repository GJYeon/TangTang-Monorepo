package com.kb.tangtang.account.client;

import com.kb.tangtang.account.client.dto.ConnectionRequest;
import com.kb.tangtang.account.client.dto.ConnectionResult;
import com.kb.tangtang.account.client.dto.IdentityDto;
import com.kb.tangtang.account.domain.AuthMethod;
import com.kb.tangtang.account.domain.AuthStatus;
import com.kb.tangtang.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 실 CODEF 간편인증 2-way 흐름 검증.
 *
 * CODEF 서버 없이 응답을 흉내내 **상태 전이**를 본다 —
 * 1차 `CF-03002`(승인 대기) → 폴링 중 재요청 → connectedId 수신(승인).
 * 요청 파라미터의 정확한 이름은 실호출로 확정해야 하지만, 흐름은 여기서 고정된다.
 */
class CodefSimpleAuthTest {

    private static final String BASE = "https://development.codef.io";

    /** CF-03002 + continue2Way: 사용자의 앱 승인을 기다리는 상태. */
    private static final String PENDING_BODY = """
            {"result":{"code":"CF-03002","message":"추가 인증이 필요합니다"},
             "data":{"continue2Way":true,"jobIndex":0,"threadIndex":1,
                     "jti":"abc-123","twoWayTimestamp":1785900000000}}
            """;

    /** 승인 완료: connectedId 발급. */
    private static final String APPROVED_BODY = """
            {"result":{"code":"CF-00000","message":"성공"},
             "data":{"connectedId":"conn-real-1"}}
            """;

    /** 사용자가 거절했거나 세션이 만료된 경우. */
    private static final String FAILED_BODY = """
            {"result":{"code":"CF-12345","message":"인증이 취소되었습니다"},"data":{}}
            """;

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private CodefFinancialDataClient client;

    private static String publicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
    }

    @BeforeEach
    void setUp() throws Exception {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new CodefFinancialDataClient(
                restTemplate, BASE, "id", "secret", new CodefPasswordCipher(publicKey()));
    }

    /** 토큰은 처음 한 번만 받는다(CodefHttpClient 가 6일간 캐시한다). */
    private boolean tokenIssued;

    /**
     * 한 번의 CODEF 호출을 흉내낸다 — 첫 호출만 **토큰 발급 → 본 요청** 두 번이고, 이후는 본 요청뿐이다.
     * once() 로 등록해 순서대로 정확히 소비되게 한다(manyTimes 를 쓰면 첫 응답이 계속 매칭된다).
     */
    private void expectCall(String body) {
        if (!tokenIssued) {
            server.expect(once(), anything())
                    .andRespond(withSuccess("""
                            {"access_token":"t"}
                            """, MediaType.APPLICATION_JSON));
            tokenIssued = true;
        }
        server.expect(once(), anything())
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private ConnectionRequest simpleAuth() {
        return ConnectionRequest.builder()
                .authMethod(AuthMethod.SIMPLE_AUTH)
                .provider("KAKAO")
                .organizations(List.of("0004"))
                .credentials(List.of())
                .identity(IdentityDto.builder()
                        .userName("홍길동")
                        .birthDate("990101")
                        .carrier("SKT")
                        .phoneNo("010-1234-5678")
                        .build())
                .build();
    }

    @Test
    @DisplayName("실 CODEF 는 기관 로그인만 알린다 — 은행권 간편인증은 거부된다")
    void advertisesOnlyWhatWorks() {
        /*
         * 2026-08-05 실호출: IBK(0003)·카카오뱅크(0090) 모두 CF-11021 로 거부됐다.
         * 화면에 띄워놓고 누르면 실패하는 선택지를 두지 않는다.
         */
        assertEquals(List.of(AuthMethod.INSTITUTION_LOGIN), client.supportedAuthMethods());
    }

    @Test
    @DisplayName("간편인증 구현은 살아 있다 — 지원 기관이 생기면 목록만 되돌리면 된다")
    void keepsSimpleAuthCapability() {
        assertEquals(List.of("KAKAO", "PASS", "NAVER"), client.simpleAuthProviders());
        /* 실 CODEF 는 "누구에게 푸시를 보낼지" 를 알아야 한다. */
        assertTrue(client.requiresIdentity());
    }

    @Test
    @DisplayName("1차 요청이 CF-03002 면 승인 대기 상태로 둔다")
    void firstCallWaitsForApproval() {
        expectCall(PENDING_BODY);

        ConnectionResult result = client.createConnection(simpleAuth());

        assertEquals(AuthStatus.PENDING, result.getStatus());
        /* connectedId 는 아직 없다. 우리 식별자를 먼저 발급해 폴링에 쓴다. */
        assertTrue(result.getConnectionId().startsWith("codef-"));
        assertEquals(300, result.getExpiresInSeconds());
    }

    @Test
    @DisplayName("승인 전에는 폴링해도 계속 대기다")
    void stillPendingBeforeApproval() {
        expectCall(PENDING_BODY);
        expectCall(PENDING_BODY); // 폴링이 2차 요청을 한 번 더 보낸다

        ConnectionResult result = client.createConnection(simpleAuth());

        assertEquals(AuthStatus.PENDING, client.getAuthStatus(result.getConnectionId()));
    }

    @Test
    @DisplayName("사용자가 앱에서 승인하면 connectedId 를 받아 승인 상태가 된다")
    void approvesAfterUserConfirms() {
        /* MockRestServiceServer 는 기대 응답을 **호출 전에** 모두 등록해야 순서대로 소비한다. */
        expectCall(PENDING_BODY); // 1차 요청
        expectCall(APPROVED_BODY); // 폴링 — 사용자가 앱에서 승인한 뒤

        ConnectionResult result = client.createConnection(simpleAuth());

        assertEquals(AuthStatus.APPROVED, client.getAuthStatus(result.getConnectionId()));
    }

    @Test
    @DisplayName("사용자가 거절하면 실패로 끝난다")
    void failsWhenUserRejects() {
        expectCall(PENDING_BODY);
        expectCall(FAILED_BODY);

        ConnectionResult result = client.createConnection(simpleAuth());

        assertEquals(AuthStatus.FAILED, client.getAuthStatus(result.getConnectionId()));
    }

    @Test
    @DisplayName("승인 전에는 계좌를 조회할 수 없다")
    void cannotFetchBeforeApproval() {
        expectCall(PENDING_BODY);
        ConnectionResult result = client.createConnection(simpleAuth());

        BusinessException e = assertThrows(BusinessException.class,
                () -> client.fetchAccounts(1L, result.getConnectionId(), "0004"));
        assertEquals("TOKEN_EXPIRED", e.getCode());
    }

    @Test
    @DisplayName("모르는 연결 식별자는 막는다")
    void rejectsUnknownConnection() {
        BusinessException e =
                assertThrows(BusinessException.class, () -> client.getAuthStatus("없는값"));
        assertEquals("CONNECTION_NOT_FOUND", e.getCode());
    }

    @Test
    @DisplayName("본인 정보가 없으면 요청하지 않는다")
    void requiresIdentityToStart() {
        ConnectionRequest noIdentity = ConnectionRequest.builder()
                .authMethod(AuthMethod.SIMPLE_AUTH)
                .provider("KAKAO")
                .organizations(List.of("0004"))
                .credentials(List.of())
                .build();

        BusinessException e =
                assertThrows(BusinessException.class, () -> client.createConnection(noIdentity));
        assertEquals("INVALID_CREDENTIALS", e.getCode());
    }

    @Test
    @DisplayName("지원하지 않는 수단은 거른다")
    void rejectsUnknownProvider() {
        ConnectionRequest unknown = ConnectionRequest.builder()
                .authMethod(AuthMethod.SIMPLE_AUTH)
                .provider("TOSS")
                .organizations(List.of("0004"))
                .credentials(List.of())
                .identity(IdentityDto.builder().userName("홍길동").phoneNo("01012345678").build())
                .build();

        assertThrows(BusinessException.class, () -> client.createConnection(unknown));
    }

    @Test
    @DisplayName("기관이 그 인증 방식을 지원하지 않으면 무엇을 하면 되는지 알려준다")
    void explainsUnsupportedLoginType() {
        /* 실제로 받은 응답 형태 — 카카오뱅크(0090) + 간편인증 (2026-08-05). */
        BusinessException e = CodefHttpClient.failure(
                Map.of("code", "CF-04000", "message", "사용자 계정정보 등록에 실패했습니다."),
                Map.of("data", Map.of("errorList", List.of(Map.of(
                        "organization", "0090",
                        "code", "CF-11021",
                        "message", "요청 처리에 실패했습니다.")))));

        assertEquals("AUTH_METHOD_UNSUPPORTED", e.getCode());
        /* "등록에 실패했습니다" 만으로는 사용자가 다음에 뭘 할지 알 수 없다. */
        assertTrue(e.getMessage().contains("금융기관 로그인"));
    }

    @Test
    @DisplayName("모르는 실패는 CODEF 메시지를 그대로 전한다")
    void keepsUnknownFailureMessage() {
        BusinessException e = CodefHttpClient.failure(
                Map.of("code", "CF-09999", "message", "점검 중입니다."), Map.of());

        assertEquals("EXTERNAL_API_ERROR", e.getCode());
        assertEquals("점검 중입니다.", e.getMessage());
    }

    @Test
    @DisplayName("통신사를 CODEF 숫자 코드로 바꾼다")
    void mapsTelecomToCode() {
        assertEquals("0", CodefFinancialDataClient.telecomCode("SKT"));
        assertEquals("1", CodefFinancialDataClient.telecomCode("KT"));
        assertEquals("2", CodefFinancialDataClient.telecomCode("LGU"));
        /* 알뜰폰은 어느 망을 쓰는지까지 구분해야 한다. */
        assertEquals("3", CodefFinancialDataClient.telecomCode("SKT_MVNO"));
        assertEquals("4", CodefFinancialDataClient.telecomCode("KT_MVNO"));
        assertEquals("5", CodefFinancialDataClient.telecomCode("LGU_MVNO"));
    }

    @Test
    @DisplayName("모르는 통신사는 요청하기 전에 막는다")
    void rejectsUnknownTelecom() {
        /* 이름을 그대로 보내면 CODEF 가 뭉뚱그린 실패만 돌려줘 원인을 찾기 어렵다. */
        BusinessException e = assertThrows(BusinessException.class,
                () -> CodefFinancialDataClient.telecomCode("LG U+"));
        assertEquals("INVALID_CREDENTIALS", e.getCode());
        assertThrows(BusinessException.class, () -> CodefFinancialDataClient.telecomCode(null));
    }

    @Test
    @DisplayName("생년월일 6자리를 8자리로 바꾼다")
    void expandsBirthDate() {
        assertEquals("19990101", CodefFinancialDataClient.toEightDigitBirthDate("990101"));
        assertEquals("20050301", CodefFinancialDataClient.toEightDigitBirthDate("050301"));
        /* 경계: 29 까지는 2000년대로 본다 */
        assertEquals("20290101", CodefFinancialDataClient.toEightDigitBirthDate("290101"));
        assertEquals("19300101", CodefFinancialDataClient.toEightDigitBirthDate("300101"));
        /* 이미 8자리면 그대로 */
        assertEquals("19990101", CodefFinancialDataClient.toEightDigitBirthDate("1999-01-01"));
        assertThrows(BusinessException.class, () -> CodefFinancialDataClient.toEightDigitBirthDate("99"));
    }
}
