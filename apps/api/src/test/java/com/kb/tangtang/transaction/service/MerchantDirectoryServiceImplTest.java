package com.kb.tangtang.transaction.service;

import com.kb.tangtang.transaction.domain.DirectoryHint;
import com.kb.tangtang.transaction.domain.MerchantDirectoryEntry;
import com.kb.tangtang.transaction.domain.Transaction;
import com.kb.tangtang.transaction.mapper.MerchantDirectoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantDirectoryServiceImplTest {

    private MerchantDirectoryMapper mapper;
    private MerchantDirectoryServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(MerchantDirectoryMapper.class);
        service = new MerchantDirectoryServiceImpl(mapper);
    }

    private static Transaction tx(long id, String merchantName) {
        return Transaction.builder().id(id).merchantName(merchantName).build();
    }

    private static MerchantDirectoryEntry entry(String name, String industryName, Long categoryId, int count, String share) {
        return MerchantDirectoryEntry.builder().merchantNameNormalized(name).industryCode("X")
                .industryName(industryName).categoryId(categoryId)
                .storeCount(count).topShare(new BigDecimal(share)).build();
    }

    @Test
    @DisplayName("정확히 일치하는 변형이 있으면 EXACT 힌트를 만든다")
    void exactMatch() {
        when(mapper.findByNames(any())).thenReturn(List.of(
                entry("성민식당", "한식>백반/한정식", 13L, 3, "1.000")));

        Map<Long, DirectoryHint> hints = service.findHints(List.of(tx(1L, "성민식당")));

        DirectoryHint hint = hints.get(1L);
        assertEquals(DirectoryHint.EXACT, hint.getMatchType());
        assertEquals("성민식당", hint.getMatchedName());
        assertEquals("한식>백반/한정식", hint.getIndustryName());
        assertEquals(13L, hint.getSuggestedCategoryId());
        assertEquals(3, hint.getStoreCount());
        assertEquals(new BigDecimal("1.000"), hint.getShare());
    }

    @Test
    @DisplayName("앞부분 일치보다 정확 일치(지점명 뗀 변형 포함)를 우선한다")
    void exactBeatsPrefix() {
        when(mapper.findByNames(any())).thenReturn(List.of(
                entry("스타벅", "기타>잡화", 1L, 1, "1.000"),
                entry("스타벅스", "비알코올>카페", 5L, 2139, "1.000")));

        DirectoryHint hint = service.findHints(List.of(tx(1L, "스타벅스 강남역점"))).get(1L);

        assertEquals(DirectoryHint.EXACT, hint.getMatchType());
        assertEquals("스타벅스", hint.getMatchedName());
    }

    @Test
    @DisplayName("정확 일치가 없으면 가장 긴 앞부분 일치를 PREFIX 로 고른다")
    void longestPrefix() {
        when(mapper.findByNames(any())).thenReturn(List.of(
                entry("카카오", "주점>일반 유흥 주점", 13L, 28, "0.290"),
                entry("카카오t", "운송>택시", 9L, 4, "1.000")));

        DirectoryHint hint = service.findHints(List.of(tx(1L, "카카오T포인트"))).get(1L);

        assertEquals(DirectoryHint.PREFIX, hint.getMatchType());
        assertEquals("카카오t", hint.getMatchedName());
    }

    @Test
    @DisplayName("업종 매핑이 없으면 industryName 은 업종 코드로, suggestedCategoryId 는 null 로 둔다")
    void unmappedIndustry() {
        when(mapper.findByNames(any())).thenReturn(List.of(
                MerchantDirectoryEntry.builder().merchantNameNormalized("성민식당").industryCode("Z99999")
                        .storeCount(1).topShare(new BigDecimal("1.000")).build()));

        DirectoryHint hint = service.findHints(List.of(tx(1L, "성민식당"))).get(1L);

        assertEquals("Z99999", hint.getIndustryName());
        assertNull(hint.getSuggestedCategoryId());
    }

    @Test
    @DisplayName("사전에 없으면 그 거래는 힌트가 없다")
    void noMatch() {
        when(mapper.findByNames(any())).thenReturn(List.of());

        assertFalse(service.findHints(List.of(tx(1L, "정체불명상점"))).containsKey(1L));
    }

    @Test
    @DisplayName("배치 전체의 조회 키를 모아 매퍼를 한 번만 호출한다")
    @SuppressWarnings("unchecked")
    void singleQueryPerBatch() {
        when(mapper.findByNames(any())).thenReturn(List.of());
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);

        service.findHints(List.of(tx(1L, "성민식당"), tx(2L, "스타벅스 강남역점")));

        verify(mapper).findByNames(captor.capture());
        Collection<String> keys = captor.getValue();
        assertTrue(keys.contains("성민식당"));
        assertTrue(keys.contains("성민식"));
        assertTrue(keys.contains("스타벅스강남역점"));
        assertTrue(keys.contains("스타벅스"));
    }

    @Test
    @DisplayName("가맹점명이 없는 거래만 있으면 매퍼를 호출하지 않는다")
    void skipsQueryWhenNoKeys() {
        Map<Long, DirectoryHint> hints = service.findHints(List.of(tx(1L, null), tx(2L, " ")));

        assertTrue(hints.isEmpty());
        verify(mapper, never()).findByNames(any());
    }

    @Test
    @DisplayName("빈 거래 목록이면 매퍼를 호출하지 않는다")
    void emptyInput() {
        assertTrue(service.findHints(List.of()).isEmpty());
        verify(mapper, never()).findByNames(any());
    }

    @Test
    @DisplayName("조회 중 DB 예외가 나면 예외 대신 빈 맵을 돌려준다")
    void swallowsDataAccessFailure() {
        when(mapper.findByNames(any())).thenThrow(new QueryTimeoutException("timeout"));

        assertTrue(service.findHints(List.of(tx(1L, "성민식당"))).isEmpty());
    }
}
