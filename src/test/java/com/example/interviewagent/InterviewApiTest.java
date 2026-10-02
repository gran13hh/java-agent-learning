package com.example.interviewagent;

import com.example.interviewagent.controller.InterviewController;
import com.example.interviewagent.domain.*;
import com.example.interviewagent.dto.CreateQuestionRequest;
import com.example.interviewagent.exception.ApiExceptionHandler;
import com.example.interviewagent.service.*;
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

class InterviewApiTest {
    private MockMvc mvc;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        var questions = new InMemoryQuestionRepository();
        for (int i = 0; i < 2; i++) questions.insert(new CreateQuestionRequest("题目" + i, Topic.JAVA, Difficulty.EASY, "保密参考" + i));
        mvc = MockMvcBuilders.standaloneSetup(new InterviewController(new InterviewService(
                new InMemoryInterviewRepository(), questions, new MockAnswerFeedbackProvider())))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private JsonNode body(MvcResult result) { return json.readTree(result.getResponse().getContentAsByteArray()); }
    private MvcResult create(String input) throws Exception {
        return mvc.perform(post("/api/interviews").contentType(MediaType.APPLICATION_JSON).content(input)).andReturn();
    }
    private MvcResult answer(long id, long turn, String answer) throws Exception {
        return mvc.perform(post("/api/interviews/" + id + "/answers").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("turnId", turn, "answer", answer)))).andReturn();
    }

    @Test
    void completePracticeHidesReferencesUntilAnsweredAndPersistsReview() throws Exception {
        var created = create("{\"topic\":\"JAVA\",\"questionCount\":2}");
        assertEquals(201, created.getResponse().getStatus());
        assertEquals("/api/interviews/1", created.getResponse().getHeader("Location"));
        var initial = body(created);
        long first = initial.get("turns").get(0).get("id").asLong();
        long second = initial.get("turns").get(1).get("id").asLong();
        assertFalse(created.getResponse().getContentAsString().contains("保密参考"));
        assertTrue(initial.get("turns").get(0).get("referenceAnswer").isNull());
        assertEquals(409, answer(1, second, "抢答下一题").getResponse().getStatus());
        var saved = body(answer(1, first, "  我的回答  "));
        assertEquals(1, saved.get("answeredCount").asInt());
        assertEquals("MOCK", saved.get("turns").get(0).get("feedbackMode").asText());
        assertFalse(saved.get("turns").get(0).get("referenceAnswer").isNull());
        assertTrue(saved.get("turns").get(1).get("referenceAnswer").isNull());
        assertEquals(saved, body(answer(1, first, "我的回答"))); // 重试不改变时间、反馈或计数。
        assertEquals(409, answer(1, first, "修改已提交回答").getResponse().getStatus());
        var completed = body(answer(1, second, "第二题的回答"));
        assertEquals("COMPLETED", completed.get("status").asText());
        assertEquals(2, completed.get("answeredCount").asInt());
        assertFalse(completed.get("completedAt").isNull());
        assertEquals(completed, body(answer(1, second, "第二题的回答")));
        assertEquals(completed, body(mvc.perform(get("/api/interviews/1")).andReturn()));
        assertEquals(1, body(mvc.perform(get("/api/interviews")).andReturn()).get("total").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{", "{\"questionCount\":0}", "{\"questionCount\":11}", "{\"questionCount\":1,\"topic\":\"UNKNOWN\"}", "{\"questionCount\":3}", "{\"questionCount\":1,\"topic\":\"REDIS\"}"})
    void invalidOrInsufficientQuestionsNeverCreateSession(String input) throws Exception {
        assertEquals(400, create(input).getResponse().getStatus());
        assertEquals(0, body(mvc.perform(get("/api/interviews")).andReturn()).get("total").asInt());
    }

    @Test
    void rejectsBlankOversizedAndForeignAnswers() throws Exception {
        create("{\"questionCount\":1}");
        create("{\"questionCount\":1}");
        assertEquals(400, answer(1, 1, "   ").getResponse().getStatus());
        assertEquals(400, answer(1, 1, "a".repeat(10001)).getResponse().getStatus());
        assertEquals(400, answer(1, 2, "别场的题目").getResponse().getStatus());
        assertEquals(400, answer(1, 0, "缺少题号").getResponse().getStatus());
        assertEquals(0, body(mvc.perform(get("/api/interviews/1")).andReturn()).get("answeredCount").asInt());
    }

    @Test
    void invalidIdsAndPaginationHaveStableStatusCodes() throws Exception {
        assertEquals(404, mvc.perform(get("/api/interviews/999")).andReturn().getResponse().getStatus());
        assertEquals(400, mvc.perform(get("/api/interviews/-1")).andReturn().getResponse().getStatus());
        assertEquals(400, mvc.perform(get("/api/interviews?page=0")).andReturn().getResponse().getStatus());
        assertEquals(400, mvc.perform(get("/api/interviews?size=101")).andReturn().getResponse().getStatus());
        assertEquals(0, body(mvc.perform(get("/api/interviews?page=2147483647&size=100")).andReturn()).get("items").size());
    }
}
