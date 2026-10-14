package com.kb.tangtang.account.client;

import com.kb.tangtang.account.client.dto.ConnectionRequest;
import com.kb.tangtang.account.client.dto.ConnectionResult;
import com.kb.tangtang.account.client.dto.CredentialDto;
import com.kb.tangtang.account.client.dto.FinancialAccountDto;
import com.kb.tangtang.account.domain.AuthMethod;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 실 CODEF 기관 로그인 — 카드사 계정 등록과 서버 재시작 뒤 재조회.
 */
class CodefInstitutionLoginTest {

    private static final String BASE = "https://development.codef.io";

    private MockRestServiceServer server;
    private CodefConnectionStore store;
    private CodefFinancialDataClient client;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String publicKey = Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());

        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        store = mock(CodefConnectionStore.class);
        client = new CodefFinancialDataClient(new CodefHttpClient(restTemplate, "id", "secret"),
                BASE, new CodefPasswordCipher(publicKey), store);
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("https://oauth.codef.io/oauth/token")))
                .andRespond(withSuccess("{\"access_token\":\"t\"}", MediaType.APPLICATION_JSON));
    }

    private static ConnectionRequest login(String organization) {
        return ConnectionRequest.builder()
                .authMethod(AuthMethod.INSTITUTION_LOGIN)
                .organizations(List.of(organization))
                .credentials(List.of(CredentialDto.builder()
                        .organization(organization).id("user").password("pw").build()))
                .build();
    }

    @Test
    @DisplayName("카드사 로그인은 businessType=CD 와 CODEF 카드 코드로 보내고, 응답 코드는 우리 코드로 되돌린다")
    void cardLoginUsesCodefCardCode() {
        server.expect(once(), requestTo(BASE + "/v1/account/create"))
                .andExpect(jsonPath("$.accountList[0].businessType").value("CD"))
                .andExpect(jsonPath("$.accountList[0].organization").value("0306"))   // 우리 0301 = 신한카드
                .andRespond(withSuccess("""
                        {"result":{"code":"CF-00000"},
                         "data":{"connectedId":"conn-card","successList":[{"organization":"0306"}],"errorList":[]}}
                        """, MediaType.APPLICATION_JSON));

        ConnectionResult result = client.createConnection(login("0301"));

        assertEquals(List.of("0301"), result.getSuccessOrganizations());
        assertEquals(Optional.of("conn-card"), client.connectedIdOf(result.getConnectionId()));
        server.verify();
    }

    @Test
    @DisplayName("카드사는 계좌가 없다 — 연결 진행 화면이 그 기관을 성공으로 넘기도록 빈 목록을 준다")
    void cardHasNoAccounts() {
        server.expect(once(), requestTo(BASE + "/v1/account/create"))
                .andRespond(withSuccess("""
                        {"result":{"code":"CF-00000"},"data":{"connectedId":"conn-card"}}
                        """, MediaType.APPLICATION_JSON));
        String connectionId = client.createConnection(login("0301")).getConnectionId();

        assertTrue(client.fetchAccounts(1L, connectionId, "0301").isEmpty());
        server.verify();
    }

    @Test
    @DisplayName("메모리 세션이 없으면(서버 재시작) DB 에 남긴 connectedId 로 다시 조회한다")
    void fallsBackToStoredConnection() {
        when(store.connectedId(1L, "0004")).thenReturn(Optional.of("conn-stored"));
        server.expect(once(), requestTo(BASE + "/v1/kr/bank/p/account/account-list"))
                .andExpect(jsonPath("$.connectedId").value("conn-stored"))
                .andRespond(withSuccess("""
                        {"result":{"code":"CF-00000"},"data":{"resDepositTrust":[
                          {"resAccountDisplay":"02004130000001","resAccountName":"통장","resAccountDeposit":"11",
                           "resAccountBalance":"1000"}]}}
                        """, MediaType.APPLICATION_JSON));

        List<FinancialAccountDto> accounts = client.fetchAccounts(1L, "codef-gone-after-restart", "0004");

        assertEquals(1, accounts.size());
        server.verify();
    }

    @Test
    @DisplayName("세션도 저장된 연결도 없으면 재연결이 필요하다(CONNECTION_NOT_FOUND)")
    void noConnectionAnywhere() {
        when(store.connectedId(1L, "0004")).thenReturn(Optional.empty());

        BusinessException e = assertThrows(BusinessException.class,
                () -> client.fetchAccounts(1L, "codef-gone", "0004"));
        assertEquals("CONNECTION_NOT_FOUND", e.getCode());
    }
}
