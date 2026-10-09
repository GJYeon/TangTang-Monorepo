package com.kb.tangtang.transaction.client.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kb.tangtang.common.exception.BusinessException;
import com.kb.tangtang.transaction.domain.Category;
import com.kb.tangtang.transaction.domain.DirectoryHint;
import com.kb.tangtang.transaction.domain.Transaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 이슈 #147 후속 — OpenAI Chat Completions API 로 거래를 표준 카테고리로 분류한다.
 *
 * Structured Outputs(response_format.type=json_schema, strict=true)로 응답 모양을
 * {"results":[{"transactionId":.., "categoryId":..}, ...]} 로 강제한다 — 모델이 자유 텍스트를
 * 섞어 보내 파싱이 깨지는 사고를 막는다. categoryId 값 자체가 실제 존재하는 카테고리인지는
 * 이 클래스의 책임이 아니다 — 호출부(LlmCategorizationProcessingServiceImpl)가 한 번 더 검증한다.
 */
@Component
public class OpenAiClassificationClient implements LlmClassificationClient {

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    /*
     * Structured Outputs 를 강제해도 드물게 content 가 온전한 JSON 이 아닌 경우가 있다(모델이
     * 스키마를 어기고 텍스트를 섞어 보내는 등). 그 묶음을 통째로 FAILED(→ 미분류)로 버리기 전에
     * 같은 요청으로 한 번 더 물어본다. refusal·finish_reason 같은 다른 실패는 다시 물어봐도
     * 결과가 같을 가능성이 높아 재시도 대상에서 제외한다(아래 parseResults 참고).
     */
    private static final int MAX_ATTEMPTS = 2;

    private static final String SYSTEM_PROMPT =
            "당신은 한국 개인 금융 거래를 표준 소비 카테고리로 분류하는 어시스턴트입니다. "
            + "카테고리 목록에서 parentId 가 없는 항목은 대분류, parentId 가 있는 항목은 그 대분류에 속한 "
            + "소분류입니다. 확신이 서는 가장 구체적인 소분류를 선택하고, 소분류까지는 확신이 서지 않으면 "
            + "대분류만 선택하세요. "
            + "반드시 사용자가 제공한 카테고리 목록의 id 중 하나만 선택하세요. "
            + "목록에 없는 id를 만들어내지 마세요. 확신이 서지 않으면 categoryId를 null로 두세요. "
            + "각 판정마다 0.0에서 1.0 사이의 confidence(확신도)도 반드시 함께 반환하세요. "
            + "확신이 없을수록 confidence를 낮게, categoryId가 null이면 confidence도 낮게 주세요. "
            + "거래에 merchantCategoryName(카드사가 분류한 업종명)이 함께 오면, merchantName 을 "
            + "해석하기 애매할 때는 이 값을 가장 신뢰할 수 있는 신호로 우선 반영하세요. "
            + "merchantName 은 '브랜드명+지점명'이 공백 없이 붙어 들어올 수 있습니다 "
            + "(예: CU역삼점, 스타벅스강남역점, 이디야논현점). 끝에 오는 지역명·역명·'점'/'지점' 토큰은 "
            + "지점 표시이므로 무시하고 앞부분의 브랜드명만으로 업종을 판단하세요. "
            + "브랜드명은 영문 표기(CU, GS25)뿐 아니라 한글 음차 표기(씨유, 지에스25)로도 들어올 수 "
            + "있습니다. 같은 브랜드의 한글 음차 표기라고 해서 영문 표기보다 확신도를 낮추지 마세요. "
            + "일부 거래에는 directoryHint 가 붙습니다. 전국 상가 등록 정보에서 가맹점명으로 찾은 참고 "
            + "근거이며 정답이 아닙니다. matchedName 은 찾은 상호, industryName 은 그 상호의 업종, "
            + "storeCount 는 같은 이름의 점포 수, share 는 그중 이 업종의 비율, suggestedCategoryId 는 "
            + "그 업종에 대응하는 카테고리 목록의 id 입니다(없을 수 있음). "
            + "matchType 이 EXACT 이고 share 가 높으면 강하게 반영하세요. "
            + "matchType 이 PREFIX 이면 상호의 앞부분만 같은 다른 가게일 수 있으니 약하게 반영하세요. "
            + "share 가 낮거나, 가맹점이 플랫폼·간편결제·결제대행(PG)·통신사·택시 호출 서비스로 보이면 "
            + "directoryHint 를 무시하고 가맹점명으로 판단하세요. "
            + "merchantCategoryName 이 있으면 directoryHint 보다 우선합니다. "
            + "suggestedCategoryId 를 그대로 답할 필요는 없고, 목록의 id 만 답하세요.";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final double confidenceThreshold;

    @Autowired
    public OpenAiClassificationClient(@Qualifier("openAiRestTemplate") RestTemplate restTemplate,
                                      ObjectMapper objectMapper,
                                      @Value("${openai.api-key:}") String apiKey,
                                      @Value("${openai.api.base-url}") String baseUrl,
                                      @Value("${openai.api.model}") String model,
                                      @Value("${llm.categorization.confidence-threshold}") double confidenceThreshold) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.confidenceThreshold = confidenceThreshold;
    }

    @Override
    public List<CategoryAssignmentDto> classify(List<Transaction> transactions, List<Category> categories,
                                                Map<Long, DirectoryHint> hints) {
        // 키가 없으면 기동을 막지 않고 이 기능만 실패시킨다. 예전에는 프로퍼티가 없으면
        // 컨텍스트 로딩 자체가 깨져 서비스 전체가 뜨지 못했다(신규 설치자가 매번 걸렸다).
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException("EXTERNAL_API_ERROR",
                    "OpenAI API 키가 설정되지 않아 자동 카테고리화를 할 수 없습니다.");
        }

        ObjectNode requestBody = buildRequestBody(transactions, categories, hints == null ? Map.of() : hints);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<String> entity = new HttpEntity<>(requestBody.toString(), headers);

        LlmContentParseException lastParseFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            ResponseEntity<String> response;
            try {
                response = restTemplate.exchange(baseUrl + CHAT_COMPLETIONS_PATH, HttpMethod.POST, entity, String.class);
            } catch (Exception e) {
                throw new BusinessException("EXTERNAL_API_ERROR", "OpenAI 호출 실패: " + e.getMessage());
            }

            try {
                return parseResults(response.getBody());
            } catch (LlmContentParseException e) {
                lastParseFailure = e;
            }
        }
        throw new BusinessException("EXTERNAL_API_ERROR",
                MAX_ATTEMPTS + "번 재시도했지만 OpenAI 응답 파싱에 계속 실패했다: " + lastParseFailure.getMessage());
    }

    private ObjectNode buildRequestBody(List<Transaction> transactions, List<Category> categories,
                                        Map<Long, DirectoryHint> hints) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);

        ArrayNode messages = root.putArray("messages");
        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", SYSTEM_PROMPT);

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", buildUserContent(transactions, categories, hints));

        root.set("response_format", buildResponseFormat());
        return root;
    }

    private String buildUserContent(List<Transaction> transactions, List<Category> categories,
                                    Map<Long, DirectoryHint> hints) {
        ObjectNode payload = objectMapper.createObjectNode();

        ArrayNode categoryArray = payload.putArray("categories");
        for (Category category : categories) {
            ObjectNode node = categoryArray.addObject();
            node.put("id", category.getId());
            node.put("name", category.getCategoryName());
            node.put("parentId", category.getParentId());
        }

        ArrayNode transactionArray = payload.putArray("transactions");
        for (Transaction tx : transactions) {
            ObjectNode node = transactionArray.addObject();
            node.put("transactionId", tx.getId());
            node.put("merchantName", tx.getMerchantName() == null ? "" : tx.getMerchantName());
            node.put("merchantCategoryName", tx.getMerchantCategoryName() == null ? "" : tx.getMerchantCategoryName());
            node.put("amount", tx.getAmount() == null ? "" : tx.getAmount().toPlainString());
            node.put("description", tx.getDescription1() == null ? "" : tx.getDescription1());
            DirectoryHint hint = tx.getId() == null ? null : hints.get(tx.getId());
            if (hint != null) {
                ObjectNode hintNode = node.putObject("directoryHint");
                hintNode.put("matchType", hint.getMatchType());
                hintNode.put("matchedName", hint.getMatchedName());
                hintNode.put("industryName", hint.getIndustryName());
                if (hint.getSuggestedCategoryId() != null) {
                    hintNode.put("suggestedCategoryId", hint.getSuggestedCategoryId());
                }
                hintNode.put("storeCount", hint.getStoreCount());
                /* double 로 보낸다 — ObjectNode.put(BigDecimal) 은 기본 JsonNodeFactory 가 끝자리 0 을 떼어 표기가 흔들린다. */
                hintNode.put("share", hint.getShare() == null ? null : hint.getShare().doubleValue());
            }
        }

        return payload.toString();
    }

    /** OpenAI Structured Outputs 스키마. results 배열 하나만 강제한다. */
    private ObjectNode buildResponseFormat() {
        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_schema");

        ObjectNode jsonSchema = responseFormat.putObject("json_schema");
        jsonSchema.put("name", "transaction_categorization");
        jsonSchema.put("strict", true);

        ObjectNode schema = jsonSchema.putObject("schema");
        schema.put("type", "object");
        schema.putArray("required").add("results");
        schema.put("additionalProperties", false);

        ObjectNode properties = schema.putObject("properties");
        ObjectNode resultsProperty = properties.putObject("results");
        resultsProperty.put("type", "array");

        ObjectNode item = resultsProperty.putObject("items");
        item.put("type", "object");
        item.put("additionalProperties", false);
        item.putArray("required").add("transactionId").add("categoryId").add("confidence");

        ObjectNode itemProperties = item.putObject("properties");
        itemProperties.putObject("transactionId").put("type", "integer");
        itemProperties.putObject("confidence").put("type", "number");
        ObjectNode categoryIdProperty = itemProperties.putObject("categoryId");
        categoryIdProperty.putArray("type").add("integer").add("null");

        return responseFormat;
    }

    private List<CategoryAssignmentDto> parseResults(String responseBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new BusinessException("EXTERNAL_API_ERROR", "OpenAI 응답 파싱 실패: " + e.getMessage());
        }

        JsonNode choice = root.path("choices").path(0);

        /*
         * Structured Outputs 는 content 대신 refusal 을 돌려줄 수 있다(모델이 요청을 거부한 경우).
         * 그냥 두면 content 가 비어 "분류 결과 0건" 으로 읽혀 작업이 COMPLETED 로 마감된다 —
         * 실제로는 아무것도 분류되지 않았는데 성공으로 기록되므로 반드시 실패로 올린다.
         */
        JsonNode refusal = choice.path("message").path("refusal");
        if (!refusal.isMissingNode() && !refusal.isNull()) {
            throw new BusinessException("EXTERNAL_API_ERROR",
                    "OpenAI 가 분류 요청을 거부했다: " + refusal.asText());
        }

        /*
         * finish_reason 이 stop 이 아니면 응답이 온전하지 않다(length = 토큰 한도로 잘림 등).
         * 잘린 JSON 은 파싱이 되더라도 일부 거래가 통째로 빠진 결과라 성공으로 볼 수 없다.
         */
        JsonNode finishReason = choice.path("finish_reason");
        if (!finishReason.isMissingNode() && !finishReason.isNull()
                && !"stop".equals(finishReason.asText())) {
            throw new BusinessException("EXTERNAL_API_ERROR",
                    "OpenAI 응답이 정상 종료되지 않았다(finish_reason=" + finishReason.asText() + ")");
        }

        try {
            String content = choice.path("message").path("content").asText();
            JsonNode parsed = objectMapper.readTree(content);

            List<CategoryAssignmentDto> results = new ArrayList<>();
            for (JsonNode item : parsed.path("results")) {
                Long transactionId = item.path("transactionId").asLong();
                Long categoryId = item.path("categoryId").isNull() || item.path("categoryId").isMissingNode()
                        ? null : item.path("categoryId").asLong();
                /*
                 * confidence 가 없거나(누락·null) 임계값 미만이면 categoryId 가 와도 분류 불가로
                 * 취급한다 — 필드가 없는 비정상 응답은 안전하게(fail-closed) 0.0 으로 간주한다.
                 */
                JsonNode confidenceNode = item.path("confidence");
                double confidence = confidenceNode.isMissingNode() || confidenceNode.isNull()
                        ? 0.0 : confidenceNode.asDouble();
                if (confidence < confidenceThreshold) {
                    categoryId = null;
                }
                results.add(CategoryAssignmentDto.builder()
                        .transactionId(transactionId)
                        .categoryId(categoryId)
                        .build());
            }
            return results;
        } catch (Exception e) {
            /* 재시도 대상: content 가 유효한 JSON 이 아니거나 기대한 모양이 아니다. classify() 가 잡아 재시도한다. */
            throw new LlmContentParseException("OpenAI 응답 파싱 실패: " + e.getMessage(), e);
        }
    }

    /** content 필드가 유효한 JSON 이 아닐 때만 던진다 — classify() 가 이 예외만 잡아 한 번 더 요청한다. */
    private static class LlmContentParseException extends RuntimeException {
        LlmContentParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
