package com.kb.tangtang.account.client;

import java.util.Map;
import java.util.Set;

/**
 * 우리 기관코드(InstitutionCatalog) ↔ CODEF 기관코드.
 *
 * **은행은 같고 카드는 다르다.** 우리 카드 코드는 목서버 시드에서 온 값이라(0301=신한카드 · 0381=KB국민카드 …)
 * CODEF 코드(0301=KB카드 · 0306=신한카드 …)와 숫자가 겹치면서 뜻이 다르다. 그대로 보내면 신한카드를 고른
 * 사용자의 로그인이 KB카드로 가 버린다. 그래서 **바깥(화면·DB)은 우리 코드만 쓰고, CODEF 로 나가는 순간
 * 여기서 바꾼다.** tbl_codef_connection.organization 에도 우리 코드가 들어간다.
 *
 * 카드 코드 출처: CODEF 개발가이드 `카드 - 개요` 카드사 목록 (2026-10-14 확인).
 */
public final class CodefOrganizations {

    /** 우리 카드 코드 → CODEF 카드 코드. 우리 카탈로그에 있는 카드사만 다룬다. */
    private static final Map<String, String> CARD_TO_CODEF = Map.of(
            "0301", "0306",     // 신한카드
            "0381", "0301",     // KB국민카드
            "0361", "0305",     // BC카드
            "0364", "0303",     // 삼성카드
            "0366", "0302",     // 현대카드
            "0371", "0311");    // 롯데카드

    /**
     * CODEF 가 개인 계좌조회를 제공하는 은행 20곳 (2026-08-05 공식 문서 `은행 - 개요` 기준).
     *
     * **카카오뱅크(0090)·토스뱅크(0092)는 목록에 없다.** 우리 기관 목록에는 있어서
     * 실 CODEF 모드에서 고르면 `CF-11021` 로 반드시 실패한다 — 그래서 여기서 걸러낸다.
     * 인터넷은행 중 제공되는 곳은 케이뱅크(0089) 하나다.
     */
    private static final Set<String> BANKS = Set.of(
            "0002", "0003", "0004", "0007", "0011", "0020", "0023", "0027", "0031", "0032",
            "0034", "0035", "0037", "0039", "0045", "0048", "0071", "0081", "0088", "0089");

    private CodefOrganizations() {
    }

    /** 실 CODEF 로 연결할 수 있는 우리 기관코드(은행 + 카탈로그 카드사). */
    public static Set<String> supported() {
        java.util.Set<String> all = new java.util.HashSet<>(BANKS);
        all.addAll(CARD_TO_CODEF.keySet());
        return Set.copyOf(all);
    }

    public static boolean isCard(String ourCode) {
        return CARD_TO_CODEF.containsKey(ourCode);
    }

    /** CODEF businessType. BK=은행 · CD=카드 */
    public static String businessTypeOf(String ourCode) {
        return isCard(ourCode) ? "CD" : "BK";
    }

    /** CODEF 요청 본문의 `organization` 값. 은행은 그대로, 카드는 바꾼다. */
    public static String toCodef(String ourCode) {
        return CARD_TO_CODEF.getOrDefault(ourCode, ourCode);
    }

    /**
     * CODEF 응답(successList 등)의 기관코드를 우리 코드로 되돌린다.
     * CODEF 카드 코드는 전부 03xx 이고 은행 코드와 겹치지 않으므로 그것으로 가른다.
     */
    public static String fromCodef(String codefCode) {
        if (codefCode == null || !codefCode.startsWith("03")) {
            return codefCode;
        }
        for (Map.Entry<String, String> entry : CARD_TO_CODEF.entrySet()) {
            if (entry.getValue().equals(codefCode)) {
                return entry.getKey();
            }
        }
        return codefCode;
    }
}
