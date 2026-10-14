package com.kb.tangtang.transaction.service;

import com.kb.tangtang.transaction.client.llm.CategoryAssignmentDto;
import com.kb.tangtang.transaction.client.llm.LlmClassificationClient;
import com.kb.tangtang.transaction.domain.Category;
import com.kb.tangtang.transaction.domain.DirectoryHint;
import com.kb.tangtang.transaction.domain.Transaction;
import com.kb.tangtang.transaction.mapper.CategoryMapper;
import com.kb.tangtang.transaction.mapper.LlmCategorizationJobItemMapper;
import com.kb.tangtang.transaction.mapper.LlmCategorizationJobMapper;
import com.kb.tangtang.transaction.mapper.TransactionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmCategorizationProcessingServiceImplTest {

    private LlmCategorizationJobMapper jobMapper;
    private LlmCategorizationJobItemMapper jobItemMapper;
    private TransactionMapper transactionMapper;
    private CategoryMapper categoryMapper;
    private LlmClassificationClient client;
    private MerchantDirectoryService merchantDirectoryService;
    private LlmCategorizationJobStateService jobStateService;
    private LlmCategorizationProcessingServiceImpl service;

    @BeforeEach
    void setUp() {
        jobMapper = mock(LlmCategorizationJobMapper.class);
        jobItemMapper = mock(LlmCategorizationJobItemMapper.class);
        transactionMapper = mock(TransactionMapper.class);
        categoryMapper = mock(CategoryMapper.class);
        client = mock(LlmClassificationClient.class);
        merchantDirectoryService = mock(MerchantDirectoryService.class);
        jobStateService = mock(LlmCategorizationJobStateService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneId.of("Asia/Seoul"));

        service = new LlmCategorizationProcessingServiceImpl(
                jobMapper, jobItemMapper, transactionMapper, categoryMapper, client,
                merchantDirectoryService, jobStateService, clock);

        when(jobStateService.claimProcessing(eq(1L), any())).thenReturn(1);
        when(categoryMapper.findAll()).thenReturn(List.of(
                Category.builder().id(5L).categoryName("카페/간식").parentId(1L).build()));
    }

    @Test
    @DisplayName("정상 처리: 선점 → classify → updateCategory → markFinished(COMPLETED)")
    void processesJobSuccessfully() {
        when(jobItemMapper.findTransactionIdsByJobId(1L)).thenReturn(List.of(10L, 11L));
        when(transactionMapper.findByIds(List.of(10L, 11L))).thenReturn(List.of(
                Transaction.builder().id(10L).merchantName("스타벅스").build(),
                Transaction.builder().id(11L).merchantName("정체불명").build()));
        when(client.classify(any(), any(), any())).thenReturn(List.of(
                CategoryAssignmentDto.builder().transactionId(10L).categoryId(5L).build(),
                CategoryAssignmentDto.builder().transactionId(11L).categoryId(null).build()));
        when(transactionMapper.updateCategory(10L, 5L, "LLM")).thenReturn(1);

        service.processJob(1L);

        verify(jobStateService).claimProcessing(eq(1L), any());
        verify(transactionMapper).updateCategory(10L, 5L, "LLM");
        verify(transactionMapper, never()).updateCategory(eq(11L), any(), any());
        verify(jobMapper).markFinished(eq(1L), eq("COMPLETED"), any());
    }

    /**
     * PROCESSING 선점은 반드시 별도 빈(REQUIRES_NEW)이 하고, 그것이 커밋된 뒤에야 나머지 작업이
     * 시작돼야 한다. processJob 이 자기 트랜잭션에서 jobMapper.markProcessing 을 직접 부르면
     * 작업이 끝날 때까지 그 행의 잠금을 쥐고 있어, 실패 시 markFailed(별도 커넥션)가 자기 잠금을
     * 기다리다 innodb_lock_wait_timeout 에 걸린다.
     *
     * 실제 잠금 대기는 진짜 MySQL 커넥션 두 개가 있어야 재현되므로 이 스위트의 범위 밖이다
     * (계획 문서 Global Constraints: 실제 DB 연결이 필요한 테스트는 만들지 않는다).
     * 여기서는 "선점은 별도 빈이 먼저, 그 다음에 나머지 작업" 이라는 순서·경로만 못 박는다.
     */
    @Test
    @DisplayName("선점은 별도 트랜잭션 빈이 먼저 수행하고, 매퍼를 직접 호출하지 않는다")
    void claimsViaSeparateBeanBeforeAnyWork() {
        when(jobItemMapper.findTransactionIdsByJobId(1L)).thenReturn(List.of(10L));
        when(transactionMapper.findByIds(List.of(10L))).thenReturn(List.of(
                Transaction.builder().id(10L).merchantName("스타벅스").build()));
        when(client.classify(any(), any(), any())).thenReturn(List.of(
                CategoryAssignmentDto.builder().transactionId(10L).categoryId(5L).build()));

        service.processJob(1L);

        /* 같은 트랜잭션에서 행 잠금을 잡는 경로가 남아 있으면 안 된다. */
        verify(jobMapper, never()).markProcessing(anyLong(), any());

        InOrder order = inOrder(jobStateService, jobItemMapper, client, jobMapper);
        order.verify(jobStateService).claimProcessing(eq(1L), any());
        order.verify(jobItemMapper).findTransactionIdsByJobId(1L);
        order.verify(client).classify(any(), any(), any());
        order.verify(jobMapper).markFinished(eq(1L), eq("COMPLETED"), any());
    }

    @Test
    @DisplayName("이미 다른 tick 이 이 작업을 가져갔으면(선점 0행) 아무것도 하지 않는다")
    void skipsWhenAlreadyClaimedByAnotherTick() {
        when(jobStateService.claimProcessing(eq(2L), any())).thenReturn(0);

        service.processJob(2L);

        verify(jobItemMapper, never()).findTransactionIdsByJobId(anyLong());
        verify(transactionMapper, never()).findByIds(any());
        verify(categoryMapper, never()).findAll();
        verify(client, never()).classify(any(), any(), any());
        verify(jobMapper, never()).markFinished(anyLong(), any(), any());
        verify(jobStateService, never()).markFailed(anyLong(), any());
    }

    @Test
    @DisplayName("LLM 이 목록에 없는 categoryId 를 돌려주면 그 건은 반영하지 않고 나머지는 정상 처리한다")
    void ignoresHallucinatedCategoryId() {
        when(jobItemMapper.findTransactionIdsByJobId(1L)).thenReturn(List.of(10L));
        when(transactionMapper.findByIds(List.of(10L))).thenReturn(List.of(
                Transaction.builder().id(10L).merchantName("스타벅스").build()));
        when(client.classify(any(), any(), any())).thenReturn(List.of(
                CategoryAssignmentDto.builder().transactionId(10L).categoryId(9999L).build()));

        service.processJob(1L);

        verify(transactionMapper, never()).updateCategory(eq(10L), any(), any());
        verify(jobMapper).markFinished(eq(1L), eq("COMPLETED"), any());
    }

    @Test
    @DisplayName("LLM 이 이 작업에 속하지 않은 transactionId 를 돌려주면 그 건은 반영하지 않는다")
    void ignoresForeignTransactionId() {
        when(jobItemMapper.findTransactionIdsByJobId(1L)).thenReturn(List.of(10L));
        when(transactionMapper.findByIds(List.of(10L))).thenReturn(List.of(
                Transaction.builder().id(10L).merchantName("스타벅스").build()));
        /* 77L 은 다른 사용자·다른 작업의 거래다 — updateCategory 에는 사용자 범위 조건이 없으므로
           여기서 막지 않으면 남의 거래 카테고리를 덮어쓴다. */
        when(client.classify(any(), any(), any())).thenReturn(List.of(
                CategoryAssignmentDto.builder().transactionId(77L).categoryId(5L).build(),
                CategoryAssignmentDto.builder().transactionId(10L).categoryId(5L).build()));

        service.processJob(1L);

        verify(transactionMapper, never()).updateCategory(eq(77L), any(), any());
        verify(transactionMapper).updateCategory(10L, 5L, "LLM");
        verify(jobMapper).markFinished(eq(1L), eq("COMPLETED"), any());
    }

    @Test
    @DisplayName("작업에 속한 거래가 하나도 없으면 LLM 을 부르지 않고 바로 COMPLETED 로 마감한다")
    void completesImmediatelyWhenJobHasNoTransactions() {
        when(jobItemMapper.findTransactionIdsByJobId(1L)).thenReturn(List.of());

        service.processJob(1L);

        /* findByIds 에 빈 목록을 넘기면 `WHERE id IN ()` 으로 SQL 이 깨진다. 애초에 부르지 않는다. */
        verify(transactionMapper, never()).findByIds(any());
        verify(client, never()).classify(any(), any(), any());
        verify(jobMapper).markFinished(eq(1L), eq("COMPLETED"), any());
        verify(jobStateService, never()).markFailed(anyLong(), any());
    }

    @Test
    @DisplayName("classify 호출 자체가 예외를 던지면 별도 트랜잭션 빈으로 FAILED 마감 후 예외를 다시 던진다")
    void marksFailedWhenClientThrows() {
        when(jobItemMapper.findTransactionIdsByJobId(1L)).thenReturn(List.of(10L));
        when(transactionMapper.findByIds(List.of(10L))).thenReturn(List.of(
                Transaction.builder().id(10L).merchantName("스타벅스").build()));
        when(client.classify(any(), any(), any())).thenThrow(new RuntimeException("network error"));

        assertThrows(RuntimeException.class, () -> service.processJob(1L));

        /*
         * FAILED 마감은 REQUIRES_NEW 를 가진 별도 빈이 해야 한다 — 같은 트랜잭션의 jobMapper 로
         * 직접 마감하면 아래 rethrow 가 그 마감까지 롤백시켜 작업이 PENDING 으로 부활한다.
         * 그리고 그 별도 빈이 잠금 대기 없이 쓰려면 선점이 먼저 커밋돼 있어야 한다 — 선점 역시
         * 같은 빈의 REQUIRES_NEW 를 거친다(순서는 claimsViaSeparateBeanBeforeAnyWork 가 검증).
         * (독립 트랜잭션 커밋 여부·실제 잠금 대기는 실제 DB 가 필요해 여기서 검증하지 않는다 —
         *  애너테이션 검증은 LlmCategorizationJobStateServiceTest 가 담당한다.)
         */
        InOrder order = inOrder(jobStateService);
        order.verify(jobStateService).claimProcessing(eq(1L), any());
        order.verify(jobStateService).markFailed(eq(1L), any());
        verify(jobMapper, never()).markFinished(anyLong(), eq("FAILED"), any());
    }

    @Test
    @DisplayName("분류할 거래로 힌트를 조회해 client 에 그대로 넘긴다")
    void passesDirectoryHintsToClient() {
        List<Transaction> transactions = List.of(Transaction.builder().id(10L).merchantName("성민식당").build());
        Map<Long, DirectoryHint> hints = Map.of(10L, DirectoryHint.builder()
                .matchType(DirectoryHint.EXACT).matchedName("성민식당").build());
        when(jobItemMapper.findTransactionIdsByJobId(1L)).thenReturn(List.of(10L));
        when(transactionMapper.findByIds(List.of(10L))).thenReturn(transactions);
        when(merchantDirectoryService.findHints(transactions)).thenReturn(hints);
        when(client.classify(any(), any(), any())).thenReturn(List.of());

        service.processJob(1L);

        verify(client).classify(eq(transactions), any(), eq(hints));
    }
}
