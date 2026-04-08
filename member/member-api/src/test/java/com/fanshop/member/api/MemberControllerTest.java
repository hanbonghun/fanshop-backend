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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class MemberControllerTest extends ContextTest {

	private final MockMvc mockMvc;

	private final ObjectMapper objectMapper;

	private final MemberRepository memberRepository;

	MemberControllerTest(MockMvc mockMvc, ObjectMapper objectMapper, MemberRepository memberRepository) {
		this.mockMvc = mockMvc;
		this.objectMapper = objectMapper;
		this.memberRepository = memberRepository;
	}

	@AfterEach
	void tearDown() {
		memberRepository.deleteAll();
	}

	@Nested
	@DisplayName("POST /api/v1/members/join")
	class Join {

		@Test
		@DisplayName("유효한 요청이면 200 OK와 회원 정보를 반환하고 DB에 저장된다")
		void success() throws Exception {
			// given
			JoinMemberRequest request = new JoinMemberRequest("integration@email.com", "Integration User", "Pangyo");

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
			memberRepository.save(new Member("dup@email.com", "Existing User", "Seoul"));
			JoinMemberRequest request = new JoinMemberRequest("dup@email.com", "New User", "Busan");

			// when & then
			mockMvc
				.perform(post("/api/v1/members/join").contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isConflict());
		}

	}

	@Nested
	@DisplayName("GET /api/v1/members/{memberId}")
	class GetMember {

		@Test
		@DisplayName("존재하는 회원 ID면 200 OK와 회원 정보를 반환한다")
		void success() throws Exception {
			// given
			Member saved = memberRepository.save(new Member("find@email.com", "Find User", "Incheon"));

			// when & then
			mockMvc.perform(get("/api/v1/members/{memberId}", saved.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.email").value(saved.getEmail()))
				.andExpect(jsonPath("$.data.name").value(saved.getName()));
		}

		@Test
		@DisplayName("존재하지 않는 회원 ID면 404 Not Found를 반환한다")
		void notFound() throws Exception {
			// when & then
			mockMvc.perform(get("/api/v1/members/{memberId}", 999L)).andExpect(status().isNotFound());
		}

	}

}
