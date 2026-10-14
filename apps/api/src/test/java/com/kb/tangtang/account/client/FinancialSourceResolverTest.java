package com.kb.tangtang.account.client;

import com.kb.tangtang.account.client.sync.CodefFinancialSyncClient;
import com.kb.tangtang.account.client.sync.FinancialSyncClient;
import com.kb.tangtang.account.client.sync.RoutingFinancialSyncClient;
import com.kb.tangtang.account.client.sync.ScenarioKeyProvider;
import com.kb.tangtang.account.client.sync.SourceAwareScenarioKeyProvider;
import com.kb.tangtang.user.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 사용자별 공급자 라우팅. 연동(FinancialDataClientResolver)과 동기화(키 접두사)가
 * 같은 판정을 따르는지 본다 — 어긋나면 CODEF 로 연결한 계좌를 목서버로 동기화하게 된다.
 */
class FinancialSourceResolverTest {

    private static final long MOCK_USER = 1L;
    private static final long CODEF_USER = 2L;

    private final UserMapper userMapper = mock(UserMapper.class);

    {
        when(userMapper.findFinancialDataSourceById(MOCK_USER)).thenReturn("MOCK");
        when(userMapper.findFinancialDataSourceById(CODEF_USER)).thenReturn("CODEF");
    }

    @Test
    @DisplayName("mock 모드는 아무도 CODEF 로 보내지 않고 DB 도 읽지 않는다")
    void mockMode() {
        FinancialSourceResolver sources = new FinancialSourceResolver(FinancialSourceResolver.Mode.MOCK, userMapper);

        assertFalse(sources.isCodef(CODEF_USER));
        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("codef 모드는 모두 CODEF 다")
    void codefMode() {
        FinancialSourceResolver sources = new FinancialSourceResolver(FinancialSourceResolver.Mode.CODEF, userMapper);

        assertTrue(sources.isCodef(MOCK_USER));
    }

    @Test
    @DisplayName("hybrid 모드는 financial_data_source 로 사용자마다 고른다")
    void hybridMode() {
        FinancialSourceResolver sources = new FinancialSourceResolver(FinancialSourceResolver.Mode.HYBRID, userMapper);

        assertFalse(sources.isCodef(MOCK_USER));
        assertTrue(sources.isCodef(CODEF_USER));
        assertFalse(sources.isCodef(99L), "값이 없는 사용자는 목서버");
    }

    @Test
    @DisplayName("설정값은 대소문자를 가리지 않고, 비면 mock, 오타면 기동을 막는다")
    void parsesMode() {
        assertEquals(FinancialSourceResolver.Mode.HYBRID, FinancialSourceResolver.parseMode("hybrid"));
        assertEquals(FinancialSourceResolver.Mode.MOCK, FinancialSourceResolver.parseMode(""));
        assertThrows(IllegalStateException.class, () -> FinancialSourceResolver.parseMode("codeff"));
    }

    @Test
    @DisplayName("hybrid: 연동 공급자도 사용자별로 갈린다")
    void dataClientRouting() {
        FinancialSourceResolver sources = new FinancialSourceResolver(FinancialSourceResolver.Mode.HYBRID, userMapper);
        FinancialDataClient mock = mock(FinancialDataClient.class);
        FinancialDataClient codef = mock(FinancialDataClient.class);

        FinancialDataClientResolver resolver = FinancialDataClientResolver.routing(sources, mock, codef);

        assertSame(mock, resolver.forUser(MOCK_USER));
        assertSame(codef, resolver.forUser(CODEF_USER));
    }

    @Test
    @DisplayName("hybrid: 동기화 키가 CODEF 사용자만 codef: 로 바뀌고, 라우터가 그 키를 CODEF 로 보낸다")
    void syncRouting() {
        FinancialSourceResolver sources = new FinancialSourceResolver(FinancialSourceResolver.Mode.HYBRID, userMapper);
        ScenarioKeyProvider mockKeys = userId -> "scenario-3";
        ScenarioKeyProvider keys = new SourceAwareScenarioKeyProvider(sources, mockKeys);
        FinancialSyncClient mock = mock(FinancialSyncClient.class);
        FinancialSyncClient codef = mock(FinancialSyncClient.class);
        FinancialSyncClient router = new RoutingFinancialSyncClient(mock, codef);
        when(mock.getCards("scenario-3")).thenReturn(List.of());
        when(codef.getCards(CodefFinancialSyncClient.keyOf(CODEF_USER))).thenReturn(List.of());

        assertEquals("scenario-3", keys.resolve(MOCK_USER));
        assertEquals("codef:2", keys.resolve(CODEF_USER));

        router.getCards(keys.resolve(MOCK_USER));
        router.getCards(keys.resolve(CODEF_USER));
        verify(mock).getCards("scenario-3");
        verify(codef).getCards("codef:2");
        verifyNoMoreInteractions(mock, codef);
    }
}
