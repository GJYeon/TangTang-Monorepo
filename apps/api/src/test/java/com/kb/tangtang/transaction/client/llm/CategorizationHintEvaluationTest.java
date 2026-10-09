package com.kb.tangtang.transaction.client.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kb.tangtang.transaction.domain.Category;
import com.kb.tangtang.transaction.domain.DirectoryHint;
import com.kb.tangtang.transaction.domain.MerchantDirectoryEntry;
import com.kb.tangtang.transaction.domain.Transaction;
import com.kb.tangtang.transaction.mapper.MerchantDirectoryMapper;
import com.kb.tangtang.transaction.service.MerchantDirectoryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 상가정보 힌트의 전후 비교 — 같은 평가셋을 실제 OpenAI 로 "힌트 없음 / 힌트 있음" 두 번 분류한다.
 * 실제 API 비용이 들어 기본 빌드에서는 돌지 않는다. 실행 방법은
 * docs/measurements/2026-10-10-merchant-directory-rag/README.md 「6. LLM 전후 비교」.
 */
@EnabledIfEnvironmentVariable(named = "TANGTANG_EVAL_DIR", matches = ".+")
class CategorizationHintEvaluationTest {

    private static final Path DATA_DIR = Path.of("../../docs/measurements/2026-10-10-merchant-directory-rag/data");
    private static final String MODEL = "gpt-4o-mini";            // application.properties openai.api.model 과 같게
    private static final double CONFIDENCE_THRESHOLD = 0.81;      // llm.categorization.confidence-threshold 와 같게
    private static final int BATCH_SIZE = 20;

    /** db/seed_category.sql + 20260816_add_income_categories.sql 과 같은 이름·계층. */
    private static final Map<String, List<String>> CATEGORY_TREE = new LinkedHashMap<>();
    static {
        CATEGORY_TREE.put("식비", List.of("음식점/외식", "배달앱", "카페/간식", "편의점"));
        CATEGORY_TREE.put("쇼핑", List.of("온라인쇼핑", "패션", "뷰티"));
        CATEGORY_TREE.put("교통", List.of("대중교통", "택시/모빌리티", "주유/충전", "주차/통행료"));
        CATEGORY_TREE.put("주거/공과금", List.of("월세", "관리비", "전기·가스·수도"));
        CATEGORY_TREE.put("생활", List.of("장보기/마트", "생활용품", "세탁비", "경조사/선물", "자녀", "반려동물"));
        CATEGORY_TREE.put("구독/디지털", List.of("통신비", "OTT", "음원", "앱·게임", "소프트웨어·클라우드"));
        CATEGORY_TREE.put("문화/여가", List.of("영화·공연·전시", "도서", "취미", "레저"));
        CATEGORY_TREE.put("여행", List.of("교통권", "숙박", "관광·여행상품"));
        CATEGORY_TREE.put("건강", List.of("병원", "약국", "운동시설", "건강관리"));
        CATEGORY_TREE.put("교육/자기계발", List.of("학원", "온라인 강의", "시험·자격증", "학습 교재"));
        CATEGORY_TREE.put("금융/보험", List.of("보험료", "이자", "수수료", "금융상품"));
        CATEGORY_TREE.put("기타", List.of());
        CATEGORY_TREE.put("수입", List.of("급여", "상여금", "용돈", "부수입", "이자/배당", "환급/캐시백", "수입 기타"));
    }

    @Test
    @DisplayName("상가정보 힌트 없음 vs 있음 — 평가셋 정답률 비교 (수동 실행)")
    void compareWithAndWithoutHints() throws IOException {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY 가 없으면 건너뛴다");
        Path evalDir = Path.of(System.getenv("TANGTANG_EVAL_DIR"));

        List<Category> categories = new ArrayList<>();
        Map<String, Long> idByName = new HashMap<>();
        Map<Long, String> nameById = new HashMap<>();
        Map<String, String> parentByName = new HashMap<>();
        long nextId = 1;
        for (Map.Entry<String, List<String>> group : CATEGORY_TREE.entrySet()) {
            long parentId = nextId++;
            categories.add(Category.builder().id(parentId).categoryName(group.getKey()).build());
            idByName.put(group.getKey(), parentId);
            nameById.put(parentId, group.getKey());
            parentByName.put(group.getKey(), group.getKey());
            for (String child : group.getValue()) {
                long id = nextId++;
                categories.add(Category.builder().id(id).categoryName(child).parentId(parentId).build());
                idByName.put(child, id);
                nameById.put(id, child);
                parentByName.put(child, group.getKey());
            }
        }

        MerchantDirectoryServiceImpl directoryService =
                new MerchantDirectoryServiceImpl(inMemoryMapper(evalDir.resolve("merchant_directory.csv"), idByName));

        /* 고유 (가맹점명, 정답) → 거래 수 */
        Map<String, Integer> weightByItem = new LinkedHashMap<>();
        for (String line : Files.readAllLines(DATA_DIR.resolve("seed_rows.tsv"), StandardCharsets.UTF_8)) {
            String[] cols = line.split("\t");
            if (cols.length < 2) {
                continue;
            }
            String label = cols[1].replaceAll("'$", "").replace("시험/자격증", "시험·자격증");
            weightByItem.merge(cols[0] + "\t" + label, 1, Integer::sum);
        }
        List<String> items = new ArrayList<>(weightByItem.keySet());
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            /* merchantCategoryName 은 비운다 — 시드에서는 그 값이 정답 라벨이라 넘기면 정답이 샌다. */
            transactions.add(Transaction.builder().id((long) i).merchantName(items.get(i).split("\t")[0]).build());
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        OpenAiClassificationClient client = new OpenAiClassificationClient(new RestTemplate(factory),
                new ObjectMapper(), apiKey, "https://api.openai.com", MODEL, CONFIDENCE_THRESHOLD);

        Map<Long, DirectoryHint> allHints = new HashMap<>();
        Map<Long, Long> withoutHints = new HashMap<>();
        Map<Long, Long> withHints = new HashMap<>();
        for (int start = 0; start < transactions.size(); start += BATCH_SIZE) {
            List<Transaction> batch = transactions.subList(start, Math.min(start + BATCH_SIZE, transactions.size()));
            Map<Long, DirectoryHint> hints = directoryService.findHints(batch);
            allHints.putAll(hints);
            client.classify(batch, categories, Map.of())
                    .forEach(a -> withoutHints.put(a.getTransactionId(), a.getCategoryId()));
            client.classify(batch, categories, hints)
                    .forEach(a -> withHints.put(a.getTransactionId(), a.getCategoryId()));
            System.out.printf("진행 %d/%d%n", Math.min(start + BATCH_SIZE, transactions.size()), transactions.size());
        }

        Map<String, int[]> score = new LinkedHashMap<>();   // 조건 → [소분류정답, 대분류만정답, 오답, null]
        score.put("힌트 없음", new int[4]);
        score.put("힌트 있음", new int[4]);
        List<String> detail = new ArrayList<>();
        detail.add("weight\tmerchant\tlabel\thint\twithout\twith");
        int total = 0;
        for (int i = 0; i < items.size(); i++) {
            String[] cols = items.get(i).split("\t");
            int weight = weightByItem.get(items.get(i));
            total += weight;
            String label = cols[1];
            String before = nameById.get(withoutHints.get((long) i));
            String after = nameById.get(withHints.get((long) i));
            tally(score.get("힌트 없음"), before, label, parentByName, weight);
            tally(score.get("힌트 있음"), after, label, parentByName, weight);
            DirectoryHint hint = allHints.get((long) i);
            String hintText = hint == null ? "-" : hint.getMatchType() + ":" + hint.getMatchedName() + ">" + hint.getIndustryName();
            detail.add(String.join("\t", String.valueOf(weight), cols[0], label, hintText,
                    String.valueOf(before), String.valueOf(after)));
        }
        Files.write(evalDir.resolve("hint_eval_result.tsv"), detail, StandardCharsets.UTF_8);

        System.out.println("조건\t소분류정답\t대분류만정답\t오답\t분류불가(null)\t소분류정답률");
        for (Map.Entry<String, int[]> e : score.entrySet()) {
            int[] s = e.getValue();
            System.out.printf("%s\t%d\t%d\t%d\t%d\t%.1f%%%n", e.getKey(), s[0], s[1], s[2], s[3], 100.0 * s[0] / total);
        }
        System.out.printf("거래 %d건, 고유 가맹점 %d개, 힌트 붙은 고유 가맹점 %d개%n", total, items.size(), allHints.size());
    }

    private static void tally(int[] s, String predicted, String label, Map<String, String> parentByName, int weight) {
        if (predicted == null) {
            s[3] += weight;
        } else if (predicted.equals(label)) {
            s[0] += weight;
        } else if (predicted.equals(parentByName.get(label))) {
            s[1] += weight;
        } else {
            s[2] += weight;
        }
    }

    /** 집계 CSV + 업종 매핑 TSV 를 메모리에 올린 가짜 매퍼. 운영 매퍼와 같은 결과 모양을 돌려준다. */
    private static MerchantDirectoryMapper inMemoryMapper(Path directoryCsv, Map<String, Long> idByName) throws IOException {
        Map<String, String> industryNameByCode = new HashMap<>();
        for (String line : Files.readAllLines(DATA_DIR.resolve("industry_codes.csv"), StandardCharsets.UTF_8)) {
            String[] c = line.split(",");
            if (c.length >= 4) {
                industryNameByCode.put(c[0], c[2].trim() + ">" + c[3].trim());
            }
        }
        Map<String, Long> categoryIdByCode = new HashMap<>();
        List<String> mapLines = Files.readAllLines(DATA_DIR.resolve("industry_category_map.tsv"), StandardCharsets.UTF_8);
        for (String line : mapLines.subList(1, mapLines.size())) {
            String[] c = line.split("\t", -1);
            if (c.length >= 3 && !c[2].isEmpty()) {
                categoryIdByCode.put(c[0], idByName.get(c[2]));
            }
        }
        Map<String, MerchantDirectoryEntry> entries = new HashMap<>(2_500_000);
        try (var lines = Files.lines(directoryCsv, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                String[] c = line.split(",");
                if (c.length < 4) {
                    return;
                }
                entries.put(c[0], MerchantDirectoryEntry.builder()
                        .merchantNameNormalized(c[0]).industryCode(c[1])
                        .industryName(industryNameByCode.get(c[1])).categoryId(categoryIdByCode.get(c[1]))
                        .storeCount(Integer.parseInt(c[2])).topShare(new BigDecimal(c[3])).build());
            });
        }
        return names -> names.stream().map(entries::get).filter(e -> e != null).collect(Collectors.toList());
    }
}
