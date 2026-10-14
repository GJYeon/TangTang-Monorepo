package com.kb.tangtang.account.client.sync;

import com.kb.tangtang.account.client.FinancialSourceResolver;

/**
 * 실 CODEF 사용자에게는 목서버 시나리오 키 대신 `codef:{userId}` 를 준다.
 *
 * 동기화 서비스(FinancialSyncServiceImpl)와 계좌 선택 미리보기(AccountLinkService)는 이 키를 그대로
 * {@link FinancialSyncClient} 에 넘긴다. {@link RoutingFinancialSyncClient} 가 접두사를 보고 CODEF 로 보낸다 —
 * 그래서 서비스 코드는 공급자가 둘이라는 사실을 모른 채 그대로 돈다.
 *
 * 목서버 사용자는 예전과 똑같이 delegate(사용자 오버라이드 → 풀-나머지)를 탄다.
 */
public class SourceAwareScenarioKeyProvider implements ScenarioKeyProvider {

    private final FinancialSourceResolver sources;
    private final ScenarioKeyProvider mockKeys;

    public SourceAwareScenarioKeyProvider(FinancialSourceResolver sources, ScenarioKeyProvider mockKeys) {
        this.sources = sources;
        this.mockKeys = mockKeys;
    }

    @Override
    public String resolve(Long userId) {
        if (userId != null && sources.isCodef(userId)) {
            return CodefFinancialSyncClient.keyOf(userId);
        }
        return mockKeys.resolve(userId);
    }
}
