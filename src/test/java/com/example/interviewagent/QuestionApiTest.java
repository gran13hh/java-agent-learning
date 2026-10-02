package com.example.interviewagent;

import com.example.interviewagent.controller.QuestionController;
import com.example.interviewagent.exception.ApiExceptionHandler;
import com.example.interviewagent.service.QuestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class QuestionApiTest {
    private MockMvc mvc;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String QUESTION = """
            {"title":"  什么是依赖注入？  ","topic":"JAVA","difficulty":"EASY",
             "referenceAnswer":"  由容器提供对象所需要的依赖。  "}
            """;

    @BeforeEach
    void setUp() {
        // 使用真实 Controller/Service/校验器和 JSON 转换器，只有存储层换成内存实现。
        mvc = MockMvcBuilders.standaloneSetup(new QuestionController(
                        new QuestionService(new InMemoryQuestionRepository())))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    @Test
    void createReturnsLocationAndCanBeReadBack() throws Exception {
        var created = mvc.perform(post("/api/questions").contentType(MediaType.APPLICATION_JSON)
                .content(QUESTION)).andReturn();
        assertEquals(201, created.getResponse().getStatus());
        assertEquals("什么是依赖注入？", body(created).get("title").asText());
        assertEquals("由容器提供对象所需要的依赖。", body(created).get("referenceAnswer").asText());
        String location = created.getResponse().getHeader("Location");
        assertNotNull(location);
        var found = mvc.perform(get(location)).andReturn();
        assertEquals(200, found.getResponse().getStatus());
        assertEquals(body(created), body(found));
    }

    @Test
    void paginationAndTopicFilterPreserveTotal() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/questions").contentType(MediaType.APPLICATION_JSON).content(QUESTION));
        }
        mvc.perform(post("/api/questions").contentType(MediaType.APPLICATION_JSON)
                .content(QUESTION.replace("JAVA", "AGENT")));
        var response = mvc.perform(get("/api/questions?topic=JAVA&page=2&size=2")).andReturn();
        assertEquals(200, response.getResponse().getStatus());
        var page = body(response);
        assertEquals(3, page.get("total").asLong());
        assertEquals(1, page.get("items").size());
        assertEquals(1, page.get("items").get(0).get("id").asLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=0", "page=-1", "size=0", "size=101", "page=abc", "topic=UNKNOWN"})
    void rejectsInvalidQueryParameters(String query) throws Exception {
        var response = mvc.perform(get("/api/questions?" + query)).andReturn();
        assertEquals(400, response.getResponse().getStatus());
        assertEquals(400, body(response).get("status").asInt());
    }

    @Test
    void enormousPageDoesNotOverflowAndReturnsEmptyPage() throws Exception {
        var response = mvc.perform(get("/api/questions?page=2147483647&size=100")).andReturn();
        assertEquals(200, response.getResponse().getStatus());
        assertEquals(0, body(response).get("items").size());
    }

    @Test
    void blankTitleReturnsFieldErrorWithoutWriting() throws Exception {
        var response = mvc.perform(post("/api/questions").contentType(MediaType.APPLICATION_JSON)
                .content(QUESTION.replace("什么是依赖注入？", ""))).andReturn();
        assertEquals(400, response.getResponse().getStatus());
        assertEquals("title", body(response).get("errors").get(0).get("field").asText());
        assertEquals(0, body(mvc.perform(get("/api/questions")).andReturn()).get("total").asLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{", "{\"title\":\"x\",\"topic\":\"UNKNOWN\"}"})
    void malformedOrMissingInputReturns400(String input) throws Exception {
        assertEquals(400, mvc.perform(post("/api/questions").contentType(MediaType.APPLICATION_JSON)
                .content(input)).andReturn().getResponse().getStatus());
    }

    @Test
    void overlongTitleIsRejected() throws Exception {
        assertEquals(400, mvc.perform(post("/api/questions").contentType(MediaType.APPLICATION_JSON)
                .content(QUESTION.replace("什么是依赖注入？", "a".repeat(201))))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void missingQuestionReturns404AndNegativeIdReturns400() throws Exception {
        assertEquals(404, mvc.perform(get("/api/questions/999")).andReturn().getResponse().getStatus());
        assertEquals(400, mvc.perform(get("/api/questions/-1")).andReturn().getResponse().getStatus());
    }
}
