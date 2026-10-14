package com.kb.tangtang.transaction.service;

import com.kb.tangtang.transaction.client.llm.CategoryAssignmentDto;
import com.kb.tangtang.transaction.client.llm.LlmClassificationClient;
import com.kb.tangtang.transaction.domain.Category;
import com.kb.tangtang.transaction.domain.CategorySource;
import com.kb.tangtang.transaction.domain.DirectoryHint;
import com.kb.tangtang.transaction.domain.LlmJobStatus;
import com.kb.tangtang.transaction.domain.Transaction;
import com.kb.tangtang.transaction.mapper.CategoryMapper;
import com.kb.tangtang.transaction.mapper.LlmCategorizationJobItemMapper;
import com.kb.tangtang.transaction.mapper.LlmCategorizationJobMapper;
import com.kb.tangtang.transaction.mapper.TransactionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 이슈 #147 후속 — LLM 작업 1건 처리.
 *
 * 카테고리 반영은 반드시 TransactionMapper.updateCategory 하나로만 한다 — 재동기화용 update() 를
 * 쓰면 안 된다(그 경로가 카테고리를 지우는 버그였고 이미 고쳤다. 이슈 #147 계획 문서 참고).
 *
 * 트랜잭션 구성: PROCESSING 선점과 FAILED 마감은 LlmCategorizationJobStateService 의
 * REQUIRES_NEW 독립 트랜잭션이 담당하고, 그 사이의 실제 작업(거래·카테고리 조회 → 분류 →
 * 카테고리 반영 → COMPLETED 마감)만 이 메서드의 @Transactional 안에서 돈다.
 * 이유는 LlmCategorizationJobStateService 주석 참고.
 */
@Service
public class LlmCategorizationProcessingServiceImpl implements LlmCategorizationProcessingService {

    private final LlmCategorizationJobMapper jobMapper;
    private final LlmCategorizationJobItemMapper jobItemMapper;
    private final TransactionMapper transactionMapper;
    private final CategoryMapper categoryMapper;
    private final LlmClassificationClient client;
    private final MerchantDirectoryService merchantDirectoryService;
    private final LlmCategorizationJobStateService jobStateService;
    private final Clock clock;

    @Autowired
    public LlmCategorizationProcessingServiceImpl(LlmCategorizationJobMapper jobMapper,
                                                   LlmCategorizationJobItemMapper jobItemMapper,
                                                   TransactionMapper transactionMapper,
                                                   CategoryMapper categoryMapper,
                                                   LlmClassificationClient client,
                                                   MerchantDirectoryService merchantDirectoryService,
                                                   LlmCategorizationJobStateService jobStateService) {
        this(jobMapper, jobItemMapper, transactionMapper, categoryMapper, client, merchantDirectoryService,
                jobStateService, Clock.systemDefaultZone());
    }

    /** 테스트에서 시간을 고정하기 위한 생성자. */
    LlmCategorizationProcessingServiceImpl(LlmCategorizationJobMapper jobMapper,
                                           LlmCategorizationJobItemMapper jobItemMapper,
                                           TransactionMapper transactionMapper,
                                           CategoryMapper categoryMapper,
                                           LlmClassificationClient client,
                                           MerchantDirectoryService merchantDirectoryService,
                                           LlmCategorizationJobStateService jobStateService,
                                           Clock clock) {
        this.jobMapper = jobMapper;
        this.jobItemMapper = jobItemMapper;
        this.transactionMapper = transactionMapper;
        this.categoryMapper = categoryMapper;
        this.client = client;
        this.merchantDirectoryService = merchantDirectoryService;
        this.jobStateService = jobStateService;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void processJob(long jobId) {
        /*
         * PROCESSING 선점은 반드시 별도 빈의 REQUIRES_NEW 트랜잭션으로 먼저 커밋한다 —
         * 이 메서드의 트랜잭션 안에서 UPDATE 하면 작업이 끝날 때까지 그 행의 InnoDB 잠금을 쥐고 있어,
         * 아래 catch 의 markFailed(역시 REQUIRES_NEW = 다른 커넥션)가 자기 자신이 쥔 잠금을 기다리다
         * innodb_lock_wait_timeout(기본 50초)에 걸린다.
         * (LlmCategorizationJobStateService 주석 참고)
         *
         * REQUIRES_NEW 라 스프링이 여기서 바깥 트랜잭션을 잠시 중단(suspend)한다. 바깥은 아직
         * 아무 SQL 도 실행하지 않아 잠금이 없으므로 중단해도 잃을 것이 없다.
         */
        int claimed = jobStateService.claimProcessing(jobId, LocalDateTime.now(clock));
        if (claimed == 0) {
            /* 이미 다른 실행 주체가 이 작업을 가져갔다. 중복 처리하지 않는다. */
            return;
        }

        try {
            List<Long> transactionIds = jobItemMapper.findTransactionIdsByJobId(jobId);
            if (transactionIds.isEmpty()) {
                /*
                 * 분류할 거래가 하나도 없는 작업이다(등록 시점 경합으로 아이템이 비어 있을 수 있다).
                 * findByIds 에 빈 목록을 넘기면 `WHERE id IN ()` 이 되어 SQL 이 깨지고,
                 * LLM 호출도 빈 배치로 돈만 나간다. 여기서 바로 정상 종료한다.
                 */
                jobMapper.markFinished(jobId, LlmJobStatus.COMPLETED, LocalDateTime.now(clock));
                return;
            }

            List<Transaction> transactions = transactionMapper.findByIds(transactionIds);
            List<Category> categories = categoryMapper.findAll();
            Set<Long> validCategoryIds = categories.stream().map(Category::getId).collect(Collectors.toSet());
            Set<Long> jobTransactionIds = Set.copyOf(transactionIds);

            /*
             * 상가(상권)정보 근거. 조회 실패·사전 미적재면 빈 맵이 와서 지금과 똑같이 분류된다
             * (MerchantDirectoryService 가 예외를 삼킨다 — 힌트 때문에 작업이 FAILED 가 되지 않는다).
             */
            Map<Long, DirectoryHint> hints = merchantDirectoryService.findHints(transactions);
            List<CategoryAssignmentDto> assignments = client.classify(transactions, categories, hints);

            for (CategoryAssignmentDto assignment : assignments) {
                if (assignment.getCategoryId() == null) {
                    continue;
                }
                if (!jobTransactionIds.contains(assignment.getTransactionId())) {
                    /*
                     * LLM 이 이 작업에 속하지 않은 거래 id 를 돌려줬다 — 반영하지 않는다.
                     * updateCategory 에는 사용자 범위 조건이 없어서, 그대로 쓰면 남의 거래를 덮어쓴다.
                     * 가맹점명·적요는 외부에서 들어온 자유 텍스트라 프롬프트 주입 통로이기도 하다.
                     */
                    continue;
                }
                if (!validCategoryIds.contains(assignment.getCategoryId())) {
                    /* LLM 이 목록에 없는 id 를 지어냈다 — 이 건은 반영하지 않고 넘어간다. */
                    continue;
                }
                transactionMapper.updateCategory(
                        assignment.getTransactionId(), assignment.getCategoryId(), CategorySource.LLM);
            }

            jobMapper.markFinished(jobId, LlmJobStatus.COMPLETED, LocalDateTime.now(clock));
        } catch (RuntimeException e) {
            /*
             * FAILED 마감도 별도 빈의 REQUIRES_NEW 트랜잭션으로 한다 —
             * 아래 rethrow 가 이 메서드의 트랜잭션을 롤백시키므로, 같은 트랜잭션에서 마감하면
             * FAILED 가 통째로 되돌아가 작업이 PENDING 으로 부활한다.
             * 위에서 선점을 이미 커밋해 잠금을 놓았으므로 여기서 잠금 대기 없이 바로 기록된다.
             * (LlmCategorizationJobStateService 주석 참고)
             */
            jobStateService.markFailed(jobId, LocalDateTime.now(clock));
            throw e;
        }
    }
}
