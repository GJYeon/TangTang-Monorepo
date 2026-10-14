# API 규격

모든 응답은 공통 래퍼 `com.kb.tangtang.common.dto.ApiResponse` 로 감싼다.

```
성공  { "success": true,  "data": { ... } }
실패  { "success": false, "code": "NOT_FOUND", "message": "..." }
```

인증이 필요한 요청은 `Authorization: Bearer <accessToken>` 헤더를 보낸다.
리프레시 토큰은 httpOnly 쿠키(`refresh_token`, `Path=/api/auth`)로만 오간다.

## Swagger UI, 실행 중인 서버에서 직접 확인한다

백엔드를 띄운 뒤 **<http://localhost:8080/swagger-ui.html>** 을 연다.
`@ApiOperation` 애노테이션에서 **코드가 바뀌면 문서도 함께 바뀐다.** 이 문서(`API_SPEC.md`)처럼
손으로 맞출 필요가 없다.

| 그룹 | 내용 |
|---|---|
| `01. 서비스 API` | 정식 엔드포인트 52개 |
| `02. 개발 전용 API` | `/api/dev/**`. 배치 트리거·미션 재배정. 로컬에서만 동작한다 |

**섹션은 모듈 단위 9개**다. 컨트롤러가 아니라 모듈로 묶여 있어 `01. 회원 · 인증` 안에
로그인·내 정보·동의·튜토리얼이 함께 들어간다. 태그 이름은 `common/docs/SwaggerTags` 가 소유한다.

| 섹션 | 모듈 |
|---|---|
| `00. 헬스체크` | common |
| `01. 회원 · 인증` | user (auth · users · consents · tutorial) |
| `02. 계좌 연동` | account |
| `03. 개인 미션(메인 챌린지)` | mission |
| `04. 그룹 챌린지(지방법원)` | challenge |
| `05. 알림` | notification |
| `06. 월간 리포트(판결문)` | report |
| `07. 고정지출 · 절약` | fixedexpense |
| `08. 거래내역` | transaction |

- 인증이 필요한 API 를 호출하려면 우측 상단 **Authorize** 에 `Bearer {accessToken}` 을 넣는다.
  (`Bearer ` 접두사까지 포함해야 한다. 인터셉터가 접두사를 직접 잘라낸다)
- 문서 원문(JSON): `/v2/api-docs?group=01. 서비스 API`
- `GET /api/auth/google`·`/google/callback` 은 브라우저 전체 이동이라 **Try it out 으로 확인할 수 없다.**
  `GET /api/notifications/stream` 도 SSE 라 응답이 끝나지 않아 확인할 수 없다.

문서 애노테이션은 컨트롤러가 아니라 **각 모듈의 `docs/` 패키지 인터페이스**에 있다
(`user/docs/UserControllerDocs.java` 등). 컨트롤러는 그 인터페이스를 `implements` 하기만 한다.
설명을 고칠 일이 있으면 컨트롤러가 아니라 `docs/` 쪽을 고친다.

> 이 `API_SPEC.md` 와 산출물 엑셀(`탕탕_API_연동규격_정의서`)을 **대체하지 않는다.**
> 제출 정본은 엑셀이고, Swagger 는 개발·시연·QA 용이다.
> 설정 근거는 `.claude/context/DECISIONS.md` 2026-08-13 (4) 참고.

## 고정지출 관리·절약 감정서

모든 엔드포인트는 Bearer 인증이 필요하며 사용자 ID를 요청으로 받지 않는다. 목록과 절약 감정서는
`yearMonth`를 생략하면 `Asia/Seoul` 현재월을 사용한다. 값이 있으면 `YYYY-MM` 형식의 현재월만
허용한다. 고정지출 상태 이력 스냅샷은 아직 없으므로 과거월·미래월 조회는 지원하지 않는다.

| Method | Endpoint | 응답 책임 |
|---|---|---|
| GET | `/api/fixedExpenses/candidates?yearMonth=YYYY-MM&categoryId={id}` | 현재 활성 후보·확정 목록과 일관된 관리 요약 |
| GET | `/api/fixedExpenses/candidates/{candidateId}` | 후보·확정 항목 공통 상세와 최근 6개월 결제 이력 |
| GET | `/api/fixedExpenses/savingReport?yearMonth=YYYY-MM` | 확정 활성 항목별·월간·연간 절약 가능액 |

- 목록은 페이지네이션 없이 조건에 맞는 전체 배열을 반환한다. `categoryId`는 선택값이며 요약·후보·확정
  배열에 함께 적용된다.
- 후보는 `ACTIVE`·미제외·`confirmedAt=null`, 확정 항목은 `ACTIVE`·미제외·`confirmedAt!=null`이다.
  BUFFER·검증 취소·제외 항목은 목록과 절약 감정서에서 제외한다.
- `expectedMonthlyAmount`는 목록에 포함된 후보와 확정 항목의 `averageAmount` 합계다. 절약 감정서는
  확정 항목만 `savingsAmount=averageAmount`로 계산하며, `yearlySavings=monthlySavings*12`다.
- 상세의 `paymentHistory`는 유효 소비 거래만 최신순으로 최대 6건이며, `sixMonthTotal`은 최근 6개월
  유효 결제 전체 합계다. 소유하지 않았거나 활성·미제외 상태가 아닌 항목은 `404 NOT_FOUND`다.

```json
{
  "success": true,
  "data": {
    "yearMonth": "2026-08",
    "summary": {
      "expectedMonthlyAmount": 86900,
      "confirmedCount": 2,
      "candidateCount": 1
    },
    "confirmed": [
      {
        "id": 101,
        "status": "ACTIVE",
        "isExcluded": false,
        "isConfirmed": true,
        "confirmedAt": "2026-08-14T10:02:03",
        "categoryCode": "subscription",
        "categoryLabel": "구",
        "name": "넷플릭스",
        "averageAmount": 17000,
        "billingCycle": { "type": "monthly", "day": 17 },
        "nextPaymentDate": "2026-08-17",
        "paymentLabel": "월 결제"
      }
    ],
    "candidates": []
  }
}
```

절약 감정서가 비어도 `200 OK`와 `monthlySavings=0`, `yearlySavings=0`, `items=[]`를 반환한다.
`yearMonth` 형식과 양수 `categoryId` 검증 실패는 `400 INVALID_REQUEST`다.

## 공통

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/health` | 불필요 | `{ status, service }` |

## 인증 (이슈 #9)

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/auth/google` | 불필요 | 302 → 구글 동의 화면. `oauth_state` 쿠키 발급 |
| GET | `/api/auth/google/callback` | 불필요 | 302 → 프론트. 성공 시 `/auth/callback` + `refresh_token` 쿠키, 실패 시 `/login?error=...` |
| POST | `/api/auth/refresh` | 쿠키 | `{ accessToken, user: 사용자정보, needsConsent, needsFinancialConsent, needsAccountLink }` |
| POST | `/api/auth/logout` | 쿠키 | `{"success":true,"data":null}` + 쿠키 만료 |
| GET | `/api/users/me` | Bearer | 사용자정보 |
| PATCH | `/api/users/me/name` | Bearer | 요청 `{ name }` → 갱신된 사용자정보 |
| PATCH | `/api/users/me/nickname` | Bearer | 요청 `{ nickname }` → 갱신된 사용자정보 |
| POST | `/api/users/me/profile-image` | Bearer | 요청 multipart (파트명 `file`) → 갱신된 사용자정보 |
| DELETE | `/api/users/me/profile-image` | Bearer | 갱신된 사용자정보 (기본 아바타로 되돌리기) |
| DELETE | `/api/users/me` | Bearer | 회원 탈퇴. `{"success":true,"data":null}` + 리프레시 쿠키 만료 |

**사용자정보** = `{ id, nickname, socialName, profileImageUrl, displayName, name, email, socialProvider, tutorialSeenAt, groupTutorialSeenAt }`

**이 모양은 `GET /api/users/me` · `POST /api/auth/refresh` 의 `user` · 사용자 정보를 바꾸는 모든
`PATCH`·`POST`·`DELETE` 응답이 똑같이 쓴다.** 서버는 `UserMeDto.from(UserDto, profileImageUrl)`
한 곳에서만 만든다 — 경로마다 따로 조립하면 필드를 추가할 때 한 곳을 빠뜨려 **그 경로에서만
값이 비는** 버그가 난다.

- `profileImageUrl` 은 **미설정이면 `null`** 이고 화면은 이니셜 아바타를 그린다. 값이 있으면
  서버가 조립을 끝낸 완성 URL(`/uploads/profile/{userId}/{uuid}.jpg` 형태)이다 — 화면은 URL 을
  조립하지 않으므로 로컬 저장소 → S3 전환에 프론트 수정이 없다.
- `POST /api/users/me/profile-image` 는 업로드된 이미지를 **256x256 정사각 JPEG 로 다시 구워
  저장**한다(가운데 크롭). 원본은 보관하지 않는다.
- `DELETE /api/users/me` (회원 탈퇴, `MY_01_05`)는 **물리 삭제하지 않는다.** 동의를 전건 철회하고
  (→ 연동 계좌 자동 해제) 리프레시 토큰을 전부 폐기한 뒤 식별정보를 `NULL` 로 익명화한다.
  `provider_user_id` 에 `_withdrawn_{id}` 접미사가 붙어 유니크 키가 비므로 **같은 소셜 계정으로
  재가입할 수 있다** — 재가입자는 신규 가입자와 같은 온보딩을 다시 거치며 과거 데이터는 딸려오지 않는다.
  이미 탈퇴한 계정의 재요청도 200 이다(멱등). 액세스 토큰은 만료(15분)까지 유효하다.
  (`DECISIONS.md` 2026-08-13)
- `name` 은 **실명(본인확인용)** 이고 `nickname` 은 표시명이다. 서로 다른 컬럼·다른 엔드포인트다.
- `PATCH /api/users/me/name` 은 **간편인증 화면이 인증 요청 직전에** 부른다. 같은 화면에서 받는
  생년월일·통신사·휴대폰은 여기로 오지 않는다 — 저장하지 않는 값이기 때문이다.
  (`DECISIONS.md` 2026-08-11 (4))
- 검증 규칙: 앞뒤 공백 제거 후 **2~50자**, **한글·영문·공백만**. 어기면 `INVALID_NAME`.

### 온보딩 게이트 — 순서가 정해져 있다

신규 사용자는 **`동의 → 금융동의 → 계좌연동 → 닉네임 → 홈`** 을 순서대로 통과한다.
프론트 라우터 가드는 **부팅 시 `POST /api/auth/refresh` 응답 하나만 보고** 다음 단계를 정한다 —
화면 진입마다 조회하지 않는다.

| 플래그 | true 이면 보낼 화면 | 판정 |
|---|---|---|
| `needsConsent` | 서비스 동의 | `SIGNUP` 필수 동의(약관·개인정보·금융정보) 미완료 |
| `needsFinancialConsent` | 금융(제3자 제공) 동의 | `THIRD_PARTY` 동의 미완료 |
| `needsAccountLink` | 계좌 연동 | 활성 연결 계좌가 0개 |
| — | 닉네임 설정 | `user.nickname` 이 `null` (별도 플래그 없음) |

- ⚠ **`THIRD_PARTY` 를 `needsConsent` 에 합치면 안 된다.** 그러면 계좌를 아직 연동하지 않은
  사용자가 가입 동의 화면을 영원히 벗어나지 못한다. 그래서 단계를 나눈다.
- **서버도 검사한다.** 계좌 연결 요청(`simple-auth` · `connections`)은 `THIRD_PARTY` 동의가 없으면
  **`CONSENT_REQUIRED`(400)** 로 거부한다. 계좌 연동 진입점이 셋이라 화면 순서만으로는 우회된다 —
  **2026-08-11 까지 실제로 아무도 제3자 제공 동의를 하지 않은 채 연동해 왔다.**
- `POST /api/consents` 와 철회 응답도 `{ needsConsent, needsFinancialConsent }` 를 함께 준다.
  저장 직후 게이트를 갱신하지 않으면 동의를 마쳐도 계속 동의 화면으로 되돌아간다.
  (`DECISIONS.md` 2026-08-11 (7))

### 닉네임 — 이름 3종을 헷갈리지 말 것 (이슈 #110)

| 필드 | 컬럼 | 누가 채우나 | 뜻 |
|---|---|---|---|
| `nickname` | `tbl_user.nickname` | 사용자 (온보딩·마이페이지) | 표시명. **`null` 이면 온보딩 미완료** |
| `socialName` | `tbl_user.social_name` | 가입 시 자동 (구글 `name`) | 입력창 prefill · 표시명 fallback |
| `name` | `tbl_user.name` | 사용자 (간편인증 화면) | **실명(본인확인용).** 표시명으로 쓰지 않는다 |

- **표시명 = `nickname ?? socialName`** 이고 **서버가 `displayName` 으로 계산해 내려준다.**
  화면마다 계산하면 어디선 닉네임, 어디선 구글 이름이 나온다.
- `PATCH /api/users/me/nickname` 은 **온보딩(`AU_03_01`)과 마이페이지 수정(`MY_01_03`)이 함께 쓴다.**
- 검증: 앞뒤 공백 제거 후 **1~50자**(빈 값·공백만 불가). 어기면 `INVALID_REQUEST`.
  실명보다 느슨하다 — 표시명이라 한 글자도, 숫자·기호도 막을 이유가 없다.
- **중복 검사를 하지 않는다.** 닉네임 중복 허용이 팀 결정이라 `UNIQUE` 도 걸지 않았다
  (`DECISIONS.md` 2026-08-11 닉네임 온보딩).
- **온보딩 완료 여부는 `nickname` 이 `null` 인지로만 판별한다. 별도 판별 API 를 두지 않는다.**
- 가입 시 `nickname` 은 **비워 둔다.** 구글 이름은 `social_name` 에 넣는다 —
  미리 채우면 "설정함"과 "안 함"을 구분할 별도 플래그가 필요해진다.

### 튜토리얼 완료 플래그 (이슈 #128)

| 메서드 | 경로 | 인증 | 뜻 |
|---|---|---|---|
| PATCH | `/api/main-challenge/tutorial/complete` | Bearer | 메인(개인·대법원) 튜토리얼 `MC_01_05` 완료 |
| DELETE | `/api/main-challenge/tutorial/complete` | Bearer | 다시 보기 — 완료 시각을 지운다 |
| PATCH | `/api/group-challenge/tutorial/complete` | Bearer | 그룹(지방법원) 튜토리얼 `GC_01_01` 완료 |
| DELETE | `/api/group-challenge/tutorial/complete` | Bearer | 다시 보기 |

- 네 개 모두 응답은 **갱신된 사용자정보**다. 프론트는 이 응답으로 스토어를 갱신한다.
- 저장 위치는 `tbl_user.tutorial_seen_at`(메인) · `tbl_user.group_tutorial_seen_at`(그룹).
  **`null` 이면 아직 안 본 것**이고, 프론트는 이 값만 보고 노출을 정한다.
  로그인 시 `POST /api/auth/refresh` 응답에 이미 실려 오므로 **화면 진입마다 조회하지 않는다.**
- `localStorage` 를 쓰지 않는다 — 기기를 바꾸면 튜토리얼이 다시 뜬다
  (`DECISIONS.md` 2026-08-11 RV-108 에서 기각된 대안이다).
- 클래스는 `user` 모듈(`user/controller/TutorialController`)에 있다. 경로는 챌린지 도메인이지만
  값이 사는 테이블이 `tbl_user` 라서다.

### 인증 에러 코드

| 코드 | HTTP | 의미 |
|---|---|---|
| `UNAUTHORIZED` | 401 | Authorization 헤더 없음/형식 오류 |
| `TOKEN_EXPIRED` | 401 | 액세스 토큰 만료 — 프론트가 자동 재발급한다 |
| `INVALID_TOKEN` | 401·400 | 서명 위조·형식 오류·리프레시 토큰 없음/만료 |
| `REFRESH_TOKEN_REUSED` | 400 | 폐기된 리프레시 토큰 재사용 — 전체 토큰 폐기됨 |
| `USER_WITHDRAWN` | 400 | 탈퇴·차단 계정 |
| `OAUTH_TOKEN_EXCHANGE_FAILED` | 400 | 구글 code↔token 교환 실패 |
| `NOT_FOUND` | 400 | `/api/users/me` 조회 시 사용자를 찾을 수 없음 (실명 갱신 대상이 탈퇴·차단 상태일 때도 이 코드다) |
| `INVALID_NAME` | 400 | `/api/users/me/name` 의 이름이 형식에 맞지 않음 (2~50자·한글/영문/공백) |
| `INVALID_REQUEST` | 400 | `/api/users/me/nickname` 의 닉네임이 비었거나 50자를 넘음 |
| `IMAGE_REQUIRED` | 400 | `/api/users/me/profile-image` 요청에 이미지가 비어 있음 |
| `IMAGE_TOO_LARGE` | 400 | 업로드 이미지가 5MB 초과 |
| `INVALID_IMAGE` | 400 | 이미지로 디코딩할 수 없는 파일 (확장자·Content-Type 은 신뢰하지 않는다) |

### 콜백 리다이렉트 error 쿼리 (`/api/auth/google/callback` 이 붙이는 값)

`cancelled`(사용자 취소) · `invalid`(state 불일치) · `failed`(교환 실패) · `withdrawn`(이용 불가 계정)

### 프론트 재발급 실패 시 error 쿼리 (`http.js`/`main.js` 가 붙이는 값)

콜백이 아니라 액세스 토큰 재발급(`/api/auth/refresh`) 실패 시 프론트가 붙인다.

`expired`(단순 세션 만료) · `security`(`REFRESH_TOKEN_REUSED` 감지 — 탈취 의심으로 전체 토큰 폐기됨)

## 동의 (이슈 #13)

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/consents/catalog?scope=SIGNUP\|FINANCIAL\|CHALLENGE` | Bearer | `{ scope, termsVersion, items:[{type,required,label,termsUrl}] }` |
| POST | `/api/consents` | Bearer | `{ needsConsent }` — 본문 `{ scope, agreements:[{type,agreed}] }` |
| GET | `/api/consents/me` | Bearer | `{ items:[{type,scope,required,label,termsUrl,agreed,withdrawable,termsVersion,expiresAt}] }` |
| POST | `/api/consents/{type}/withdraw` | Bearer | `{ needsConsent }` |

동의 그룹은 3종이다.

| scope | 항목 |
|---|---|
| `SIGNUP` | 필수 `TERMS`·`PRIVACY`·`FINANCIAL_DATA` / 선택 `AI_USAGE`·`MARKETING` |
| `FINANCIAL` | 필수 `THIRD_PARTY` |
| `CHALLENGE` | 선택 `CHALLENGE` — 개인·그룹 공통 챌린지 참여 동의 |

약관 본문은 서버가 갖지 않는다. `termsUrl` 은 노션 공개 페이지이고 새 탭으로 연다.
`terms_version` 은 요청값을 무시하고 서버 카탈로그 값을 저장한다.
`needsConsent` 는 `SIGNUP` 필수 3종 기준으로만 판정한다.

`CHALLENGE` 동의는 사용자가 메인 챌린지를 처음 시작할 때 한 번 받는다. 이후에는 다시 묻지 않으며,
마이페이지 동의 관리에서 철회하거나 재동의한다. 최초 동의와 철회 후 재동의가 완료되면 커밋 이후
`ChallengeConsentAgreedEvent`를 발행해 오늘 미션이 없는 사용자에게 당일 미션 배정을 시도한다.
이미 활성인 동의를 다시 제출해도 이벤트를 중복 발행하지 않는다. 챌린지 약관 URL은 확정 전까지 `null`이다.

동의를 기록하려면 `agreed: true` 를 명시적으로 보내야 한다. `agreed` 를 생략하거나 `null` 로 보내면 원시형 기본값 `false` 로 바인딩되어, 해당 항목을 요청에 넣지 않은 것과 동일하게 미동의로 저장된다.

`FINANCIAL_DATA` 철회는 부수효과가 있다. `THIRD_PARTY` 를 **함께 철회**하고 `ConsentWithdrawnEvent` 를 발행해
**연결된 계좌를 모두 해제**한다. 또 `SIGNUP` 필수 항목이라 철회 즉시 `needsConsent=true` 가 되어
라우터 가드가 이후 모든 이동을 동의 화면으로 되돌린다 — 화면은 철회 전에 이 사실을 알려야 한다.

### 철회한 동의를 다시 켜기 (재동의)

전용 엔드포인트가 없다. **기존 `POST /api/consents` 를 그대로 재사용**한다.

- `POST /api/consents` 는 **scope 단위 전량 저장**이다(`ConsentService.submit`).
  다시 켤 항목 하나만 보내면 **같은 scope 의 나머지가 전부 꺼진다.**
  현재 상태를 함께 실어 보내야 한다 (프론트 `utils/consent.js` 의 `buildAgreeAgainPayload`).
- 그래서 `GET /api/consents/me` 가 항목마다 **`scope`** 를 내려준다.
  프론트가 타입→scope 매핑을 들고 있으면 서버의 `ConsentScope` 가 바뀔 때 어긋난다.
- `TERMS`·`PRIVACY` 는 `withdrawable=false` 라 철회 자체가 없다.

### 동의 에러 코드

| 코드 | HTTP | 의미 |
|---|---|---|
| `CONSENT_REQUIRED_MISSING` | 400 | 필수 항목에 동의하지 않고 저장 시도 |
| `CONSENT_TYPE_INVALID` | 400 | 알 수 없는 동의 항목·그룹이거나 해당 scope 밖의 항목 |
| `CONSENT_NOT_WITHDRAWABLE` | 400 | `TERMS`·`PRIVACY` 철회 시도 (탈퇴에 해당) |
| `NOT_FOUND` | 400 | 철회할 동의 내역 없음 |

## 계좌 연동 (이슈 #12)

기준 파일은 `AccountLinkController` 이고, 프론트 `apps/web/src/api/account.js` 의 함수 13종과 1:1 이다.
사용자 식별은 전부 `@LoginUser` 로만 한다 — 요청 본문·쿼리로 `userId` 를 받는 엔드포인트는 없다.

**어느 금융 데이터 공급자(목서버 / 실 CODEF)가 붙었는지 프론트는 알지 못한다.**
`financial.client` 프로퍼티(`mock` · `codef` · `hybrid`)가 정하고, 화면은 `GET /api/accounts/link/auth-methods` 응답만 보고 인증 화면을 고른다.
`hybrid` 에서는 **사용자마다** 다르다 — `tbl_user.financial_data_source='CODEF'` 인 사용자만 실 CODEF(기관 로그인), 나머지는 목서버(간편인증)다.
그래서 `auth-methods`·`institutions` 응답이 사용자별로 달라질 수 있다. (DECISIONS.md 2026-08-05)

### 1단계 — 기관 선택

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/accounts/institutions` | Bearer | `{ banks:[], cards:[], securities:[], loans:[], payMoney:[] }` — 각 항목 `{code,name,shortLabel,connected}` |

- `connected: true` 는 **이미 연결된 기관**이라는 표시일 뿐 선택은 막지 않는다 — 같은 은행의 두 번째 계좌 추가와 재연동이 막히기 때문이다. 중복은 4단계의 `alreadyLinked` 가 막는다.
- 업권은 **5종**이다(응답 필드 순서 = 화면 칩 순서). 대출은 마이데이터에서 독립 업권이 아니라 은행 업권 산하 상품이므로, `loans` 에는 **할부금융(캐피탈)·저축은행**이 들어간다. 은행 대출은 은행을 연결하면 함께 조회된다.
- `loans` · `payMoney` 의 기관 코드는 **CODEF organization 코드가 아니다**(해당 업권 코드를 확인하지 못해 자체 코드를 쓴다). 실 CODEF 사용자에게는 `supportedOrganizations` 가 은행 20곳 + 카드사 6곳만 허용해 두 업권이 자동으로 빈 배열이 된다. 은행 대출은 은행을 연결하면 함께 조회된다.
- `cards` 의 기관 코드도 목서버 코드다(0301=신한카드 등). **CODEF 카드 코드와 숫자가 겹치면서 뜻이 다르므로**(CODEF 0301=KB카드) 실 CODEF 로 나가는 순간 `CodefOrganizations` 가 바꾼다. 화면·DB 는 계속 이 목록의 코드를 쓴다.
- 공급자가 다루지 못하는 기관은 목록에서 아예 빠진다(`supportedOrganizations`. 빈 집합이면 "제한 없음").
- **보험은 연동 범위에서 제외됐다(2026-08-06).** 되살리려면 `InstitutionCatalog` 부터 손봐야 한다.

### 2단계 — 인증

| 메서드 | 경로 | 인증 | 요청 본문 | 응답 |
|---|---|---|---|---|
| GET | `/api/accounts/link/auth-methods` | Bearer | — | `{ methods:[{type,providers:[{code,name}],requiresIdentity}] }` |
| POST | `/api/accounts/connections/simple-auth` | Bearer | `{provider, organizations:[code], userName?, birthDate?, carrier?, phoneNo?}` | 연결 응답 |
| POST | `/api/accounts/connections` | Bearer | `{credentials:[{organization,loginType,id,password}]}` | 연결 응답 |
| GET | `/api/accounts/connections/{connectionId}/auth-status` | Bearer | — | `{ status }` |
| POST | `/api/accounts/connections/{connectionId}/extra-auth` | Bearer | `{authCode}` | 연결 응답 |

연결 응답 = `{ connectionId, status, expiresInSeconds, needsExtraAuth, extraAuthType }`
(간편인증·기관 로그인이 **같은 모양**을 돌려준다. 이후 단계는 어느 경로로 왔는지 모른다.)

- `type` 이 `SIMPLE_AUTH` 면 `providers` 가 채워지고, `INSTITUTION_LOGIN` 이면 빈 배열이다.
- 본인 정보(`userName`·`birthDate`·`carrier`·`phoneNo`)는 **`requiresIdentity: true` 일 때만** 보낸다.
  목 모드에서는 화면이 형식만 검증하고 폐기해 필드가 비어 온다. 서버도 공급자에 전달만 하고 저장·로깅하지 않는다.
  - ⚠ **이름만 예외다.** 화면이 입력받은 이름은 이 요청과 별개로 `PATCH /api/users/me/name` 이
    `tbl_user.name` 에 저장한다. 이 요청의 `userName` 은 여전히 **공급자 전달용**이고 저장되지 않는다.
    (`DECISIONS.md` 2026-08-11 (4) — 목 모드는 `requiresIdentity: false` 라 이 페이로드에만 실으면
    시연 환경에서 실명이 영영 저장되지 않는다)
- **`credentials` 는 요청 처리 중에만 존재한다.** 진행 상태·DB·로그 어디에도 남지 않는다.
- 인증 승인은 프론트가 `auth-status` 를 1초 간격으로 폴링해 확인한다.

### 3단계 — 조회 진행

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/accounts/connections/{connectionId}/progress` | Bearer | `{ percent, done, institutions:[{code,name,status}] }` |

- **진행률은 서버가 계산한다. 프론트 연출이 아니다.**
- 이 엔드포인트는 **폴링될 때마다 기관을 하나씩 실제로 조회**한다(브로커·비동기 인프라가 없는 구조라 의도한 설계).
  한 기관이 실패해도 `FAILED` 로 두고 다음 기관으로 넘어간다.
- 승인(`APPROVED`) 전에는 조회를 시작하지 않고 현재 상태만 돌려준다.
- 진행 상태는 메모리 보관이고 **TTL 10분**이다. 지나면 `CONNECTION_NOT_FOUND` 다.

### 4단계 — 계좌 선택

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/accounts/connections/{connectionId}/accounts` | Bearer | `[{bankCode,bankName,shortLabel,accounts:[…]}]` |

`accounts[]` 항목 = `{accountId, bankCode, bankName, accountType, accountName, accountNoMasked, currency, balance, alreadyLinked}`

- 이 엔드포인트만 `data` 가 **배열**이다(공통 래퍼는 동일).
- `accountId` 는 아직 DB 에 없는 **목록 순번 기반 임시 식별자**다. 5단계 요청에만 쓰고 보관하지 않는다.
- `accountNoMasked` 는 서버가 마스킹한 값이다. 원본 계좌번호는 내려보내지 않는다.
- `alreadyLinked: true` 는 이미 연결된 계좌다(계좌번호 해시로 판정).

### 5단계 — 연결 저장

| 메서드 | 경로 | 인증 | 요청 본문 | 응답 |
|---|---|---|---|---|
| POST | `/api/accounts` | Bearer | `{connectionId, accountIds:[임시 id]}` | `{ linkedCount }` |

- 중복 `accountId`·이미 연결된 계좌는 **조용히 건너뛴다**. `linkedCount` 는 실제로 연결된 수다.
- 해제했던 계좌는 새 행을 만들지 않고 되살린다(UNIQUE KEY 충돌 방지).
- 하나도 연결되지 않으면 `EXTERNAL_API_ERROR` 다.

### 연결 계좌 관리

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/accounts` | Bearer | `{ accounts:[…], nextAutoSyncAt }` |
| POST | `/api/accounts/refresh` | Bearer | `{ lastSyncAt, institutionCount, cooldownSeconds, institutions:[…] }` |
| DELETE | `/api/accounts/{accountId}` | Bearer | `{ accountId, disconnected }` |
| POST | `/api/accounts/{accountId}/resync` | Bearer | `{ accountId, syncStatus, lastSyncAt }` |

`accounts[]` 항목 = `{accountId, bankCode, bankName, shortLabel, accountName, accountNoMasked, accountType, balance, syncStatus, lastSyncAt, syncFailReason, expiresAt}`
`institutions[]` 항목 = `{bankCode, bankName, shortLabel, newTransactionCount, balance, syncStatus}`

- `nextAutoSyncAt` — 자동 동기화는 **매일 18시 배치**다. 오늘 시각이 지났으면 내일 18시를 안내한다.
- `expiresAt` — 연결 시점 **+365일**(마이데이터 재동의 주기).
- `cooldownSeconds` 는 **150 고정이고 서버가 판단한다.** 프론트가 직접 계산하지 않는다.
- `POST /refresh` 는 **공급자를 실제로 호출한다.** 기관 단위로 묶어 한 번씩 부르고,
  `institutions[].balance` 는 그 기관 계좌들의 **잔액 합계**다(첫 계좌 값이 아니다).
- `newTransactionCount` 는 거래내역 수집이 붙기 전까지 **항상 0** 이다. 값을 지어내지 않는다.
- 조회 결과 `NEED_RECONNECT` 가 되면 `AccountReconnectRequiredEvent` 가 발행되어
  `ACCOUNT_RECONNECT` 알림이 나간다(→ 「알림」 절).

### 계좌 연동 열거값

| 대상 | 값 |
|---|---|
| 인증 수단 `type` | `SIMPLE_AUTH` · `INSTITUTION_LOGIN` |
| 인증 상태 `status` | `PENDING` · `APPROVED` · `FAILED` · `EXPIRED` |
| 진행 상태 `institutions[].status` | `WAITING` · `FETCHING` · `DONE` · `FAILED` |
| 동기화 상태 `syncStatus` | `NORMAL` · `SYNCING` · `NEED_SYNC` · `FAILED` · `NEED_RECONNECT` |

`syncStatus` 값은 `tbl_connected_account.sync_status` 컬럼과 1:1 이다 (`db/schema.sql` 이 기준).

### 계좌 연동 에러 코드

| 코드 | HTTP | 의미 |
|---|---|---|
| `INVALID_CREDENTIALS` | 400 | 인증 수단·아이디/비밀번호·인증번호·본인 정보 누락 또는 형식 오류 |
| `CONNECTION_NOT_FOUND` | 400 | `connectionId` 가 없거나 TTL(10분) 만료, 또는 남의 연결 |
| `TOKEN_EXPIRED` | 400 | **금융기관** 연결 인증 만료 — 재연동이 필요하다 |
| `AUTH_METHOD_UNSUPPORTED` | 400 | 공급자가 지원하지 않는 인증 수단 |
| `EXTERNAL_API_ERROR` | 400 | 기관·계좌 미선택, 공급자 응답 오류 |
| `EXTERNAL_API_UNAVAILABLE` | 400 | 공급자 장애·무응답 |
| `NOT_FOUND` | 400 | 연결된 계좌 없음 또는 남의 계좌 |

> ⚠ `TOKEN_EXPIRED` 는 인증 절의 액세스 토큰 만료와 **이름만 같다.** 이쪽은 HTTP 400 이라
> 프론트 `http.js` 의 자동 재발급(401 트리거)을 유발하지 않는다.

## 자산 현황 (이슈 #240)

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/assets/summary?baseDate=YYYY-MM-DD` | Bearer | 순자산·전월 대비 증감·6개월 추이·구성·종류별 목록 |
| GET | `/api/assets/trend?baseDate=YYYY-MM-DD` | Bearer | 순자산·부채 6개월 추이만 |

`baseDate` 는 두 엔드포인트 모두 선택값이며 생략하면 오늘 날짜(`Asia/Seoul`)를 기준으로 한다.
`YYYY-MM-DD` 형식이 아니면 `400 INVALID_REQUEST` 다.

`GET /api/assets/trend` 는 "순자산 추이" 상세 화면(`NetWorthTrendView`) 전용이다. 자산 홈의
순자산 카드 스파크라인은 이 엔드포인트를 쓰지 않는다 — 그 화면은 어차피 `/assets/summary` 를
호출하므로 그 응답의 `trend` 를 그대로 재사용한다(중복 호출 없음). 두 엔드포인트의 6개월 추이
계산은 백엔드에서 `AssetCompositionCalculator`(라이브 순자산 계산)·`AssetNetWorthTrendService`
(과거월 스냅샷 조회 + null 채움 + 최신월 라이브 값 채움)를 공유해 로직이 갈라지지 않는다.

- `asOf` 는 이 응답을 계산한 단일 기준 시각이다. `netWorth`·`composition`·`assetGroups`·`trend` 의
  마지막 달 값이 전부 이 시각의 라이브 잔액을 사용해 화면 카드 간 금액이 어긋나지 않는다.
- `netWorth` = 입출금 + 예적금 + 투자 + 페이머니 − 대출잔액. 연결 해제(`is_active=0`) 계좌는 제외한다.
  투자 금액은 증권계좌 `balance` 가 아니라 `tbl_investment_holding.market_value` 합계다(보유 종목이
  없으면 계좌 `balance` 로 대체). 대출은 `tbl_loan.balance` 합계이며 `composition`·`assetGroups` 에서는
  음수로 내려간다.
- `composition`(도넛차트용)·`assetGroups`(종류별 목록용)는 항상 `DEMAND_DEPOSIT`·`SAVINGS`·
  `SECURITIES`·`PAY_MONEY`·`LOAN` 5종을 고정 순서로 반환한다. 연결된 계좌가 없는 종류도
  `amount=0`·`count=0` 으로 채워 화면이 빈 배열을 따로 처리하지 않게 한다.
- 전월 비교(`previousMonthNetWorth`·`changeAmount`·`changeRate`)와 `trend`(최근 6개월, `baseDate`가
  속한 달 포함)의 과거월 값은 `tbl_asset_snapshot.net_worth`·`total_debt` 를 조회해 채운다. 이
  테이블은 매월 초 자동 배치(`MonthlyReportBatchScheduler`)와 `POST /api/reports/monthly/ai-analysis`
  호출 시 채워진다. 그래도 가입 첫 달처럼 아직 스냅샷이 없는 달은 있을 수 있고, 그 경우
  `trend` 항목의 `netWorth`·`totalDebt`는 `null`, 전월 비교 3개 필드도 전부 `null` 이다 — 가입
  첫 달의 전월 비교를 생략하는 월간 리포트(`hasPreviousComparison=false`)와 같은 "데이터 없음"
  처리 기준이다. `trend` 의 마지막 항목(= `baseDate` 가 속한 달)만은 스냅샷 유무와 무관하게 방금
  계산한 라이브 `netWorth`·대출잔액(`totalDebt`)을 그대로 채운다.

```json
{
  "success": true,
  "data": {
    "asOf": "2026-08-15T10:30:00",
    "netWorth": 9445500,
    "previousMonthNetWorth": 9125500,
    "changeAmount": 320000,
    "changeRate": 3.51,
    "trend": [
      { "yearMonth": "2026-03", "netWorth": null, "totalDebt": null },
      { "yearMonth": "2026-08", "netWorth": 9445500, "totalDebt": 1500000 }
    ],
    "composition": [
      { "type": "DEMAND_DEPOSIT", "label": "입출금", "amount": 2066800 },
      { "type": "LOAN", "label": "대출", "amount": -1500000 }
    ],
    "assetGroups": [
      { "type": "DEMAND_DEPOSIT", "label": "입출금 계좌", "count": 2, "amount": 2066800 }
    ]
  }
}
```

```json
// GET /api/assets/trend
{
  "success": true,
  "data": {
    "asOf": "2026-08-15T10:30:00",
    "trend": [
      { "yearMonth": "2026-03", "netWorth": null, "totalDebt": null },
      { "yearMonth": "2026-04", "netWorth": null, "totalDebt": null },
      { "yearMonth": "2026-05", "netWorth": null, "totalDebt": null },
      { "yearMonth": "2026-06", "netWorth": null, "totalDebt": null },
      { "yearMonth": "2026-07", "netWorth": 9125500, "totalDebt": 1600000 },
      { "yearMonth": "2026-08", "netWorth": 9445500, "totalDebt": 1500000 }
    ]
  }
}
```

### 자산 상세 목록 (이슈 #240 후속)

자산 홈에서 종류별 카드를 눌렀을 때 진입하는 상세 화면용이다. 세 종류는 응답 모양이 서로 다르다 —
입출금·예적금·페이머니는 `tbl_connected_account` 한 테이블에서 나와 필드가 같지만, 투자는
`tbl_investment_holding` 종목별 상세, 대출은 `tbl_loan` 조건이 필요해 엔드포인트를 분리했다.

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/assets/accounts?type=DEMAND_DEPOSIT\|SAVINGS\|PAY_MONEY` | Bearer | 해당 종류 연결 계좌 목록 + 잔액 합계 |
| GET | `/api/assets/investments` | Bearer | 보유 종목 목록 + 평가금액·원금 합계 |
| GET | `/api/assets/loans` | Bearer | 대출 목록 + 잔액 합계 |

- 셋 다 `@LoginUser` 로 식별한 사용자 소유 데이터만 조회한다(`WHERE user_id = ...`). 남의 계좌를
  가리키는 경로 파라미터 자체가 없어 별도 404 케이스가 없다 — 항상 "내 것"만 나온다.
- `GET /api/assets/accounts` 의 `type` 은 필수이며 `DEMAND_DEPOSIT`·`SAVINGS`·`PAY_MONEY` 중
  하나가 아니면 `400 INVALID_REQUEST` 다. `SECURITIES`(투자)·`LOAN`(대출)은 이 엔드포인트가 아니라
  전용 엔드포인트를 쓴다.
- `tbl_connected_account.account_type` 의 실제 저장값은 `PAYMONEY`(밑줄 없음)이지만 `type` 쿼리
  파라미터와 응답의 `accountType` 은 계약값 그대로 나간다 — `/api/assets/summary` 와 동일한
  정규화 경계를 여기서도 둔다.
- `GET /api/assets/investments` 의 `profitLossAmount`·`profitLossRate` 는 화면이 다시 계산하지
  않고 `tbl_investment_holding` 에 동기화된 값을 그대로 옮긴다.
- `GET /api/assets/loans` 는 `loanNoEncrypted`(내부 upsert 키)를 응답에 포함하지 않지만 `bankCode`
  는 포함한다(로고 표시용, `/api/assets/summary` 의 `composition`·`assetGroups` 와 달리 종류별
  상세는 기관 배지가 필요하다).
- `GET /api/assets/accounts` 의 `accounts[]` 항목에는 `manageable` 필드가 있다(연결 계좌 관리
  화면과 DTO 를 공유하는 흔적). 이 엔드포인트는 `type` 이 `DEMAND_DEPOSIT`·`SAVINGS`·`PAY_MONEY`
  로 고정돼 대출 표시 행(`manageable=false`)이 섞여 들어오지 않으므로 여기서는 항상 `true` 다.

```json
// GET /api/assets/accounts?type=PAY_MONEY
{
  "success": true,
  "data": {
    "total": 244500,
    "accounts": [
      {
        "accountId": 7,
        "bankCode": "0090",
        "bankName": "카카오뱅크",
        "shortLabel": "kb",
        "accountName": "카카오페이머니",
        "accountNoMasked": null,
        "accountType": "PAYMONEY",
        "balance": 150000,
        "syncStatus": "NORMAL",
        "lastSyncAt": "2026-08-15T09:00:00",
        "syncFailReason": null,
        "expiresAt": "2027-08-15T00:00:00",
        "manageable": true
      }
    ]
  }
}
```

```json
// GET /api/assets/investments
{
  "success": true,
  "data": {
    "totalValuation": 2100000,
    "totalCost": 1980000,
    "asOf": "2026-08-15T09:41:00",
    "holdings": [
      {
        "accountId": 10,
        "symbol": "005930",
        "name": "삼성전자",
        "marketCountry": "KR",
        "currency": "KRW",
        "quantity": 20,
        "averagePurchasePrice": 75000,
        "lastPrice": 80000,
        "purchaseAmount": 1500000,
        "marketValue": 1600000,
        "profitLossAmount": 100000,
        "profitLossRate": 0.0667
      }
    ]
  }
}
```

```json
// GET /api/assets/loans
{
  "success": true,
  "data": {
    "total": 1500000,
    "loans": [
      {
        "loanId": 3,
        "bankName": "하나은행",
        "bankCode": "0081",
        "loanType": "신용대출",
        "loanAmount": 2000000,
        "balance": 1500000,
        "interestRate": 4.5,
        "startDate": "2025-01-10",
        "maturityDate": "2027-12-10",
        "monthlyPayment": 50000,
        "nextPaymentDate": "2026-09-10"
      }
    ]
  }
}
```

### 자산 상세 목록 에러 코드

| 코드 | HTTP | 설명 |
|---|---|---|
| `INVALID_REQUEST` | 400 | `type` 이 `DEMAND_DEPOSIT`·`SAVINGS`·`PAY_MONEY` 중 하나가 아니거나 누락됨 |

## 금융 데이터 동기화 (이슈 #147)

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| POST | `/api/financial-syncs` | Bearer | `{ status, syncedSources, syncedAt, collectedTransactionCount, ruleCategorizedCount, llmPendingTransactionCount, llmCategorizationStatus }` |

요청 본문 — **없어도 된다** (`@RequestBody(required = false)`). 평소에는 빈 채로 호출한다.
```json
{
  "institutionCodes": ["CP_KB"]
}
```
- `institutionCodes` 는 선택 항목이다. 대출·페이머니는 계좌 연동(`AccountLinkService.link()`)이
  다루지 못해 `tbl_connected_account` 행이 생기지 않고, 이 동기화의 수집 범위(scope)는 평소 그
  테이블에 이미 있는 기관 코드로만 정해진다 — 그래서 두 업권은 한 번 선택돼도 범위에 영영 들어오지
  못하는 순환이 있었다(이슈 #334). 계좌 연동 완료 직후 최초 동기화(`LinkDoneView`)에서만 방금 선택한
  기관 코드를 여기 채워 보내 범위를 넓힌다. 그 뒤로는 생성된 `tbl_connected_account`/`tbl_loan`
  행 덕분에 평소처럼 scope 에 자연히 포함되므로, 두 번째 동기화부터는 비워 보낸다.

```json
{
  "success": true,
  "data": {
    "status": "COMPLETED",
    "syncedSources": ["BANK", "DEPOSIT", "SECURITIES", "LOAN", "PAY_MONEY", "CARD"],
    "syncedAt": "2026-08-13T10:00:00+09:00",
    "collectedTransactionCount": 42,
    "ruleCategorizedCount": 30,
    "llmPendingTransactionCount": 12,
    "llmCategorizationStatus": "PENDING"
  }
}
```

- `syncedSources` 는 항상 6종 고정 목록이다 — 이번 호출에서 실제로 데이터가 있었던 업권만 골라
  돌려주지 않는다.
- `collectedTransactionCount`·`ruleCategorizedCount`·`llmPendingTransactionCount` 는 전부 **이번 호출 한 번에서 upsert 된 거래 기준**이다(사용자의 누적 미분류 거래 수가 아니다).
- 저장이 끝난 뒤 규칙 1~4단계(사용자 가맹점 매핑 → 공용 가맹점 매핑 → MCC/업종명 → 키워드)로 **동기** 카테고리화한다. `ruleCategorizedCount` 는 이 응답 안에서 확정된 값이다.
- 규칙으로도 분류하지 못한 소비 거래는 이 응답 이후 **비동기**로 LLM 분류 작업(`tbl_llm_categorization_job`, 사용자별 transaction_date 오름차순 최대 20건 배치)에 등록된다. 실제 LLM 호출은 이번 범위에 포함되지 않는다 — 작업 등록까지만 한다. 이 작업 등록은 응답을 만든 뒤 이벤트(`LlmCategorizationRequestedEvent`)로 비동기 실행되므로, 이 응답의 `llmPendingTransactionCount`·`llmCategorizationStatus` 는 작업 행이 실제로 만들어졌는지와 무관하게 규칙 분류 결과만으로 계산된다.
- `llmCategorizationStatus`: `PENDING`(LLM 대상 거래가 있음) · `NOT_REQUIRED`(전부 규칙으로 분류됐거나 대상 자체가 없음).
- 사용자가 직접 지정한 카테고리(`category_source='USER'`)는 재동기화로 자동 분류가 절대 덮어쓰지 않는다(DB 레벨 가드).
- 환불 거래(`is_refund=1`)는 일반 거래와 동일하게 규칙 1~4단계를 적용하되, 전부 미스하면 LLM 대상에서는 제외한다. 원거래 카테고리를 그대로 물려받는(계승) 처리는 **후속 작업**이다.
- `tbl_merchant_category_map`(공용 캐시)에 대한 write-back(3·4단계 판정 결과를 다시 채워 넣는 것)은 이번 범위가 아니다 — 비어 있으면 2단계는 항상 미스로 3단계로 넘어간다.
- 정기 동기화 스케줄러, 연결된 전체 사용자 대상 자동 동기화, 증분 동기화 조회 범위 변경도 이번 범위 밖이다.
- 실 CODEF 사용자(`codef` 모드 전체, `hybrid` 의 CODEF 사용자)는 이 동기화도 **실 CODEF** 를 부른다
  (`CodefFinancialSyncClient`). 은행 수시입출·적금·대출 거래, 카드 보유·승인·청구를 가져온다.
  증권은 범위 밖이고 페이머니는 CODEF 개인 상품이 없어 **빈 결과**다 — 목서버 데이터를 섞지 않는다.
  CODEF 연결(connectedId)은 계좌 연동 3단계에서 기관별로 암호화해 `tbl_codef_connection` 에 남긴다.
- 실 CODEF 사용자의 자동 동기화 간격은 `financial.codef.sync.min-interval-minutes`(기본 360분)를 따른다
  — CODEF 는 배치형 과다 호출 시 IP 를 차단한다고 경고한다. 수동 호출(이 엔드포인트)에는 적용되지 않는다.
- 실 CODEF 는 기관 단위로 실패를 넘긴다 — 한 은행이 점검 중이어도 나머지 기관 결과는 저장한다. 모든 기관이 실패할 때만 실패로 응답한다.

### 금융 데이터 동기화 에러 코드

| 코드 | HTTP | 의미 |
|---|---|---|
| `EXTERNAL_API_UNAVAILABLE` | 400 | 목서버 연결 실패(네트워크 단절·타임아웃) |
| `EXTERNAL_API_ERROR` | 400 | 목서버 응답이 실패 코드·빈 본문이거나, 방금 저장한 계좌·대출·카드를 다시 찾지 못함 |

> 저장 단계(`SAVE`)·카테고리화 단계에서 나는 예외는 `BusinessException` 이 아닐 수도 있다 —
> 그 경우 `500 INTERNAL_ERROR` 로 응답하지만, 어느 소스에서 실패했는지는 `tbl_financial_sync_history`
> (`failed_source`·`fail_reason`)에는 항상 남는다.

### LLM 분류 작업 처리 (후속 구현)

`tbl_llm_categorization_job`에 `PENDING`으로 등록된 작업은 별도 API 호출 없이 서버 내부 스케줄러(`LlmCategorizationScheduler`)가 주기적으로 집어 OpenAI Chat Completions API로 실제 분류를 수행한다.

- 주기: `llm.categorization.poll.fixed-delay-ms`(기본 1분). 한 번에 최대 `llm.categorization.poll.max-jobs-per-tick`(기본 20)개 작업을 처리한다.
- 분류 결과는 `category_source='LLM'`으로 `tbl_transaction.category_id`에 반영된다. 사용자 지정(`USER`) 카테고리는 기존 DB 가드로 보호된다.
- 프롬프트에 보내는 카테고리 목록은 `id`·`name`과 함께 `parentId`도 포함한다 — `parentId`가 없으면 대분류, 있으면 그 대분류에 속한 소분류라는 것을 LLM이 구분할 수 있게 한다. LLM은 확신이 서는 가장 구체적인(소분류) 레벨을 고르고, 소분류까지 확신이 안 서면 대분류만 골라도 된다.
- LLM은 각 판정마다 `confidence`(0.0~1.0)도 함께 반환해야 하며(Structured Outputs 스키마에 필수 필드로 강제), `confidence`가 `llm.categorization.confidence-threshold`(기본 0.8) 미만이거나 아예 없으면 `categoryId`가 있어도 분류 불가(null)로 취급한다(fail-closed).
- LLM이 제공된 카테고리 목록에 없는 id를 응답하면 그 거래는 반영하지 않고 건너뛴다(환각 방지).
- LLM이 **그 작업에 속하지 않은 `transactionId`**를 응답해도 반영하지 않고 건너뛴다. `updateCategory`에는 사용자 범위 조건이 없어, 이 검증이 없으면 남의 거래 카테고리를 덮어쓸 수 있다(가맹점명·적요는 외부 유입 자유 텍스트라 프롬프트 주입 통로이기도 하다).
- 작업에 속한 거래가 하나도 없으면 LLM을 호출하지 않고 즉시 `COMPLETED`로 마감한다.
- OpenAI가 `refusal`을 돌려주거나 `finish_reason`이 `stop`이 아니면(응답 잘림) 실패로 처리한다 — "0건 분류"가 정상 완료로 기록되는 것을 막는다.
- 작업 상태는 `PENDING → PROCESSING → COMPLETED`(정상 처리, 개별 거래가 분류 안 됐어도 정상 종료) 또는 `PROCESSING → FAILED`(API 호출 자체가 실패)로 전이한다. **`FAILED` 작업은 이번 범위에서 자동 재시도하지 않는다** — 후속 작업. `PROCESSING` 선점과 `FAILED` 마감은 둘 다 `LlmCategorizationJobStateService`가 `REQUIRES_NEW` 독립 트랜잭션으로 커밋한다(처리 트랜잭션이 롤백돼도 `FAILED`가 살아남아야 재실행 루프에 빠지지 않고, 선점이 먼저 커밋돼 행 잠금을 놓아야 그 `FAILED` 기록이 잠금 대기 없이 즉시 반영된다).
- `openai.api.key`는 로컬에서는 `application-local.properties`에만 둔다(팀 공용 키는 팀 채널에서 배포). 도커(`APP_ENV=docker`)에서는 `application-docker.properties`가 `${OPENAI_API_KEY}` 환경변수를 참조하며, 실제 값은 `.env` → `docker-compose.yml`을 거쳐 주입된다.
- OpenAI 호출은 전용 `RestTemplate`(`OpenAiClientConfig.openAiRestTemplate`)을 쓰며 `openai.api.connect-timeout-ms`(기본 10초)·`openai.api.read-timeout-ms`(기본 30초) 타임아웃이 걸려 있다.
- **토큰 최적화는 이번 범위 밖**(후속 작업, 2026-08-13 논의): 지금은 작업(job) 1건마다 `classify()`를 한 번씩 호출해, 매 호출마다 카테고리 전체 목록을 새로 실어 보낸다. 처리량이 늘어나면 한 틱에서 여러 job의 거래를 모아 한 번의 `classify()` 호출로 묶어(카테고리 목록을 한 번만 전송) 토큰을 아끼는 걸 고려한다 — 단, 지금의 "job 1건 = 호출 1번" 전제로 짜인 스케줄러·작업 단위 구조를 바꿔야 하는 작업이라 별도 설계가 필요하다.

## 마이페이지 (이슈 #57)

전용 엔드포인트를 새로 만들지 않았다. 아래 기존 API 를 조합해 그린다.

| 화면 | 쓰는 API |
|---|---|
| 프로필 카드 (`/my`) | `GET /api/users/me` — `{id, nickname, profileImageUrl, name, email, socialProvider}` |
| 프로필 이미지 변경 (`/my`) | `POST /api/users/me/profile-image` (업로드) · `DELETE /api/users/me/profile-image` (기본 아바타로 되돌리기) |
| 동의 관리 (`/my/consents`) | `GET /api/consents/me` · `POST /api/consents/{type}/withdraw` · `POST /api/consents` (재동의) |
| 로그아웃 | `POST /api/auth/logout` |

- `socialProvider` 는 마이페이지가 "google · 이메일" 형식으로 표시하려고 추가한 필드다.
- 등급은 화면에 표시하지 않는다 (DECISIONS.md 2026-08-06).
- 재동의 흐름은 「동의」 절의 *철회한 동의를 다시 켜기* 를 따른다.

## 맞춤 미션 개시 안내 (이슈 #129)

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| POST | `/api/main-challenge/mission-unlock/status` | Bearer | 안내 상태 동기화 후 노출 여부 조회. **요청 본문 없음** - 자격 판정은 서버가 한다 |
| PATCH | `/api/main-challenge/mission-unlock/acknowledge` | Bearer | 맞춤 미션 개시 안내 확인 처리 |

상태는 `tbl_user.personal_mission_unlock_status` 한 컬럼에서
`UNTRACKED → INSUFFICIENT → PENDING → SEEN` 순서로 전이한다. 처음부터 데이터가 충분한 사용자는
`UNTRACKED`에 머물러 안내가 뜨지 않는다. `INSUFFICIENT`를 거친 사용자가 데이터 조건을 충족하면
`PENDING`과 `showUnlock=true`를 반환하며, 확인 후에는 `SEEN`이라 다른 기기에서도 다시 뜨지 않는다.

```json
// POST request - 본문 없음. 자격 판정은 서버가 한다 (이슈 #315)

// response data
{ "status": "PENDING", "showUnlock": true }
```

## 오늘의 개인 미션 조회 (이슈 #160)

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| GET | `/api/missions/today` | Bearer | 로그인 사용자의 오늘 배정된 개인 미션 조회 |

응답은 `{ missionId, missionTitle, missionContent, missionType, categoryId, parentCategoryName,
categoryName, assignDate, difficultyName, targetRate, baseAmount, targetValue, result,
assignmentReason, guideMessage, streakDays }` 형태다.

- `streakDays`는 전날 미션 판정 배치가 `tbl_streak_count`에 저장한 현재 연속 성공 일수다.
- 전날 미션이 성공하면 1 증가하고 실패하면 0으로 초기화한다. 오늘 진행 중인 미션은 포함하지 않는다.

```json
{
  "success": true,
  "data": {
    "missionId": 11,
    "missionTitle": "배달 현장 급습",
    "missionContent": "오늘 배달 지출을 목표 금액 안으로 묶는다.",
    "missionType": "RELATIVE",
    "categoryId": 18,
    "parentCategoryName": "식비",
    "categoryName": "배달앱",
    "assignDate": "2026-08-12",
    "difficultyName": "NORMAL",
    "targetRate": 25,
    "baseAmount": 24000,
    "targetValue": 18000,
    "result": "PENDING",
    "assignmentReason": null,
    "guideMessage": null,
    "streakDays": 4
  }
}
```

- 오늘 날짜는 `Asia/Seoul` 기준으로 판단한다.
- 조회 API는 미션을 새로 배정하지 않는다.
- `LOW_SPENDING_NO_SPEND` 배정은 카테고리명과 배정 사유를 조합해 `guideMessage`를 생성한다.
- 일반 배정의 `guideMessage`는 `null`이다.
- 오늘 배정된 미션이 없으면 `TODAY_MISSION_NOT_FOUND`를 반환한다.

### 개인 미션 연속 성공 및 이번 주 판정 조회 (이슈 #186)

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/missions/streak` | Bearer | 현재·최장 연속 성공 일수와 이번 주 미션 결과 |

응답은 `{ streakCount, longestStreakCount, weekStartDate, weekEndDate, weeklyResults }` 형태다.
`weeklyResults[]` 항목은 `{ date, result }`이며, 조회 주간은 월요일부터 일요일까지다.

- `streakCount`는 현재 이어지고 있는 연속 성공 일수다.
- `longestStreakCount`는 사용자의 역대 최장 연속 성공 일수다.
- 전날 `PENDING` 미션을 자정 배치에서 먼저 판정한 뒤 성공이면 증가하고, 실패면 0으로 초기화한다.
- 주간 결과의 `SUCCESS`, `FAIL`, `PENDING`을 화면에서 각각 인정, 기각, 오늘 수사 중으로 표시한다.

### 개인 미션 미확인 판정 조회 및 확인 (이슈 #230)

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| GET | `/api/missions/verdicts/pending` | Bearer | 확정됐지만 확인하지 않은 개인 미션 판정 1건 조회 |
| POST | `/api/missions/verdicts/{assignmentId}/acknowledge` | Bearer | 개인 미션 판정 확인 처리 |

미확인 판정 조회 응답은 `{ assignmentId, result, assignDate, categoryName, currentAmount,
targetValue, remainAmount, overAmount, points, bonusPoints, streakDays, pendingCount, transactions }`
형태다. `transactions[]` 항목은 `{ transactionId, merchantName, amount }`이다.

- `Asia/Seoul` 기준 전날 배정됐고 `SUCCESS` 또는 `FAIL`로 확정된 판정만 조회한다.
- 사용자별 하루 한 건 제약에 따라 `pendingCount`는 현재 범위에서 항상 0이다.
- 미확인 판정이 없으면 성공 응답의 `data`는 `null`이다.
- 거래 금액은 판정 배치와 동일하게 소비·출금·요약 포함 거래만 합산하고 환불은 차감한다.
- 확인 API는 최초 `result_checked_at`을 유지하는 멱등 요청이다.
- 본인 소유의 확정 판정이 아니면 `NOT_FOUND`를 반환한다.

```json
{
  "success": true,
  "data": {
    "assignmentId": 123,
    "result": "SUCCESS",
    "assignDate": "2026-08-13",
    "categoryName": "배달앱",
    "currentAmount": 9800,
    "targetValue": 12000,
    "remainAmount": 2200,
    "overAmount": 0,
    "points": 35,
    "bonusPoints": 5,
    "streakDays": 6,
    "pendingCount": 0,
    "transactions": [
      { "transactionId": 91, "merchantName": "돈까스집 배달주문", "amount": 6300 }
    ]
  }
}
```

### 개인 미션 월간 점수 및 상위 백분율 조회 (이슈 #194, #226)

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/missions/monthly-score` | Bearer | 로그인 사용자의 이번 달 개인 미션 누적 점수와 상위 백분율 |

응답은 `{ yearMonth, totalScore, topPercent }` 형태다. `yearMonth`는 `Asia/Seoul` 기준 현재 월의 `YYYY-MM` 값이다.

```json
{
  "success": true,
  "data": {
    "yearMonth": "2026-08",
    "totalScore": 75,
    "topPercent": 15
  }
}
```

- `SUCCESS`로 확정된 미션만 점수에 포함한다. `FAIL`, `PENDING`은 0점이다.
- 상대형 기본 점수는 배정 당시 `difficulty_id`가 참조하는 `tbl_mission_difficulty.score`를 사용한다.
- 절대형 미션은 난이도와 관계없이 성공 시 기본 50점을 부여한다.
- 전날 미션과 해당일 미션이 모두 `SUCCESS`이면 해당일 점수에 연속 성공 보너스 5점을 더한다.
- 전날 미션이 `FAIL`이거나 배정 이력이 없으면 연속 성공으로 계산하지 않는다.
- 절대형도 연속 성공일에 포함하며, 전날 미션이 성공이면 기존 연속 성공 보너스 5점을 더한다.
- 월간 점수는 해당 월 확정 미션 이력 전체를 다시 합산해 `tbl_monthly_ranking.total_score`에 갱신하므로 배치 재실행에도 중복 반영되지 않는다.
- `topPercent`는 해당 월 활성 사용자의 점수 순위와 전체 참여자 수를 기준으로 올림 계산한다.
- 해당 월 랭킹 행이 아직 없으면 `totalScore`는 0, `topPercent`는 `null`을 반환한다.

## 개인 챌린지 월간 성과 리포트 (이슈 #244)

모든 엔드포인트는 Bearer 인증이 필요하며, 사용자 ID를 요청 파라미터로 받지 않는다. 리포트는
익월 1일 00:20 KST 배치가 전월의 최종 판정 개인 미션을 확정한 뒤에만 조회할 수 있다.

| 메서드 | 경로 | 응답 |
|---|---|---|
| GET | `/api/reports/challenge/months` | 진입 상태와 조회 가능한 확정 월 목록 |
| GET | `/api/reports/challenge?yearMonth=YYYY-MM` | 선택한 확정 월의 개인 챌린지 성과 |

집계 대상은 대상 월에 배정되고 `SUCCESS` 또는 `FAIL`로 최종 판정됐으며 배정 시점 기준금액
`base_amount(B)`가 저장된 개인 미션이다. 상대형과 절대형을 구분하지 않으며, `PENDING`과 B가 없는
과거 이력은 제외한다. 결과는 `tbl_challenge_monthly_report`에 사용자·월별로 확정 저장한다.
API 모드에서 월 목록을 새로 조회할 때 전월 행이 없으면 해당 사용자·전월만 보정 생성하며,
이미 행이 있으면 다시 계산하지 않는다.

`weeklyResults`는 월요일 시작 달력 주 순서다. 각 주의 `totalDays`·`successDays`·`successRate`는
선택 월의 날짜만 세므로, 이전 달과 다음 달에 걸친 날짜는 분자·분모에서 모두 제외한다.
첫 확정 리포트가 시작된 달에는 사용자의 최초 개인 미션 배정일부터 계산한다.
`week`는 시작 전 주를 생략해도 다시 번호를 매기지 않으며, 해당 월의 달력 주차를 유지한다.

### `GET /api/reports/challenge/months`

리포트 화면에 처음 들어올 때 호출한다. 진입 상태와 월 선택기에 그릴 목록을 함께 준다.

```json
{
  "entryState": "READY",
  "months": [
    {
      "value": "2026-07",
      "year": 2026,
      "month": 7,
      "available": true,
      "hasReport": true,
      "firstReport": true,
      "status": "READY"
    }
  ]
}
```

`entryState`는 세 가지다. 화면 분기는 이 값 하나로 결정한다.

| `entryState` | 뜻 | `months` |
|---|---|---|
| `NOT_AGREED` | 활성 챌린지 동의가 없다 | 빈 배열 |
| `PREPARING_FIRST_REPORT` | 동의는 했지만 확정된 월이 아직 없다 | 준비 중인 월 1건. 그 월조차 특정할 수 없으면 빈 배열 |
| `READY` | 조회 가능한 확정 월이 있다 | 최신 월부터 내림차순 |

- `PREPARING_FIRST_REPORT`의 월 1건은 **첫 개인 미션 배정월**이며, 그 이력이 없으면 챌린지 동의월을
  대신 쓴다. 이 항목은 `available=false`, `hasReport=false`, `status=PREPARING_FIRST_REPORT`라
  선택할 수 없다. 「첫 리포트 준비 중」 안내를 띄우는 용도다.
- **당월은 확정 전이므로 절대 포함하지 않는다.** `available=true`인 항목은 전부 지난달 이전이다.
- `firstReport`는 목록에서 **가장 오래된 확정 월 1건에만** `true`다. 상세의
  `hasPreviousComparison=false`와 짝이며, 프론트는 이 플래그로 전월 비교 영역만 숨긴다.
- ⚠ **이 조회는 읽기 전용이 아니다.** 전월 확정 행이 없으면 해당 사용자·전월만 보정 생성한 뒤
  목록을 만든다. 이미 행이 있으면 다시 계산하지 않는다.

### `GET /api/reports/challenge?yearMonth=YYYY-MM`

상세 응답은 다음 형태다.

```json
{
  "period": "2026-07",
  "hasChallengeHistory": true,
  "isFirstServiceMonth": false,
  "hasPreviousComparison": true,
  "missionSuccessRate": 82.76,
  "monthOverMonthPercentagePoint": 9.43,
  "successfulDays": 24,
  "challengeDays": 29,
  "bestStreakDays": 8,
  "bestWeekday": "화요일",
  "earnedPoints": 245,
  "savedAmount": 128000,
  "overspentAmount": 32000,
  "netSavings": 96000,
  "annualizedNetSavings": 1152000,
  "categoryEffects": [
    {
      "categoryId": 12,
      "categoryName": "카페·간식",
      "successfulDays": 18,
      "savedAmount": 72000,
      "failedDays": 4,
      "overspentAmount": 18000
    }
  ],
  "weeklyResults": [
    { "week": 1, "successDays": 4, "totalDays": 5, "successRate": 80.00 }
  ],
  "difficulties": [
    { "difficultyName": "EASY", "attempts": 12, "successDays": 11, "successRate": 91.67 }
  ],
  "groupRecordState": "READY",
  "groupRecord": {
    "participatingGroups": 2,
    "survivedCount": 1,
    "eliminatedCount": 1,
    "indictedCount": 3,
    "acquittedCount": 2,
    "convictedCount": 1
  }
}
```

첫 챌린지 리포트는 일반 리포트로 반환하되 `isFirstServiceMonth=true`,
`hasPreviousComparison=false`, `monthOverMonthPercentagePoint=null`이다. 프론트는 이 필드로
전월 비교 영역만 숨긴다.

금액 4종은 확정 스냅샷 값이며 저장된 값이 없으면 `0`이다. `netSavings`는 절약액에서 초과액을
뺀 값이고, `annualizedNetSavings`는 그 12배다(연 환산 표시용).

`groupRecord`는 개인 미션 성과와 **별도 집계**다. 선택 월에 종료되고 최종 결과까지 확정된
그룹 챌린지 전적만 센다. 표시 여부는 `groupRecordState`로 가른다.

| `groupRecordState` | 뜻 | `groupRecord` |
|---|---|---|
| `READY` | 확정된 그룹 전적이 있다 | 객체 |
| `JUDGING` | 종료한 그룹 중 최종 판정 대기가 있어 아직 못 보여준다 | `null` |
| `EMPTY` | 선택 월에 표시할 그룹 전적이 없다 | `null` |

현재 월·미래 월은 `CHALLENGE_REPORT_NOT_AVAILABLE`, 확정 스냅샷이 없는 과거 월은
`CHALLENGE_REPORT_NOT_FOUND`, 형식 오류는 `INVALID_YEAR_MONTH`로 반환한다.
활성 챌린지 동의가 없는 상태로 상세를 부르면 `CHALLENGE_REPORT_NOT_AVAILABLE`이다
(월 목록은 오류 대신 `entryState=NOT_AGREED`를 준다). 저장된 스냅샷 JSON을 읽지 못하면
`CHALLENGE_REPORT_UNREADABLE`이다.

로컬 시연에서는 `POST /api/dev/reports/challenge/monthly?yearMonth=YYYY-MM`로 선택한 과거 월의
확정 배치를 즉시 실행할 수 있다. 응답은 `{ yearMonth, affected }`이며, `affected`는 실제로
스냅샷을 계산한 사용자 수다. `app.env=local`에서만 동작하고 인증이 필요하다.

## 개인 미션 월간 랭킹 조회 (이슈 #209)

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/missions/rankings?yearMonth=YYYY-MM` | Bearer | 해당 월 상위 10명과 내 순위 |
| GET | `/api/missions/rankings/months` | Bearer | 전체 랭킹 데이터가 있는 `YYYY-MM` 목록 |
| GET | `/api/missions/rankings/certificate?yearMonth=YYYY-MM` | Bearer | 확정 월 명예 인증서 데이터 |
| GET | `/api/missions/rankings/certificate/titles?yearMonth=YYYY-MM` | Bearer | 확정 월 AI 명예 타이틀 3개 |

- 월 목록 응답은 `{ "yearMonths": ["2026-08", "2026-07"] }` 형식이다.
- 프론트에서 이 목록을 기준으로 월 선택 버튼과 연도 이동을 활성화한다.
- 인증서는 서울 시간 기준 전월 이전만 발급할 수 있다. 당월·미래 월 요청은 `CERTIFICATE_NOT_FINALIZED` 오류를 반환한다.
- 인증서 응답은 `{ yearMonth, totalUsers, myRanking, streakDays, bestStreakDays, completedMissionCount, successMissionCount }` 형식이다.
- AI 명예 타이틀은 매월 1일 00:40 KST에 전월 마지막 미션 판정과 당월 미션 배정 후 생성해 저장한다.
  인증서 발급 알림은 타이틀 생성 이후인 00:50 KST에 발송한다. 조회 API는
  `{ yearMonth, titles, source }` 형식이며 AI 생성 전·실패 시 `source: "FALLBACK"` 타이틀을 반환한다.

## 메인 챌린지 카테고리 분석 (이슈 #119)

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/missions/categoryAnalysis` | Bearer | 최근 28일 상대형 미션 대상 소비 상위 3개 |

응답은 `{ analysisStartDate, analysisEndDate, transactionCount, cumulativeTransactionCount, requiredCumulativeTransactionCount, topCategories }` 다.
`transactionCount`는 최근 28일 분석 건수이고, `cumulativeTransactionCount`는 최초 영구 자격에
사용하는 실제 누적 유효 소비 건수다. 화면은 `requiredCumulativeTransactionCount`와 비교해 진행도를 표시한다.
`topCategories[]` 항목은 `{ rank, categoryId, parentCategoryName, categoryName, totalAmount, transactionCount, spendingRatio, rotationAssignDate, rotationResult, missionRound }` 형태다.

- 응답의 상위 카테고리는 매일 재산정한 목록이 아니라 아직 소진 중인 현재 분석 주기의 스냅샷이다.
- `rotationAssignDate`는 현재 주기에서 해당 카테고리가 배정된 날짜다. 아직 배정 전이면 `null`이다.
- `rotationResult`는 현재 주기의 결과이며 `SUCCESS`, `FAIL`, `PENDING`, `WAITING` 중 하나다. `WAITING`은 현재 주기에 선정됐지만 아직 배정되지 않았다는 뜻이다.
- `missionRound`는 이번 배정 또는 다음 배정이 해당 카테고리의 몇 번째 수사인지를 나타낸다. 해당 카테고리로 배정된 전체 이력 수를 세고, 현재 주기에서 아직 `WAITING`이면 다음 예정 회차를 위해 1을 더한다.

- 분석 기간은 오늘을 제외한 최근 28일이다.
- 최초 상대형 미션 자격은 기간과 무관하게 누적 유효 소비가 50건 이상일 때 획득한다.
- 최초 자격 획득 시 `tbl_user.relative_mission_qualified_at`을 기록한다. 이 값은 챌린지 동의를 철회해도 유지한다.
- 자격 획득 후에는 최근 28일 거래 건수를 자격 조건으로 다시 검사하지 않는다.
- 상대형 후보 카테고리는 최근 28일에 유효 소비 3건 이상, 서로 다른 소비일 2일 이상,
  환불 반영 순소비금액이 양수인 카테고리다. 조건을 만족한 카테고리 중 소비금액 상위 3개를 선정한다.
- 후보 카테고리가 없으면 영구 자격은 유지하고 그날은 절대형 미션으로 대체한다.
- 미션 대상은 `tbl_mission_pool`에 `RELATIVE` 행이 존재하는 소분류다. 현재 정책은 15개다.
- 환불은 거래 건수에서 제외하고 순소비금액에서 차감한다.
- 상대형 후보 조건을 만족하는 카테고리가 없으면 `topCategories=[]`를 반환한다.
- 소비금액, 거래 건수, 카테고리 ID 순으로 정렬해 최대 3개를 반환한다.
- `spendingRatio`의 분모는 최근 28일 전체 분류 소비의 순소비금액이다.

### 상대형 미션 자동 배정 (이슈 #139)

정규 배정은 서버 스케줄러가 매일 한국 시간 00:10에 실행한다.
실행 시간은 `mission.assignment.cron`, 타임존은 `mission.assignment.zone` 설정으로 변경할 수 있다.

- 정규 배정 대상은 `CHALLENGE` 동의가 활성 상태이고 오늘 미션이 없는 활성 사용자다.
- 최초 동의 또는 철회 후 재동의 시에는 해당 사용자만 당일 즉시 배정한다.
- 서버 시작 시 한 번, 매일 00:30에 활성 동의 기간의 누락 날짜를 오래된 순서로 복구한다.
- 과거 누락일은 해당 날짜 미션을 먼저 배정한 뒤 그 날짜 소비로 즉시 판정하며,
  점수·연속 성공일·랭킹을 함께 다시 계산한다. 복구 배정 알림은 보내지 않는다.
- 이미 저장된 날짜별 미션은 다시 뽑지 않으며, `PENDING` 결과만 판정한다.
- 복구 시각은 `mission.assignment.recovery-cron`으로 변경할 수 있다.
- 동의를 철회하면 기존 미션 기록은 유지하고 이후 정규·복구 배정 대상에서 제외한다.
- 사용자 한 명의 실패가 나머지 사용자 배정을 중단하지 않으며, 다음 복구 주기에 다시 대상이 된다.

- 사용자별로 하루 한 개만 배정하며 `uk_umi_user_date`를 최종 중복 방어선으로 사용한다.
- 최신 분석 주기의 미소진 항목 중 `category_rank` 숫자가 가장 낮은 카테고리를 먼저 사용한다.
- 미소진 항목이 없으면 최근 28일 소비를 다시 분석해 새 스냅샷을 만든다.
- 직전에 같은 카테고리에서 배정한 미션을 우선 제외하되 후보가 없으면 재사용한다.
- 절감률은 배정 시점 사용자 난이도의 DB 구간에서 양 끝을 포함한 정수로 선택한다.
- `baseAmount`는 배정일 전 28일의 해당 카테고리 양수 일별 순소비 평균이다.
- 정상 상대형의 `targetValue = baseAmount × (1 - targetRate / 100)`이다.
- 계산 목표가 해당 카테고리의 최근 28일 최소 양수 단건 결제 금액보다 낮으면 목표를 올리지 않고,
  같은 카테고리의 `ABSOLUTE`이면서 `limit_price=0`인 무지출 미션으로 전환한다.
- 무지출 전환 배정은 실제 수행 목표인 `targetValue=0`으로 저장하되,
  전환 판단 근거를 추적할 수 있도록 `targetRate`, `baseAmount`, `difficultyId`는 유지한다.
- `assignmentReason=LOW_SPENDING_NO_SPEND`를 저장하여 월 1회 절대형과 구분하고 다음 안내를 표시한다.
  `평소 {카테고리} 지출이 이미 낮아 금액을 더 나누기 어려워요. 오늘은 {카테고리} 하루 쉬기에 도전해볼까요?`
- 미션 저장과 스냅샷 `assigned_date` 갱신은 하나의 사용자별 트랜잭션으로 처리한다.

### 절대형 미션 자동 배정 (이슈 #292)

- 상대형 자격을 얻지 못한 사용자는 자격 획득 전까지 매일 절대형 미션을 받는다.
- 콜드스타트 절대형 카테고리는 날짜를 기준으로 고정 계산하며, 같은 날에는 모든 콜드스타트
  사용자가 같은 카테고리의 공통 미션을 받는다.
- 상대형 자격 여부와 무관하게 월 1회 전체 단속일에는 절대형 미션을 우선 배정한다.
- 전체 단속 날짜와 카테고리는 연월을 기준으로 고정 계산하므로 모든 사용자와 서버에서 같고,
  재기동·복구 배치에도 바뀌지 않는다.
- 절대형 후보는 `mission_type=ABSOLUTE`이면서 `limit_price=0`인 무지출 미션만 사용한다.
- `targetValue=0`, `targetRate=null`로 저장한다. 선택 카테고리의 배정일 전 28일 양수 일별
  순소비 평균을 `baseAmount`로 저장하고, 계산할 소비가 없으면 `null`로 둔다.
- 배정 사유는 콜드스타트 `COLD_START`, 월간 전체 단속 `MONTHLY_RANDOM`으로 구분한다.
- 절대형 배정은 상대형 분석 스냅샷을 소진하지 않는다.

## 그룹 챌린지 — 생성·초대·참여·조회 (이슈 #151)

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| POST | `/api/group-challenges` | Bearer | `{ groupId, inviteCode }` |
| GET | `/api/group-challenges?status=` | Bearer | `ChallengeGroup[]` |
| GET | `/api/group-challenges/{groupId}` | Bearer | `ChallengeGroup` |
| GET | `/api/group-challenges/invite-codes/{inviteCode}` | Bearer | `{ challenge, joinable, reason }` |
| POST | `/api/group-challenges/invite-codes/{inviteCode}/members` | Bearer | `ChallengeGroup` |
| DELETE | `/api/group-challenges/{groupId}` | Bearer | `null` |

생성 요청 본문은 `{ groupName, categoryId, limitAmount, evalType, startDate, endDate, memo }` 다.
**정원(`maxMembers`)은 받지 않는다** — 생성 화면에 입력 UI 가 없어 서버가 6 으로 고정한다.
프론트의 자유 규칙 입력값은 ERD 컬럼명에 맞춰 `memo` 로 보낸다.

`ChallengeGroup` 은 목록·상세·미리보기가 **모두 같은 한 가지 모양**이다.
`tbl_challenge_group` 전 컬럼(`memo` · `createdAt` 포함 — `createdAt` 은 초대 화면의 소환장이
「발부일」로 찍는다) + 로그인 사용자 본인의
`tbl_group_member` 값(`livesCount`, `finalOutcome`, `finalRank`, `finalChargeAmount`)
+ 파생값(`totalDays`, `currentDay`, `daysUntilStart`, `maxLives`, `memberCount`,
`owner`, `member`, `joinable`) + `members[{userId, nickname, owner}]`.

> 목록/상세를 다른 모양으로 나누지 않았다. 차이가 `memo` 하나뿐인데 나누면 미리보기 응답이
> `data.challenge.challenge.groupName` 처럼 두 겹으로 접혀 프론트가 매번 풀어야 한다.

- `status` 는 콤마 또는 반복으로 여러 개를 넘긴다. 화면의 「종료됨」 탭은 `JUDGING,CLOSED` 를 함께 본다.
  값을 안 주면 전체다. 열거값에 없는 값은 빈 목록이 아니라 400 이다.
- **목숨은 참여 시점에 계산해 저장한다.** `lives_count` 가 NOT NULL 인데 기본값이 없어서다.
  DAILY = 기간 일수, PERIOD = 1. 늦게 참여해도 목숨은 같다 — 의도된 정책이다.
- **초대 코드는 5자리 대문자·숫자다.** 참여 코드 입력 UI 가 5칸이라 거기에 맞췄다.
  혼동되는 글자(`0 O 1 I L`)는 알파벳에서 뺐다.
- **초대 코드에는 만료 컬럼이 없다.** `start_date` 에서 파생한다 — 시작일 당일 23:59 까지 모집하고
  그 다음 날부터 만료다. 코드는 대소문자를 가리지 않으며 저장은 항상 대문자다.
- **제한 금액 0원은 정상 입력값이다** (무지출 챌린지). 음수만 막는다.
- 방장도 `tbl_group_member` 에 들어간다. 정원·목숨·랭킹이 전부 이 테이블을 세기 때문이다.
- 상세는 **참여자만** 볼 수 있다. 비참여자는 초대 코드 미리보기 경로를 쓴다.
- **참여는 `groupId` 가 아니라 초대 코드로 한다** (이슈 #346). 예전 경로 `POST /{groupId}/members` 는
  코드를 전혀 받지 않아, 미리보기로 알아낸 id 나 연번 순회만으로 남의 방에 들어갈 수 있었다.
  코드를 함께 받아 검증하는 대신 **`groupId` 파라미터 자체를 없앴다** — 검증을 빠뜨리면 다시 뚫리는
  구조를 남기지 않기 위해서다. 오류 코드는 미리보기와 같은 `GROUP_INVITE_CODE_NOT_FOUND` 로 시작하고,
  코드는 맞는데 참여만 막힌 경우 `GROUP_ALREADY_JOINED`·`GROUP_CLOSED`·
  `GROUP_INVITE_CODE_EXPIRED`·`GROUP_FULL` 이다.
- 재판 배지(`pendingTrialCount`·`defendant`·`myVoteStatus`)는 **목록에서만** 채워진다 (이슈 #169).
  상세·참여·초대 미리보기에서는 `0`·`false`·`null` 이다.
  절감액·채팅 필드는 근거 데이터가 없어 **아예 내려주지 않는다.**
- 상태 전이(`RECRUITING` → `ACTIVE`, 모집 미달 시 삭제)와 시작 알림은 이 API 가 하지 않는다 —
  별도 배치(이슈 #152, 아래 참고). **참여 가능 판정의 기준은 `status` 다.** 그래서 배치가 밀리면
  시작일이 지났어도 잠시 참여가 열려 있을 수 있다.
  **단 `ACTIVE` 한 갈래에서만 시작일을 함께 본다** (이슈 #350). 모집 마감은 **시작일 23:59** 인데
  상태 전이 배치는 시작일 00:01 에 돌아 `ACTIVE` 로 바꿔 버린다. `status` 만 보면 시작일 하루가
  통째로 막혀 시작일 참여 창이 0초가 된다. `오늘 <= startDate` 인 동안에는 `ACTIVE` 라도 열어 둔다.
  배치가 밀렸을 때는 `RECRUITING` 이라 이 조건에 애초에 닿지 않으므로 #152 의 결정과 충돌하지 않는다.
- **`categoryId` 는 소분류만 받는다** (이슈 #352). 대분류를 주면 `GROUP_CATEGORY_INVALID` 다.
  `null` 은 「총 소비」라 정상값이다. 대분류를 막는 이유는 `tbl_transaction.category_id` 에 소분류만
  들어가는데 결과 집계가 `t.category_id = g.category_id` 로 정확히 맞춰 보기 때문이다 — 대분류로 만든
  그룹은 **매칭 거래가 영원히 0건**이라 오류 하나 없이 「0원」만 뜨고 아무도 기소되지 않는다.
  화면이 고를 목록은 `GET /api/categories` 의 `parentId != null` 인 것들이다.

### 삭제 (이슈 #352)

`DELETE /api/group-challenges/{groupId}` — **방장만, `RECRUITING` 일 때만.** 성공 응답에 `data` 는 없다.

- **시작한 뒤에는 못 지운다.** 참여자들의 소비 집계·기소·투표가 이미 쌓여 있어 한 사람의 결정으로
  남의 기록까지 CASCADE 로 지우게 된다. `ACTIVE` 이후에도 열어 줄지 검토했으나
  **2026-08-20 팀 논의에서 열지 않기로 확정했다** - 챌린지가 최대 7일이라 오래 매여 있지 않고, 진행 중인 재판과 집계가 갑자기 사라지는 쪽이 더 나쁘다.
- 자식 행(참여자·결산·기소·투표)은 FK `ON DELETE CASCADE` 가 정리하고 **채팅방(Redis)도 즉시 지운다.**
  되돌릴 수 없다.
- **「나가기(멤버 탈퇴)」는 없다.** 목숨·순위가 참여자 수를 전제로 계산돼 중간 이탈을 넣으려면
  그 계산을 전부 다시 정의해야 한다.
- 방장을 뺀 **남은 참여자에게** `GROUP_CHALLENGE_DELETED` 알림이 간다 (딥링크 `/group-challenges` —
  그룹이 사라져 상세로 보내면 404 다). 모집 중이라도 이미 최대 5명이 들어와 있을 수 있다.
- 삭제는 상태 전이 배치의 미성립 처리와 **같은 절차**를 쓴다(`ChallengeGroupDeleter`).
  조회 시점엔 `RECRUITING` 이었어도 그 사이 시작 배치가 `ACTIVE` 로 바꿨으면 `GROUP_NOT_DELETABLE` 이다.

### 초대 코드 미리보기가 200 인 이유

코드 자체가 없으면 `GROUP_INVITE_CODE_NOT_FOUND` 로 끝내지만, **코드는 유효한데 참여만 못 하는**
경우는 200 + `joinable:false` + `reason` 으로 내려간다. 참여 확인 화면(GC_01_06)이 그룹 정보를
먼저 보여준 다음 사유를 안내해야 하기 때문이다.

| `reason` | 뜻 |
|---|---|
| `ALREADY_JOINED` | 이미 참여 중 (다른 사유보다 먼저 판정한다 — 그룹으로 보내야 하므로) |
| `CLOSED` | 상태가 `JUDGING` 또는 `CLOSED` |
| `EXPIRED` | 모집이 끝났다 (`status=ACTIVE` **이고** 시작일이 지났다. 시작일 당일은 아직 열려 있다 · #350) |
| `FULL` | 정원(6명) 초과 |

같은 상황에서 **참여 API** 는 200 이 아니라 400 이다 — 아래 코드로 매핑된다.

### 그룹 챌린지 에러 코드

| 코드 | HTTP | 상황 |
|---|---|---|
| `GROUP_NAME_REQUIRED` | 400 | 이름이 비었거나 공백뿐 |
| `GROUP_NAME_TOO_LONG` | 400 | 이름 100자 초과 |
| `GROUP_LIMIT_AMOUNT_INVALID` | 400 | 제한 금액이 없거나 음수 (0원은 정상) |
| `GROUP_EVAL_TYPE_INVALID` | 400 | `DAILY`/`PERIOD` 가 아님 |
| `GROUP_PERIOD_INVALID` | 400 | 기간 누락 · 종료일이 시작일보다 앞 · 7일 초과 |
| `GROUP_START_DATE_INVALID` | 400 | 시작일이 오늘이거나 과거 (**내일 이후만 받는다** · #350) |
| `GROUP_MEMO_TOO_LONG` | 400 | 메모 300자 초과 |
| `GROUP_CATEGORY_INVALID` | 400 | 없는 카테고리이거나 **대분류** (소분류만 받는다 · #352) |
| `GROUP_STATUS_INVALID` | 400 | 알 수 없는 `status` 필터 |
| `GROUP_NOT_FOUND` | 400 | 없는 그룹 |
| `GROUP_NOT_MEMBER` | 400 | 참여자가 아닌데 상세 조회 |
| `GROUP_NOT_OWNER` | 400 | 방장이 아닌데 삭제 시도 (#352) |
| `GROUP_NOT_DELETABLE` | 400 | 이미 시작된 그룹 삭제 시도 (#352) |
| `GROUP_INVITE_CODE_NOT_FOUND` | 400 | 없는 초대 코드 |
| `GROUP_INVITE_CODE_EXPIRED` | 400 | 모집 마감 후 참여 시도 |
| `GROUP_CLOSED` | 400 | 종료된 챌린지에 참여 시도 |
| `GROUP_FULL` | 400 | 정원 초과 |
| `GROUP_ALREADY_JOINED` | 400 | 이미 참여 중인데 다시 참여 시도 |
| `GROUP_INVITE_CODE_EXHAUSTED` | 400 | 초대 코드 채번 재시도 실패 (사실상 발생하지 않는다) |

## 그룹 챌린지 — 시작 상태 전이 배치 (이슈 #152)

시작일이 된 그룹의 `status` 를 서버 스케줄러가 바꾼다. **API 는 상태를 전이시키지 않는다.**

실행은 매일 한국 시간 **00:01**이다. 시각은 `challenge.group.status-transition.cron`,
타임존은 `challenge.group.status-transition.zone` 으로 바꾼다.

> **00:00 정각으로 당기지 않는다.** 기준일을 배치 안에서 `LocalDate.now(zone)` 으로 계산하는데
> 정각에는 이 값이 전날로 나올 수 있어(트리거가 미세하게 일찍 발화하거나 NTP 가 시계를 되돌림)
> 그날 시작하는 그룹이 통째로 누락된다. 미션 자동 배정(00:10)보다 앞서야 한다는 제약도 있다 —
> 미션은 챌린지가 `ACTIVE` 인 것을 전제로 한다.

- 대상은 `status=RECRUITING` 이면서 `start_date <= 기준일` 인 그룹이다.
  **등호가 아니라 부등호다** — 서버가 내려가 배치를 건너뛰었어도 다음 실행이 밀린 그룹을 주워 담는다.
- 참여자 **2명 이상**이면 `ACTIVE` 로 간다. **1명(방장뿐)**이면 그룹을 **삭제한다**(이슈 #261).
  혼자서는 재판이 성립하지 않는다.
- **미성립 그룹을 `CLOSED` 로 두지 않는 이유**는 `CLOSED` 가 「정상 종료 + 최종 결과 확정 완료」를
  뜻하는 상태이기 때문이다. 시작조차 못 한 그룹은 `final_*` 가 영원히 NULL 이고 결산 행도 없으며
  `end_date` 가 미래라, 종료 연월로 월 귀속을 잡는 그룹 전적·월간 리포트가 이 그룹을 미래 달에 끌어다 놓는다.
  자식 행(참여자·결산·기소·투표)은 FK 의 `ON DELETE CASCADE` 가 정리한다.
- 알림은 `ACTIVE` 면 참여자 전원에게 `GROUP_CHALLENGE_STARTED` (딥링크 `/group-challenges/{groupId}`),
  삭제면 방장에게 `GROUP_CHALLENGE_CANCELED` (딥링크 `/group-challenges` — 그룹이 사라져 상세로 보내면 404 다).
배치는 두 전이를 **순서대로** 돌린다. 아래는 첫 번째(`RECRUITING → ACTIVE`/삭제) 이야기다.
두 번째 `ACTIVE → JUDGING` 은 이슈 #169 에서 붙었다 — 같은 절 끝에 따로 적었다.

- 트랜잭션은 **그룹 한 건 단위**다. 한 그룹이 실패해도 나머지는 처리되고, 실패분은 다음 실행이 다시 집는다.
- 멱등하다. `status = 'RECRUITING'` 인 행만 골라 UPDATE·DELETE 하고 바뀐 행이 0이면 알림을 보내지 않는다.
  같은 날 두 번 돌려도 알림이 두 번 나가지 않는다.
  DELETE 쪽 조건절은 중복 알림보다 **오삭제**를 막는 장치다 — 조회 시점엔 혼자였어도 그 사이 참여가 생겨
  다른 실행이 `ACTIVE` 로 전이시켰을 수 있고, 조건이 없으면 방금 시작된 그룹이 통째로 사라진다.

### 종료 전이 `ACTIVE → JUDGING` (이슈 #169)

같은 배치의 두 번째 단계다. **`JUDGING` 을 쓰는 코드가 저장소에 한 곳도 없었다** — 종료일이 지난
그룹이 영영 `ACTIVE` 로 남아 「진행 중」 탭에 쌓이고 「종료됨」 탭(`JUDGING,CLOSED`)은 비어 있었다.
시드 SQL 이 `JUDGING` 행을 직접 넣고 있어 화면상으로만 가려져 있었다.

- 대상은 `status=ACTIVE` 이면서 **`end_date < 기준일`** 인 그룹이다.
  기준일은 `today` 가 아니라 **`today - 1일`** 이다. 평가·기소 배치가 종료 다음 날까지 `ACTIVE`
  그룹을 봐야 마지막 날 소비가 평가된다 — 종료 당일 자정에 `JUDGING` 으로 넘기면 그 하루가 통째로 빠진다.
- **알림도 트랜잭션도 없다.** UPDATE 한 문장이라 그 자체가 원자적이고, 함께 묶을 다른 쓰기가 없다.
  멱등성은 `WHERE status = 'ACTIVE'` 비교가 그대로 보장한다.
- `JUDGING → CLOSED`(최종 결과 확정)는 이 배치가 하지 않는다. 이슈 #172 · #173 범위다.

### 배치 수동 트리거 (DEV 전용)

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| POST | `/api/dev/batches/{name}?date=` | Bearer | `{ batch, baseDate, affected }` |
| POST | `/api/dev/reports/monthly/batch` | Bearer + `X-Report-Batch-Key` | `{ yearMonth, targetCount, snapshotSavedCount, aiGeneratedCount, failureCount, forced }` |

자정을 기다리지 않고 배치를 돌리기 위한 시연·검증용이다.

**로컬에서만 동작한다.** 판단 기준은 `app.env` 이며, 로컬은 `APP_ENV` 가 없어 기본값 `local`,
도커는 compose 가 `docker` 를 주입한다. `/api/dev/missions/**`(이슈 #165)와 같은 규칙·같은 에러 코드다.
별도 on/off 프로퍼티는 두지 않는다 — **배포 환경에서 쓸 계획이 없는 도구**라 스위치를 달면
로컬에서 쓸 때마다 설정을 고쳐야 하고, 켜진 채 배포될 위험만 새로 생긴다.

- `name` 은 `group-challenge-status` · `group-challenge-evaluation` · `fixed-expense-payment-reminders`. 배치가 늘어나면 여기에 추가된다.
- `date` 는 `yyyy-MM-dd`. 생략하면 오늘이다. 미래 날짜를 넣으면 그날 시작하는 챌린지까지 당겨 처리한다.
  `group-challenge-evaluation` 은 기준일을 **종료 다음 날**로 넣어야 기간평가(PERIOD) 기소가 재현된다.
- `affected` 는 배치가 처리한 건수다 (`group-challenge-status` 는 전이된 그룹 수,
  `group-challenge-evaluation` 은 새로 만든 기소 수).

프론트에서는 `components/dev/DevBatchTriggerFab.vue` 가 이 API 를 부른다.
**그룹 챌린지 홈**과 **재판 전체보기** 두 화면 우하단에 「챌린지 시작 배치」 버튼으로 붙어 있다
(전체보기 쪽이 본거지다 — 시작 전/진행 중/종료됨 탭이 한 화면에 있어 전이 결과가 바로 보인다).

- **짧게 누르면** 오늘 기준으로 실행한다.
- **길게 누르면(0.6초)** 기준일을 직접 넣는다. 로컬 테스트 데이터는 시작일이 대부분 미래라
  (생성 검증이 과거 시작일을 막는다) 오늘 기준으로는 대상이 잡히지 않는 경우가 흔하다.
- 결과는 버튼 라벨에 잠깐 표시하고(`2건 전이됨` / `대상 없음`), 부모 화면이 목록을 다시 불러온다.

> ⚠ 버튼은 `import.meta.env.DEV` 로 가려 **프로덕션에서 렌더링되지 않지만, 청크 자체는 번들에 들어간다**
> (`DevDataSourceFab` 도 마찬가지다). 트리셰이킹으로 사라지지 않는다 — 조건 검사가 import 지점이 아니라
> 컴포넌트 안에 있기 때문이다. **차단의 근거는 프론트가 아니라 서버의 `app.env` 다.**

| 코드 | HTTP | 상황 |
|---|---|---|
| `DEV_API_DISABLED` | 400 | 로컬 환경이 아니다 (`app.env != local`) |
| `DEV_BATCH_NOT_FOUND` | 400 | 없는 배치 이름 |
| `MANUAL_REPORT_BATCH_DISABLED` | 400 | `REPORT_MONTHLY_MANUAL_BATCH_KEY`가 설정되지 않았다 |
| `MANUAL_REPORT_BATCH_FORBIDDEN` | 400 | 월간 리포트 수동 배치 운영 키가 틀렸다 |

## 그룹 챌린지 — 재판 진입로 (이슈 #169 · #432)

지방법원 홈과 그룹 상세 화면이 쓰는 조회 3종이다. **쓰기(변론 제출·투표)는 여기 없다** —
이슈 #170 · #171 범위다.

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/group-challenges/my-trials` | Bearer | `MyTrial[]` |
| GET | `/api/group-challenges/trials` | Bearer | `GroupIndictment[]` |
| GET | `/api/group-challenges/trial-records` | Bearer | `GroupClosedTrial[]` |
| GET | `/api/group-challenges/{groupId}/trial-records` | Bearer | `GroupClosedTrial[]` |
| GET | `/api/group-challenges/{groupId}/detail` | Bearer | `ChallengeGroupDetail` |

> `/my-trials` · `/trials` · `/trial-records` 는 `/{groupId}` 와 겹쳐 보이지만 Spring 이 리터럴
> 경로를 변수 경로보다 먼저 매칭한다. 순서를 바꿔도 같다.

**앞의 둘은 범위가 다르다.** `/my-trials` 는 「내가 지금 <b>행동할 수 있는</b> 것」 2가지(내 변론 ·
내 투표)이고, `/trials` 는 「내가 속한 그룹의 진행 중인 재판 <b>전부</b>」 6가지다.
`/my-trials` 를 지우지 않은 것은 전체 보기 바텀시트가 아직 그 얇은 모양을 쓰기 때문이다.

**`/trial-records` 둘은 그 셋의 반대다.** 위가 진행 중(`DEFENSE_WAIT`·`VOTING`)만 주는 반면
이쪽은 **확정된 것(`GUILTY`·`INNOCENT`)만** 준다 — 두 목록은 겹치지 않는다.
경로에 `{groupId}` 가 있으면 그 그룹만, 없으면 내가 속한 모든 그룹이다.

### 마감 시각은 저장값이 아니라 계산값이다

`tbl_indictment` 에 마감 컬럼이 없다(`schema.sql` 의 `[0803]` 주석). 응답의 모든 마감은
`created_at` 에서 파생한다.

```
변론 마감 = created_at + challenge.trial.defense-hours   (기본 6시간)
투표 마감 = created_at + defense-hours + vote-hours       (기본 6 + 24 = 30시간)
```

- 프로퍼티를 줄이면 **이미 생성된 기소의 마감도 함께 앞당겨진다.** 시연 때 코드를 고칠 필요가 없다.
- **마감이 지난 건은 `/my-trials` · `/trials` 에서 빠진다.** 목록에 남겨 두면 카운트다운이
  `00:00:00` 인 줄을 눌러 화면까지 들어간 뒤 제출에서야 튕긴다. 상태 전이 배치와 판단 주체가
  둘이 되지만, 배치는 `status` 를 옮기고 조회는 **보여줄지**를 정한다 — 배치가 아직 안 돈
  구간에서 옳은 답은 「보여주지 않는다」다.
  `{groupId}/detail` 의 `indictments[]` 는 그룹 상세라 <b>거르지 않는다</b>(그 화면은
  「지금 할 일」이 아니라 그룹의 재판 목록이다).
- **마감 판단 기준은 상태가 정한다** — `DEFENSE_WAIT` 은 변론 마감, `VOTING` 은 투표 마감이다.
  한쪽 기준만 쓰면 투표가 열린 재판이 열리자마자 사라지거나, 변론 마감이 지난 건이 남는다.
- 응답은 **ISO-8601 절대시각**(`2026-08-16T15:00:00`)이다. 남은 분 수로 주지 않는다 —
  화면을 열어 둔 채 몇 시간이 지나면 카운트다운이 응답 받은 시점 기준으로 굳는다.

### `MyTrial` — 오늘의 할 일

`{ indictmentId, type, challengeId, challengeName, defendantNickname, amount, voteCount, totalVoters, deadline }`

- 변론 대기와 투표 대기를 **한 배열로** 마감 임박순 정렬해 내려준다. 화면이 하나의 목록으로 보여주고
  필터 칩으로만 나누기 때문이다. 두 배열로 나눠 주면 프론트가 다시 합쳐 정렬해야 하는데
  그 기준이 서버와 어긋나면 카드와 시트의 순서가 달라진다.
- `type` 은 **소문자 고정** `accuse`(내가 변론) · `vote`(내가 투표). `GroupTodoItem.vue` 가
  소문자 리터럴로 비교한다.
- `type` 에 따라 비는 필드가 있다 — `accuse` 는 `amount` 만, `vote` 는
  `defendantNickname`·`voteCount`·`totalVoters` 만 채워진다.
- **「3/5 투표」 같은 표시 문구는 담지 않는다.** 문구를 서버가 만들면 디자인을 고칠 때마다
  war 를 다시 올려야 한다. 서버는 재료만 주고 `api/groupChallenge.js` 가 조립한다.

### `GroupIndictment` — 재판 현황 (이슈 #432)

`{ id, groupId, groupName, userId, nickname, profileImageUrl, status, settlementDate,
exceededAmount, mine, defended, myVote, voteCount, totalVoters, defenseDeadline, voteDeadline }`

`{groupId}/detail` 의 `indictments[]` 와 **같은 모양이다.** 지방법원 홈이 여러 그룹의 재판을
한 목록에 섞어 내리므로 `groupId` · `groupName` 이 붙는 것만 다르다(그룹 상세 응답에도 같이
붙지만 그쪽 화면은 이미 아는 값이라 쓰지 않는다).

재판 한 건에서 나올 수 있는 **내 입장 6가지**를 전부 준다.

| 피고 | `status` | 조건 | 내 입장 | 할 일 |
|---|---|---|---|---|
| 나 | `DEFENSE_WAIT` | `defended: false` | 변론 필요 | ✅ |
| 나 | `DEFENSE_WAIT` | `defended: true` | 변론 제출 | |
| 나 | `VOTING` | — | 심판받는 중 | |
| 남 | `DEFENSE_WAIT` | — | 변론 대기 | |
| 남 | `VOTING` | `myVote: null` | 투표 필요 | ✅ |
| 남 | `VOTING` | `myVote` 있음 | 투표 완료 | |

- **뱃지·문구·CTA 는 서버가 정하지 않는다.** 위 네 값으로 프론트(`utils/groupTrial.js`)가 만든다.
  판정을 서버로 올리면 상태가 하나 늘 때마다 war 를 다시 올려야 한다.
- 정렬은 **할 일이 있는 것 먼저, 그 안에서 마감 임박순**이다. 화면에 맡기지 않는 이유는
  아코디언을 펼칠 때마다 카드가 다시 튀기 때문이다.
- 카드 제목에 쓰는 **카테고리·한도는 여기 없다.** 화면이 이미 들고 있는 참여 그룹 목록
  (`GET /api/group-challenges`)을 `groupId` 로 조인해 채운다 — 같은 값을 재판 수만큼 되풀이해
  내려보내지 않기 위함이다.
- **사건번호는 없다.** `tbl_indictment` 에 대응하는 컬럼이 없다(디자인 시안에는 있다).

### `GroupClosedTrial` — 확정된 재판 기록

`{ id, groupId, groupName, userId, nickname, profileImageUrl, status, verdictMethod,
aiVerdictReason, settlementDate, exceededAmount, mine, defended, myVote,
guiltyCount, innocentCount, totalVoters, createdAt, confirmedAt }`

`GroupIndictment` 과 **일부러 다른 자료형이다.** 저쪽의 축은 마감(`defenseDeadline` ·
`voteDeadline`)이고 이쪽은 확정 시각과 개표다. 한 모양으로 합치면 진행 중 화면과
기록 화면 중 한쪽에서 절반이 항상 NULL 이 된다.

- **개표를 가리지 않는다.** 투표 중에 `guiltyCount`·`innocentCount`·`aiVerdictReason` 을
  감춘 건 편승 투표를 막으려던 것이고(#171), 그 근거는 투표가 열려 있는 동안에만 성립한다.
  확정된 뒤에는 실수(`0`)까지 그대로 내려간다.
- **`verdictMethod` 는 항상 채워진다.** 프론트가 이 값으로 화면을 가른다 —
  `AI_JUDGMENT` 면 동점 안내(`groupVoteTie`), 나머지 셋은 판결문(`verdictResult`)이다.
  빼면 AI 판결 건이 엉뚱한 화면을 연다.
- `aiVerdictReason` 은 `AI_JUDGMENT` 일 때만 값이 있다(`ck_ind_ai_reason` 이 그걸 강제한다).
  나머지 방식의 사유 문구는 서버에 없고 프론트가 방식에서 만든다.
- 정렬은 **최근 확정순**(`updated_at DESC, id DESC`)이다. 진행 중 목록의 「마감 임박순」과
  반대인 것이 맞다 — 기록은 히스토리라 최근 것이 먼저다.
- **`confirmedAt` 은 `tbl_indictment.updated_at` 이다.** 확정 시각 전용 컬럼이 없다.
  상태 전이 UPDATE 4개가 모두 WHERE 에서 `GUILTY`·`INNOCENT` 를 제외해 확정 뒤로는
  갱신되지 않는다. ⚠ 확정된 기소를 UPDATE 하는 구문이 생기면 이 값이 밀린다 —
  그때는 `confirmed_at` 컬럼을 따로 만들어야 한다.
- **그룹원이 아니면 빈 배열이다.** 예외를 던지지 않는다 —
  두 쿼리 모두 `tbl_group_member` 조인이 권한 검사를 겸한다.
- `settlementDate` 는 `GroupIndictment` 과 같이 **위반한 날짜**다(기소 생성일이 아니다).

### `ChallengeGroupDetail` — 상세 화면 한 벌

목록과 같은 `ChallengeGroup` 에 상세 전용 필드를 얹은 것이다. **그룹 필드는 한 겹 없이 같은
높이로 펼쳐진다**(`@JsonUnwrapped`) — 프론트가 응답을 `{...dto}` 로 펼쳐 쓰기 때문에
`challenge.groupName` 처럼 접히면 화면 전체가 빈칸이 된다.

추가 필드: `myDailyAmount`, `myUsagePercent`, `myRemainingAmount`, `indictments[]`, `dailyMembers[]`.

- `dailyMembers` 에는 **내가 빠져 있다.** 내 몫은 위의 `my*` 세 개다.
- `myRemainingAmount` 는 **초과 시 음수**다. 0 으로 깎으면 얼마나 넘겼는지 화면이 알 수 없다.
- `dailyAmount` 는 일일평가면 오늘 하루치, 기간평가면 기간 합계다. 이름은 하나로 뒀다 —
  둘로 나누면 화면이 매번 둘 중 하나를 골라야 한다.
- `usagePercent` 는 **100 을 넘을 수 있다**(화면이 막대만 100% 에서 자른다).
  한도 0원인 무지출 챌린지는 한 푼이라도 쓰면 100 이다.

`indictments[]` = 위 **`GroupIndictment` 과 같은 모양**이다(`groupId`·`groupName` 포함 —
이 화면은 이미 아는 값이라 쓰지 않는다).

- 진행 중(`DEFENSE_WAIT`·`VOTING`)인 기소만 **오래된 순**(= 마감이 급한 순)으로 준다.
  확정된 기소는 카드가 아니라 전적으로 간다.
- **카드 종류는 서버가 정하지 않는다.** 프론트가 `mine`·`status`·`defended`·`myVote` 를 조합해
  만든다. 6가지 표는 위 `GroupIndictment` 절에 있다.
- **마감 두 개를 둘 다 채운다.** 상태별로 한 쪽만 채우면 상태 전이가 도는 순간 화면에서
  마감이 잠깐 사라진다.
- `settlementDate` 는 기소 생성일이 아니라 **위반한 날짜**다. 심야 거래를 다음 날 배치가 잡아
  하루 어긋난다.
- `mine`·`defended`·`exceeded` 는 Lombok 이 `isMine()` 으로 만들지만 **JSON 키는 접두어 없는
  `mine`·`defended`·`exceeded`** 다.

### 아직 NULL 인 필드

`settleTime` · `memoAuthor` · `memoDate` 는 근거 컬럼이 없다.
채팅 미리보기는 이슈 #174, `savingsAmount`(종료 화면 절약액)는 산식이 정해지지 않았다.
화면이 이미 NULL 을 견디도록 만들어져 있다.

`trialStats` · `finalMembers` 는 #173 이 채웠다 — **`status = CLOSED` 일 때만** 값이 있고
그 외 상태에서는 둘 다 null 이다(아래 「명예 법정」 절 참고).

목록의 `finalOutcome` · `finalRank` · `finalChargeAmount` 는 **#172 가 채운다.** 그룹이
`CLOSED` 로 확정되는 순간 값이 들어간다 — 진행 중에는 계속 `null` 이다(목숨이 0이어도
「탈락 위기」까지만 표시한다. 최종 판정은 확정 배치 한 곳에서만 일어난다).

| 코드 | HTTP | 상황 |
|---|---|---|
| `GROUP_NOT_FOUND` | 400 | 없는 그룹 |
| `GROUP_NOT_MEMBER` | 400 | 참여자가 아닌데 상세 조회 |

## 그룹 챌린지 — 소비 재판 변론 · 혐의 인정 (이슈 #170) · 투표 (이슈 #171) · 개표 (이슈 #172)

기소 안내 → 실제 부담금 입력 → 변론 작성 → 제출, 그 반대인 혐의 인정, 배심원 투표, 그리고 개표까지.

**개표에는 엔드포인트가 없다.** 확정은 1분마다 도는 배치(`GroupVerdictScheduler`)가 하고,
화면은 같은 `GroupTrialDetail` 을 다시 읽어 `status` 가 `GUILTY`·`INNOCENT` 로 바뀐 것을 본다.
확정 시점에는 그룹 채팅 시스템 메시지와 `GROUP_JUDGMENT` 알림이 함께 나간다.

개표 규칙은 다음과 같다(요구사항정의서 6.5).

| 표 | 결과 | `verdictMethod` |
|---|---|---|
| 유죄 > 무죄 | `GUILTY` | `VOTE` |
| 무죄 > 유죄 | `INNOCENT` | `VOTE` |
| 아무도 안 던짐 | `INNOCENT` (무죄 추정) | `NO_VOTE` |
| 동률 | 판사 탕이(LLM)가 판단 | `AI_JUDGMENT` |
| 혐의 인정 | `GUILTY` | `CONFESSION` |

- 확정 시점은 **「투표 마감(기소 + 30시간)이 지났거나, 던질 사람이 전부 던졌거나」** 다.
  전원 투표가 끝나면 마감을 기다리지 않는다.
- 유죄면 `lives_count` 가 1 줄고(0 아래로는 안 내려간다), 무죄면 변론에 적은 `deductionAmount`
  만큼 그 구간 소비액이 깎인다.
- **판사 탕이 호출이 실패하면 무죄로 확정한다.** 0표 무죄 추정과 같은 기준이다. 이때
  `aiVerdictReason` 에 실패 안내 문구가 들어가고 `verdictMethod` 는 `AI_JUDGMENT` 다
  (사유를 담으려면 그래야 한다 — `ck_ind_ai_reason`). 재시도는 하지 않는다.

| 메서드 | 경로 | 인증 | 권한 | 응답 |
|---|---|---|---|---|
| GET | `/api/group-challenges/trials/{indictmentId}` | Bearer | 그룹 참여자 | `GroupTrialDetail` |
| GET | `/api/group-challenges/trials/{indictmentId}/transactions` | Bearer | **피고 본인만** | `TrialTransactions` |
| POST | `/api/group-challenges/trials/{indictmentId}/defense` | Bearer | 피고 본인만 | `null` |
| POST | `/api/group-challenges/trials/{indictmentId}/confession` | Bearer | 피고 본인만 | `null` |
| POST | `/api/group-challenges/trials/{indictmentId}/votes` | Bearer | **피고를 뺀 그룹 참여자** | `null` |

### `GroupTrialDetail` — 재판 화면 한 벌

**#171 과 공유하는 계약이다.** 기소 안내 · 변론 작성 · 투표 · 판결 상세가 이 응답 하나를 쓴다.
화면마다 엔드포인트를 두면 같은 금액을 네 곳에서 다르게 계산하게 된다.

```
{ indictmentId, groupId, groupName, status, result, verdictMethod, message,
  aiVerdictReason,                                               // 개표 전 null
  accused: { userId, nickname, profileImageUrl, mine },
  evalType, challengeDate, startDate, endDate,
  categoryId, categoryName,
  limitAmount, currentAmount, exceededAmount,
  createdAt, defenseDeadline, voteDeadline,
  defense: { content, actualBurdenAmount, deductionAmount, imageUrls[], createdAt } | null,
  myVerdict, voteCount, totalVoters,
  guiltyCount, innocentCount,                                    // 개표 전 null
  comments: [ { comment, createdAt } ] | null }                  // 개표 전 null
```

- 마감 두 개는 **#169 와 같은 계산값**이다 (`created_at + defense-hours` / `+ vote-hours`). 컬럼이 없다.
- `defense` 는 **아직 변론이 없으면 NULL 이다.** 빈 객체로 채우지 않는다 — 화면이 「변론 대기」와
  「내용 없는 변론」을 구분할 수 없게 된다.
- `myVerdict` 는 안 던졌으면 NULL, **피고 본인은 항상 NULL** 이다. `totalVoters` = 참여자 − 피고 1명.
  #170 이 이 세 필드를 함께 채운다 — 서브쿼리가 이미 있어 비용이 0 이고 #171 은 쓰는 쪽만 만들면 된다.
- **`guiltyCount`·`innocentCount`·`comments`·`aiVerdictReason` 은 개표 후에만 값이 있다.**
  `status` 가 `DEFENSE_WAIT`·`VOTING` 이면 넷 다 **`null`** 이다 — **`0` 이 아니다.**
  「아직 모른다」와 「0표」를 구분해야 해서 서버 타입도 `int` 가 아니라 `Integer` 다.
  투표 중에 비율이 보이면 이기는 쪽에 표가 몰리고, 코멘트 문장에는 어느 쪽에 던졌는지가 드러나
  숫자를 가린 의미가 없어진다. 프론트에서 `?? 0` 으로 뭉개면 정책이 무너진다.
  `aiVerdictReason` 을 같은 줄에 세운 이유는 **AI 판결이 동률일 때만 나오기 때문**이다 —
  사유가 보이는 것만으로 「지금 2:2」가 드러난다.
  마스킹 판단은 `GroupTrialService#isCounted` **한 곳**에만 있다.
- `verdictMethod` 는 확정 전 `null`, 확정 후 `VOTE`·`NO_VOTE`·`AI_JUDGMENT`·`CONFESSION` 중
  하나다. `aiVerdictReason` 은 **`AI_JUDGMENT` 일 때만** 값이 있다(최대 300자). 화면은
  이 문장을 요약하지 않고 그대로 띄운다.
- **누가 투표했는지는 어떤 필드로도 내려가지 않는다.** 배심원 명단은 비공개이고 `comments[]` 에도
  `userId`·닉네임이 없다. 진행 상황은 `voteCount / totalVoters`(몇 명이 던졌나)까지만 공개한다.
- `categoryName` 이 NULL 이면 총소비 챌린지다.
- `currentAmount` 는 **무죄 감액이 반영된 결산 구간 소비액**이고, 동시에 **실제 부담금 입력의 상한**이다.
- `accused.mine` 은 Lombok 이 `isMine()` 으로 만들지만 **JSON 키는 `mine`** 이다.
- 「D-2시간」·「일일결산 8/18」 같은 표시 문구는 담지 않는다. 서버는 재료만 주고
  `api/groupChallenge.js` 의 `toTrialDetailViewModel` 이 조립한다.
  사건번호(`2026-재판-0805`)도 컬럼이 아니라 **결산일에서 만드는 라벨**이다.

### `TrialTransactions` — 결산 구간 거래 목록

```
{ evalType, limitAmount, currentAmount,
  days: [ { date, dailyAmount,
            transactions: [ { transactionId, time, merchantName, amount,
                              categoryName, paymentMethod, isRefund } ] } ] }
```

**상세와 분리한 이유는 권한이다.** 상세는 투표자도 보지만 거래 목록은 피고 본인만 본다.
한 응답에 섞으면 투표자에게 남의 거래내역이 흘러간다.

- **기소를 발화시킨 거래 1건이 아니라 구간 전체다.** 한도 초과는 누적 판정이라 발화 거래가 문제의
  거래라는 보장이 없다. 친구들 몫까지 대납한 큰 거래는 그 시점에 한도 미달이라 기소를 만들지 않고,
  뒤따르는 평범한 소비가 기소를 만든다 — 발화 거래만 보이면 정작 대납한 금액을 신고할 방법이 없다.
- DAILY 는 `days` 가 **하나**, PERIOD 는 거래가 있는 날만큼 여러 개다(날짜 오름차순).
  거래가 없는 날은 내려보내지 않는다.
- **`transactions[].amount` 의 합 = `dailyAmount`, 그 합 = `currentAmount`** 여야 한다
  (무죄 감액이 0 인 동안. 감액이 붙으면 `currentAmount` 만 줄어든다 — 아래 참고). 화면이
  「실제 부담금 합계 vs 한도」를 이 값으로 판정하므로 어긋나면 판정이 무너진다. 그래서 조회 조건을
  집계 배치와 같은 `consumptionFilter` 조각으로 공유한다(복사하지 않는다).
  `dailyAmount` 도 `tbl_group_challenge_daily_result` 에서 다시 읽지 않고 **목록을 더해 만든다** —
  두 값이 어긋나면 화면에 「합계와 항목이 다른」 하루가 나온다.
- 환불은 **음수 `amount`** 다(`is_refund=1 → -COALESCE(refunded_amount, amount)`). 0 으로 깎으면
  환불받은 소비로 기소된 것처럼 보인다.
- `dailyAmount` 에는 **무죄 감액이 빠져 있지 않다.** 감액은 구간 총액(`currentAmount`)에만 적용된다 —
  어느 날에서 깎을지가 정해지지 않은 값이라 날짜별로 배분할 수 없다. 감액을 기록하는 주체는 #172 라
  이 시점에는 항상 0 이고 두 값이 같다.
- `time` 은 원천에 시각이 없으면 NULL, `categoryName` 은 LLM 분류 전이면 NULL 이다.
- `isRefund` 는 `@JsonProperty("isRefund")` 로 **이름을 고정했다.** 고정하지 않으면 Jackson 이
  `refund` 로 내려보내 프론트에서 항상 `undefined` 다.
- 피고가 아닌 사람이 부르면 `NOT_INDICTMENT_OWNER` 가 아니라 **`TRIAL_NOT_FOUND`** 다.
  「그 재판의 피고가 누구인지」를 노출하지 않기 위함이다.

### 변론 제출 — `multipart/form-data`

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `content` | text | O | 변론 내용. **최대 150자** (`DefenseService.CONTENT_MAX_LENGTH`) |
| `actualBurdenAmount` | number | O | 건별 실제 부담금의 **합계** |
| `images` | file[] | X | 증빙. **최대 3장**, 장당 5MB |

- **건별 입력은 서버로 올라오지 않는다.** 거래-변론 연결 테이블을 만들지 않기로 했고(마이그레이션 0건),
  건별 값은 순수 UI 다. 합계만 전송된다.
- **`deductionAmount` 는 클라이언트를 믿지 않고 서버가 계산한다** — `currentAmount − actualBurdenAmount`.
  무죄 확정 시 소비액에서 빠질 금액이라 클라이언트가 정하게 두면 감액을 임의로 부풀릴 수 있다.
- 이미지는 `ImageProcessor.toBoundedJpeg` 로 **긴 변 1280px 상한**까지만 줄인다(비율 유지).
  프로필의 `toSquareJpeg`(256×256 센터 크롭)를 쓰면 영수증이 잘린다.
- **저장되는 값은 URL 이 아니라 키**다 — `defense/{indictmentId}/{uuid}.jpg`. 읽을 때
  `imageStorage.urlOf(key)` 로 변환해 `defense.imageUrls` 로 내려준다. 로컬 → S3 이전 시
  기존 행을 변환하지 않아도 되게 하기 위함이다.
- 성공하면 `tbl_indictment.status` 가 `DEFENSE_WAIT` → **`VOTING`** 으로 넘어가고
  `DefenseRegistered` 이벤트가 그룹 채팅에 시스템 메시지를 남긴다.
- 프론트에서 `axios` 로 보낼 때 **`headers: { 'Content-Type': undefined }`** 로 인스턴스 기본값
  (`application/json`)을 지워야 한다. 안 지우면 axios 가 FormData 를 `JSON.stringify` 한다.

### 혐의 인정 — 본문 없음

`status='GUILTY'`, `result=1`, `verdict_method='CONFESSION'` 으로 조건부 UPDATE 하고
`VerdictConfirmed` 이벤트를 발행한다.

- **`tbl_vote` · `tbl_defense` 행을 만들지 않는다.** 투표 없이 끝난 재판이다.
- **목숨 차감과 무죄 감액(`verdict_deduction_amount`) 기록은 하지 않는다 — 이슈 #172 담당이다.**
  그래서 완료 화면도 「목숨 5 → 4」 를 그리지 않는다. 그리면 아직 일어나지 않은 차감을 단정하게 되고,
  #172 가 들어온 뒤엔 값이 두 벌로 갈린다.
- 상태 UPDATE 는 `WHERE ... AND status='DEFENSE_WAIT'` 라 **멱등**이다. 같은 요청을 두 번 보내면
  두 번째는 `DEFENSE_NOT_ALLOWED` 다.

### 투표 — `POST .../votes` (이슈 #171)

```json
{ "verdict": "GUILTY", "comment": "그건 좀 아니지" }
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `verdict` | string | O | `GUILTY` · `INNOCENT` 둘 중 하나 |
| `comment` | string | X | 익명 한줄. **최대 40자** (`VoteService.COMMENT_MAX_LENGTH`) |

- 응답 본문이 없다. 던진 뒤 화면은 **재판 상세를 다시 읽어** 그린다 — 여기서 표수를 돌려주면
  개표 전 비공개 정책을 이 한 곳에서 뚫는다.
- `comment` 가 공백만이면 **`NULL` 로 저장**한다. 빈 문자열로 넣으면 개표 후 코멘트 목록에
  이유 없는 빈 줄이 생긴다.
- **수정·취소가 없다.** 한 재판에 한 표이고 두 번째 요청은 `VOTE_ALREADY_EXISTS` 다.
  바꿀 수 있으면 마감 직전 눈치싸움이 생긴다. PK `(group_id, user_id, indictment_id)` 가
  마지막 방어선이다.
- **알림·채팅 이벤트를 발행하지 않는다.** 투표 한 건마다 알리면 누가 언제 던졌는지가 채팅에 남아
  익명이 깨진다. 개표 알림은 #172 의 `VerdictConfirmed` 몫이다.
- **개표하지 않는다.** 마지막 한 표가 들어와도 `status` 는 `VOTING` 그대로다.
  표가 다 모였다고 즉시 확정하면 마감 전에 결과가 나와 남은 사람이 투표할 이유가 없어진다.
- 마감 판정은 컬럼이 아니라 `created_at + defense-hours + vote-hours` 계산값이다.
  개표 배치가 없으므로 **마감이 지나도 `status` 는 `VOTING` 이다** — 상태만 믿으면 마감 후 표가 들어온다.
- **마이그레이션 0건.** `tbl_vote` 는 `db/schema.sql` 에 이미 있다.

### 기간결산 소비액 공식이 바뀌었다 — #168 · #169 응답에도 영향

기간평가(`PERIOD`) 소비액을 `SUM(daily_amount)` 에서 아래로 통일했다. #170 이 변론 화면용 소비액을
계산하며 **복사본을 하나 더 만드는** 시점이라 함께 정리했다(변론 화면은 결국 재판 상세의 같은 조각을
재사용한다).

```sql
GREATEST(SUM(rp.daily_amount - rp.verdict_deduction_amount), 0)
```

- 그전까지 기간결산은 `verdict_deduction_amount` 를 전혀 보지 않았다 → **무죄를 받아도 그룹 상세
  소비액·초과액·재기소 판정이 하나도 바뀌지 않았다.**
- `SUM(effective_amount)` 로 바꾸면 안 된다. 그 생성컬럼은 **행마다** `GREATEST(…,0)` 를 거는데
  환불은 음수 `daily_amount` 행으로 상쇄되므로 그 음수가 0 으로 깎여 **환불받은 소비로 기소된다.**
  합산한 뒤 한 번만 클램프해야 한다.
- 값이 바뀌는 응답: `ChallengeGroupDetail` 의 `dailyAmount`·`myDailyAmount`(기간평가 그룹),
  `indictments[].exceededAmount`, 그리고 이 절의 `currentAmount`. **세 곳이 항상 같은 값이어야 한다.**
- 공식 원본은 `GroupChallengeResultMapper.xml` 상단 주석이다. `<sql>` 조각으로 못 묶었다 —
  상관 서브쿼리가 참조하는 바깥 별칭이 호출부마다 달라 `${}` 가 필요해지는데 금지 대상이다.
  대신 내부 별칭을 `rp` 로 통일하고 `ChallengeMapperXmlTest` 가 세 statement
  (`findOverLimitPeriod`·`findMemberConsumption`·`findTrialDetail`)의 SQL 을 문자열로 대조한다 —
  한 곳만 고치면 테스트가 깨진다.
- `findDeductionOverflow` 경고는 **DAILY 이고 감액이 0 보다 큰 행만** 검사한다.
  PERIOD 는 기간 전체 감액을 기소가 붙은 `end_date` 행 한 줄에 적으므로
  `verdict_deduction_amount > daily_amount` 가 정상이고, 환불이 소비보다 많은 날은 `daily_amount` 가
  음수라 **감액한 적이 없는 행까지** `0 > -10000` 으로 걸린다. 둘 다 수동 검증에서 실제로 오탐이 났다.

> 감액을 **기록하는** 주체는 #172 다. #170 은 그 값을 *읽는* 쪽만 준비해 뒀고, 지금은 항상 0 이라
> 실제 응답 값은 달라지지 않는다.

### 변론 마감은 배치가 넘긴다

`GroupTrialDeadlineScheduler` 가 `challenge.trial.deadline.fixed-delay-ms`(기본 5분)마다
마감이 지난 `DEFENSE_WAIT` 를 `VOTING` 으로 넘긴다. **일일 cron 에 붙이지 않았다** — 변론 창이
6시간인데 하루 한 번 돌면 재판이 최대 24시간 멈춘다.
**자동 유죄 처리는 하지 않는다.** 변론이 없어도 투표는 열린다.

| 코드 | HTTP | 상황 |
|---|---|---|
| `TRIAL_NOT_FOUND` | 400 | 없는 기소 · 그룹 참여자가 아님 · (거래 목록에서) 피고가 아님 |
| `NOT_INDICTMENT_OWNER` | 400 | 남의 재판에 변론·혐의 인정 |
| `DEFENSE_NOT_ALLOWED` | 400 | `status != DEFENSE_WAIT` (이미 투표 중이거나 확정됨) |
| `DEFENSE_CLOSED` | 400 | 변론 마감 시각이 지남 (배치가 아직 안 돈 사이) |
| `DEFENSE_ALREADY_EXISTS` | 400 | 이미 변론을 제출함 (`uk_def_indictment`) |
| `DEFENSE_CONTENT_REQUIRED` | 400 | 내용이 공백 |
| `DEFENSE_CONTENT_TOO_LONG` | 400 | 150자 초과 |
| `INVALID_BURDEN_AMOUNT` | 400 | 음수이거나 `currentAmount` 초과 |
| `TOO_MANY_IMAGES` | 400 | 4장 이상 |
| `IMAGE_TOO_LARGE` | 400 | 장당 5MB 초과 |
| `VOTE_NOT_ALLOWED` | 400 | `status != VOTING` 이거나 투표 마감 시각이 지남 |
| `CANNOT_VOTE_OWN_TRIAL` | 400 | 피고 본인이 자기 재판에 투표 |
| `VOTE_ALREADY_EXISTS` | 400 | 이미 투표함 (수정 경로 없음) |
| `INVALID_VERDICT` | 400 | `GUILTY`·`INNOCENT` 가 아님 |
| `COMMENT_TOO_LONG` | 400 | 코멘트 40자 초과 |

> ⚠ `IMAGE_TOO_LARGE` 는 **업무 검증보다 먼저** 난다. 컨트롤러가 파일을 바이트로 읽기 전에
> `requireWithinLimit` 을 걸기 때문에, 없는 기소에 5MB 넘는 이미지를 보내면 `TRIAL_NOT_FOUND`
> 대신 이 코드가 온다. 메모리에 거대한 파일을 올린 뒤 "없는 재판입니다" 를 답하는 것보다 낫다.

## 그룹 챌린지 — 명예 법정 생존자 랭킹 · 종료 화면 (이슈 #173)

| 메서드 | 경로 | 인증 | 권한 | 응답 |
|---|---|---|---|---|
| GET | `/api/group-challenges/{groupId}/ranking` | Bearer | 그룹 참여자 | `GroupRanking` |

### `GroupRanking` — 명예 법정 화면 한 벌

`{ evalType, status, limitAmount, maxLives, memo, lastSettlementDate,
members[{ rank, userId, nickname, profileImageUrl, mine, livesCount, totalConsumption,
finalOutcome, finalChargeAmount }] }`

- 정렬은 요구사항정의서 6.6 — 일일평가는 남은 목숨 내림차순 → 누적 소비액 오름차순,
  기간평가는 누적 소비액 오름차순. **동률은 공동 순위**다(1, 1, 3).
- `status = CLOSED` 면 `rank` · `finalOutcome` · `finalChargeAmount` 는 확정 배치(#172)가 남긴
  **저장값**이고 다시 계산하지 않는다 — CLOSED 쿼리에는 윈도우 함수 자체가 없다.
  그 외 상태(`JUDGING` 포함 — 아직 `final_*` 미기록)에서는 조회 시점의 스냅샷이다.
- `finalOutcome`(`SURVIVED`/`ELIMINATED`) · `finalChargeAmount` 는 **CLOSED 전에는 null** 이다.
  진행 중의 목숨 0 은 「탈락 위기」이지 탈락이 아니다 — 탈락 표시 여부는 프론트가
  `status`+`finalOutcome` 으로 정한다.
- `totalConsumption` 은 표시 전용 재계산값이다(무죄 감액 반영). 종료 후 환불이 생기면
  `finalChargeAmount` 와 달라질 수 있는 게 정상이다.
- `lastSettlementDate` 는 마지막으로 결산이 끝난 날짜다 — 진행 중에는 어제까지 중 마지막 결산일
  (오늘 행은 5분 재집계 중이라 제외), 종료 후에는 `end_date`. 결산 행이 아직 없으면 null 이고
  화면이 날짜 부분을 생략한다.
- 아바타 색·이니셜은 내려주지 않는다 — 프론트 `UserAvatar` 가 닉네임에서 만든다(팀 관례).
- `trialStats` · `groupName` 은 담지 않는다 — 명예 법정 화면이 쓰지 않는다.

### 종료 그룹 상세에 붙는 필드

`/detail` 응답(`ChallengeGroupDetail`)에 **`status = CLOSED` 일 때만** 채워진다.
그 외 상태에서는 둘 다 null 이다.

- `finalMembers[]` = `{ userId, nickname, profileImageUrl, finalRank, finalOutcome, livesCount }` —
  확정 배치가 남긴 `finalRank` 오름차순 **저장값**, 재계산 없음.
- `trialStats` = `{ totalTrials, guiltyCount, innocentCount }` — 확정(`GUILTY` · `INNOCENT`)
  재판만 센다. 진행 중 기소는 포함하지 않는다.
- `savingsAmount`(종료 화면 절약액)는 산식이 정해지지 않아 **계속 null** 이다.

| 코드 | HTTP | 상황 |
|---|---|---|
| `GROUP_NOT_FOUND` | 400 | 없는 그룹 |
| `GROUP_NOT_MEMBER` | 400 | 참여자가 아닌데 랭킹 조회 |

## 그룹 채팅 (이슈 #174)

그룹 챌린지방(지방법원)마다 딸린 실시간 채팅이다. 조회 3종은 REST 지만 **발송은 STOMP 로만** 한다.
이 절엔 발송 엔드포인트가 없다.

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/groups/{groupId}/chat/room` | Bearer | `{ groupId, groupName, status, memberCount, unreadCount, dayIndex, daysLeft }` |
| GET | `/api/groups/{groupId}/chat/messages?before=&after=&limit=50` | Bearer | `{ messages:[{messageId,type,senderId,senderNickname,content,sentAt,systemType,deepLink,caseNo,verdict}], hasMore }` |
| POST | `/api/groups/{groupId}/chat/read` | Bearer | 없음 (호출한 사용자의 안 읽은 수를 0으로 초기화) |

- `before`·`after` 는 `messageId` 기준 페이징이다. **둘을 동시에 주면 `INVALID_REQUEST`.** 둘 다 없으면
  최근 `limit` 건을 준다.
  - `before` - 그 메시지보다 앞 구간 (위로 스크롤)
  - `after` - 그 메시지보다 뒤 구간 (재연결 후 놓친 구간 보충)
- `limit` 은 **1~100, 기본값 50** 이다. 범위를 벗어나면 `INVALID_REQUEST` 다(`ChatQueryService`).
- `hasMore` 는 "위로 더 있는가" 다. 반환된 메시지 중 가장 오래된 `messageId` 가 1보다 크면 `true`.
- `sentAt` 은 ISO-8601 문자열(`2026-08-16T12:34:56`)이다. **REST 와 STOMP 가 같은 형식**이다
  (`WebSocketConfig#jsonConverter` 가 브로커 컨버터에도 REST 와 같은 ObjectMapper 를 꽂는다).
- `type` 은 `TEXT`(참여자가 보낸 메시지) 또는 `SYSTEM`(재판 진행 봇 메시지)이다. `SYSTEM` 이면
  `senderId`·`senderNickname` 이 `null` 이고, 대신 아래 세 값이 채워진다.
  - `systemType` - `VIOLATION_DETECTED` · `TRIAL_OPENED` · `DEFENSE_REGISTERED` · `VERDICT_CONFIRMED`.
    **화면이 카드 모양을 고르는 기준이다.** 문구를 파싱해 종류를 알아내지 말 것 — 문구가 바뀌면 깨진다.
  - `deepLink` - "재판 보러가기" 가 여는 라우터 경로(`/challenge/group/{groupId}/trial/{indictmentId}`)
  - `caseNo` - 표시용 사건번호(`2026-재판-0729`). 서버도 이 값을 다시 파싱하지 않는다
  - `verdict` - **`systemType` 이 `VERDICT_CONFIRMED` 인 메시지에만** 있다. 나머지는 `null`.
    `{ outcome, guiltyVotes, innocentVotes, livesLost }` 이며 화면이 도장과
    「투표 4:2 · 목숨 1 차감」을 그리는 값이다.
    - `outcome` - `GUILTY` · `INNOCENT`. **도장은 이 값으로 고른다.** 문구에서 "유죄" 를 찾지 말 것
    - `guiltyVotes`·`innocentVotes` - 확정 시점의 표. **투표 절차가 없던 판결(혐의 인정)은 `null`** 이다.
      `0:0` 은 「아무도 던지지 않아 무죄 추정으로 끝났다」라서 `null` 과 뜻이 다르다 — 뭉개지 말 것
    - `livesLost` - 이 판결로 깎인 목숨. 무죄면 `0`, **유죄여도 남은 목숨이 없었으면 `0`** 이다
  - **네 값은 나중에 추가돼 그 전에 저장된 메시지에는 없다(`null`).** 화면은 그때도 그려져야 한다
    (프론트는 문구만 있는 pill 로 떨어뜨리고, 판결 카드는 중립 도장을 찍는다).
  - `content` 는 **본문만** 담는다. "판결이 확정됐어요" 같은 제목은 넣지 않는다 — 채팅 카드는
    `systemType` 이, 알림은 `NotificationType` 이 각자 제목을 갖고 있어 같은 문장이 두 번 나온다.
- `dayIndex` 는 시작일을 1일차로 세는 진행 일차(시작 전이면 `0`), `daysLeft` 는 종료일까지 남은 날
  (종료일 당일 `0`, 지났으면 음수)이다. **서버(Asia/Seoul)가 계산한다** — 기기 시계·시간대에 따라
  값이 달라지면 안 되는 값이라 프론트에서 세지 않는다.
- **`CLOSED`(재판 절차가 끝난) 챌린지는 조회 자체가 막힌다.** `JUDGING`(재판 중)은 대화가 가장
  활발한 구간이라 허용한다.

### 실시간 발송·수신 (STOMP)

발송·수신은 REST 가 아니라 STOMP 다.

- 접속 엔드포인트: `/ws/chat` (SockJS 폴백 없음. 네이티브 WebSocket 전용)
- 구독: `/sub/chat/{groupId}` · 발행: `/pub/chat/{groupId}`
- **이 두 목적지 패턴 말고는 서버가 전부 거부하고 연결을 끊는다**(`StompAuthChannelInterceptor`,
  deny-by-default). `/sub/chat/**` 같은 와일드카드로 우회하려는 시도도 막는다.
- 인증은 CONNECT(또는 STOMP) 프레임의 네이티브 헤더 `Authorization: Bearer {accessToken}` 이다.
  브라우저 WebSocket API 는 핸드셰이크에 커스텀 헤더를 실을 수 없어 이 방식을 쓴다.
- 액세스 토큰은 15분 만료다. 세션 중 토큰을 교체하는 경로는 없다. 만료되면 프론트가 재연결하며
  새 토큰으로 다시 붙는다.
- 발행 본문은 `{ content }` 하나다. 비어 있거나 500자를 넘으면 `INVALID_REQUEST` 다.
- 구독·발행 시점에도 REST 와 같은 `ChatRoomAccessService.verifyCanEnter` 를 거치므로
  `NOT_FOUND`·`CHAT_NOT_MEMBER`·`CHAT_ROOM_CLOSED` 가 그대로 발생할 수 있다. STOMP 에서는
  응답 코드가 아니라 **연결 종료**로 나타난다.
- 수신 메시지 모양은 REST 조회의 `messages[]` 항목과 **완전히 같다**(`ChatMessageDto` 하나를 공유).
- 프론트는 접속 URL 을 **`VITE_WS_BASE_URL` 에서 먼저 유도하고, 없으면 `VITE_API_BASE_URL` 로
  폴백**한다(`api/chatSocketUrl.js`). 둘 다 없으면 현재 호스트로 폴백한다.
- **로컬 개발은 그대로 붙는다.** `vite.config.js` 의 `/ws` 프록시(`ws: true`)가 업그레이드 요청을
  :8080 으로 넘긴다. 로컬에는 두 환경변수 모두 설정할 필요가 없다.
- **프로덕션 소켓은 동작한다**(이슈 #268). EC2 앞단 nginx 가 443 에서 TLS 를 끊고 8080 으로 넘긴다.
  - 요청이 두 길로 갈린다. **REST 는 Vercel rewrite 를 거쳐 EC2:8080**, **소켓은 EC2:443 직행**이다.
    `vercel.json` 의 rewrite 가 WebSocket 업그레이드를 프록시하지 못해 소켓만 빼냈다.
  - Vercel 환경변수는 `VITE_WS_BASE_URL=https://kb-tangtang.duckdns.org` 하나다
    (**Production · Preview 두 스코프 모두**). `VITE_API_BASE_URL` 은 건드리지 않는다.
  - ⚠ 값에 `wss://` 를 넣으면 절대 URL 로 인식되지 않아 **조용히 Vercel 호스트로 폴백**한다.
    반드시 `https://` 로 넣고 끝에 `/api` 를 붙이지 않는다.
  - 되돌리려면 `VITE_WS_BASE_URL` 을 지우고 재배포하면 된다. 코드가 폴백 경로를 유지한다.
- **설정 원본·확인 명령은 `docs/DEPLOY_WEBSOCKET.md` 에 있다**
  (nginx 블록 · `curl --http1.1` 검증 · REST 까지 옮기면 생기는 함정 2건).

### 시스템 메시지 (재판 이벤트 수신부, 이슈 #169~#172 인계)

재판 진행 상황(기소·재판 개시·변론·판결)이 바뀌면 채팅방에 봇 메시지(`type=SYSTEM`)가 뜬다.
**수신부는 이번 브랜치가 만들었고, 발행부는 이슈 #169~#172 담당자 몫이다.** 각자 로직이 끝나는
지점에서 아래처럼 한 줄만 호출하면 된다.

```java
events.publishEvent(new GroupTrialEvents.TrialOpened(groupId, indictmentId, targetNickname));
```

이벤트 4종은 `challenge/domain/GroupTrialEvents.java` 에 있다.

| 이벤트 | 발행 시점 | 생성자 인자 |
|---|---|---|
| `ViolationDetected` | 소비 위반이 감지돼 기소 후보가 생겼을 때 | `(groupId, indictmentId, targetNickname)` |
| `TrialOpened` | 재판이 열렸을 때 | `(groupId, indictmentId, targetNickname)` |
| `DefenseRegistered` | 피고인이 변론을 등록했을 때 | `(groupId, indictmentId, targetNickname)` |
| `VerdictConfirmed` | 판결이 확정됐을 때 | `(groupId, indictmentId, summary)` |

발행부가 붙기 전까지는 아래 DEV 전용 엔드포인트로 같은 이벤트를 직접 쏴서 채팅방 렌더링을
시연·확인한다. **발행부가 붙으면 이 컨트롤러는 지운다.**

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| POST | `/api/dev/chat/system-message?groupId=&indictmentId=&kind=&nickname=` | Bearer | 없음 |

- **로컬에서만 동작한다.** `/api/dev/batches/**`(이슈 #152)와 같은 `app.env` 기반 차단이다.
- `kind`: `VIOLATION`(소비 위반 적발) · `TRIAL_OPENED`(재판 개시, 기본값) · `DEFENSE`(변론 등록) ·
  `VERDICT`(판결 확정)
- `indictmentId` 기본값 1, `nickname` 기본값 `절약왕`.

### 저장소 - Redis 전용, MySQL 테이블 없음

메시지 원본은 **Redis 에만** 있다. MyBatis 매퍼도 `tbl_chat_*` 테이블도 만들지 않았다
(DECISIONS.md 2026-08-15). **스키마 변경이 없다.**

- TTL 은 **챌린지 `end_date` + 2일**이다. 종료 다음날 도는 판결 확정 배치가 도착할 방이 남아
  있어야 해서다.
- 챌린지가 `CLOSED` 로 전이되거나 모집 미달로 삭제되면 **TTL 을 기다리지 않고 방을 즉시 삭제한다.**
  TTL 은 백스톱일 뿐이다.
- 안 읽은 수(`unreadCount`)도 같은 Redis 인스턴스에 저장하며 같은 TTL 규칙을 따른다.

### 그룹 채팅 에러 코드

| 코드 | HTTP | 상황 |
|---|---|---|
| `NOT_FOUND` | 400 | 존재하지 않는 챌린지 |
| `CHAT_NOT_MEMBER` | 400 | 이 챌린지의 참여자가 아님 |
| `CHAT_ROOM_CLOSED` | 400 | 종료(`CLOSED`)된 챌린지의 대화 조회·발행 시도 |
| `INVALID_REQUEST` | 400 | `before`·`after` 동시 지정, `limit` 범위(1~100) 밖, 메시지 내용이 비었거나 500자 초과 |
| `CHAT_SENDER_NOT_FOUND` | 400 | STOMP 발행 - 인증된 세션인데 발신자가 탈퇴 등으로 사라짐 |
| `CHAT_BROADCAST_UNAVAILABLE` | 500 | STOMP 브로커가 아직 바인딩되지 않아 전달 불가 (서버 기동 이상) |

## 알림 (이슈 #58)

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/notifications?cursor=&size=` | Bearer | `{ items:[{id,type,title,content,deepLinkUrl,isRead,createdAt}], nextCursor, unreadCount }` |
| GET | `/api/notifications/unread-count` | Bearer | `{ unreadCount }` |
| POST | `/api/notifications/{id}/read` | Bearer | `{ unreadCount }` |
| POST | `/api/notifications/read-all` | Bearer | `{ unreadCount }` |
| GET | `/api/notifications/stream` | Bearer | SSE. `connected` 1회 → 이후 `notification` 이벤트 |

- `cursor` 없으면 최신부터. 있으면 `id < cursor` 부터 `id DESC`
- `nextCursor` 는 마지막 항목의 `id`. 더 없으면 `null`
- `size` 기본 20 · 최대 50 (초과는 50 으로 자른다)
- SSE 는 스트림이라 `ApiResponse` 로 감싸지 않는다. 15초마다 `: ping` 주석 프레임을 보낸다
- `notification` 이벤트의 `data` 는 **목록 항목과 완전히 같은 모양**이다
  (`{id,type,title,content,deepLinkUrl,isRead,createdAt}`) — 변환은 `NotificationDto.from` 한 곳뿐
- `isRead` 는 boolean. Lombok getter 를 Jackson 이 `read` 로 바꾸지 않도록 `@JsonProperty("isRead")` 로 고정돼 있다
- `createdAt` 은 `yyyy-MM-ddTHH:mm:ss` (ISO-8601, 초 단위). 푸시된 알림도 서버가 값을 채워 내려보낸다

### 알림 종류 (`type`)

`tbl_notification.type` 컬럼(VARCHAR(30))에 그대로 들어가는 값이다.

| 값 | 기본 제목 | 발행 주체 |
|---|---|---|
| `ACCOUNT_RECONNECT` | 계좌 재연동이 필요해요 | `AccountReconnectRequiredEvent` |
| `GROUP_CHALLENGE_STARTED` | 챌린지가 시작됐어요 | challenge — 상태 전이 배치 (#152) |
| `GROUP_CHALLENGE_CANCELED` | 챌린지가 성립되지 않았어요 | challenge — 상태 전이 배치 (#152). 시작일에 참여자가 방장 1명뿐일 때 |
| `GROUP_JUDGMENT` | 판결이 확정됐어요 | challenge — 백엔드 미구현 |
| `GROUP_TRIAL_OPENED` | 재판이 열렸어요 | challenge — 평가·기소 배치 (#168). 딥링크는 변론 첫 화면 |
| `MISSION_DEADLINE` | 오늘 미션 마감 임박 | mission |
| `MISSION_ASSIGNED` | 오늘의 미션 도착 | mission |
| `MISSION_VERDICT` | 어제 미션 판결 확정 | mission |
| `MISSION_CERTIFICATE_ISSUED` | 개인 미션 명예 인증서 발급 | mission |
| `MONTHLY_REPORT` | 판결문이 도착했어요 | report — 백엔드 미구현 |
| `PAYMENT_DUE` | 결제 예정 알림 | fixedexpense — 백엔드 미구현 |

미구현 종류는 `db/seed_notification_demo.sql` 로만 들어간다 (DECISIONS.md 2026-08-06 (3)).

### 알림 에러 코드

| 코드 | HTTP | 상황 |
|---|---|---|
| `NOT_FOUND` | 400 | 없는 알림 또는 남의 알림 (구분해 알려주지 않는다) |

## 개인 미션 난이도 변경

`PATCH /api/users/me/difficulty`

요청은 `{ "difficultyName": "EASY" }` 형식이며 `EASY`, `NORMAL`, `HARD` 중 하나를 보낸다.
응답은 갱신된 `UserMeDto`다. 변경한 값은 이미 배정된 오늘 미션에는 소급하지 않고 다음 미션 배정부터 적용한다.

## 월간 소비 리포트

모든 엔드포인트는 Bearer 인증이 필요하며 사용자 ID를 요청 파라미터로 받지 않는다.
`yearMonth`는 `YYYY-MM` 형식이고 현재월·미래월·가입 이전 월은 상세 조회할 수 없다.

| Method | Endpoint | 응답 책임 |
|---|---|---|
| GET | `/api/reports/monthly/spending-trend?yearMonth=YYYY-MM` | 선택월을 포함한 최근 6개월 순소비 추이 |
| GET | `/api/reports/monthly/summary?yearMonth=YYYY-MM` | 당월·전월 총소비, 증감률, 활성 고정지출 후보·확정 개수 |
| GET | `/api/reports/monthly/categories?yearMonth=YYYY-MM` | 대분류 차트와 소분류 선고 명세용 순소비 정보 |
| GET | `/api/reports/monthly/months` | 가입월부터 현재월까지의 월 선택기 정보 |
| POST | `/api/reports/monthly/ai-analysis?yearMonth=YYYY-MM` | 매월 1일 00:15 KST 배치가 자동 생성하는 결과의 수동 재처리·재시도 또는 저장된 성공 결과 재사용 |
| GET | `/api/reports/monthly/ai-analysis?yearMonth=YYYY-MM` | 저장된 AI 분석 상태·소비 피드백·절약 비유 조회. 스냅샷 행이 없으면 생성하지 않고 빈 결과 반환 |

`fixedExpenseCandidateCount`는 `ACTIVE`·미제외·미확정 항목 수이고, `confirmedFixedExpenseCount`는 `ACTIVE`·미제외·확정 항목 수다. 후보가 없어도 확정 항목이 있으면 절약 감정서로 이동할 수 있다.

월 선택기 응답은 다음 형태다.

```json
{
  "months": [
    {
      "value": "2026-08",
      "year": 2026,
      "month": 8,
      "available": true,
      "hasReport": false,
      "status": "ONBOARDING"
    }
  ]
}
```

`status`는 `ONBOARDING`, `FIRST_REPORT`, `READY`, `CURRENT` 중 하나다. 가입월이 아직
진행 중이면 `ONBOARDING`으로 반환하며, 이 항목은 `available=true`, `hasReport=false`다.
가입월 이후 현재월은 `CURRENT`로 반환하고 조회할 수 없다
(`available=false`, `hasReport=false`). 완료된 가입월은 `FIRST_REPORT`, 그 이후 완료월은
`READY`로 반환하며 두 상태 모두 `available=true`, `hasReport=true`다. 단, 완료된 월이라도
v2 리포트 스냅샷이 없으면 목록에 노출하지 않는다. `ONBOARDING` 월을
선택한 화면은 상세 집계 API를 호출하지 않고 온보딩 화면만 표시한다.

집계에는 `CONSUMPTION` 거래만 포함하고 `is_excluded_from_summary=1`인 거래는 제외한다.
일반 소비는 `amount`를 더하고 환불은 `COALESCE(refunded_amount, amount)`를 한 번 차감한다.
기간 조건은 월 시작일 이상, 다음 달 시작일 미만의 반개구간을 사용한다.

최근 6개월 추이는 가입월 이전 슬롯을 `{amount:null, hasData:false}`로 표시한다.
가입 이후 완료월에 소비가 없으면 `{amount:0, hasData:true}`로 표시하여 실제 0원 월과 구분한다.

증감률과 비율은 소수 둘째 자리에서 `HALF_UP`으로 반올림한다. 전월과 당월이 모두 0이면
증감률은 `0.00`, 전월이 0이고 당월이 양수이면 계산 불가이므로 `null`이다. 가입 첫 달은
`hasPreviousComparison=false`이고 전월 금액과 증감률을 `null`로 반환한다.

프론트 화면 상태는 월 목록의 `status`와 요약 응답의 `hasPreviousComparison`으로 결정한다.
`ONBOARDING`은 온보딩 화면, `FIRST_REPORT`는 전월 비교가 없는 첫 리포트,
`READY`는 일반 리포트로 표시한다. 상세 집계 응답에는 별도 상태 필드를 추가하지 않고,
`hasPreviousComparison=false`인 응답을 `FIRST_REPORT` 화면 모델로 조합한다.

카테고리 응답의 `parentCategories`는 원형 차트용 대분류별 금액·비율이고, `categories`는
선고 명세용 소분류별 금액·비율·전월 증감률이다. 각 소분류에는 `parentCategoryId`와
`parentCategoryName`을 포함한다. 대분류가 직접 지정된 거래는 해당 대분류 자체를 명세 항목으로
반환한다. 카테고리 없는 소비는 두 목록 모두 ID `null`, 이름 `"미분류"`로 반환한다. 환불 반영 후
각 분류의 순소비가 0 이하이면 해당 목록에서 제외한다. 예산 대비 분석과 단일 `categoryId` 조회는
이 API의 범위가 아니다.

월간 리포트 배치는 매월 1일 **00:15 KST**에 정확히 이전 달만 대상으로 실행한다. `ACTIVE`이면서
대상 월의 다음 달 1일보다 전에 가입한 사용자만 처리하며, 서버 기동 시 과거 월을 일괄 보정하지 않는다.
리포트 생성 시점에 `AI_USAGE` 동의가 없으면 스냅샷 상태를 `NOT_CONSENTED`로 고정하고 외부 AI를 호출하지 않는다.
이후 동의해도 이미 `NOT_CONSENTED`로 저장된 과거 월 리포트에는 AI 분석을 소급 제공하지 않는다.
같은 사용자·월의 `tbl_asset_snapshot.category_summary_json`에는 아래처럼 화면 전체 리포트 페이로드를
저장한다. 컬럼명은 과거 호환을 위해 유지한다. `snapshotVersion=2` 행만 월간 소비 리포트 화면의
조회 기준이며, 이후 거래·카테고리·고정지출 데이터가 바뀌어도 `summary`·`spendingTrend`·`categoryReport`
값은 바뀌지 않는다. 이전 형식 행과 스냅샷 없는 과거 월은 원본 거래 데이터로 대체 조회하지 않으며,
수동 강제 배치로 v2 스냅샷을 생성해야 한다.

```json
{
  "snapshotVersion": 2,
  "aiUsageConsented": true,
  "summary": { "yearMonth": "2026-07" },
  "spendingTrend": { "yearMonth": "2026-07", "items": [] },
  "categoryReport": { "yearMonth": "2026-07", "parentCategories": [], "categories": [] }
}
```

일반 배치는 기존 v2 스냅샷을 수정하지 않는다. `force=true` 수동 배치만 기존 화면 스냅샷과 AI 결과를
초기화한 뒤 재생성한다. AI 분석도 위 JSON의 `summary`·`categoryReport`만 입력으로 사용한다.

일시 장애(`TOO_MANY_REQUESTS`, `AI_PROVIDER_UNAVAILABLE`)로 `FAILED`가 된 행은 총 3회 시도 안에서만
자동 재시도한다. 1~3일 **00:40 KST** 복구 실행은 마지막 실패 후 20분이 지난 행만 다시 처리한다.
`IN_PROGRESS`는 자동 복구하지 않아 외부 AI의 중복 호출을 피한다. AI 생성 실패는 해당 사용자의 상태만
`FAILED`로 남기며, 월간 집계·추이·카테고리 조회는 계속 가능하다.

### `POST /api/dev/reports/monthly/batch`

월간 소비 리포트의 수동 배치다. 일반 DEV API와 달리 로컬뿐 아니라 배포 환경에서도 실행할 수 있다.
Bearer 인증과 함께 환경변수 `REPORT_MONTHLY_MANUAL_BATCH_KEY`로 설정한 값을
`X-Report-Batch-Key` 헤더에 넣어야 하며, 키가 비어 있으면 API 자체가 비활성화된다.

```json
{
  "yearMonth": "2026-07",
  "force": true,
  "targetUserIds": [101, 102],
  "missingSnapshotAiConsents": [
    { "userId": 101, "aiUsageConsented": true },
    { "userId": 102, "aiUsageConsented": false }
  ]
}
```

- `yearMonth`는 완료된 과거 월만 허용한다.
- `force=false`는 자동 배치와 같이 미생성·재시도 대상만 멱등 처리한다.
- `force=true`에는 하나 이상의 `targetUserIds`가 필수다. 지정한 활성 사용자만 재생성하며,
  가입 이전 월이거나 조회할 수 없는 사용자를 포함하면 `REPORT_NOT_AVAILABLE`로 실패하고 아무도 갱신하지 않는다.
  기존 v2 스냅샷이 있으면 저장된 `aiUsageConsented` 값을 사용한다.
- 스냅샷이 없거나 v2 이전 형식이면 당시 AI 동의를 확인할 수 없다. 이 경우
  `missingSnapshotAiConsents`에 지정 사용자별 값을 빠짐없이 넘겨야 하며, 대상 밖의 사용자 ID 또는
  하나라도 빠진 값이 있으면 DB를 변경하지 않고 `MISSING_AI_CONSENT_INPUT` 또는 `INVALID_REQUEST`로 실패한다.
- 응답의 `snapshotSavedCount`는 화면 스냅샷 저장 성공 수, `aiGeneratedCount`는 AI 분석 생성 성공 수,
  `failureCount`는 사용자 단위 실패 수다.

```json
{
  "yearMonth": "2026-07",
  "totalSpent": 3802832,
  "parentCategories": [
    {
      "categoryId": 1,
      "categoryName": "식비",
      "amount": 671083,
      "ratio": 17.65
    }
  ],
  "categories": [
    {
      "parentCategoryId": 1,
      "parentCategoryName": "식비",
      "categoryId": 13,
      "categoryName": "음식점/외식",
      "amount": 174649,
      "ratio": 4.59,
      "previousMonthAmount": 136374,
      "changeRate": 28.07
    }
  ]
}
```

### `POST /api/reports/monthly/ai-analysis?yearMonth=YYYY-MM`

이 공개 경로는 월초 자동 배치가 저장한 `NOT_REQUESTED`·`FAILED` 스냅샷의 수동 재처리용이다. 월간 소비 집계를 바탕으로 AI 소비 피드백과 절약 비유를 생성하며, 요청 본문은 없고 `yearMonth`는 필수 쿼리 파라미터다. 스냅샷이 없거나 `NOT_CONSENTED`면 외부 AI를 호출하지 않고 빈 결과를 반환한다. `COMPLETED`면 저장된 스냅샷과 성공 결과를 그대로 반환하며, `IN_PROGRESS`면 스냅샷을 바꾸지 않고 중복 생성을 막는다.

- 인증: Bearer JWT
- 요청 본문: 없음
- `yearMonth`: `YYYY-MM` 형식, 필수
- 외부 전송 범위: 월별·카테고리별 집계 및 서버가 계산한 절감액만 허용한다. 사용자 식별 정보, 계좌·카드 정보, 거래 원문은 전송하지 않는다.
- `feedbacks`: 1~3개의 문자열만 반환한다. 각 배열 원소는 제목이나 분류 객체가 아닌 사용자에게 보여 줄 피드백 문장 하나다.
- `savingsAnalogy`: 절감액이 양수이고 전월 비교가 가능한 경우에만 문자열로 반환한다. AI는 반드시 `이번달 아낀 {절감액}원은 {실물자산} {수량}{실물자산의 단위}` 형식으로 생성한다. 예: `이번달 아낀 128,000원은 카페라떼 26잔`.
- 첫 리포트이거나 절감액이 0원이면 `savingsAnalogy`는 `null`이다.
- `status`: `COMPLETED`면 생성 결과를 반환한다. `NOT_CONSENTED`면 대상 월 리포트 제공 시점에 AI 활용 동의가 없어 외부 AI를 호출하지 않았음을 뜻하며, `feedbacks`는 빈 배열이고 `savingsAnalogy`는 `null`이다. 상태와 결과는 `tbl_asset_snapshot`에 함께 저장하지만, 기존 #156의 AI 입력 집계·비식별화·외부 호출 규칙은 변경하지 않는다.

```json
{
  "success": true,
  "data": {
    "yearMonth": "2026-07",
    "status": "COMPLETED",
    "feedbacks": [
      "식비 지출 비중이 지난달보다 늘었어요. 자주 이용한 지출 항목을 한 번 확인해 보세요.",
      "고정지출을 제외한 소비가 줄어 이번 달 지출을 안정적으로 관리했어요."
    ],
    "savingsAnalogy": "이번달 아낀 128,000원은 카페라떼 26잔"
  }
}
```

주요 실패는 잘못된 월(`400 INVALID_REQUEST`), 조회 불가 월(`404 NOT_FOUND`), 이미 생성 중인 월(`409 AI_ANALYSIS_IN_PROGRESS`), 호출 제한(`429 TOO_MANY_REQUESTS`), 외부 AI 일시 장애(`503 AI_PROVIDER_UNAVAILABLE`)로 구분한다.

### `GET /api/reports/monthly/ai-analysis?yearMonth=YYYY-MM`

스냅샷 행이 이미 있으면 저장된 AI 분석 상태와 결과만 조회한다. 스냅샷 행이 없을 때도 이 조회는 스냅샷 저장이나 AI 분석 생성을 시작하지 않고 `NOT_CONSENTED` 빈 결과를 반환한다. 기존 행이 `NOT_REQUESTED`이면 상태만 반환하므로 화면은 이어서 `POST` 생성 경로를 호출한다. `FAILED`·`IN_PROGRESS`·`COMPLETED`·`NOT_CONSENTED` 기존 행은 이 조회로 상태를 바꾸지 않는다.

- 인증: Bearer JWT
- 요청 본문: 없음
- `yearMonth`: `YYYY-MM` 형식, 필수
- `COMPLETED`: `feedbacks`는 저장된 문자열 1~3개, `savingsAnalogy`는 저장된 문자열 또는 `null`
- `NOT_REQUESTED`, `IN_PROGRESS`, `FAILED`, `NOT_CONSENTED`: `feedbacks`는 빈 배열, `savingsAnalogy`는 `null`
- 스냅샷 행이 없으면 `NOT_CONSENTED` 빈 결과를 반환한다. 이 경로에서는 외부 AI를 호출하지 않는다.
- `ai_analysis_failure_code`, 제공자·모델·프롬프트 버전·입력 해시·원문 AI 응답은 노출하지 않는다.

```json
{
  "success": true,
  "data": {
    "yearMonth": "2026-07",
    "status": "COMPLETED",
    "feedbacks": [
      "식비 지출 비중이 지난달보다 늘었어요. 자주 이용한 지출 항목을 한 번 확인해 보세요.",
      "고정지출을 제외한 소비가 줄어 이번 달 지출을 안정적으로 관리했어요."
    ],
    "savingsAnalogy": "이번달 아낀 128,000원은 카페라떼 26잔"
  }
}
```

저장된 `COMPLETED` JSON이 훼손돼 결과를 안전하게 조립할 수 없는 경우에만
`503 AI_ANALYSIS_RESULT_UNAVAILABLE`을 반환한다. 이는 월간 총소비·추이·카테고리 조회 실패와
분리된 부가 데이터 오류이며, 화면은 핵심 리포트를 계속 표시한다.

## 거래 카테고리 수동 수정 (이슈 #237)

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| PATCH | `/api/transactions/{transactionId}/category` | Bearer | `{ transactionId, categoryId, categorySource, merchantRuleApplied }` |

요청 본문
```json
{
  "categoryId": 12,
  "applyToMerchant": false
}
```

응답
```json
{
  "success": true,
  "data": {
    "transactionId": 501,
    "categoryId": 12,
    "categorySource": "USER",
    "merchantRuleApplied": false
  }
}
```

- 거래 한 건의 카테고리를 사용자가 직접 지정한다. `categorySource`는 항상 `USER`로 바뀐다 — 이미
  `USER`로 지정된 거래를 다시 고치는 요청도 반영된다(자동 재동기화만 `USER` 값을 보호한다).
- `applyToMerchant=true`면 같은 요청 안에서 `tbl_user_category_map`에도 가맹점 규칙을 upsert한다.
  이후 그 가맹점(정규화된 이름 기준)의 새 거래에는 이 카테고리가 최우선 적용된다. **과거 거래는
  소급 반영되지 않는다.**
- 거래가 없거나 본인 소유가 아니면 `404 NOT_FOUND`, `categoryId`가 `tbl_category`에 없으면
  `404 CATEGORY_NOT_FOUND`, `categoryId`를 아예 보내지 않으면 `400 INVALID_REQUEST`다.
  `applyToMerchant=true`인데 거래에 가맹점명이 없으면 `400 MERCHANT_NAME_REQUIRED`다.

## 거래내역 월별 조회 (장부)

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/transactions/months` | Bearer | `{ months: [{ value, hasData }] }` |
| GET | `/api/transactions?yearMonth=YYYY-MM` (생략 가능) | Bearer | `{ period, summary, transactions }` |
| GET | `/api/categories` | Bearer | `{ categories: [{ id, name, parentId }] }` |

`GET /api/transactions/months` 응답
```json
{
  "success": true,
  "data": {
    "months": [
      { "value": "2026-06", "hasData": false },
      { "value": "2026-07", "hasData": true },
      { "value": "2026-08", "hasData": false }
    ]
  }
}
```
- **데이터가 있는 가장 이른 달**(거래가 하나도 없으면 이번 달)~현재월 범위를 **오름차순**으로
  반환한다. `hasData`는 그 달에 집계 제외(`is_excluded_from_summary=1`)가 아닌 거래가 1건이라도
  있는지다.
  > 가입월(`tbl_user.created_at`) 기준이 **아니다.** CODEF 동기화는 계좌 연동 시점부터 과거
  > 거래를 끌어오므로 데이터가 가입일보다 앞설 수 있다 — 로컬 데모 시드(`seed_local_demo.sql`)가
  > 실계정에도 과거 7개월치 거래를 백필하면서 `tbl_user.created_at`은 그대로 두기 때문에 이 어긋남이
  > 실제로 나타난다. 가입월 기준으로 만들었다가 범위가 "이번 달"뿐이 되어 월 이동 버튼이 전부
  > 비활성화되는 버그가 있었다(2026-08-15 수정).

`GET /api/categories` 응답
```json
{
  "success": true,
  "data": {
    "categories": [
      { "id": 1, "name": "식비", "parentId": null },
      { "id": 2, "name": "음식점/외식", "parentId": 1 }
    ]
  }
}
```
- `parentId`가 `null`이면 대분류, 아니면 부모 대분류의 `id`.

`GET /api/transactions?yearMonth=2026-07` 응답
```json
{
  "success": true,
  "data": {
    "period": "2026-07",
    "summary": {
      "period": "2026-07",
      "totalSpent": 420900,
      "totalDeposit": 3420000,
      "monthOverMonthRate": 12.50,
      "paymentMethods": ["신한카드", "KB국민 체크카드", "입금"]
    },
    "transactions": [
      {
        "id": 501,
        "date": "2026-07-29",
        "merchant": "오늘의집",
        "category": "온라인쇼핑",
        "paymentMethod": "신한카드",
        "classification": "CONSUMPTION",
        "amount": -48900,
        "isRefund": false
      }
    ]
  }
}
```
- `amount`는 부호 있는 값이다 — `CONSUMPTION`은 음수, `INCOME`은 양수, 환불(`is_refund=1`)은
  지출을 상계하는 양수. `TRANSFER`(이체)도 목록에는 포함되지만 `summary.totalSpent`/
  `totalDeposit`/`monthOverMonthRate` 계산에서는 제외된다.
- `isRefund`: 이 거래가 환불 행인지. `true`면 `amount`가 양수여도 실제로는 이전 소비를 상계하는
  것이지 새로운 입금이 아니다 — 프론트가 "(환불)" 같은 별도 표기를 붙이는 근거로 쓴다.
- `paymentMethod`: 신용카드는 `{카드사명}카드`, 체크카드는 `{카드사명} 체크카드`, 계좌 입금은
  `입금`이다. `tbl_transaction.card_id`/`account_id`로 `tbl_card`/`tbl_connected_account`를
  조인해 만든다 — DB에 결제수단 컬럼이 따로 있는 게 아니다.
- `yearMonth`를 생략하면 `period: null`, `summary: null`이고 `transactions`는 기간 제한 없이
  데이터가 있는 전체 월을 합쳐서 반환한다(검색 화면 전용).
- `yearMonth`가 `YYYY-MM` 형식이 아니면 `400 INVALID_REQUEST`, 데이터가 있는 가장 이른 달보다
  이전이면 `400 LEDGER_NOT_AVAILABLE`.

## 오늘 쓴 돈 요약 (홈 카드)

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/api/transactions/summary/daily` | Bearer | `{ date, todayAmount, monthAmount, changeRate }` |

```json
{
  "success": true,
  "data": {
    "date": "2026-08-23",
    "todayAmount": 32000,
    "monthAmount": 486300,
    "changeRate": -20.00
  }
}
```
- 홈 최상단 「오늘 쓴 돈」 카드 전용이다. 필드명이 프론트 `HomeSpendingCard` 의 props 와 1:1로 맞춰져 있다.
- 구간은 **서버 KST**(`transaction.ledger.zone`, 기본 `Asia/Seoul`) 기준 반개구간이다.
  오늘 `[오늘, 내일)` · 어제 `[어제, 오늘)` · 이번 달 `[1일, 다음 달 1일)`.
- `date`: 서버가 판단한 "오늘"(`yyyy-MM-dd`). 자정 넘김·서버 타임존 사고를 프론트에서 확인할 유일한 단서다.
- **`monthAmount`는 month-to-date가 아니라 월 전체다.** 장부 월 요약(`GET /api/transactions?yearMonth=`)의
  `summary.totalSpent`와 **같은 값이어야 한다** — 카드에 「거래내역」 입구가 붙어 있어 사용자가 한 탭 만에
  두 숫자를 대조한다. 미래 날짜 거래가 한 행이라도 생기면 month-to-date 로 자른 순간 두 값이 갈린다.
- 환불은 지출을 상계하므로 **금액이 음수일 수 있다.** 0으로 자르지 않고 그대로 내려준다(장부와 같은 규칙).
- 금액 두 개는 데이터가 없어도 `null`이 아니라 `0`이다. 카드의 「—」·「집계 중」 자리는 **API 호출 실패 전용**이라
  0원 쓴 날에 `null`을 주면 정상이 장애처럼 보인다.
- `changeRate`: 어제 대비 증감률(%, scale 2). 음수면 덜 쓴 것이다. **어제 순지출이 0 이하면 `null`이다** —
  비율을 낼 수 없고, 음수를 분모로 쓰면 부호가 뒤집혀 "더 썼는데 적게 쓰는 중"이 뜬다.
  장부의 `monthOverMonthRate`는 같은 상황에서 `0`을 주므로 **계약이 다르다.** 혼동하지 말 것.
- 집계 기준은 장부·월간 리포트와 같다(`classification='CONSUMPTION'` + `is_excluded_from_summary=0`).
  > ⚠ **미션·그룹챌린지 집계는 여기에 `direction='OUT'`을 더 건다.** 페이머니 소비(`direction`이 `NULL`)가
  > 빠지므로 같은 사용자·같은 기간이라도 값이 다를 수 있다. 기존 시스템의 알려진 불일치이고 아직 통일하지 않았다.
