package com.kb.tangtang.account.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kb.tangtang.common.exception.BusinessException;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CODEF 공통 HTTP 계층 — 토큰 발급 · 요청 · URL 디코딩 · 결과코드 판정.
 *
 * 계좌 연동(CodefFinancialDataClient)과 거래내역 동기화(CodefFinancialSyncClient)가 같이 쓴다.
 * 예전에는 연동 클라이언트 안에 있었는데, 동기화가 붙으면서 두 벌이 되지 않게 여기로 뺐다.
 *
 * **토큰을 캐시한다.** CODEF 액세스 토큰은 7일 유효한데 예전에는 요청마다 새로 받았다 —
 * 연동은 몇 번이라 상관없었지만, 동기화는 한 사용자당 계좌·카드 수만큼 호출이 이어져 토큰 발급이
 * 호출 수를 두 배로 만든다(CODEF 는 과다 호출 시 IP 를 차단한다고 경고한다). 6일이 지나거나 401 을
 * 받으면 버리고 다시 받는다.
 */
@Log4j2
public class CodefHttpClient {

    static final String TOKEN_URL = "https://oauth.codef.io/oauth/token";

    /** 정상 응답 코드. */
    static final String SUCCESS = "CF-00000";

    /** 추가 인증이 필요하다는 응답 코드. 오류가 아니라 정상 흐름이다. */
    static final String CONTINUE_TWO_WAY = "CF-03002";

    /** 기관이 그 로그인 방식을 지원하지 않을 때 CODEF 가 주는 코드. */
    private static final String UNSUPPORTED_LOGIN_TYPE = "CF-11021";

    /** 7일 유효 토큰을 하루 일찍 버린다 — 만료 직전 요청이 중간에 끊기지 않게. */
    private static final Duration TOKEN_TTL = Duration.ofDays(6);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestTemplate restTemplate;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;

    private String cachedToken;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public CodefHttpClient(RestTemplate restTemplate, String clientId, String clientSecret) {
        this(restTemplate, clientId, clientSecret, Clock.systemUTC());
    }

    CodefHttpClient(RestTemplate restTemplate, String clientId, String clientSecret, Clock clock) {
        this.restTemplate = restTemplate;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
    }

    /**
     * CODEF 상품 호출. 응답은 URL 디코딩·JSON 파싱을 거친 최상위 Map(result · data)이다.
     * `CF-00000`·`CF-03002` 가 아니면 사용자가 다음 행동을 알 수 있는 {@link BusinessException} 으로 바꿔 던진다.
     */
    public Map<String, Object> post(String url, Map<String, Object> body) {
        try {
            return send(url, body, accessToken());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.UNAUTHORIZED) {
                throw unavailable();
            }
            /* 토큰이 CODEF 쪽에서 먼저 무효가 됐다. 한 번만 새로 받아 다시 보낸다. */
            invalidateToken();
            try {
                return send(url, body, accessToken());
            } catch (RestClientException retry) {
                throw unavailable();
            }
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    private Map<String, Object> send(String url, Map<String, Object> body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);

        String raw = restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("EXTERNAL_API_ERROR", "금융기관 연동에 실패했어요.");
        }
        /* CODEF 는 응답 본문을 URL 인코딩해 내려준다. SDK 를 쓰지 않으므로 여기서 디코딩한다. */
        return parse(URLDecoder.decode(raw, StandardCharsets.UTF_8));
    }

    private synchronized String accessToken() {
        if (cachedToken != null && clock.instant().isBefore(tokenExpiresAt)) {
            return cachedToken;
        }
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new BusinessException("EXTERNAL_API_ERROR",
                    "CODEF 연동 설정이 없어요. financial.codef.client-id 를 확인해 주세요.");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8)));

        try {
            Map<?, ?> token = restTemplate.postForObject(
                    TOKEN_URL + "?grant_type=client_credentials&scope=read",
                    new HttpEntity<>(headers), Map.class);
            String value = token == null ? null : string(token.get("access_token"));
            if (value == null) {
                throw new BusinessException("TOKEN_EXPIRED", "인증이 만료됐어요. 다시 연결해 주세요.");
            }
            cachedToken = value;
            tokenExpiresAt = clock.instant().plus(TOKEN_TTL);
            return value;
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    private synchronized void invalidateToken() {
        cachedToken = null;
        tokenExpiresAt = Instant.EPOCH;
    }

    private static Map<String, Object> parse(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(json, HashMap.class);
            Map<String, Object> result = asMap(parsed.get("result"));
            String code = string(result.get("code"));
            /*
             * CF-03002 는 오류가 아니라 **정상 흐름**이다 — "사용자의 승인을 기다리는 중".
             * 여기서 예외로 던지면 간편인증이 시작조차 못 한다. 호출부가 상태로 다룬다.
             */
            if (code != null && !SUCCESS.equals(code) && !CONTINUE_TWO_WAY.equals(code)) {
                logFailure(code, result, parsed);
                throw failure(result, parsed);
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw new BusinessException("EXTERNAL_API_ERROR", "금융기관 응답을 읽지 못했어요.");
        }
    }

    /**
     * CODEF 실패를 사용자가 **무엇을 하면 되는지 아는** 오류로 바꾼다.
     *
     * 최상위 메시지("사용자 계정정보 등록에 실패했습니다")는 화면에 띄워도 다음 행동이 나오지 않는다.
     * 원인은 `errorList` 에 있고, 그중 `CF-11021`(기관과 로그인 방식 불일치)은 우리가 정확히 안내할 수 있다 —
     * 그 기관은 간편인증을 지원하지 않으니 금융기관 로그인으로 가면 된다.
     * 나머지는 CODEF 메시지를 그대로 전한다(지어내지 않는다).
     */
    static BusinessException failure(Map<String, Object> result, Map<String, Object> parsed) {
        for (Object item : asList(asMap(parsed.get("data")).get("errorList"))) {
            if (UNSUPPORTED_LOGIN_TYPE.equals(string(asMap(item).get("code")))) {
                return new BusinessException("AUTH_METHOD_UNSUPPORTED",
                        "이 금융기관은 선택한 인증 방식을 지원하지 않아요. 금융기관 로그인으로 연결해 주세요.");
            }
        }
        String message = string(result.get("message"));
        return new BusinessException("EXTERNAL_API_ERROR",
                message == null ? "금융기관 연동에 실패했어요." : message);
    }

    /**
     * CODEF 가 실패를 돌려줬을 때 **원인을 찾을 수 있을 만큼만** 남긴다.
     *
     * 최상위 `result.message` 는 뭉뚱그려 오고, 진짜 이유는 `data.errorList[].code/message` 에
     * 기관 단위로 들어 있다.
     *
     * ⚠ 응답 본문 전체를 찍지 않는다 — 이름·계좌번호 같은 개인정보가 섞여 있다.
     *   여기서 남기는 것은 코드·메시지·기관코드뿐이다.
     */
    private static void logFailure(String code, Map<String, Object> result, Map<String, Object> parsed) {
        log.warn("CODEF 응답 실패 code={} message={}", code, string(result.get("message")));
        for (Object item : asList(asMap(parsed.get("data")).get("errorList"))) {
            Map<String, Object> error = asMap(item);
            log.warn("  errorList organization={} code={} message={} extra={}",
                    string(error.get("organization")),
                    string(error.get("code")),
                    string(error.get("message")),
                    string(error.get("extraMessage")));
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException("EXTERNAL_API_UNAVAILABLE",
                "금융기관이 점검 중이에요. 잠시 후 다시 시도해 주세요.");
    }

    /* ── CODEF 응답 읽기 도우미 (연동·동기화 공용) ───────── */

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    /**
     * CODEF 는 **단건이면 객체, 다건이면 배열**로 준다(상품 문서 「출력부」 공통 안내).
     * 배열만 기대하면 계좌가 하나뿐인 사용자의 데이터가 통째로 사라진다 — 객체면 한 건짜리 목록으로 감싼다.
     */
    public static List<?> asList(Object value) {
        if (value instanceof List) {
            return (List<?>) value;
        }
        if (value instanceof Map && !((Map<?, ?>) value).isEmpty()) {
            return List.of(value);
        }
        return List.of();
    }

    public static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
