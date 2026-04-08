package com.fanshop.member.api;

import com.fanshop.member.service.MemberService;
import com.fanshop.support.response.ApiResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

	private final MemberService memberService;

	@PostMapping("/join")
	public ApiResponse<MemberResponse> join(@RequestBody JoinMemberRequest request) {
		return ApiResponse.success(memberService.join(request));
	}

	@GetMapping("/{memberId}")
	public ApiResponse<MemberResponse> getMember(@PathVariable Long memberId) {
		return ApiResponse.success(memberService.getMember(memberId));
	}

}
