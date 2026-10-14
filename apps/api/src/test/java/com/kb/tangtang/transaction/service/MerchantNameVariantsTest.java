package com.kb.tangtang.transaction.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantNameVariantsTest {

    @Test
    @DisplayName("끝의 _숫자 접미어를 뗀 변형을 만든다")
    void stripsNumericSuffix() {
        assertEquals(List.of("컴포즈커피4", "컴포즈커피"), MerchantNameVariants.of("컴포즈커피_4"));
    }

    @Test
    @DisplayName("괄호 묶음을 뗀 변형을 만든다 — 닫는 괄호가 잘려 없어도 뗀다")
    void stripsParentheses() {
        assertEquals(List.of("카페미엘cafemiel", "카페미엘"), MerchantNameVariants.of("카페미엘(cafe miel)"));
        assertEquals(List.of("엑시트피씨카페exi", "엑시트피씨카페"), MerchantNameVariants.of("엑시트피씨카페(EXI"));
    }

    @Test
    @DisplayName("공백 뒤 지점명 토큰을 뗀 변형을 만든다")
    void stripsBranchToken() {
        assertEquals(List.of("스타벅스강남역점", "스타벅스"), MerchantNameVariants.of("스타벅스 강남역점"));
        assertEquals(List.of("교촌치킨역삼1호점", "교촌치킨"), MerchantNameVariants.of("교촌치킨 역삼1호점"));
    }

    @Test
    @DisplayName("전각 공백(U+3000) 뒤 지점명 토큰도 뗀다 — 측정 스크립트(JS \\s)와 같게")
    void stripsBranchTokenAfterFullWidthSpace() {
        assertEquals(List.of("스타벅스강남역점", "스타벅스"), MerchantNameVariants.of("스타벅스　강남역점"));
    }

    @Test
    @DisplayName("주) 와 (유) 법인 표기를 지운다")
    void stripsExtraCorporateMarkers() {
        assertEquals(List.of("비지에프네트웍"), MerchantNameVariants.of("주)비지에프네트웍"));
        assertEquals(List.of("한국맥도날드어린이대공원점"), MerchantNameVariants.of("한국맥도날드(유)어린이대공원점"));
    }

    @Test
    @DisplayName("null·빈 문자열·정규화 후 2자 미만이면 빈 목록")
    void emptyForBlankOrTooShort() {
        assertTrue(MerchantNameVariants.of(null).isEmpty());
        assertTrue(MerchantNameVariants.of("  ").isEmpty());
        assertTrue(MerchantNameVariants.of("쉼").isEmpty());
    }

    @Test
    @DisplayName("앞부분 키는 길이 n-1 부터 3 까지 내림차순")
    void prefixesFromLongestToThree() {
        assertEquals(List.of("카카오t포인", "카카오t포", "카카오t", "카카오"),
                MerchantNameVariants.prefixes("카카오t포인트"));
        assertTrue(MerchantNameVariants.prefixes("스타벅").isEmpty());
    }
}
