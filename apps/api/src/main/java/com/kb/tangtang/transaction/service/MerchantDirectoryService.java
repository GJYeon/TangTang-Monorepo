package com.kb.tangtang.transaction.service;

import com.kb.tangtang.transaction.domain.DirectoryHint;
import com.kb.tangtang.transaction.domain.Transaction;

import java.util.List;
import java.util.Map;

/** 상가(상권)정보 상호 사전에서 LLM 카테고리화 힌트를 찾는다. */
public interface MerchantDirectoryService {
    /**
     * @return 거래 id → 힌트. 힌트를 못 찾은 거래는 맵에 없다.
     *         조회에 실패하면 예외 대신 빈 맵을 돌려준다(힌트는 부가 기능이라 분류를 막지 않는다).
     */
    Map<Long, DirectoryHint> findHints(List<Transaction> transactions);
}
