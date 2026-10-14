package com.kb.tangtang.transaction.client.llm;

import lombok.Builder;
import lombok.Getter;

/** LLM 이 판정한 거래 하나의 카테고리. categoryId 가 null 이면 "분류 불가"로 판정한 것. */
@Getter
@Builder
public class CategoryAssignmentDto {
    private Long transactionId;
    private Long categoryId;
    /**
     * 모델이 돌려준 확신도(0.0~1.0) 원본. 임계값 미만이라 categoryId 를 비운 경우에도 그대로 담는다 —
     * 임계값을 바꿨을 때의 결과를 다시 호출하지 않고 계산하기 위함이다. 응답에 없으면 0.0.
     */
    private Double confidence;
}
