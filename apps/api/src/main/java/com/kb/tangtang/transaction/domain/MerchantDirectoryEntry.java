package com.kb.tangtang.transaction.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * tbl_merchant_directory 한 행 + 업종 매핑(tbl_industry_category_map).
 * industryName·categoryId 는 매핑에 없는 업종이면 null 이다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantDirectoryEntry {
    private String merchantNameNormalized;
    private String industryCode;
    private String industryName;
    private Long categoryId;
    private Integer storeCount;
    private BigDecimal topShare;
}
