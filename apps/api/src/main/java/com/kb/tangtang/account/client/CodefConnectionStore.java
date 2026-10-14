package com.kb.tangtang.account.client;

import com.kb.tangtang.account.domain.CodefConnection;
import com.kb.tangtang.account.mapper.CodefConnectionMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 실 CODEF connectedId 보관소. **평문 connectedId 가 DB 와 오가는 유일한 창구다.**
 *
 * 예전에는 connectedId 가 CodefFinancialDataClient 의 메모리 세션에만 있어, 서버를 재시작하면
 * 「전체 즉시 조회」가 전부 NEED_RECONNECT 로 떨어졌고 거래내역 동기화는 아예 CODEF 를 부를 수 없었다.
 */
@Log4j2
@Component
public class CodefConnectionStore {

    private final CodefConnectionMapper mapper;
    private final ConnectedIdCipher cipher;

    @Autowired
    public CodefConnectionStore(CodefConnectionMapper mapper,
                                @Value("${financial.codef.connected-id-secret:${jwt.secret}}") String secret) {
        this(mapper, new ConnectedIdCipher(secret));
    }

    CodefConnectionStore(CodefConnectionMapper mapper, ConnectedIdCipher cipher) {
        this.mapper = mapper;
        this.cipher = cipher;
    }

    /** @param organization 우리 기관코드(InstitutionCatalog). CODEF 코드로 바꾸는 일은 호출 시점에 한다. */
    public void save(long userId, String organization, String connectedId) {
        mapper.upsert(CodefConnection.builder()
                .userId(userId)
                .organization(organization)
                .businessType(CodefOrganizations.businessTypeOf(organization))
                .connectedIdEncrypted(cipher.encrypt(connectedId))
                .build());
    }

    public Optional<String> connectedId(long userId, String organization) {
        CodefConnection row = mapper.findActive(userId, organization);
        return row == null ? Optional.empty() : decrypt(row);
    }

    /** 사용자의 살아 있는 연결 전부(기관코드 → 평문 connectedId). 복호화에 실패한 행은 건너뛴다. */
    public List<Connection> activeConnections(long userId) {
        List<Connection> result = new ArrayList<>();
        for (CodefConnection row : mapper.findActiveByUser(userId)) {
            decrypt(row).ifPresent(id -> result.add(
                    new Connection(row.getOrganization(), row.getBusinessType(), id)));
        }
        return result;
    }

    public void deactivateAll(long userId) {
        mapper.deactivateAllByUser(userId);
    }

    private Optional<String> decrypt(CodefConnection row) {
        try {
            return Optional.of(cipher.decrypt(row.getConnectedIdEncrypted()));
        } catch (IllegalStateException e) {
            /* 키 교체 등으로 못 여는 행. 조회 대상에서 빼면 호출부가 재연결 필요로 안내한다. */
            log.warn("CODEF 연결을 복호화하지 못해 건너뛴다 userId={} organization={}",
                    row.getUserId(), row.getOrganization());
            return Optional.empty();
        }
    }

    /** 평문 연결 한 건. 로그·응답으로 내보내지 않는다. */
    public record Connection(String organization, String businessType, String connectedId) {
        public boolean isCard() {
            return "CD".equals(businessType);
        }

        @Override
        public String toString() {
            return "Connection[" + organization + "/" + businessType + "]";
        }
    }
}
