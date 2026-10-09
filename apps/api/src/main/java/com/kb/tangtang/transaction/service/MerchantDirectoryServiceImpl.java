package com.kb.tangtang.transaction.service;

import com.kb.tangtang.transaction.domain.DirectoryHint;
import com.kb.tangtang.transaction.domain.MerchantDirectoryEntry;
import com.kb.tangtang.transaction.domain.Transaction;
import com.kb.tangtang.transaction.mapper.MerchantDirectoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 배치(최대 20건)의 조회 키를 모아 기본키 IN 쿼리 한 번으로 찾는다.
 * 선택 규칙: 정확 일치(변형 순서대로 첫 번째) → 가장 긴 앞부분 일치 → 없음.
 *
 * @Transactional 을 걸지 않는다 — 조회 예외를 여기서 삼키는데, 트랜잭션 프록시 경계를 넘으면
 * 호출부(LlmCategorizationProcessingServiceImpl.processJob)의 트랜잭션이 rollback-only 로 표시된다.
 */
@Service
public class MerchantDirectoryServiceImpl implements MerchantDirectoryService {

    private static final Logger log = LoggerFactory.getLogger(MerchantDirectoryServiceImpl.class);

    private final MerchantDirectoryMapper merchantDirectoryMapper;

    public MerchantDirectoryServiceImpl(MerchantDirectoryMapper merchantDirectoryMapper) {
        this.merchantDirectoryMapper = merchantDirectoryMapper;
    }

    @Override
    public Map<Long, DirectoryHint> findHints(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<String>> variantsByTransaction = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Transaction tx : transactions) {
            List<String> variants = MerchantNameVariants.of(tx.getMerchantName());
            if (variants.isEmpty()) {
                continue;
            }
            variantsByTransaction.put(tx.getId(), variants);
            for (String variant : variants) {
                keys.add(variant);
                keys.addAll(MerchantNameVariants.prefixes(variant));
            }
        }
        if (keys.isEmpty()) {
            return Map.of();
        }

        Map<String, MerchantDirectoryEntry> entries = new HashMap<>();
        try {
            for (MerchantDirectoryEntry entry : merchantDirectoryMapper.findByNames(keys)) {
                entries.put(entry.getMerchantNameNormalized(), entry);
            }
        } catch (RuntimeException e) {
            log.warn("상호 사전 조회 실패 — 힌트 없이 분류를 계속한다. 거래 {}건", transactions.size(), e);
            return Map.of();
        }

        Map<Long, DirectoryHint> hints = new HashMap<>();
        variantsByTransaction.forEach((transactionId, variants) -> {
            DirectoryHint hint = pick(variants, entries);
            if (hint != null) {
                hints.put(transactionId, hint);
            }
        });
        return hints;
    }

    private static DirectoryHint pick(List<String> variants, Map<String, MerchantDirectoryEntry> entries) {
        for (String variant : variants) {
            MerchantDirectoryEntry exact = entries.get(variant);
            if (exact != null) {
                return toHint(DirectoryHint.EXACT, exact);
            }
        }
        MerchantDirectoryEntry best = null;
        for (String variant : variants) {
            for (String prefix : MerchantNameVariants.prefixes(variant)) {
                MerchantDirectoryEntry candidate = entries.get(prefix);
                if (candidate != null) {
                    if (best == null || prefix.length() > best.getMerchantNameNormalized().length()) {
                        best = candidate;
                    }
                    break;
                }
            }
        }
        return best == null ? null : toHint(DirectoryHint.PREFIX, best);
    }

    private static DirectoryHint toHint(String matchType, MerchantDirectoryEntry entry) {
        return DirectoryHint.builder()
                .matchType(matchType)
                .matchedName(entry.getMerchantNameNormalized())
                .industryName(entry.getIndustryName() != null ? entry.getIndustryName() : entry.getIndustryCode())
                .suggestedCategoryId(entry.getCategoryId())
                .storeCount(entry.getStoreCount())
                .share(entry.getTopShare())
                .build();
    }
}
