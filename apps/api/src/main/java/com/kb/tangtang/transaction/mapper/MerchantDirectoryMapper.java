package com.kb.tangtang.transaction.mapper;

import com.kb.tangtang.transaction.domain.MerchantDirectoryEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/** 상가(상권)정보 상호 사전. LLM 카테고리화 힌트 조회 전용. */
@Mapper
public interface MerchantDirectoryMapper {
    /** 정규화 이름 목록 중 사전에 있는 것만 돌려준다. names 는 비어 있으면 안 된다(IN () 가 된다). */
    List<MerchantDirectoryEntry> findByNames(@Param("names") Collection<String> names);
}
