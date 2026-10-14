package com.kb.tangtang.account.client;

import com.kb.tangtang.account.client.dto.ConnectionRequest;
import com.kb.tangtang.account.client.dto.ConnectionResult;
import com.kb.tangtang.account.client.dto.FinancialAccountDto;
import com.kb.tangtang.account.domain.AuthMethod;
import com.kb.tangtang.account.domain.AuthStatus;

import java.util.List;

/**
 * 금융 데이터 공급자 경계.
 *
 * 목서버와 실 CODEF 의 차이를 이 인터페이스 뒤에 가둔다. 서비스·컨트롤러·프론트는
 * 어느 구현이 붙어 있는지 알지 못하며, 전환은 financial.client 프로퍼티 한 줄로 끝난다.
 * 이 경계가 발표 QA 의 "설정 교체만으로 실 CODEF 전환 가능" 주장의 근거다.
 *
 * @see MockFinancialDataClient
 * @see CodefFinancialDataClient
 */
public interface FinancialDataClient {

    /**
     * 이 공급자가 지원하는 인증 수단.
     * 프론트는 이 목록만 보고 인증 화면을 고른다.
     */
    List<AuthMethod> supportedAuthMethods();

    /** 간편인증 수단 코드 목록. INSTITUTION_LOGIN 만 지원하면 빈 목록 */
    List<String> simpleAuthProviders();

    /**
     * 간편인증에 본인 정보(이름·생년월일·통신사·휴대폰)가 필요한지.
     *
     * **프론트가 이 값을 보고 전송 여부를 정한다.** 목 모드는 보낼 인증기관이 없으므로 false 이고,
     * 그때 개인정보는 화면 밖으로 나가지 않는다. 실 CODEF 는 "누구에게 푸시를 보낼지" 를
     * 알아야 하므로 true 다.
     */
    default boolean requiresIdentity() {
        return false;
    }

    /**
     * 이 공급자가 실제로 계좌를 조회할 수 있는 기관코드.
     *
     * **빈 집합은 "제한 없음" 이다.** 목서버는 시나리오 DB 에 있는 기관을 모두 다루므로 비워 둔다.
     * 실 CODEF 는 제공 기관이 정해져 있어(은행 20곳) 그 밖을 고르면 반드시 실패한다 —
     * 화면에 띄워놓고 누르면 실패하는 선택지를 두지 않기 위해 서비스가 이 값으로 목록을 거른다.
     * 인증 수단과 같은 원칙이다: **공급자가 되는 것만 알리고 프론트는 그대로 그린다.**
     */
    default java.util.Set<String> supportedOrganizations() {
        return java.util.Set.of();
    }

    /**
     * 승인된 연결을 서버 재시작 뒤에도 다시 쓸 수 있게 하는 공급자 쪽 식별자(실 CODEF connectedId).
     *
     * **비어 있으면 남길 것이 없다는 뜻이다.** 목서버는 시나리오 키로 사용자를 식별하므로 비워 둔다.
     * 값이 있으면 AccountLinkService 가 기관 단위로 암호화해 저장하고, 동기화가 그 값으로 CODEF 를 부른다.
     */
    default java.util.Optional<String> connectedIdOf(String connectionId) {
        return java.util.Optional.empty();
    }

    /** 연결(인증) 요청 */
    ConnectionResult createConnection(ConnectionRequest request);

    /** 인증 승인 여부. 프론트가 폴링한다 */
    AuthStatus getAuthStatus(String connectionId);

    /** 2-way 추가인증 제출. 지원하지 않는 구현은 그대로 승인을 돌려준다 */
    AuthStatus submitExtraAuth(String connectionId, String authCode);

    /**
     * 한 기관의 계좌 목록을 조회한다.
     * 실패하면 예외를 던진다 — 호출부가 그 기관만 FAILED 로 표시하고 나머지를 계속 진행한다.
     *
     * @param userId 목 구현이 시나리오를 정하는 데 쓴다(#334) — 실 CODEF 는 connectionId 로 이미
     *               사용자를 식별하므로 이 값을 쓰지 않는다.
     */
    List<FinancialAccountDto> fetchAccounts(long userId, String connectionId, String organization);
}
