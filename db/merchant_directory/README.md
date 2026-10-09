# 상호 사전 (선택)

LLM 카테고리화에 **상가(상권)정보 업종 근거(`directoryHint`)** 를 붙이기 위한 사전이다.
**선택 항목이다.** `tbl_merchant_directory` 가 비어 있으면 힌트 없이 기존과 똑같이 동작한다.

- 테이블은 `db/migration/20261010_add_merchant_directory.sql` 이 만든다(업종 매핑 247행 포함).
- 측정 근거: `docs/measurements/2026-10-10-merchant-directory-rag/README.md`

## 1. 배포본으로 적재 (보통 이것만 하면 된다)

팀 공유 드라이브의 `merchant_directory.sql.gz` (약 20MB) 를 받아 압축을 풀고 적재한다. 1~2분 걸린다.

```bash
gunzip -k merchant_directory.sql.gz
mysql -u tangtang -p tangtang --default-character-set=utf8mb4 < merchant_directory.sql
```

```powershell
# 압축은 Git Bash 의 gunzip 으로 푼다
mysql -u tangtang -p tangtang --default-character-set=utf8mb4 -e "source D:/.../merchant_directory.sql"
```

확인: `SELECT COUNT(*) FROM tbl_merchant_directory;` → 약 184만

## 2. 원본에서 직접 생성 (사전을 갱신할 때)

1. 공공데이터포털에서 「소상공인시장진흥공단_상가(상권)정보」 를 받는다(시도별 CSV, UTF-8, 약 1.4GB).
2. `node --max-old-space-size=6144 db/merchant_directory/build.mjs <CSV폴더> <출력폴더>`
3. 출력폴더의 `merchant_directory.sql(.gz)` 를 위 1. 처럼 적재한다.

생성 파일은 **커밋하지 않는다**(`.gitignore`). 원본·생성본 모두 용량이 커서 레포 밖에 둔다.

## 규칙

- 정규화는 `MerchantNameNormalizer` 와 같아야 한다. 한쪽을 바꾸면 다른 쪽도 바꾸고 사전을 다시 생성한다.
- 테스트: `node --test db/merchant_directory/build.test.mjs`
