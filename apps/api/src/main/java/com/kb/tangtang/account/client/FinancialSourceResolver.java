package com.kb.tangtang.account.client;

import com.kb.tangtang.user.mapper.UserMapper;

/**
 * 사용자마다 금융 데이터를 어디서 가져올지 정한다 — 목서버 또는 실 CODEF.
 *
 * `financial.client` 가 정하는 모드:
 *   · mock   — 모두 목서버 (기본값, 예전 동작 그대로)
 *   · codef  — 모두 실 CODEF
 *   · hybrid — **사용자별**. tbl_user.financial_data_source 가 CODEF 인 사용자만 실 CODEF, 나머지는 목서버.
 *              시연용 더미 사용자는 목서버 시나리오를 그대로 쓰고, 실계좌 시연 사용자만 CODEF 로 돈다.
 *
 * 계좌 연동(FinancialDataClientResolver)과 거래내역 동기화(SourceAwareScenarioKeyProvider)가 이 한 곳을 본다 —
 * 둘이 따로 판단하면 "CODEF 로 연결한 계좌를 목서버로 동기화" 하는 어긋남이 생긴다.
 *
 * account.client 가 user 모듈의 UserMapper 를 직접 참조하는 것은 UserOverridingScenarioKeyProvider 와
 * 같은 예외다(값 하나 조회를 위해 이벤트·서비스 계층을 두는 것은 과하다).
 */
public class FinancialSourceResolver {

    public enum Mode { MOCK, CODEF, HYBRID }

    private final Mode mode;
    private final UserMapper userMapper;

    public FinancialSourceResolver(Mode mode, UserMapper userMapper) {
        this.mode = mode;
        this.userMapper = userMapper;
    }

    /** 설정 문자열 → 모드. 모르는 값은 기동을 막는다 — 오타로 조용히 목서버가 뜨면 원인을 찾기 어렵다. */
    public static Mode parseMode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        try {
            return normalized.isEmpty() ? Mode.MOCK : Mode.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "financial.client 는 mock | codef | hybrid 중 하나여야 합니다. 현재 값: " + value);
        }
    }

    public Mode mode() {
        return mode;
    }

    public boolean isCodef(long userId) {
        return switch (mode) {
            case MOCK -> false;
            case CODEF -> true;
            case HYBRID -> "CODEF".equalsIgnoreCase(userMapper.findFinancialDataSourceById(userId));
        };
    }
}
