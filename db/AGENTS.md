# db — 스키마 규칙

**이 폴더의 SQL이 스키마의 실행 기준 원본이다.** ERD 엑셀·기획 문서와 다르면 **여기 SQL이 맞다.**

## 파일

| 파일 | 용도 |
|---|---|
| `00_init_local_db.sql` | 로컬 DB·계정 생성 (관리자 계정으로 1회 실행) |
| `schema.sql` | 전체 DDL |
| `seed.sql` | 개발용 초기 데이터 |
| `seed_category.sql` | 소비 카테고리 (대분류 12 · 소분류 46). 아래 시드들이 이름으로 참조하므로 **선행 필수** |
| `seed_mission_pool.sql` | 데일리 미션 풀. 비어 있으면 미션 배정 자체가 안 된다 |
| `seed_merchant_keyword_rule.sql` | 가맹점명 키워드 → 카테고리 규칙(선택). 비어 있으면 카테고리화 4단계가 항상 미스로 넘어간다 |
| `merchant_directory/` | 상가(상권)정보 상호 사전 생성·적재 도구(선택). 비어 있으면 LLM 카테고리화가 힌트 없이 기존과 똑같이 동작한다. `README.md` 참고 |
| `seed_notification_demo.sql` | 알림 화면 시연용 데이터 (선택, 팀 공용 seed와 분리) |
| `seed_local_demo.sql` | 로컬 시연·검증용 한 방 시드 (선택). 아래 「로컬 시연 데이터」 참고 |
| `seed_demo_transactions.sql` | **구버전.** 거래내역만 넣는다. `seed_local_demo.sql` 이 이걸 포함하므로 새로 쓸 일은 없다 |
| `seed_transaction_mock_local.sql` | 특정 사용자(`MOCK-LOCAL-U1-%`) 거래 목업 (선택) |
| `migration/YYYYMMDD_<변경요약>.sql` | 스키마 변경분. **`schema.sql` 에 반영하지 않는 것이 원칙** |

## 신규 설치 순서

`00_init_local_db.sql` → `schema.sql` → `seed.sql` → **`migration/` 을 파일명(날짜)순으로 전부**

**마지막 단계를 빼면 컬럼이 모자란 DB 가 된다.** `schema.sql` 은 팀에 공유된 2026-08-03 이후로
갱신하지 않으므로, 그 뒤의 변경은 전부 `migration/` 에만 있다.

> ⚠ 예외 하나 — `20260805_add_account_name_to_connected_account.sql` 은 `schema.sql` 에도 반영돼 있어
> 신규 설치에서 실행하면 `Duplicate column name` 으로 죽는다. **건너뛴다.**
> 원칙이 정착되기 전에 만들어진 파일이라 그렇다. 이후 마이그레이션은 전부 실행 대상이다.

## 로컬 시연 데이터 — `seed_local_demo.sql`

**시드 SQL 을 새로 만들지 말고 이 파일 하나를 고친다.** 랭킹 모수용 가상 사용자, 사용자별 편차가
있는 거래내역, 미션 판정 이력·연속 성공일, 그룹 챌린지 2개, 월간 랭킹을 한 번에 넣는다.
같은 INSERT 를 여러 파일에 복사해 두면 어느 것이 최신인지 아무도 모르게 된다.

- **마이그레이션이 아니다.** 스키마를 바꾸지 않으므로 `migration/` 에 두지 않는다.
  신규 설치 필수 절차에도 넣지 않는다 — 원할 때만 돌리는 선택 파일이다.
- 선행: `seed.sql` · `seed_category.sql` · `seed_mission_pool.sql`. 맨 위 `seed_ready` 가 1 이어야 한다.
  **0 이어도 SQL 오류는 안 나고 조용히 0행이 들어간다.** 출력을 눈으로 확인한다.
- **몇 번을 돌려도 같은 상태가 된다(멱등).** 자기가 만든 것만 지우고 다시 넣는다.
  실계정·실 CODEF 거래·`MOCK-LOCAL-%` 거래는 건드리지 않는다.
- **구글 로그인 계정은 하드코딩하지 않는다.** 매칭 키인 구글 `sub` 는 사람마다 달라 SQL 에 박으면
  그 계정을 가진 사람만 쓸 수 있고 개인정보가 git 에 남는다. 실행 시점의
  `social_provider <> 'DEMO' AND status='ACTIVE'` 사용자를 찾아 동의·계좌·거래·미션이력을 백필한다.
  ⇒ **한 번 실행 → 구글로 로그인 → 다시 실행** 순서로 쓴다. 두 번째 실행에서 내 계정에 데이터가 붙는다.
- **오늘치 미션 행도 이 파일이 넣는다(5-2).** 미션 배정 스케줄러는 cron 시각과 앱 기동 시점에만
  돈다. 백엔드가 떠 있는 동안 로그인해 새로 생긴 사용자는 오늘 배정이 없어
  `GET /api/missions/today` 가 400(`TODAY_MISSION_NOT_FOUND`)을 낸다.
  **재기동 대신 이 파일을 다시 돌린다.** 날이 바뀌었을 때도 마찬가지다.
- 자기가 넣은 행에 표식을 남긴다. 지울 때 그것만 지운다.
  `tbl_user.social_provider='DEMO'` · `codef_tr_key LIKE 'DUMMY-%'` ·
  `tbl_user_mission_info.assignment_reason='LOCAL_SEED'` · `invite_code LIKE 'SEED%'`
- ⚠ **`seed_demo_transactions.sql` 을 이 파일 뒤에 돌리지 않는다.** 둘 다 `DUMMY-%` 키를 쓰는데
  형식이 다르다(구버전 `DUMMY-{user}-{개월수}-{번호}` · 이 파일 `DUMMY-{user}-{YYYYMM}-{번호}`).
  뒤에 돌리면 서로 다른 키로 인식돼 **같은 거래가 두 벌 쌓인다.** 순서가 꼬였으면
  `DELETE FROM tbl_transaction WHERE codef_tr_key LIKE 'DUMMY-%';` 후 이 파일만 다시 돌린다.
- 되돌리는 방법을 파일 머리말에 적어 둔다.

## 규칙

- 테이블 `tbl_` 접두, 컬럼 snake_case (MyBatis 가 camelCase 로 자동 매핑)
- 문자셋 `utf8mb4` / 콜레이션 `utf8mb4_general_ci`
- **이미 팀에 공유된 스키마는 `schema.sql` 을 직접 고치지 말고 `migration/` 에 변경분을 추가한다.**
  `schema.sql` 헤더에도 같은 문구가 있다. **양쪽에 같은 DDL 을 두면 신규 설치에서 중복 컬럼 오류가 난다** —
  DDL 은 항상 한 곳에서만 관리한다.
- **새 마이그레이션 머리말에 신규 설치 시 실행 여부를 적는다.** 신규 합류자가 어느 파일을 돌려야 할지
  파일만 열어보면 알 수 있어야 한다.
- 스키마를 바꿀 때 반영 순서: **SQL → 매퍼 XML → 문서**
- **계정·비밀번호를 SQL 에 넣지 않는다. 예외 없다.** `00_init_local_db.sql` 은 비밀번호 자리에
  `CHANGE_ME_DB_PASSWORD` 플레이스홀더를 두며, 각자 실행 직전에 자기 값으로 바꿔 쓰고 커밋하지 않는다.
  같은 값을 `.env`(`MYSQL_PASSWORD`) 와 `application-local.properties`(`jdbc.password`) 에도 넣어야 한다.
