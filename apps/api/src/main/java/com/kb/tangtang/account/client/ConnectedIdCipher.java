package com.kb.tangtang.account.client;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * CODEF connectedId 를 DB 에 넣기 전에 암호화한다(AES-256-GCM).
 *
 * connectedId 는 비밀번호가 아니지만 **우리 CODEF 클라이언트 키와 합쳐지면 그 사용자의 계좌를 조회할 수 있는
 * 값**이다. DB 덤프 한 번에 클라이언트 키와 함께 새어 나가면 실계좌가 열린다 — 그래서 평문으로 두지 않는다.
 *
 * 계좌번호(AccountNumberPolicy)와 달리 **복호화가 필요하다**(동기화 때 다시 CODEF 에 보내야 한다).
 * 중복 검사에 쓰지 않으므로 결정적일 필요가 없어 매번 랜덤 IV 를 쓴다.
 *
 * 저장 형식: Base64( IV(12바이트) || 암호문+태그 )
 */
public class ConnectedIdCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param secret 임의 길이 문자열. SHA-256 으로 256비트 키를 만든다 — 설정 파일에 정확히 32바이트를
     *               요구하면 팀원 세팅이 또 깨진다(AccountNumberPolicy 의 jwt.secret 재사용과 같은 이유).
     */
    public ConnectedIdCipher(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "financial.codef.connected-id-secret 또는 jwt.secret 이 필요합니다. application-local.properties 를 확인하세요.");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("connectedId 암호화 키를 만들지 못했습니다.", e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            throw new IllegalArgumentException("암호화할 connectedId 가 비어 있습니다.");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (Exception e) {
            /* 예외 메시지에 평문이 섞여 나가지 않도록 원인을 감싸서 던진다. */
            throw new IllegalStateException("connectedId 암호화에 실패했습니다.", e);
        }
    }

    /**
     * 복호화. **키가 바뀌었거나 값이 훼손되면 예외를 던진다** — GCM 태그가 그 둘을 구분 없이 잡아낸다.
     * 호출부는 이를 "재연결이 필요한 연결" 로 다룬다.
     */
    public String decrypt(String encoded) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            if (all.length <= IV_BYTES) {
                throw new IllegalArgumentException("암호문이 너무 짧습니다.");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("connectedId 를 복호화하지 못했습니다. 키가 바뀌었는지 확인하세요.", e);
        }
    }
}
