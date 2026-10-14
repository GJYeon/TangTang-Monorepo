package com.kb.tangtang.account.client;

import com.kb.tangtang.account.client.sync.FinancialSyncClient;
import com.kb.tangtang.account.client.sync.FinancialSyncClientConfig;
import com.kb.tangtang.account.client.sync.MockFinancialSyncClient;
import com.kb.tangtang.account.client.sync.RoutingFinancialSyncClient;
import com.kb.tangtang.account.client.sync.ScenarioKeyProvider;
import com.kb.tangtang.account.client.sync.SourceAwareScenarioKeyProvider;
import com.kb.tangtang.account.service.AccountNumberPolicy;
import com.kb.tangtang.account.service.InstitutionCatalog;
import com.kb.tangtang.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 연동·동기화 설정 두 클래스를 진짜 컨테이너로 띄워 모드별 빈 조립을 확인한다.
 * 단위 테스트는 생성자를 직접 부르므로 "빈 이름·타입이 안 맞아 컨테이너에서만 깨지는" 사고를 못 잡는다.
 */
class FinancialClientConfigTest {

    private AnnotationConfigApplicationContext context;
    private final UserMapper userMapper = mock(UserMapper.class);

    @AfterEach
    void tearDown() {
        if (context != null) {
            ((ThreadPoolTaskExecutor) context.getBean("financialSyncExecutor")).shutdown();
            context.close();
        }
    }

    private void start(String mode) throws Exception {
        context = new AnnotationConfigApplicationContext();
        context.register(FinancialClientConfig.class, FinancialSyncClientConfig.class);
        context.registerBean("userMapper", UserMapper.class, () -> userMapper);
        context.registerBean(CodefConnectionStore.class, () -> mock(CodefConnectionStore.class));
        context.registerBean(AccountNumberPolicy.class, () -> new AccountNumberPolicy("test-secret"));
        context.registerBean(InstitutionCatalog.class, InstitutionCatalog::new);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        Properties props = new Properties();
        props.setProperty("financial.client", mode);
        props.setProperty("financial.codef.public-key",
                Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded()));
        props.setProperty("mock.server.base-url", "http://localhost:8081");
        props.setProperty("mock.server.connect-timeout-ms", "3000");
        props.setProperty("mock.server.read-timeout-ms", "5000");
        props.setProperty("mock.server.scenario-keys", "1,2");
        PropertySourcesPlaceholderConfigurer placeholders = new PropertySourcesPlaceholderConfigurer();
        placeholders.setProperties(props);
        context.addBeanFactoryPostProcessor(placeholders);
        context.refresh();
    }

    @Test
    @DisplayName("mock 모드: 예전 그대로 목서버 구현만 뜬다")
    void mockMode() throws Exception {
        start("mock");

        assertTrue(context.getBean(FinancialSyncClient.class) instanceof MockFinancialSyncClient);
        assertFalse(context.getBean(ScenarioKeyProvider.class) instanceof SourceAwareScenarioKeyProvider);
        assertTrue(context.getBean(FinancialDataClientResolver.class).forUser(1L) instanceof MockFinancialDataClient);
    }

    @Test
    @DisplayName("hybrid 모드: 두 구현이 함께 뜨고 사용자별로 갈린다")
    void hybridMode() throws Exception {
        when(userMapper.findFinancialDataSourceById(1L)).thenReturn("MOCK");
        when(userMapper.findFinancialDataSourceById(2L)).thenReturn("CODEF");
        start("hybrid");

        FinancialDataClientResolver clients = context.getBean(FinancialDataClientResolver.class);
        assertTrue(clients.forUser(1L) instanceof MockFinancialDataClient);
        assertTrue(clients.forUser(2L) instanceof CodefFinancialDataClient);
        assertTrue(context.getBean(FinancialSyncClient.class) instanceof RoutingFinancialSyncClient);
        assertEquals("codef:2", context.getBean(ScenarioKeyProvider.class).resolve(2L));
    }

    @Test
    @DisplayName("codef 모드: 모든 사용자가 CODEF 다")
    void codefMode() throws Exception {
        start("codef");

        assertTrue(context.getBean(FinancialDataClientResolver.class).forUser(1L) instanceof CodefFinancialDataClient);
        assertEquals("codef:1", context.getBean(ScenarioKeyProvider.class).resolve(1L));
    }
}
