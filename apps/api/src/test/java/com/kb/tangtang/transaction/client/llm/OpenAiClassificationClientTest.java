package com.kb.tangtang.transaction.client.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kb.tangtang.common.exception.BusinessException;
import com.kb.tangtang.transaction.domain.Category;
import com.kb.tangtang.transaction.domain.DirectoryHint;
import com.kb.tangtang.transaction.domain.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiClassificationClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private OpenAiClassificationClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        client = new OpenAiClassificationClient(restTemplate, new ObjectMapper(),
                "sk-test-key", "https://api.openai.com", "gpt-4o-mini", 0.8);
    }

    private static Transaction tx(long id, String merchantName) {
        return Transaction.builder().id(id).merchantName(merchantName)
                .amount(new BigDecimal("15000")).description1("결제").build();
    }

    @Test
    @DisplayName("Authorization 헤더에 Bearer 토큰을 싣고 chat/completions 에 POST 한다")
    void sendsBearerTokenToCorrectEndpoint() {
        mockServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-test-key"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"{\\\"results\\\":"
                                + "[{\\\"transactionId\\\":1,\\\"categoryId\\\":5,\\\"confidence\\\":0.95}]}\"}}]}",
                        MediaType.APPLICATION_JSON));

        List<CategoryAssignmentDto> result = client.classify(
                List.of(tx(1L, "스타벅스")),
                List.of(Category.builder().id(5L).categoryName("카페/간식").parentId(1L).build()));

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getTransactionId());
        assertEquals(5L, result.get(0).getCategoryId());
        mockServer.verify();
    }

    @Test
    @DisplayName("확신이 없으면 categoryId 가 null 인 결과를 그대로 돌려준다")
    void nullCategoryIdMeansUnclassifiable() {
        mockServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"{\\\"results\\\":[{\\\"transactionId\\\":2,\\\"categoryId\\\":null}]}\"}}]}",
                        MediaType.APPLICATION_JSON));

        List<CategoryAssignmentDto> result = client.classify(
                List.of(tx(2L, "정체불명상점")),
                List.of(Category.builder().id(5L).categoryName("카페/간식").parentId(1L).build()));

        assertEquals(1, result.size());
        assertNull(result.get(0).getCategoryId());
    }

    @Test
    @DisplayName("요청 본문에 model·거래 id·카테고리 id 가 포함된다")
    void requestBodyContainsModelAndData() throws Exception {
        mockServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(request -> {
                    String body = request.getBody().toString();
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"model\":\"gpt-4o-mini\""));
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"json_schema\""));
                })
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"{\\\"results\\\":[]}\"}}]}",
                        MediaType.APPLICATION_JSON));

        client.classify(List.of(tx(1L, "스타벅스")),
                List.of(Category.builder().id(5L).categoryName("카페/간식").parentId(1L).build()));

        mockServer.verify();
    }

    @Test
    @DisplayName("모델이 거부(refusal)하면 빈 결과가 아니라 예외를 던진다")
    void throwsWhenModelRefuses() {
        mockServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"refusal\":\"I can't help with that.\"},"
                                + "\"finish_reason\":\"stop\"}]}",
                        MediaType.APPLICATION_JSON));

        /* 그냥 두면 content 가 비어 "0건 분류" 로 읽혀 작업이 COMPLETED 로 마감된다 — 실패로 올려야 한다. */
        BusinessException e = assertThrows(BusinessException.class, () -> client.classify(
                List.of(tx(1L, "스타벅스")),
                List.of(Category.builder().id(5L).categoryName("카페/간식").parentId(1L).build())));

        assertEquals("EXTERNAL_API_ERROR", e.getCode());
        assertTrue(e.getMessage().contains("거부"));
    }

    @Test
    @DisplayName("finish_reason 이 stop 이 아니면(응답 잘림) 예외를 던진다")
    void throwsWhenResponseTruncated() {
        mockServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"{\\\"results\\\":[]}\"},"
                                + "\"finish_reason\":\"length\"}]}",
                        MediaType.APPLICATION_JSON));

        BusinessException e = assertThrows(BusinessException.class, () -> client.classify(
                List.of(tx(1L, "스타벅스")),
                List.of(Category.builder().id(5L).categoryName("카페/간식").parentId(1L).build())));

        assertEquals("EXTERNAL_API_ERROR", e.getCode());
        assertTrue(e.getMessage().contains("length"));
    }

    @Test
    @DisplayName("finish_reason 이 stop 이면 정상 파싱한다")
    void parsesNormallyWhenFinishReasonIsStop() {
        mockServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"{\\\"results\\\":"
                                + "[{\\\"transactionId\\\":1,\\\"categoryId\\\":5,\\\"confidence\\\":0.95}]}\",\"refusal\":null},"
                                + "\"finish_reason\":\"stop\"}]}",
                        MediaType.APPLICATION_JSON));

        List<CategoryAssignmentDto> result = client.classify(
                List.of(tx(1L, "스타벅스")),
                List.of(Category.builder().id(5L).categoryName("카페/간식").parentId(1L).build()));

        assertEquals(1, result.size());
        assertEquals(5L, result.get(0).getCategoryId());
    }

    @Test
    @DisplayName("카테고리 목록에 parentId 를 함께 보내 대분류/소분류를 구분할 수 있게 한다")
    void categoryPayloadIncludesParentIdForHierarchyAwareness() throws Exception {
        mockServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(request -> {
                    /* user 메시지 content 는 JSON 문자열이라, 바깥 JSON 안에서 따옴표가 이스케이프된다. */
                    String body = request.getBody().toString();
                    assertTrue(body.contains("\\\"parentId\\\":null"),
                            "대분류(parentId 없음)는 parentId:null 로 표시돼야 한다: " + body);
                    assertTrue(body.contains("\\\"parentId\\\":1"),
                            "소분류는 소속 대분류의 id 를 parentId 로 실어야 한다: " + body);
                })
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"{\\\"results\\\":[]}\"}}]}",
                        MediaType.APPLICATION_JSON));

        client.classify(List.of(tx(1L, "스타벅스")), List.of(
                Category.builder().id(1L).categoryName("식비").parentId(null).build(),
                Category.builder().id(5L).categoryName("카페/간식").parentId(1L).build()));

        mockServer.verify();
    }

    @Test
    @DisplayName("confidence 가 임계값 이상이면 categoryId 를 그대로 반영한다")
    void keepsCategoryIdWhenConfidenceAtOrAboveThreshold() {
        mockServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"{\\\"results\\\":"
                                + "[{\\\"transactionId\\\":1,\\\"categoryId\\\":5,\\\"confidence\\\":0.8}]}\"}}]}",
                        MediaType.APPLICATION_JSON));

        List<CategoryAssignmentDto> result = client.classify(
                List.of(tx(1L, "스타벅스")),
                List.of(Category.builder().id(5L).categoryName("카페/간식").parentId(1L).build()));

        assertEquals(1, result.size());
        assertEquals(5L, result.get(0).getCategoryId());
    }

    @Test
    @DisplayName("confidence 가 임계값 미만이면 categoryId 가 있어도 null 로 취급한다")
    void dropsCategoryIdWhenConfidenceBelowThreshold() {
        mockServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"{\\\"results\\\":"
                                + "[{\\\"transactionId\\\":1,\\\"categoryId\\\":5,\\\"confidence\\\":0.5}]}\"}}]}",
                        MediaType.APPLICATION_JSON));

        List<CategoryAssignmentDto> result = client.classify(
                List.of(tx(1L, "스타벅스")),
                List.of(Category.builder().id(5L).categoryName("카페/간식").parentId(1L).build()));

        assertEquals(1, result.size());
        assertNull(result.get(0).getCategoryId());
    }

    @Test
    @DisplayName("content 가 유효한 JSON 이 아니면 한 번 더 물어보고, 재시도에서 성공하면 그 결과를 쓴다")
    void retriesOnceWhenContentIsMalformedJsonAndSucceeds() {
        mockServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"이건 JSON 이 아니다\"}}]}",
                        MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"{\\\"results\\\":"
                                + "[{\\\"transactionId\\\":1,\\\"categoryId\\\":5,\\\"confidence\\\":0.95}]}\"}}]}",
                        MediaType.APPLICATION_JSON));

        List<CategoryAssignmentDto> result = client.classify(
                List.of(tx(1L, "스타벅스")),
                List.of(Category.builder().id(5L).categoryName("카페/간식").parentId(1L).build()));

        assertEquals(1, result.size());
        assertEquals(5L, result.get(0).getCategoryId());
        mockServer.verify();
    }

    @Test
    @DisplayName("재시도에서도 content 가 계속 깨져 있으면 예외를 던지고 더 이상 재시도하지 않는다")
    void stopsAfterMaxAttemptsWhenContentStaysMalformed() {
        mockServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"이건 JSON 이 아니다\"}}]}",
                        MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"또 깨졌다\"}}]}",
                        MediaType.APPLICATION_JSON));

        BusinessException e = assertThrows(BusinessException.class, () -> client.classify(
                List.of(tx(1L, "스타벅스")),
                List.of(Category.builder().id(5L).categoryName("카페/간식").parentId(1L).build())));

        assertEquals("EXTERNAL_API_ERROR", e.getCode());
        mockServer.verify();
    }

    @Test
    @DisplayName("모델이 거부(refusal)한 경우는 파싱 재시도 없이 바로 예외를 던진다")
    void doesNotRetryOnRefusal() {
        mockServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"refusal\":\"I can't help with that.\"},"
                                + "\"finish_reason\":\"stop\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThrows(BusinessException.class, () -> client.classify(
                List.of(tx(1L, "스타벅스")),
                List.of(Category.builder().id(5L).categoryName("카페/간식").parentId(1L).build())));

        /* 두 번째 요청이 없어야 한다 — 등록된 expectation 은 하나뿐이라, 더 불렀다면 mockServer.verify()가 아니라
           unexpected request 예외로 먼저 실패한다. */
        mockServer.verify();
    }

    @Test
    @DisplayName("요청 본문에 merchantCategoryName(MCC 업종명)이 포함된다")
    void requestBodyIncludesMerchantCategoryName() throws Exception {
        mockServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(request -> {
                    String body = request.getBody().toString();
                    assertTrue(body.contains("\\\"merchantCategoryName\\\":\\\"편의점\\\""),
                            "MCC 업종명이 payload 에 실려야 한다: " + body);
                })
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"{\\\"results\\\":[]}\"}}]}",
                        MediaType.APPLICATION_JSON));

        Transaction tx = Transaction.builder().id(1L).merchantName("씨유역삼점")
                .merchantCategoryName("편의점")
                .amount(new BigDecimal("3000")).description1("결제").build();

        client.classify(List.of(tx),
                List.of(Category.builder().id(5L).categoryName("편의점").parentId(1L).build()));

        mockServer.verify();
    }

    @Test
    @DisplayName("confidence 필드가 아예 없으면 안전하게 분류 불가(null)로 취급한다")
    void dropsCategoryIdWhenConfidenceMissing() {
        mockServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"{\\\"results\\\":"
                                + "[{\\\"transactionId\\\":1,\\\"categoryId\\\":5}]}\"}}]}",
                        MediaType.APPLICATION_JSON));

        List<CategoryAssignmentDto> result = client.classify(
                List.of(tx(1L, "스타벅스")),
                List.of(Category.builder().id(5L).categoryName("카페/간식").parentId(1L).build()));

        assertEquals(1, result.size());
        assertNull(result.get(0).getCategoryId());
    }

    @Test
    @DisplayName("힌트가 있는 거래에만 directoryHint 를 붙이고, 시스템 프롬프트에 판단 기준을 싣는다")
    void sendsDirectoryHintOnlyForHintedTransactions() {
        DirectoryHint hint = DirectoryHint.builder()
                .matchType(DirectoryHint.EXACT).matchedName("성민식당").industryName("한식>백반/한정식")
                .suggestedCategoryId(13L).storeCount(3).share(new BigDecimal("1.000")).build();

        mockServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(request -> {
                    String body = request.getBody().toString();
                    assertTrue(body.contains("directoryHint"), "system prompt 에 directoryHint 설명이 있어야 한다");
                    assertTrue(body.contains("\\\"directoryHint\\\":{\\\"matchType\\\":\\\"EXACT\\\""),
                            "1번 거래에 힌트 객체가 직렬화돼야 한다: " + body);
                    assertTrue(body.contains("\\\"suggestedCategoryId\\\":13"));
                    assertTrue(body.contains("\\\"share\\\":1.0"));
                    assertEquals(1, body.split("\\\\\"directoryHint\\\\\":\\{").length - 1,
                            "힌트 없는 2번 거래에는 directoryHint 가 없어야 한다");
                })
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"{\\\"results\\\":[]}\"}}]}",
                        MediaType.APPLICATION_JSON));

        client.classify(List.of(tx(1L, "성민식당"), tx(2L, "정체불명")),
                List.of(Category.builder().id(13L).categoryName("음식점/외식").parentId(1L).build()),
                Map.of(1L, hint));

        mockServer.verify();
    }

    @Test
    @DisplayName("2-인자 classify 는 힌트 없이 동작한다")
    void twoArgClassifySendsNoHint() {
        mockServer.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(request -> {
                    String body = request.getBody().toString();
                    assertTrue(!body.contains("\\\"directoryHint\\\":{"), "힌트 객체가 없어야 한다");
                })
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"{\\\"results\\\":[]}\"}}]}",
                        MediaType.APPLICATION_JSON));

        client.classify(List.of(tx(1L, "성민식당")),
                List.of(Category.builder().id(13L).categoryName("음식점/외식").parentId(1L).build()));

        mockServer.verify();
    }
}
