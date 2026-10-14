package com.kb.tangtang.account.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kb.tangtang.account.dto.AuthMethodDto;
import com.kb.tangtang.account.dto.AuthMethodListDto;
import com.kb.tangtang.account.dto.ConnectionResponseDto;
import com.kb.tangtang.account.dto.LinkProgressDto;
import com.kb.tangtang.account.dto.ProgressRowDto;
import com.kb.tangtang.account.dto.ProviderDto;
import com.kb.tangtang.account.dto.SimpleAuthRequestDto;
import com.kb.tangtang.account.service.AccountLinkService;
import com.kb.tangtang.common.exception.BusinessException;
import com.kb.tangtang.common.exception.CommonExceptionAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 계좌 연동 엔드포인트 검증.
 * 응답이 ApiResponse 로 감싸지는지, 업무 예외가 code/message 로 내려가는지를 본다.
 */
@ExtendWith(MockitoExtension.class)
class AccountLinkControllerTest {

    private static final long USER_ID = 1L;

    @Mock private AccountLinkService accountLinkService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * standaloneSetup 에는 LoginUserArgumentResolver 가 없다.
     * 인터셉터도 타지 않으므로 @LoginUser 자리에 고정 사용자 ID 를 넣어주는 리졸버를 직접 단다.
     */
    @BeforeEach
    void setUp() {
        HandlerMethodArgumentResolver loginUser = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(org.springframework.core.MethodParameter parameter) {
                return parameter.hasParameterAnnotation(com.kb.tangtang.common.auth.LoginUser.class);
            }

            @Override
            public Object resolveArgument(org.springframework.core.MethodParameter parameter,
                                          ModelAndViewContainer container,
                                          org.springframework.web.context.request.NativeWebRequest request,
                                          org.springframework.web.bind.support.WebDataBinderFactory factory) {
                return USER_ID;
            }
        };

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AccountLinkController(accountLinkService))
                .setCustomArgumentResolvers(loginUser)
                .setControllerAdvice(new CommonExceptionAdvice())
                .build();
    }

    @Test
    @DisplayName("인증 수단 목록을 ApiResponse 로 감싸 내려준다")
    void authMethods() throws Exception {
        when(accountLinkService.authMethods(anyLong())).thenReturn(AuthMethodListDto.builder()
                .methods(List.of(AuthMethodDto.builder()
                        .type("SIMPLE_AUTH")
                        .providers(List.of(ProviderDto.builder().code("KAKAO").name("카카오톡").build()))
                        .build()))
                .build());

        mockMvc.perform(get("/api/accounts/link/auth-methods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.methods[0].type").value("SIMPLE_AUTH"))
                .andExpect(jsonPath("$.data.methods[0].providers[0].name").value("카카오톡"));
    }

    @Test
    @DisplayName("간편인증 요청 결과를 그대로 내려준다")
    void simpleAuth() throws Exception {
        when(accountLinkService.requestSimpleAuth(anyLong(), any()))
                .thenReturn(ConnectionResponseDto.builder()
                        .connectionId("conn-1")
                        .status("PENDING")
                        .expiresInSeconds(300)
                        .needsExtraAuth(false)
                        .build());

        SimpleAuthRequestDto request = new SimpleAuthRequestDto();
        request.setProvider("KAKAO");
        request.setOrganizations(List.of("0004"));

        mockMvc.perform(post("/api/accounts/connections/simple-auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectionId").value("conn-1"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300));
    }

    @Test
    @DisplayName("진행 상황은 퍼센트와 기관별 상태를 함께 내려준다")
    void progress() throws Exception {
        when(accountLinkService.progress(anyLong(), anyString()))
                .thenReturn(LinkProgressDto.builder()
                        .percent(50)
                        .done(false)
                        .institutions(List.of(ProgressRowDto.builder()
                                .code("0004").name("KB국민은행").status("DONE").build()))
                        .build());

        mockMvc.perform(get("/api/accounts/connections/conn-1/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.percent").value(50))
                .andExpect(jsonPath("$.data.done").value(false))
                .andExpect(jsonPath("$.data.institutions[0].status").value("DONE"));
    }

    @Test
    @DisplayName("업무 예외는 400 + code/message 로 내려간다")
    void businessExceptionBecomes400() throws Exception {
        when(accountLinkService.progress(anyLong(), anyString()))
                .thenThrow(new BusinessException("CONNECTION_NOT_FOUND",
                        "연결 정보를 찾을 수 없어요. 처음부터 다시 시도해 주세요."));

        mockMvc.perform(get("/api/accounts/connections/없는값/progress"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("CONNECTION_NOT_FOUND"));
    }
}
