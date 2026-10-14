package com.kb.tangtang.account.service;

import com.kb.tangtang.account.mapper.ConnectedAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 이슈 #199 — 배치 스케줄러 단위 테스트. LlmCategorizationSchedulerTest 와 같은 방식:
 * 매퍼·서비스는 전부 목이라 DB 없이 돈다.
 */
class FinancialSyncBatchSchedulerTest {

    private ConnectedAccountMapper connectedAccountMapper;
    private FinancialSyncService financialSyncService;

    @BeforeEach
    void setUp() {
        connectedAccountMapper = mock(ConnectedAccountMapper.class);
        financialSyncService = mock(FinancialSyncService.class);
    }

    @Test
    @DisplayName("대상 사용자 각각에 대해 sync() 를 호출한다")
    void callsSyncForEachDueUser() {
        when(connectedAccountMapper.findUserIdsDueForSync(25, 25, "NONE", 20)).thenReturn(List.of(1L, 2L, 3L));
        FinancialSyncBatchScheduler scheduler = new FinancialSyncBatchScheduler(
                connectedAccountMapper, financialSyncService, true, 20, 25);

        scheduler.runBatch();

        verify(financialSyncService).sync(1L);
        verify(financialSyncService).sync(2L);
        verify(financialSyncService).sync(3L);
    }

    @Test
    @DisplayName("한 사용자가 실패해도 나머지 사용자는 계속 처리한다")
    void oneFailureDoesNotStopTheRest() {
        when(connectedAccountMapper.findUserIdsDueForSync(25, 25, "NONE", 20)).thenReturn(List.of(1L, 2L, 3L));
        when(financialSyncService.sync(2L)).thenThrow(new RuntimeException("동기화 실패"));
        FinancialSyncBatchScheduler scheduler = new FinancialSyncBatchScheduler(
                connectedAccountMapper, financialSyncService, true, 20, 25);

        scheduler.runBatch();

        verify(financialSyncService).sync(1L);
        verify(financialSyncService).sync(2L);
        verify(financialSyncService).sync(3L);
    }

    @Test
    @DisplayName("enabled=false 면 대상 조회조차 하지 않는다")
    void doesNothingWhenDisabled() {
        FinancialSyncBatchScheduler scheduler = new FinancialSyncBatchScheduler(
                connectedAccountMapper, financialSyncService, false, 20, 25);

        scheduler.runBatch();

        verifyNoInteractions(connectedAccountMapper, financialSyncService);
    }

    @Test
    @DisplayName("minIntervalMinutes·maxUsersPerTick 를 findUserIdsDueForSync 에 그대로 전달한다")
    void passesThresholdAndMaxUsersPerTickToMapper() {
        when(connectedAccountMapper.findUserIdsDueForSync(25, 25, "NONE", 5)).thenReturn(List.of());
        FinancialSyncBatchScheduler scheduler = new FinancialSyncBatchScheduler(
                connectedAccountMapper, financialSyncService, true, 5, 25);

        scheduler.runBatch();

        verify(connectedAccountMapper).findUserIdsDueForSync(25, 25, "NONE", 5);
    }

    @Test
    @DisplayName("설정된 쿨다운(minIntervalMinutes)을 그대로 넘긴다 — 하드코딩된 값이 아니다")
    void passesConfiguredCooldownToMapper() {
        when(connectedAccountMapper.findUserIdsDueForSync(90, 90, "NONE", 20)).thenReturn(List.of());
        FinancialSyncBatchScheduler scheduler = new FinancialSyncBatchScheduler(
                connectedAccountMapper, financialSyncService, true, 20, 90);

        scheduler.runBatch();

        verify(connectedAccountMapper).findUserIdsDueForSync(90, 90, "NONE", 20);
    }
}
