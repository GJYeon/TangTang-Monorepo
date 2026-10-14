package com.kb.tangtang.account.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * tbl_codef_connection 한 행 — 사용자 × 기관 단위의 실 CODEF 연결.
 *
 * ⚠ connectedIdEncrypted 는 **암호문**이다. 평문 connectedId 는 CodefConnectionStore 밖으로 나가지 않는다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodefConnection {
    private Long id;
    private Long userId;
    private String organization;
    /** BK=은행 · CD=카드 */
    private String businessType;
    private String connectedIdEncrypted;
}
