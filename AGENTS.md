# 탕탕 · 지갑재판소 — AI 에이전트 작업 규칙

> **이 파일이 규칙 원본이다.** `CLAUDE.md` · `GEMINI.md` 는 이 파일을 임포트하는 포인터일 뿐이다.
> 규칙을 바꿀 일이 있으면 **반드시 이 파일을 고친다.**

## 프로젝트

새는 돈을 법정에 세우는 자산관리 서비스. 계좌·카드 거래내역에서 고정지출을 자동 탐지(기소)하고,
절약 시뮬레이션 → 챌린지·미션(집행) → 실제 절감액 검증(판결문=월간 리포트)까지 폐루프로 잇는다.
KB IT's Your Life 7기 종합실무 프로젝트. 6인 팀이 동시에 개발한다.

## ❌ 절대 금지 기술 (사용 시 평가 감점 — 예외 없음)

| 금지 | 반드시 사용 |
|------|------------|
| Spring Boot (`spring-boot-*`, `@SpringBootApplication`) | **Spring Legacy (Spring MVC)** — JavaConfig 수동 설정, war 배포 |
| JPA / Hibernate (`@Entity`, `JpaRepository`) | **MyBatis** (Mapper 인터페이스 + XML) |
| React (웹) | **Vue3** |

제안·코드·의존성 어디에도 위 항목이 등장하면 즉시 멈추고 오른쪽 대체 기술로 바꾼다.
`build.gradle` 에 `spring-boot-starter-*` 가 들어가면 그 자체로 감점이다.

## 기술 스택 (고정 — 임의 변경 금지)

- **Backend**: Java 17 · Spring Framework 5.3.39 (Spring MVC) · MyBatis 3.5.16 / mybatis-spring 2.1.2 · MySQL 8 · HikariCP · **Gradle** · Tomcat 9.x (war)
- **Frontend**: **Node 24.14.1** (팀 표준 — `.nvmrc` 참고) · Vue3 + Vite · Pinia · Vue Router · axios
- **모듈 간 비동기**: Spring Event (`ApplicationEventPublisher` + `@EventListener`/`@Async`) — 메시지 브로커 없음
- **실시간 알림**: SSE (`SseEmitter`). `WebConfig` 에 `setAsyncSupported(true)` 적용됨
- **알림 실패 대비**: `tbl_notification_dlq` + 스케줄 재시도 배치
- **API 문서**: Springfox 2.9.2 → `/swagger-ui.html`. **springdoc 은 금지 대상인 Boot 모듈을 끌고 와 사용 불가**
- 빌드에 **JDK 17 필수** (toolchain 강제. JDK 21만 설치된 환경은 빌드 실패)
- 프론트는 **Node 24.12 이상 필수**. 팀 표준은 **24.14.1** — 버전이 다르면 빌드 산출물이 미묘하게 달라진다

## 폴더 구조

```
Monorepo/                 Gradle 멀티프로젝트 (루트의 settings.gradle·gradlew·gradle/ 는 정상 위치, 옮기지 말 것)
├─ apps/api/              백엔드. Spring Legacy, war
├─ apps/web/              프론트. Vite + Vue3
├─ db/                    스키마 SQL — 실행 기준 원본
├─ docs/                  API 규격 · 도메인 용어집
└─ .claude/skills/        반복 작업 절차 (팀 공유)
```

폴더별 상세 규칙은 각 폴더의 `AGENTS.md` 를 읽는다.

## 실행 명령어

```bash
./gradlew :apps:api:build      # 백엔드 빌드 → apps/api/build/libs/*.war
./gradlew :apps:api:test       # 백엔드 테스트
cd apps/web && npm install     # 프론트 의존성
cd apps/web && npm run dev     # 프론트 개발 서버 :5173, /api 는 :8080 으로 프록시
```

최초 세팅 (신규 합류자):
```bash
# 1) DB 비밀번호를 정하고 db/00_init_local_db.sql 의 CHANGE_ME_DB_PASSWORD 2곳을 그 값으로 바꾼다
mysql -u root -p < db/00_init_local_db.sql            # tangtang DB + 전용 계정

# 2) 테이블 33개 + 초기 데이터
mysql -u tangtang -p tangtang < db/schema.sql
mysql -u tangtang -p tangtang < db/seed.sql

# 3) 스키마 변경분 — db/migration/ 을 파일명(날짜)순으로 적용한다. 빼먹으면 컬럼이 모자란 DB 가 된다
#    schema.sql 은 팀에 공유된 뒤로 갱신하지 않는다(아래 「스키마 변경」 참고)
for f in db/migration/*.sql; do
  mysql -u tangtang -p tangtang < "$f"      # 파일마다 비밀번호를 묻는다
done

# 4) 백엔드 설정 — jdbc.password 에 1)에서 정한 같은 값을 넣는다
cp apps/api/src/main/resources/application-local.properties.example \
   apps/api/src/main/resources/application-local.properties

# 5) Docker 로 띄울 때만 — MYSQL_PASSWORD 에도 같은 값을 넣는다
cp .env.example .env
```

> 그룹 채팅(이슈 #174)이 들어간 뒤로는 **redis 컨테이너가 필요하다.**
> `docker compose up` 을 다시 돌리면 따라 올라온다. 로컬에서 도커 밖 API 를 띄운다면
> `application-local.properties` 의 `redis.host=localhost` 를 확인한다.

> ⚠ **`20260805_add_account_name_to_connected_account.sql` 은 건너뛴다.** 이 변경만 `schema.sql` 에도
> 반영돼 있어 신규 설치에서 실행하면 `Duplicate column name` 으로 죽는다.
> **각 마이그레이션 머리말에 신규 설치 시 실행 여부가 적혀 있으니 그것을 따른다.**
> 이후 추가되는 마이그레이션은 전부 실행 대상이다.

**PowerShell 은 `<` 리다이렉션을 지원하지 않는다.** Windows 에서는 mysql 의 `source` 를 쓴다
(경로는 슬래시, 한글 주석이 깨지지 않게 `--default-character-set=utf8mb4` 를 붙인다):
```powershell
mysql -u root -p -e "source D:/.../db/00_init_local_db.sql"
mysql -u tangtang -p tangtang --default-character-set=utf8mb4 -e "source D:/.../db/schema.sql"
mysql -u tangtang -p tangtang --default-character-set=utf8mb4 -e "source D:/.../db/seed.sql"

# 3) 마이그레이션 — 날짜순. 위 ⚠ 에 적힌 건너뛸 파일은 제외한다
Get-ChildItem db/migration/*.sql | Sort-Object Name | ForEach-Object {
    mysql -u tangtang -p tangtang --default-character-set=utf8mb4 `
      -e "source $($_.FullName -replace '\\','/')"
}
```
`Get-Content | mysql` 은 PowerShell 5.1 의 기본 인코딩이 UTF-8 이 아니라 한글 주석이 깨진다.

> 비밀번호는 **위 3곳이 모두 같아야** 한다. 바꾼 `00_init_local_db.sql` 은 커밋하지 않는다.
> 이미 계정이 있는데 비밀번호를 바꾸고 싶다면 파일을 고쳐 재실행하거나 아래 한 줄이면 된다.
> ```
> mysql -u root -p -e "ALTER USER 'tangtang'@'localhost' IDENTIFIED BY '새비밀번호'; FLUSH PRIVILEGES;"
> ```

확인: `mysql -u tangtang -p tangtang -e "SHOW TABLES"` → **2)까지만 했으면 33개, 3) 마이그레이션까지 적용하면 45개**
(마이그레이션이 테이블 12개를 더 만든다: `tbl_card` · `tbl_card_bill` · `tbl_codef_connection` · `tbl_financial_sync_history` ·
`tbl_fixed_expense_payment_reminder` · `tbl_industry_category_map` · `tbl_llm_categorization_job` ·
`tbl_llm_categorization_job_item` · `tbl_merchant_directory` · `tbl_merchant_keyword_rule` · `tbl_mission_certificate_title` · `tbl_user_mission_analysis`)
마이그레이션까지 들어갔는지 확인: `mysql -u tangtang -p tangtang -e "DESC tbl_user"` → `social_name` · `tutorial_seen_at` 이 보여야 한다
연결 확인: `GET http://localhost:8080/api/health` → `{"success":true,"data":{"status":"UP",...}}`

## 작업 프로토콜

1. 코드를 쓰기 전에 **관련 기존 코드·문서를 먼저 읽는다.** 유사 기능이 이미 있는지 확인한다.
2. 계획을 짧게 요약해 보여주고 시작한다. **추측으로 구조를 바꾸지 않는다.**
3. 기능 구현에는 **단위 테스트를 동반**한다.
4. 확실하지 않은 값(포트·경로·버전·컬럼명)은 지어내지 말고 실제 파일을 확인하거나 질문한다.
5. 6명이 병렬로 작업한다. 담당 모듈 밖의 파일을 고칠 때는 먼저 알린다.

## 코드 스타일 (팀 컨벤션)

| 구분 | 규칙 | 예 |
|---|---|---|
| Java 클래스 | `PascalCase` | `UserController`, `FixedExpenseService` |
| Java 메서드·변수 | `camelCase` | `getUserInfo()`, `fixedExpenseList` |
| Vue 컴포넌트 | `PascalCase` (**두 단어 이상**) | `FixedExpenseCard.vue`, `UserList.vue` |
| Vue 반응형 변수 | `camelCase` | `userName`, `isLoading` |
| 상수 (공통) | `UPPER_SNAKE_CASE` | `MAX_USER_LIMIT`, `API_URL` |
| Vue 이벤트·prop | `kebab-case` | `@click-submit`, `user-id` |

- 들여쓰기 **4칸**, 세미콜론 사용, 문자열은 **싱글 쿼트**
- 프론트는 **Prettier + ESLint** 로 포맷을 강제한다 (`apps/web`)

## API 응답 규칙

모든 REST 응답은 공통 래퍼 `com.kb.tangtang.common.dto.ApiResponse` 로 감싼다. **raw 객체 반환 금지.**

```json
성공  { "success": true,  "data": { ... } }
실패  { "success": false, "code": "NOT_FOUND", "message": "..." }
```

업무 규칙 위반은 `BusinessException(code, message)` 를 던진다 → 400 으로 자동 변환된다.
전역 예외 처리는 `common/exception/CommonExceptionAdvice` 한 곳에서만 한다.

## Git 규칙 (팀 컨벤션)

### 브랜치
| 브랜치 | 용도 |
|---|---|
| `main` | 배포 가능한 안정 버전. **직접 push 금지** |
| `dev` | 개발 통합 브랜치. feature 병합 대상 |
| `feature/{issueNumber}-{domainName}-{detail}` | 기능 개발 (예: `feature/12-fixedexpense-detection`) |
| `fix/{issueNumber}-{detail}` | 버그 수정 (예: `fix/34-mapper-null`) |

- 작업 시작 전 **최신 `dev` 를 pull** 한다.
- 모든 작업은 브랜치 기반. `main` 에 직접 push 하지 않는다.

### 커밋
형식: `type: 작업 내용` (한국어)

| 타입 | 설명 | 타입 | 설명 |
|---|---|---|---|
| `feat` | 새로운 기능 추가 | `test` | 테스트 코드 추가/수정 |
| `fix` | 버그 수정 | `chore` | 빌드·패키지 설정 등 기타 |
| `docs` | 문서 수정 (코드 변경 없음) | `design` | CSS 등 UI 디자인 변경 |
| `style` | 포맷팅·세미콜론 등 (논리 변경 없음) | `comment` | 주석 추가/변경 |
| `refactor` | 리팩토링 (기능 변화 없음) | `rename` | 파일·폴더명 수정/이동만 |
| `!HOTFIX` | 치명적 버그 긴급 수정 | `remove` | 파일 삭제만 수행 |

예) `feat: 고정지출 탐지 룰 기반 매칭 구현`

### Pull Request
- 기능 단위 작업 완료 시 **`dev` 기준**으로 PR 생성
- 제목: `[타입] 제목` — 이슈 번호를 포함하면 자동 링크가 걸린다. 예) `[feat] 고정지출 탐지 API 구현 #12`
- **최소 1명 이상 리뷰 후 merge.** 리뷰 코멘트 반영 후 병합한다.
- 문서·주석·커밋 메시지·PR 은 **한국어**

> 기술적 의견이 갈리면 근거를 들어 논의하고 **다수결로 결정**한다.

### 커밋 금지 목록
- 시크릿: CODEF 키, DB 비밀번호, `application-local.properties`, `*.key`, `.env`
- 빌드 산출물: `build/`, `.gradle/`, `node_modules/`, `dist/`
- 개인 설정: `.idea/`, `CLAUDE.local.md`, `AGENTS.override.md`, `.claude/settings.local.json`

## 참조 문서 (내용을 이 파일로 복사하지 말 것)

| 문서 | 내용 |
|---|---|
| `apps/api/AGENTS.md` | 백엔드 패키지 구조·MyBatis·모듈 경계 |
| `apps/web/AGENTS.md` | Vue3 규칙·디자인 토큰·API 호출 |
| `db/AGENTS.md` | 스키마 원본 규칙·네이밍 |
| `docs/DOMAIN_GLOSSARY.md` | 한글 용어 ↔ 영문 코드명 ↔ 테이블명 |
| `docs/AI_TOOLING_GUIDE.md` | 이 규칙 파일들이 각각 무엇이고 어떻게 쓰이는지 (신규 합류자용) |
| `docs/API_SPEC.md` | 엔드포인트 목록 |
