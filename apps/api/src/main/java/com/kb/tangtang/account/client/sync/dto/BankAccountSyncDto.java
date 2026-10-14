package com.kb.tangtang.account.client.sync.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class BankAccountSyncDto {
    private final long accountId;
    private final String institutionCode;
    private final String institutionName;
    private final String accountTypeCode;
    private final String productName;
    private final String accountNoMasked;
    private final String currency;
    private final BigDecimal balance;
    private final BigDecimal availableAmount;
    /**
     * 실 CODEF 가 준 전체 계좌번호. 목서버는 마스킹 번호만 주므로 null 이다.
     *
     * 계좌 연동(AccountLinkService)은 `hash(기관, 전체번호)` 로 행 키를 만든다. 동기화가 마스킹 번호로
     * 해시하면 **같은 실계좌가 두 행**이 되므로, 이 값이 있으면 동기화도 이걸로 해시한다.
     * ⚠ 저장·로그 금지 — 해시 입력으로만 쓴다.
     */
    private final String accountNoRaw;
}
