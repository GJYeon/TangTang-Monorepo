package com.kb.tangtang.account.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConnectedIdCipherTest {

    private final ConnectedIdCipher cipher = new ConnectedIdCipher("test-secret");

    @Test
    @DisplayName("암호화한 connectedId 는 복호화하면 원래 값이 된다")
    void roundTrip() {
        String sealed = cipher.encrypt("3Lj7J-OvQub96etCy.0xZz");

        assertNotEquals("3Lj7J-OvQub96etCy.0xZz", sealed);
        assertEquals("3Lj7J-OvQub96etCy.0xZz", cipher.decrypt(sealed));
    }

    @Test
    @DisplayName("같은 값도 매번 다른 암호문이 된다(랜덤 IV)")
    void randomIv() {
        assertNotEquals(cipher.encrypt("conn-1"), cipher.encrypt("conn-1"));
    }

    @Test
    @DisplayName("암호문이 컬럼(VARCHAR 255)에 들어간다")
    void fitsColumn() {
        /* 실제 connectedId 는 22자 안팎이다. 넉넉히 100자로 확인한다. */
        assertTrue(cipher.encrypt("x".repeat(100)).length() <= 255);
    }

    @Test
    @DisplayName("키가 다르면 복호화하지 못한다 — 다른 값을 조용히 돌려주지 않는다")
    void wrongKeyFails() {
        String sealed = cipher.encrypt("conn-1");

        assertThrows(IllegalStateException.class, () -> new ConnectedIdCipher("other-secret").decrypt(sealed));
    }

    @Test
    @DisplayName("키 설정이 없으면 기동 시점에 실패한다")
    void requiresSecret() {
        assertThrows(IllegalStateException.class, () -> new ConnectedIdCipher(" "));
    }
}
