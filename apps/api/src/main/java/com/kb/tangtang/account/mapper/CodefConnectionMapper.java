package com.kb.tangtang.account.mapper;

import com.kb.tangtang.account.domain.CodefConnection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CodefConnectionMapper {

    /** UNIQUE(user_id, organization) 기준 upsert. 재연결하면 새 connectedId 로 덮고 다시 켠다. */
    int upsert(CodefConnection connection);

    List<CodefConnection> findActiveByUser(@Param("userId") long userId);

    CodefConnection findActive(@Param("userId") long userId, @Param("organization") String organization);

    /** 금융정보 동의 철회 등으로 연동을 모두 해제할 때. */
    int deactivateAllByUser(@Param("userId") long userId);
}
