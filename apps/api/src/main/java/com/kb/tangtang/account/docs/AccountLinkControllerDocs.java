package com.kb.tangtang.account.docs;

import com.kb.tangtang.account.dto.AuthMethodListDto;
import com.kb.tangtang.account.dto.AuthStatusDto;
import com.kb.tangtang.account.dto.ConnectedAccountListDto;
import com.kb.tangtang.account.dto.ConnectionResponseDto;
import com.kb.tangtang.account.dto.DisconnectResultDto;
import com.kb.tangtang.account.dto.ExtraAuthRequestDto;
import com.kb.tangtang.account.dto.InstitutionListDto;
import com.kb.tangtang.account.dto.InstitutionLoginRequestDto;
import com.kb.tangtang.account.dto.LinkProgressDto;
import com.kb.tangtang.account.dto.LinkRequestDto;
import com.kb.tangtang.account.dto.LinkResultDto;
import com.kb.tangtang.account.dto.LinkableGroupDto;
import com.kb.tangtang.account.dto.RefreshResultDto;
import com.kb.tangtang.account.dto.ResyncResultDto;
import com.kb.tangtang.account.dto.SimpleAuthRequestDto;
import com.kb.tangtang.common.docs.SwaggerTags;
import com.kb.tangtang.common.dto.ApiResponse;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import springfox.documentation.annotations.ApiIgnore;

import java.util.List;

/**
 * {@code AccountLinkController} 의 Swagger 문서.
 *
 * <p>연동은 <b>기관 선택 → 인증 → 조회 진행 → 계좌 선택 → 연결 저장</b> 5단계로 진행되며,
 * 아래 순서가 그 흐름 그대로다. 프론트 {@code apps/web/src/api/account.js} 의 함수 13종과 1:1 이다.
 */
@Api(tags = SwaggerTags.ACCOUNT)
public interface AccountLinkControllerDocs {

    /* ── 1단계: 기관 선택 ───────────────────────────────── */

    @ApiOperation(value = "[1단계] 연동 가능 기관 목록",
            notes = "은행·카드·증권·대출·페이머니 5종 목록. 이미 연동한 기관도 함께 내려간다.")
    ApiResponse<InstitutionListDto> institutions(@ApiIgnore Long userId);

    /* ── 2단계: 인증 ────────────────────────────────────── */

    @ApiOperation(value = "[2단계] 지원 인증 수단 목록",
            notes = "**프론트 분기의 유일한 근거다.** 간편인증과 기관 로그인 중 무엇을 띄울지 여기서 정해진다. "
                    + "hybrid 모드에서는 사용자마다(목서버/실 CODEF) 결과가 다르다.")
    ApiResponse<AuthMethodListDto> authMethods(@ApiIgnore Long userId);

    @ApiOperation(value = "[2단계] 간편인증 요청",
            notes = "카카오·네이버 등 간편인증 앱으로 인증 요청을 보낸다. "
                    + "요청 직후에는 아직 승인 전이므로 `auth-status` 로 폴링해야 한다.")
    ApiResponse<ConnectionResponseDto> simpleAuth(@ApiIgnore Long userId,
                                                  @ApiParam(value = "기관 코드 · 실명 · 생년월일 · 통신사 · 휴대폰번호", required = true)
                                                  SimpleAuthRequestDto request);

    @ApiOperation(value = "[2단계] 기관 로그인 인증",
            notes = "기관 홈페이지 아이디·비밀번호로 인증한다. 간편인증을 지원하지 않는 기관용이다.")
    ApiResponse<ConnectionResponseDto> connect(@ApiIgnore Long userId,
                                               @ApiParam(value = "기관 코드 · 아이디 · 비밀번호", required = true)
                                               InstitutionLoginRequestDto request);

    @ApiOperation(value = "[2단계] 인증 승인 여부 폴링",
            notes = "간편인증 앱에서 사용자가 승인했는지 확인한다. **승인될 때까지 주기적으로 호출한다.**")
    ApiResponse<AuthStatusDto> authStatus(@ApiIgnore Long userId,
                                          @ApiParam(value = "인증 요청 시 받은 연결 ID", required = true) String connectionId);

    @ApiOperation(value = "[2단계] 2-way 추가인증 제출",
            notes = "기관이 추가 정보를 요구할 때만 호출한다(ARS·보안문자 등).")
    ApiResponse<ConnectionResponseDto> extraAuth(@ApiIgnore Long userId,
                                                 @ApiParam(value = "연결 ID", required = true) String connectionId,
                                                 @ApiParam(value = "기관이 요구한 추가 인증값", required = true)
                                                 ExtraAuthRequestDto request);

    /* ── 3단계: 조회 진행 ───────────────────────────────── */

    @ApiOperation(value = "[3단계] 계좌 조회 진행률",
            notes = "인증 통과 후 기관에서 계좌 목록을 긁어오는 동안의 진행 상태다. 진행률 화면이 폴링한다.")
    ApiResponse<LinkProgressDto> progress(@ApiIgnore Long userId,
                                          @ApiParam(value = "연결 ID", required = true) String connectionId);

    /* ── 4단계: 계좌 선택 ───────────────────────────────── */

    @ApiOperation(value = "[4단계] 연결 가능한 계좌 목록",
            notes = "사용자가 체크박스로 고를 목록이다. 입출금·예적금·카드 등 그룹으로 묶여 내려간다.")
    ApiResponse<List<LinkableGroupDto>> linkableAccounts(@ApiIgnore Long userId,
                                                         @ApiParam(value = "연결 ID", required = true) String connectionId);

    /* ── 5단계: 연결 저장 ───────────────────────────────── */

    @ApiOperation(value = "[5단계] 선택한 계좌 연결 저장",
            notes = "여기까지 와야 거래내역 수집이 시작된다. 연결 저장과 동시에 초기 수집이 걸린다.")
    ApiResponse<LinkResultDto> link(@ApiIgnore Long userId,
                                    @ApiParam(value = "연결 ID 와 선택한 계좌 목록", required = true) LinkRequestDto request);

    /* ── 연결 계좌 관리 ─────────────────────────────────── */

    @ApiOperation(value = "연동된 계좌 목록", notes = "계좌 관리 화면이 쓴다. 재인증이 필요한 계좌도 상태로 구분된다.")
    ApiResponse<ConnectedAccountListDto> connectedAccounts(@ApiIgnore Long userId);

    @ApiOperation(value = "전체 계좌 거래내역 새로고침",
            notes = "연동된 **모든** 계좌의 거래내역을 다시 수집한다. 개별 계좌는 아래 `resync` 를 쓴다.")
    ApiResponse<RefreshResultDto> refresh(@ApiIgnore Long userId);

    @ApiOperation(value = "계좌 연동 해제")
    ApiResponse<DisconnectResultDto> disconnect(@ApiIgnore Long userId,
                                                @ApiParam(value = "연동 계좌 ID", required = true) long accountId);

    @ApiOperation(value = "개별 계좌 거래내역 재수집",
            notes = "해당 계좌만 다시 수집한다. 재인증이 필요한 상태면 실패한다.")
    ApiResponse<ResyncResultDto> resync(@ApiIgnore Long userId,
                                        @ApiParam(value = "연동 계좌 ID", required = true) long accountId);
}
