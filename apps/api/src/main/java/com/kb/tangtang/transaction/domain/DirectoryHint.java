package com.kb.tangtang.transaction.domain;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * LLM 카테고리화 프롬프트에 붙이는 상가(상권)정보 근거. 확정값이 아니다 — 최종 판정은 LLM 이 한다.
 * suggestedCategoryId 는 업종 매핑이 없으면 null 이다.
 */
@Getter
@Builder
public class DirectoryHint {

    /** 가맹점명 변형이 사전 상호와 정확히 같다. */
    public static final String EXACT = "EXACT";
    /** 사전 상호가 가맹점명의 앞부분과 같다(체인명+지점명 등). 근거가 약할 수 있다. */
    public static final String PREFIX = "PREFIX";

    private String matchType;
    private String matchedName;
    private String industryName;
    private Long suggestedCategoryId;
    private Integer storeCount;
    private BigDecimal share;
}
