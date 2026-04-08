package com.fanshop.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.fanshop.auth.JwtTokenProvider;
import com.fanshop.member.api.JoinMemberRequest;
import com.fanshop.member.api.LoginRequest;
import com.fanshop.member.api.LoginResponse;
import com.fanshop.member.api.MemberResponse;
import com.fanshop.member.domain.Member;
import com.fanshop.member.domain.MemberRepository;
import com.fanshop.support.error.CoreException;
import com.fanshop.support.error.ErrorType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private PasswordEncoder passwordEncoder;

	@Mock
	private JwtTokenProvider jwtTokenProvider;

	@InjectMocks
	private MemberService memberService;

	@Nested
	@DisplayName("join (회원 가입)")
	class Join {

		@Test
		@DisplayName("유효한 요청이면 회원을 저장하고 응답을 반환한다")
		void success() {
			// given
			JoinMemberRequest request = new JoinMemberRequest("test@email.com", "Test User", "Pangyo", "password!");
			Member member = new Member("test@email.com", "Test User", "Pangyo", "encoded");
			given(memberRepository.findByEmail(request.getEmail())).willReturn(Optional.empty());
			given(passwordEncoder.encode(request.getPassword())).willReturn("encoded");
			given(memberRepository.save(any(Member.class))).willReturn(member);

			// when
			MemberResponse response = memberService.join(request);

			// then
			assertThat(response.getEmail()).isEqualTo(request.getEmail());
			assertThat(response.getName()).isEqualTo(request.getName());
			verify(memberRepository).save(any(Member.class));
		}

		@Test
		@DisplayName("중복된 이메일이면 DUPLICATE_EMAIL 예외를 던진다")
		void duplicateEmail() {
			// given
			JoinMemberRequest request = new JoinMemberRequest("dup@email.com", "User", "Seoul", "password!");
			given(memberRepository.findByEmail(request.getEmail()))
				.willReturn(Optional.of(new Member("dup@email.com", "User", "Seoul", "encoded")));

			// when & then
			assertThatThrownBy(() -> memberService.join(request))
				.isInstanceOf(CoreException.class)
				.satisfies(e -> assertThat(((CoreException) e).getErrorType())
					.isEqualTo(ErrorType.DUPLICATE_EMAIL));
		}

	}

	@Nested
	@DisplayName("login (로그인)")
	class Login {

		@Test
		@DisplayName("올바른 이메일/비밀번호면 JWT를 반환한다")
		void success() {
			// given
			LoginRequest request = new LoginRequest("test@email.com", "password!");
			Member member = new Member("test@email.com", "Test User", "Pangyo", "encoded");
			given(memberRepository.findByEmail(request.getEmail())).willReturn(Optional.of(member));
			given(passwordEncoder.matches(request.getPassword(), member.getPassword())).willReturn(true);
			given(jwtTokenProvider.generate(any(), anyString())).willReturn("jwt-token");

			// when
			LoginResponse response = memberService.login(request);

			// then
			assertThat(response.getAccessToken()).isEqualTo("jwt-token");
		}

		@Test
		@DisplayName("존재하지 않는 이메일이면 MEMBER_NOT_FOUND 예외를 던진다")
		void memberNotFound() {
			// given
			LoginRequest request = new LoginRequest("none@email.com", "password!");
			given(memberRepository.findByEmail(request.getEmail())).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> memberService.login(request))
				.isInstanceOf(CoreException.class)
				.satisfies(e -> assertThat(((CoreException) e).getErrorType())
					.isEqualTo(ErrorType.MEMBER_NOT_FOUND));
		}

		@Test
		@DisplayName("비밀번호가 틀리면 INVALID_PASSWORD 예외를 던진다")
		void invalidPassword() {
			// given
			LoginRequest request = new LoginRequest("test@email.com", "wrong!");
			Member member = new Member("test@email.com", "Test User", "Pangyo", "encoded");
			given(memberRepository.findByEmail(request.getEmail())).willReturn(Optional.of(member));
			given(passwordEncoder.matches(request.getPassword(), member.getPassword())).willReturn(false);

			// when & then
			assertThatThrownBy(() -> memberService.login(request))
				.isInstanceOf(CoreException.class)
				.satisfies(e -> assertThat(((CoreException) e).getErrorType())
					.isEqualTo(ErrorType.INVALID_PASSWORD));
		}

	}

	@Nested
	@DisplayName("getMember (회원 조회)")
	class GetMember {

		@Test
		@DisplayName("존재하는 회원 ID면 회원 정보를 반환한다")
		void success() {
			// given
			Long memberId = 1L;
			Member member = new Member("test@email.com", "Test User", "Pangyo", "encoded");
			given(memberRepository.findById(memberId)).willReturn(Optional.of(member));

			// when
			MemberResponse response = memberService.getMember(memberId);

			// then
			assertThat(response.getEmail()).isEqualTo(member.getEmail());
			assertThat(response.getName()).isEqualTo(member.getName());
		}

		@Test
		@DisplayName("존재하지 않는 회원 ID면 MEMBER_NOT_FOUND 예외를 던진다")
		void notFound() {
			// given
			given(memberRepository.findById(999L)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> memberService.getMember(999L))
				.isInstanceOf(CoreException.class)
				.satisfies(e -> assertThat(((CoreException) e).getErrorType())
					.isEqualTo(ErrorType.MEMBER_NOT_FOUND));
		}

	}

}
