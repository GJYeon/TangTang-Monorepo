package com.kb.tangtang.account.client;

import com.kb.tangtang.account.domain.CodefConnection;
import com.kb.tangtang.account.mapper.CodefConnectionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodefConnectionStoreTest {

    private final CodefConnectionMapper mapper = mock(CodefConnectionMapper.class);
    private final ConnectedIdCipher cipher = new ConnectedIdCipher("test-secret");
    private final CodefConnectionStore store = new CodefConnectionStore(mapper, cipher);

    @Test
    @DisplayName("저장할 때 connectedId 를 평문으로 남기지 않고, 업권을 기관코드로 정한다")
    void savesEncrypted() {
        store.save(1L, "0301", "conn-real");

        ArgumentCaptor<CodefConnection> saved = ArgumentCaptor.forClass(CodefConnection.class);
        verify(mapper).upsert(saved.capture());
        assertEquals("0301", saved.getValue().getOrganization(), "우리 기관코드로 남긴다");
        assertEquals("CD", saved.getValue().getBusinessType());
        assertNotEquals("conn-real", saved.getValue().getConnectedIdEncrypted());
        assertEquals("conn-real", cipher.decrypt(saved.getValue().getConnectedIdEncrypted()));
    }

    @Test
    @DisplayName("복호화하지 못하는 행(키 교체 등)은 건너뛰고 나머지를 돌려준다")
    void skipsUndecryptableRows() {
        when(mapper.findActiveByUser(1L)).thenReturn(List.of(
                row("0004", "BK", cipher.encrypt("conn-bank")),
                row("0088", "BK", new ConnectedIdCipher("old-secret").encrypt("conn-old"))));

        List<CodefConnectionStore.Connection> connections = store.activeConnections(1L);

        assertEquals(1, connections.size());
        assertEquals("conn-bank", connections.get(0).connectedId());
        assertFalse(connections.get(0).toString().contains("conn-bank"), "toString 으로 connectedId 가 새지 않는다");
    }

    @Test
    @DisplayName("기관 단위 조회")
    void findsByOrganization() {
        when(mapper.findActive(1L, "0004")).thenReturn(row("0004", "BK", cipher.encrypt("conn-bank")));

        assertEquals("conn-bank", store.connectedId(1L, "0004").orElseThrow());
        assertTrue(store.connectedId(1L, "0088").isEmpty());
    }

    private static CodefConnection row(String organization, String businessType, String encrypted) {
        return CodefConnection.builder()
                .userId(1L).organization(organization).businessType(businessType)
                .connectedIdEncrypted(encrypted).build();
    }
}
