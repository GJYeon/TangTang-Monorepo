package com.kb.tangtang.account.client.sync;

import com.kb.tangtang.account.client.CodefConnectionStore;
import com.kb.tangtang.account.client.CodefHttpClient;
import com.kb.tangtang.account.client.FinancialSourceResolver;
import com.kb.tangtang.account.service.AccountNumberPolicy;
import com.kb.tangtang.account.service.InstitutionCatalog;
import com.kb.tangtang.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * FinancialSyncServiceImplTest 는 이 빈을 거치지 않고 서비스를 직접 new 하므로
 * (Runnable::run 이나 손으로 만든 스레드풀을 넘긴다), @Qualifier("financialSyncExecutor") 가
 * 실제로 이 클래스의 빈과 이어지는지는 그 테스트로는 증명되지 않는다 — 이름이 틀리거나
 * 타입이 안 맞아도 단위 테스트는 계속 통과한다. 여기서는 진짜 Spring 컨테이너로 이 설정
 * 클래스만 띄워 빈이 실제로 만들어지고 동작하는지 확인한다(RootContextWiringTest 와 같은
 * 동기 — "컴파일도 단위 테스트도 통과했는데 컨테이너에서만 깨지는" 부류의 사고를 잡는다).
 */
class FinancialSyncClientConfigTest {

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context == null) {
            return;
        }
        Executor executor = context.getBean("financialSyncExecutor", Executor.class);
        if (executor instanceof ThreadPoolTaskExecutor) {
            ((ThreadPoolTaskExecutor) executor).shutdown();
        }
        context.close();
    }

    @Test
    @DisplayName("financialSyncExecutor 빈이 실제로 뜨고, 배치+수동이 겹쳐도 완전 직렬화되지 않을 크기(12)로 고정돼 있다")
    void financialSyncExecutorBeanIsWiredAndBounded() {
        context = new AnnotationConfigApplicationContext();
        context.register(FinancialSyncClientConfig.class);
        // scenarioKeyProvider() 가 UserMapper 를 요구한다 — 이 테스트는 MapperScan 을 거치지 않는
        // 고립된 컨텍스트라 실제 매퍼 대신 mock 을 등록해 컨텍스트 refresh 가 깨지지 않게 한다.
        context.registerBean("userMapper", UserMapper.class, () -> mock(UserMapper.class));
        // financialSyncClient() 가 공급자 판정·CODEF 부품을 요구한다(목 모드에서는 쓰이지 않는다).
        context.registerBean(FinancialSourceResolver.class,
                () -> new FinancialSourceResolver(FinancialSourceResolver.Mode.MOCK, null));
        context.registerBean(CodefHttpClient.class, () -> mock(CodefHttpClient.class));
        context.registerBean(CodefConnectionStore.class, () -> mock(CodefConnectionStore.class));
        context.registerBean(AccountNumberPolicy.class, () -> new AccountNumberPolicy("test-secret"));
        context.registerBean(InstitutionCatalog.class, InstitutionCatalog::new);

        PropertySourcesPlaceholderConfigurer placeholders = new PropertySourcesPlaceholderConfigurer();
        Properties props = new Properties();
        props.setProperty("mock.server.base-url", "http://localhost:8081");
        props.setProperty("mock.server.connect-timeout-ms", "3000");
        props.setProperty("mock.server.read-timeout-ms", "5000");
        props.setProperty("mock.server.scenario-keys", "1");
        placeholders.setProperties(props);
        context.addBeanFactoryPostProcessor(placeholders);

        context.refresh();

        Executor executor = context.getBean("financialSyncExecutor", Executor.class);
        assertTrue(executor instanceof ThreadPoolTaskExecutor, "ThreadPoolTaskExecutor 여야 풀 크기를 검증할 수 있다");
        ThreadPoolTaskExecutor pooled = (ThreadPoolTaskExecutor) executor;
        /* collectAll() 은 한 번의 sync() 호출마다 정확히 6개(BANK/DEPOSIT/SECURITIES/LOAN/PAY_MONEY/CARD)
           태스크를 던진다. 풀이 6이면 배치가 한 사용자를 처리하는 동안 그 6개를 전부 점유해 버려
           수동 새로고침이 완전히 직렬화된다 — 12(배치 1명분 + 수동 1명분)로 그 직렬화만 풀어준다.
           이보다 훨씬 크게 잡지 않는 이유는 단일 목서버 인스턴스에 필요 이상의 동시 요청을 보내면
           타임아웃 실패 위험만 커지기 때문이다(FinancialSyncClientConfig 주석 참고). */
        assertEquals(12, pooled.getCorePoolSize());
        assertEquals(12, pooled.getMaxPoolSize());

        /* 빈이 존재하는 것만이 아니라 실제로 이 풀의 스레드에서 태스크를 실행하는지까지 확인한다. */
        AtomicReference<String> runnerThreadName = new AtomicReference<>();
        CompletableFuture<Void> future = CompletableFuture.runAsync(
                () -> runnerThreadName.set(Thread.currentThread().getName()), executor);
        future.join();
        assertTrue(runnerThreadName.get().startsWith("financial-sync-"),
                "financialSyncExecutor 로 실행됐다면 스레드 이름이 financial-sync- 로 시작해야 한다");
    }
}
