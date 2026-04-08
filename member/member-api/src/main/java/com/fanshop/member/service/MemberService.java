package com.fanshop.member.service;

import com.fanshop.member.api.JoinMemberRequest;
import com.fanshop.member.api.MemberResponse;
import com.fanshop.member.domain.Member;
import com.fanshop.member.domain.MemberRepository;
import com.fanshop.support.error.CoreException;
import com.fanshop.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {

	private final MemberRepository memberRepository;

	@Transactional
	public MemberResponse join(JoinMemberRequest request) {
		memberRepository.findByEmail(request.getEmail()).ifPresent(m -> {
			throw new CoreException(ErrorType.DUPLICATE_EMAIL, request.getEmail());
		});
		Member saved = memberRepository.save(request.toEntity());
		return MemberResponse.from(saved);
	}

	public MemberResponse getMember(Long memberId) {
		Member member = memberRepository.findById(memberId)
			.orElseThrow(() -> new CoreException(ErrorType.MEMBER_NOT_FOUND, memberId));
		return MemberResponse.from(member);
	}

}
