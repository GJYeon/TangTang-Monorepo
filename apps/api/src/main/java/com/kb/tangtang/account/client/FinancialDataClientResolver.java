package com.kb.tangtang.account.client;

/**
 * 사용자에게 맞는 계좌 연동 공급자를 고른다.
 *
 * 예전에는 FinancialDataClient 빈이 하나뿐이라 서버 전체가 목서버 아니면 CODEF 였다.
 * hybrid 모드에서는 두 구현이 함께 떠 있고, {@link FinancialSourceResolver} 가 사용자별로 고른다.
 * 서비스·컨트롤러는 여전히 어느 구현이 붙었는지 모른다.
 */
@FunctionalInterface
public interface FinancialDataClientResolver {

    FinancialDataClient forUser(long userId);

    /** 고정 구현 하나만 쓰는 경우(mock/codef 모드, 단위 테스트). */
    static FinancialDataClientResolver fixed(FinancialDataClient client) {
        return userId -> client;
    }

    /** hybrid: 사용자 출처에 따라 둘 중 하나. */
    static FinancialDataClientResolver routing(FinancialSourceResolver sources,
                                               FinancialDataClient mock,
                                               FinancialDataClient codef) {
        return userId -> sources.isCodef(userId) ? codef : mock;
    }
}
