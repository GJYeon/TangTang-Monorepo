package com.kb.tangtang.user.mapper;

import com.kb.tangtang.user.dto.UserDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * @Mapper 는 필수다. RootConfig 가 @MapperScan(annotationClass = Mapper.class) 로
 * 제한하고 있어 붙이지 않으면 빈으로 등록되지 않는다.
 */
@Mapper
public interface UserMapper {

    UserDto findBySocialId(@Param("socialProvider") String socialProvider,
                           @Param("providerUserId") String providerUserId);

    /** 실행 후 user.getId() 에 생성된 PK 가 채워진다. */
    void insert(UserDto user);

    UserDto findById(@Param("id") Long id);

    /**
     * 목서버 시나리오 키 수동 오버라이드 조회 (account.client.sync.UserOverridingScenarioKeyProvider 전용).
     * 값이 없으면(NULL) null 을 돌려주고, 호출부가 풀-나머지 로직으로 폴백한다.
     * UserDto 에 필드를 넣지 않는 이유: mock 전용 개념이라 /users/me 등 일반 응답에 섞이면 안 된다.
     */
    String findMockScenarioKeyById(@Param("id") Long id);

    /**
     * 금융 데이터 공급자(MOCK/CODEF) 조회 (account.client.FinancialSourceResolver 전용).
     * financial.client=hybrid 일 때 계좌 연동·동기화가 사용자별로 목서버/실 CODEF 를 고르는 근거다.
     * findMockScenarioKeyById 와 같은 이유로 UserDto 에 넣지 않는다.
     */
    String findFinancialDataSourceById(@Param("id") Long id);

    /**
     * 실명 갱신 (간편인증 화면). 반환값은 실제로 바뀐 행 수다 —
     * 0 이면 그 사용자가 없다는 뜻이라 서비스가 404 로 바꾼다.
     */
    int updateName(@Param("id") Long id, @Param("name") String name);

    /**
     * 닉네임(표시명) 갱신. 온보딩과 마이페이지 수정이 같은 경로를 쓴다.
     * 중복 검사는 하지 않는다 — 닉네임 중복 허용이 팀 결정이다.
     */
    int updateNickname(@Param("id") Long id, @Param("nickname") String nickname);

    Long findDifficultyIdByName(@Param("difficultyName") String difficultyName);

    int updateDifficulty(@Param("id") Long id, @Param("difficultyId") Long difficultyId);

    /**
     * 프로필 이미지 키 갱신. **삭제도 이 메서드로 한다** — null 을 넣으면 미설정으로 돌아간다.
     * 설정과 해제를 한 메서드로 두는 이유는 updateTutorialSeenAt 과 같다.
     *
     * @return 바뀐 행 수. 0 이면 그 사용자가 없다는 뜻이라 서비스가 404 로 바꾼다.
     */
    int updateProfileImageKey(@Param("id") Long id,
                              @Param("profileImageKey") String profileImageKey);

    /**
     * 튜토리얼 완료 시각 갱신 (이슈 #128).
     *
     * 완료·해제를 한 메서드로 처리한다 — `seenAt` 이 null 이면 「다시 보기」다.
     * `target` 은 {@link com.kb.tangtang.user.domain.TutorialType} 의 이름이며
     * 값이 enum 으로 한정되므로 XML 이 이 값을 그대로 비교해도 안전하다.
     *
     * @return 바뀐 행 수. 0 이면 그 사용자가 없다는 뜻이라 서비스가 404 로 바꾼다.
     */
    int updateTutorialSeenAt(@Param("id") Long id,
                             @Param("target") String target,
                             @Param("seenAt") java.time.LocalDateTime seenAt);

    /**
     * 맞춤 미션 개시 안내 상태를 한 문장으로 전이시킨다(이슈 #315 (1)).
     *
     * 자격 판정은 **요청이 아니라 DB 가 한다** - 같은 행의 `relative_mission_qualified_at`
     * 을 CASE 안에서 직접 본다. 이 컬럼은 `MissionAnalysisSnapshotService.markQualified()`
     * 가 `IS NULL` 가드로 한 번만 박는 되돌릴 수 없는 래치라, 한번 얻은 자격은 사라지지 않는다.
     * 덕분에 `PENDING` 에서 자격을 잃는 전이가 아예 생기지 않는다(이슈 #315 (2)).
     *
     * @return 바뀐 행 수. 0 이면 그 사용자가 없거나 ACTIVE 가 아니다.
     */
    int syncPersonalMissionUnlockStatus(@Param("id") Long id);

    int acknowledgePersonalMissionUnlock(@Param("id") Long id);

    /**
     * 회원 탈퇴. 상태 변경 · 식별정보 익명화 · 유니크 키 비우기를 한 문장으로 한다.
     * (DECISIONS.md 2026-08-13 회원 탈퇴)
     *
     * @return 갱신된 행 수. 0 이면 이미 탈퇴했거나 ACTIVE 가 아니다(멱등).
     */
    int withdraw(@Param("id") Long id,
                 @Param("withdrawnAt") java.time.LocalDateTime withdrawnAt);
}
