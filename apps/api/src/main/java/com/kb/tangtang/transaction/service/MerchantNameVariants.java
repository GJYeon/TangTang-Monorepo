package com.kb.tangtang.transaction.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 상호 사전 조회 키 생성. 카드 승인 가맹점명은 지점명·결제 접미어·잘린 괄호가 섞여 들어와
 * 사전의 상호명과 그대로는 맞지 않는다. 변형 규칙은 측정 스크립트
 * (docs/measurements/2026-10-10-merchant-directory-rag/scripts/match.mjs 의 variants())와 같아야
 * 측정한 커버리지·정답률이 유효하다.
 */
public final class MerchantNameVariants {

    static final int MIN_NAME_LENGTH = 2;
    static final int MIN_PREFIX_LENGTH = 3;

    /** MerchantNameNormalizer 가 지우지 않는 법인 표기. "주)" 는 카드사가 "(주)" 앞 괄호를 잘라 보낸 형태다. */
    private static final Pattern EXTRA_CORPORATE_MARKERS = Pattern.compile("주\\)|\\(유\\)");
    private static final Pattern NUMERIC_SUFFIX = Pattern.compile("_\\d+$");
    /** 닫는 괄호가 잘려 없어도 끝까지 지운다: "엑시트피씨카페(EXI" */
    private static final Pattern PARENTHESES = Pattern.compile("\\([^)]*\\)?");
    private static final Pattern BRANCH_TOKEN = Pattern.compile("\\s+\\S*(점|호점)$");

    private MerchantNameVariants() {
    }

    public static List<String> of(String rawMerchantName) {
        if (rawMerchantName == null || rawMerchantName.isBlank()) {
            return List.of();
        }
        String noSuffix = NUMERIC_SUFFIX.matcher(rawMerchantName).replaceAll("");
        String noParen = PARENTHESES.matcher(noSuffix).replaceAll("");
        String noBranch = BRANCH_TOKEN.matcher(noParen).replaceAll("");

        Set<String> variants = new LinkedHashSet<>();
        for (String candidate : List.of(rawMerchantName, noSuffix, noParen, noBranch)) {
            String cleaned = EXTRA_CORPORATE_MARKERS.matcher(candidate).replaceAll("");
            String normalized = MerchantNameNormalizer.normalize(cleaned);
            if (normalized != null && normalized.length() >= MIN_NAME_LENGTH) {
                variants.add(normalized);
            }
        }
        return new ArrayList<>(variants);
    }

    public static List<String> prefixes(String normalized) {
        List<String> prefixes = new ArrayList<>();
        for (int length = normalized.length() - 1; length >= MIN_PREFIX_LENGTH; length--) {
            prefixes.add(normalized.substring(0, length));
        }
        return prefixes;
    }
}
