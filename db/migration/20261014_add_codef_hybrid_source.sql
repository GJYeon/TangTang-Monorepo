-- 신규 설치 시 실행: 예. schema.sql → seed.sql 뒤 migration 순서에 따라 실행한다.
--
-- 목서버와 실 CODEF 를 한 서버에서 함께 쓰기 위한 변경이다(financial.client=hybrid).
--
-- 1) tbl_user.financial_data_source
--    사용자마다 금융 데이터 공급자를 고른다. 기본값 MOCK 이라 기존 사용자·더미 사용자는 그대로 목서버를 쓴다.
--    실계좌로 시연할 사용자만 수동 SQL 로 바꾼다(mock_scenario_key 와 같은 방식):
--      UPDATE tbl_user SET financial_data_source='CODEF' WHERE id=...;
--    financial.client 가 mock/codef 면 이 값은 읽지 않는다(서버 전체가 한쪽으로 고정).
--
-- 2) tbl_codef_connection
--    실 CODEF connectedId 를 기관 단위로 보관한다. 예전에는 메모리 세션에만 있어 서버를 재시작하면
--    다시 조회할 길이 없었다(tbl_connected_account.codef_connected_id 에는 우리 세션 식별자가 들어간다).
--    connectedId 는 AES-GCM 으로 암호화해 넣는다(ConnectedIdCipher). 키는 financial.codef.connected-id-secret.
--
-- 되돌리는 방법:
--   DROP TABLE tbl_codef_connection;
--   ALTER TABLE tbl_user DROP COLUMN financial_data_source;
ALTER TABLE tbl_user
    ADD COLUMN financial_data_source VARCHAR(10) NOT NULL DEFAULT 'MOCK'
    COMMENT '금융 데이터 공급자 MOCK/CODEF. financial.client=hybrid 일 때만 읽는다'
    AFTER mock_scenario_key;

CREATE TABLE tbl_codef_connection (
  id                     BIGINT       NOT NULL AUTO_INCREMENT,
  user_id                BIGINT       NOT NULL,
  organization           VARCHAR(10)  NOT NULL COMMENT '우리 기관코드(InstitutionCatalog). 카드는 CODEF 코드와 달라 호출 시 바꾼다(CodefOrganizations)',
  business_type          VARCHAR(5)   NOT NULL COMMENT 'CODEF businessType. BK=은행 · CD=카드',
  connected_id_encrypted VARCHAR(255) NOT NULL COMMENT 'CODEF connectedId AES-GCM 암호문(Base64)',
  is_active              TINYINT(1)   NOT NULL DEFAULT 1,
  created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cc_user_org (user_id, organization),
  CONSTRAINT fk_cc_user FOREIGN KEY (user_id) REFERENCES tbl_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='실 CODEF 연결(connectedId) 보관';
