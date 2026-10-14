package com.kb.tangtang.account.controller;

import com.kb.tangtang.account.docs.AccountLinkControllerDocs;
import com.kb.tangtang.account.dto.*;
import com.kb.tangtang.account.service.AccountLinkService;
import com.kb.tangtang.common.auth.LoginUser;
import com.kb.tangtang.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 계좌 연동 (이슈 #12).
 *
 * 사용자 식별은 @LoginUser 로만 한다. 요청으로 userId 를 받으면 남의 계좌에 접근할 수 있다.
 * JwtAuthInterceptor 가 /api/** 를 보호하므로 별도 설정 없이 인증이 걸린다.
 *
 * 엔드포인트 구성은 프론트 `apps/web/src/api/account.js` 의 함수 13종과 1:1 이다.
 * 응답은 전부 ApiResponse 로 감싼다 (raw 객체 반환 금지).
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountLinkController implements AccountLinkControllerDocs {

    private final AccountLinkService accountLinkService;

    public AccountLinkController(AccountLinkService accountLinkService) {
        this.accountLinkService = accountLinkService;
    }

    /* ── 1단계: 기관 선택 ───────────────────────────────── */

    @GetMapping("/institutions")
    public ApiResponse<InstitutionListDto> institutions(@LoginUser Long userId) {
        return ApiResponse.ok(accountLinkService.institutions(userId));
    }

    /* ── 2단계: 인증 ────────────────────────────────────── */

    /** 지원 인증 수단. 프론트 분기의 유일한 근거다. */
    @GetMapping("/link/auth-methods")
    public ApiResponse<AuthMethodListDto> authMethods(@LoginUser Long userId) {
        return ApiResponse.ok(accountLinkService.authMethods(userId));
    }

    /** 간편인증 요청 */
    @PostMapping("/connections/simple-auth")
    public ApiResponse<ConnectionResponseDto> simpleAuth(@LoginUser Long userId,
                                                         @RequestBody SimpleAuthRequestDto request) {
        return ApiResponse.ok(accountLinkService.requestSimpleAuth(userId, request));
    }

    /** 기관 로그인 인증 */
    @PostMapping("/connections")
    public ApiResponse<ConnectionResponseDto> connect(@LoginUser Long userId,
                                                      @RequestBody InstitutionLoginRequestDto request) {
        return ApiResponse.ok(accountLinkService.requestInstitutionLogin(userId, request));
    }

    /** 인증 승인 여부 폴링 */
    @GetMapping("/connections/{connectionId}/auth-status")
    public ApiResponse<AuthStatusDto> authStatus(@LoginUser Long userId,
                                                 @PathVariable String connectionId) {
        return ApiResponse.ok(accountLinkService.authStatus(userId, connectionId));
    }

    /** 2-way 추가인증 제출 */
    @PostMapping("/connections/{connectionId}/extra-auth")
    public ApiResponse<ConnectionResponseDto> extraAuth(@LoginUser Long userId,
                                                        @PathVariable String connectionId,
                                                        @RequestBody ExtraAuthRequestDto request) {
        return ApiResponse.ok(accountLinkService.submitExtraAuth(userId, connectionId, request));
    }

    /* ── 3단계: 조회 진행 ───────────────────────────────── */

    @GetMapping("/connections/{connectionId}/progress")
    public ApiResponse<LinkProgressDto> progress(@LoginUser Long userId,
                                                 @PathVariable String connectionId) {
        return ApiResponse.ok(accountLinkService.progress(userId, connectionId));
    }

    /* ── 4단계: 계좌 선택 ───────────────────────────────── */

    @GetMapping("/connections/{connectionId}/accounts")
    public ApiResponse<List<LinkableGroupDto>> linkableAccounts(@LoginUser Long userId,
                                                                @PathVariable String connectionId) {
        return ApiResponse.ok(accountLinkService.linkableAccounts(userId, connectionId));
    }

    /* ── 5단계: 연결 저장 ───────────────────────────────── */

    @PostMapping
    public ApiResponse<LinkResultDto> link(@LoginUser Long userId,
                                           @RequestBody LinkRequestDto request) {
        return ApiResponse.ok(accountLinkService.link(userId, request));
    }

    /* ── 연결 계좌 관리 ─────────────────────────────────── */

    @GetMapping
    public ApiResponse<ConnectedAccountListDto> connectedAccounts(@LoginUser Long userId) {
        return ApiResponse.ok(accountLinkService.connectedAccounts(userId));
    }

    @PostMapping("/refresh")
    public ApiResponse<RefreshResultDto> refresh(@LoginUser Long userId) {
        return ApiResponse.ok(accountLinkService.refresh(userId));
    }

    @DeleteMapping("/{accountId}")
    public ApiResponse<DisconnectResultDto> disconnect(@LoginUser Long userId,
                                                       @PathVariable long accountId) {
        return ApiResponse.ok(accountLinkService.disconnect(userId, accountId));
    }

    @PostMapping("/{accountId}/resync")
    public ApiResponse<ResyncResultDto> resync(@LoginUser Long userId,
                                               @PathVariable long accountId) {
        return ApiResponse.ok(accountLinkService.resync(userId, accountId));
    }
}
