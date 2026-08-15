package com.fanshop.member.api;

import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessRequest;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fanshop.member.domain.Member;
import com.fanshop.member.domain.MemberRepository;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 실제 애플리케이션 컨텍스트 위에서 문서를 생성한다. standalone MockMvc를 쓰면 {@code WebConfig}의 경로 prefix와 API
 * 버저닝이 적용되지 않아 문서에 실제 호출 경로가 남지 않으므로, 전체 컨텍스트 기반으로 작성했다.
 */
@Tag("restdocs")
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureRestDocs
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class MemberApiDocsTest {

    private final MockMvc mockMvc;

    private final ObjectMapper objectMapper;

    private final MemberRepository memberRepository;

    private final PasswordEncoder passwordEncoder;

    MemberApiDocsTest(MockMvc mockMvc, ObjectMapper objectMapper, MemberRepository memberRepository,
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

    @Test
    @DisplayName("POST /api/v1/members/join 문서화")
    void memberJoin() throws Exception {
        JoinMemberRequest request = new JoinMemberRequest("fan@fanshop.com", "한봉훈", "성남시 분당구", "password!");

        mockMvc
            .perform(post("/api/v1/members/join").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andDo(document("member-join", preprocessRequest(prettyPrint()), preprocessResponse(prettyPrint()),
                    requestFields(fieldWithPath("email").type(JsonFieldType.STRING).description("이메일 (로그인 ID로 사용)"),
                            fieldWithPath("name").type(JsonFieldType.STRING).description("회원 이름"),
                            fieldWithPath("address").type(JsonFieldType.STRING).description("배송지 주소"),
                            fieldWithPath("password").type(JsonFieldType.STRING)
                                .description("비밀번호 (BCrypt로 암호화되어 저장)")),
                    responseFields(
                            fieldWithPath("result").type(JsonFieldType.STRING).description("처리 결과 (SUCCESS / ERROR)"),
                            fieldWithPath("data.id").type(JsonFieldType.NUMBER).description("생성된 회원 ID"),
                            fieldWithPath("data.email").type(JsonFieldType.STRING).description("이메일"),
                            fieldWithPath("data.name").type(JsonFieldType.STRING).description("회원 이름"),
                            fieldWithPath("error").type(JsonFieldType.NULL).description("에러 정보 (성공 시 null)"))));
    }

    @Test
    @DisplayName("POST /api/v1/members/login 문서화")
    void memberLogin() throws Exception {
        memberRepository.save(new Member("fan@fanshop.com", "한봉훈", "성남시 분당구", passwordEncoder.encode("password!")));
        LoginRequest request = new LoginRequest("fan@fanshop.com", "password!");

        mockMvc
            .perform(post("/api/v1/members/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andDo(document("member-login", preprocessRequest(prettyPrint()), preprocessResponse(prettyPrint()),
                    requestFields(fieldWithPath("email").type(JsonFieldType.STRING).description("가입 시 사용한 이메일"),
                            fieldWithPath("password").type(JsonFieldType.STRING).description("비밀번호")),
                    responseFields(
                            fieldWithPath("result").type(JsonFieldType.STRING).description("처리 결과 (SUCCESS / ERROR)"),
                            fieldWithPath("data.accessToken").type(JsonFieldType.STRING)
                                .description("JWT 액세스 토큰. 이후 요청의 Authorization 헤더에 `Bearer {token}` 형태로 담는다"),
                            fieldWithPath("data.memberId").type(JsonFieldType.NUMBER).description("로그인한 회원 ID"),
                            fieldWithPath("error").type(JsonFieldType.NULL).description("에러 정보 (성공 시 null)"))));
    }

    @Test
    @DisplayName("GET /api/v1/members/{memberId} 문서화")
    void memberGet() throws Exception {
        Member saved = memberRepository
            .save(new Member("fan@fanshop.com", "한봉훈", "성남시 분당구", passwordEncoder.encode("password!")));
        String token = loginAndGetToken("fan@fanshop.com", "password!");

        mockMvc
            .perform(get("/api/v1/members/{memberId}", saved.getId()).header(HttpHeaders.AUTHORIZATION,
                    "Bearer " + token))
            .andExpect(status().isOk())
            .andDo(document("member-get", preprocessRequest(prettyPrint()), preprocessResponse(prettyPrint()),
                    requestHeaders(
                            headerWithName(HttpHeaders.AUTHORIZATION).description("`Bearer {accessToken}` 형식의 JWT")),
                    pathParameters(parameterWithName("memberId").description("조회할 회원 ID")),
                    responseFields(
                            fieldWithPath("result").type(JsonFieldType.STRING).description("처리 결과 (SUCCESS / ERROR)"),
                            fieldWithPath("data.id").type(JsonFieldType.NUMBER).description("회원 ID"),
                            fieldWithPath("data.email").type(JsonFieldType.STRING).description("이메일"),
                            fieldWithPath("data.name").type(JsonFieldType.STRING).description("회원 이름"),
                            fieldWithPath("error").type(JsonFieldType.NULL).description("에러 정보 (성공 시 null)"))));
    }

    private String loginAndGetToken(String email, String password) throws Exception {
        MvcResult result = mockMvc
            .perform(post("/api/v1/members/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data").get("accessToken").asText();
    }

}
