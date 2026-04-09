package com.fanshop.member.service;

import com.fanshop.auth.JwtTokenProvider;
import com.fanshop.member.api.JoinMemberRequest;
import com.fanshop.member.api.LoginRequest;
import com.fanshop.member.api.LoginResponse;
import com.fanshop.member.api.MemberResponse;
import com.fanshop.member.domain.Member;
import com.fanshop.member.domain.MemberRepository;
import com.fanshop.support.error.CoreException;
import com.fanshop.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {

    private final MemberRepository memberRepository;

    private final PasswordEncoder passwordEncoder;

    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public MemberResponse join(JoinMemberRequest request) {
        memberRepository.findByEmail(request.getEmail()).ifPresent(m -> {
            throw new CoreException(ErrorType.DUPLICATE_EMAIL, request.getEmail());
        });
        Member saved = memberRepository.save(request.toEntity(passwordEncoder.encode(request.getPassword())));
        return MemberResponse.from(saved);
    }

    public MemberResponse getMember(Long memberId) {
        Member member = memberRepository.findById(memberId)
            .orElseThrow(() -> new CoreException(ErrorType.MEMBER_NOT_FOUND, memberId));
        return MemberResponse.from(member);
    }

    public LoginResponse login(LoginRequest request) {
        Member member = memberRepository.findByEmail(request.getEmail())
            .orElseThrow(() -> new CoreException(ErrorType.MEMBER_NOT_FOUND));
        if (!passwordEncoder.matches(request.getPassword(), member.getPassword())) {
            throw new CoreException(ErrorType.INVALID_PASSWORD);
        }
        String token = jwtTokenProvider.generate(member.getId(), member.getEmail());
        return new LoginResponse(token, member.getId());
    }

}
