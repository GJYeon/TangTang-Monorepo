package com.kb.tangtang.account.client;

import com.kb.tangtang.account.service.InstitutionCatalog;
import com.kb.tangtang.account.dto.InstitutionDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodefOrganizationsTest {

    @Test
    @DisplayName("카드 코드는 CODEF 코드로 바꾼다 — 우리 0301(신한)이 CODEF 0301(KB)로 가면 안 된다")
    void translatesCardCodes() {
        assertEquals("0306", CodefOrganizations.toCodef("0301"));   // 신한카드
        assertEquals("0301", CodefOrganizations.toCodef("0381"));   // KB국민카드
        assertEquals("0305", CodefOrganizations.toCodef("0361"));   // BC카드
        assertEquals("0303", CodefOrganizations.toCodef("0364"));   // 삼성카드
        assertEquals("0302", CodefOrganizations.toCodef("0366"));   // 현대카드
        assertEquals("0311", CodefOrganizations.toCodef("0371"));   // 롯데카드
    }

    @Test
    @DisplayName("은행 코드는 그대로다")
    void keepsBankCodes() {
        assertEquals("0004", CodefOrganizations.toCodef("0004"));
        assertEquals("BK", CodefOrganizations.businessTypeOf("0004"));
        assertEquals("CD", CodefOrganizations.businessTypeOf("0301"));
    }

    @Test
    @DisplayName("CODEF 응답의 카드 코드는 우리 코드로 되돌린다")
    void translatesBack() {
        assertEquals("0381", CodefOrganizations.fromCodef("0301"));
        assertEquals("0301", CodefOrganizations.fromCodef("0306"));
        assertEquals("0004", CodefOrganizations.fromCodef("0004"));
    }

    @Test
    @DisplayName("우리 카탈로그의 카드사는 모두 CODEF 로 연결할 수 있다")
    void everyCatalogCardIsSupported() {
        List<InstitutionDto> cards = new InstitutionCatalog().cards(List.of());

        assertFalse(cards.isEmpty());
        for (InstitutionDto card : cards) {
            assertTrue(CodefOrganizations.supported().contains(card.getCode()), card.getName());
            assertTrue(CodefOrganizations.isCard(card.getCode()), card.getName());
        }
    }

    @Test
    @DisplayName("카카오뱅크·토스뱅크는 CODEF 제공 은행이 아니다")
    void excludesUnsupportedBanks() {
        assertFalse(CodefOrganizations.supported().contains("0090"));
        assertFalse(CodefOrganizations.supported().contains("0092"));
    }
}
