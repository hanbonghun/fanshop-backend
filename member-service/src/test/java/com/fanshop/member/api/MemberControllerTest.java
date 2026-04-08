package com.fanshop.member.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fanshop.ContextTest;
import com.fanshop.member.domain.Member;
import com.fanshop.member.domain.MemberRepository;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class MemberControllerTest extends ContextTest {

	private final MockMvc mockMvc;

	private final ObjectMapper objectMapper;

	private final MemberRepository memberRepository;

	private final PasswordEncoder passwordEncoder;

	MemberControllerTest(MockMvc mockMvc, ObjectMapper objectMapper, MemberRepository memberRepository,
			PasswordEncoder passwordEncoder) {
		this.mockMvc = mockMvc;
		this.objectMapper = objectMapper;
		this.memberRepository = memberRepository;
		this.passwordEncoder = passwordEncoder;
	}

	@AfterEach
	void tearDown() {
		memberRepository.deleteAll();
	}

	private String loginAndGetToken(String email, String password) throws Exception {
		LoginRequest request = new LoginRequest(email, password);
		MvcResult result = mockMvc
			.perform(post("/api/v1/members/login").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isOk())
			.andReturn();
		String body = result.getResponse().getContentAsString();
		return objectMapper.readTree(body).get("data").get("accessToken").asText();
	}

	@Nested
	@DisplayName("POST /api/v1/members/join")
	class Join {

		@Test
		@DisplayName("유효한 요청이면 200 OK와 회원 정보를 반환하고 DB에 저장된다")
		void success() throws Exception {
			// given
			JoinMemberRequest request = new JoinMemberRequest("integration@email.com", "Integration User", "Pangyo",
					"password!");

			// when & then (HTTP 응답 검증)
			mockMvc
				.perform(post("/api/v1/members/join").contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.email").value(request.getEmail()))
				.andExpect(jsonPath("$.data.name").value(request.getName()));

			// then (DB 상태 검증)
			Member saved = memberRepository.findByEmail(request.getEmail()).orElse(null);
			assertThat(saved).isNotNull();
			assertThat(saved.getName()).isEqualTo(request.getName());
		}

		@Test
		@DisplayName("중복된 이메일이면 409 Conflict를 반환한다")
		void duplicateEmail() throws Exception {
			// given
			memberRepository
				.save(new Member("dup@email.com", "Existing User", "Seoul", passwordEncoder.encode("password!")));
			JoinMemberRequest request = new JoinMemberRequest("dup@email.com", "New User", "Busan", "password!");

			// when & then
			mockMvc
				.perform(post("/api/v1/members/join").contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isConflict());
		}

	}

	@Nested
	@DisplayName("POST /api/v1/members/login")
	class Login {

		@Test
		@DisplayName("올바른 이메일/비밀번호면 200 OK와 JWT를 반환한다")
		void success() throws Exception {
			// given
			memberRepository
				.save(new Member("login@email.com", "Login User", "Seoul", passwordEncoder.encode("password!")));
			LoginRequest request = new LoginRequest("login@email.com", "password!");

			// when & then
			mockMvc
				.perform(post("/api/v1/members/login").contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.data.memberId").isNumber());
		}

		@Test
		@DisplayName("존재하지 않는 이메일이면 404 Not Found를 반환한다")
		void memberNotFound() throws Exception {
			// given
			LoginRequest request = new LoginRequest("none@email.com", "password!");

			// when & then
			mockMvc
				.perform(post("/api/v1/members/login").contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isNotFound());
		}

		@Test
		@DisplayName("비밀번호가 틀리면 401 Unauthorized를 반환한다")
		void invalidPassword() throws Exception {
			// given
			memberRepository
				.save(new Member("login@email.com", "Login User", "Seoul", passwordEncoder.encode("password!")));
			LoginRequest request = new LoginRequest("login@email.com", "wrong!");

			// when & then
			mockMvc
				.perform(post("/api/v1/members/login").contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isUnauthorized());
		}

	}

	@Nested
	@DisplayName("GET /api/v1/members/{memberId}")
	class GetMember {

		@Test
		@DisplayName("유효한 JWT로 존재하는 회원 ID면 200 OK와 회원 정보를 반환한다")
		void success() throws Exception {
			// given
			memberRepository
				.save(new Member("find@email.com", "Find User", "Incheon", passwordEncoder.encode("password!")));
			String token = loginAndGetToken("find@email.com", "password!");
			Member saved = memberRepository.findByEmail("find@email.com").get();

			// when & then
			mockMvc
				.perform(get("/api/v1/members/{memberId}", saved.getId())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.email").value(saved.getEmail()))
				.andExpect(jsonPath("$.data.name").value(saved.getName()));
		}

		@Test
		@DisplayName("존재하지 않는 회원 ID면 404 Not Found를 반환한다")
		void notFound() throws Exception {
			// given: 인증을 위해 먼저 회원을 가입하고 토큰을 발급받는다
			memberRepository
				.save(new Member("auth@email.com", "Auth User", "Seoul", passwordEncoder.encode("password!")));
			String token = loginAndGetToken("auth@email.com", "password!");

			// when & then
			mockMvc
				.perform(get("/api/v1/members/{memberId}", 999L)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isNotFound());
		}

	}

}
