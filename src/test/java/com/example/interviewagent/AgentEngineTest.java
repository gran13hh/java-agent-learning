package com.example.interviewagent;

import com.example.interviewagent.agent.*;
import com.example.interviewagent.ai.CallDeadline;
import com.example.interviewagent.exception.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AgentEngineTest {
    static class Trace implements AgentEngine.Trace {
        final List<String> states = new ArrayList<>(), outputs = new ArrayList<>();
        public int start(String kind, String name, String input) { states.add("STARTED"); outputs.add(""); return states.size(); }
        public void end(int step, String state, String output) { states.set(step-1,state); outputs.set(step-1,output); }
    }
    static AssistantMessage call(String id, String name, String args) {
        return AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(id,"function",name,args))).build();
    }
    static AssistantMessage done(String sources) { return AssistantMessage.builder().content("{\"answer\":\"先复习集合，再做题\",\"sourceIds\":"+sources+"}").build(); }

    @Test
    void modelChoosesToolThenReceivesActualResultAndOnlyPublicTraceIsSaved() {
        var calls = new AtomicInteger(); var trace = new Trace();
        var engine = new AgentEngine((messages, allow) -> {
            if (calls.getAndIncrement() == 0) return call("c1","list_questions","{\"topic\":\"JAVA\",\"limit\":2}");
            var result = (ToolResponseMessage) messages.getLast();
            assertTrue(result.getResponses().getFirst().responseData().contains("真实题目"));
            assertEquals("c1", result.getResponses().getFirst().id()); return done("[\"E1\"]");
        }, (name,args,session) -> { assertEquals(24L,session); return "{\"title\":\"真实题目\"}"; });
        var result = engine.run("挑选练习",24L,trace);
        assertEquals("SUCCEEDED",result.state()); assertEquals(2,result.modelCalls()); assertEquals(1,result.toolCalls());
        assertTrue(result.result().contains("E1")); assertEquals(List.of("SUCCEEDED","SUCCEEDED","SUCCEEDED"),trace.states);
    }

    @Test
    void excessiveBatchIsStoppedBeforeAnyToolSideEffect() {
        var tools = new AtomicInteger();
        var response = AssistantMessage.builder().content("").toolCalls(java.util.stream.IntStream.range(0,4)
                .mapToObj(i -> new AssistantMessage.ToolCall("c"+i,"function","list_questions","{}")).toList()).build();
        var result = new AgentEngine((m,a)->response,(n,p,s)->{tools.incrementAndGet(); return "[]";}).run("test",null,new Trace());
        assertEquals("LIMIT_REACHED",result.state()); assertEquals(0,tools.get());
    }

    @Test
    void repeatedArgumentsWithDifferentKeyOrderStopWithoutReexecuting() {
        var models = new AtomicInteger(); var tools = new AtomicInteger(); var trace = new Trace();
        var result = new AgentEngine((m,a)->models.getAndIncrement()==0
                ? call("c1","list_questions","{\"topic\":\"JAVA\",\"limit\":2}")
                : call("c2","list_questions","{ \"limit\":2, \"topic\":\"JAVA\" }"),
                (n,p,s)->{tools.incrementAndGet(); return "[]";}).run("test",null,trace);
        assertEquals("LIMIT_REACHED",result.state()); assertEquals(1,tools.get()); assertEquals("REJECTED",trace.states.getLast());
    }

    @Test
    void invalidArgumentsCanBeCorrectedWithinBudgetAndLastRoundForbidsTools() {
        var count = new AtomicInteger(); var trace = new Trace();
        var result = new AgentEngine((m,allow)->switch(count.incrementAndGet()) {
            case 1 -> call("c1","list_questions","{\"limit\":100}");
            case 2 -> { assertTrue(((ToolResponseMessage)m.getLast()).getResponses().getFirst().responseData().contains("error")); yield call("c2","list_questions","{\"limit\":2}"); }
            default -> { assertFalse(allow); yield done("[\"E2\"]"); }
        },(n,p,s)->{if(p.contains("100"))throw new ToolRejectedException("参数越界");return "[]";}).run("test",null,trace);
        assertEquals("SUCCEEDED",result.state()); assertEquals(3,result.modelCalls()); assertEquals(2,result.toolCalls());
        assertTrue(trace.states.contains("REJECTED"));
    }

    @Test
    void modelCannotIgnoreFinalRoundToolBan() {
        var models = new AtomicInteger(); var tools = new AtomicInteger();
        var result = new AgentEngine((m,a)->call("c"+models.incrementAndGet(),"search_knowledge","{\"query\":\"q"+models.get()+"\"}"),
                (n,p,s)->{tools.incrementAndGet();return "[]";}).run("test",null,new Trace());
        assertEquals("LIMIT_REACHED",result.state()); assertEquals(3,models.get()); assertEquals(2,tools.get());
    }

    @Test
    void quotaAndToolFailureStopImmediatelyAndDoNotInventAnswer() {
        var models = new AtomicInteger(); var trace = new Trace();
        var result = new AgentEngine((m,a)->{models.incrementAndGet();return call("c1","search_knowledge","{}");},
                (n,p,s)->{throw new AiRateLimitException(37);}).run("test",null,trace);
        assertEquals("RATE_LIMITED",result.state()); assertEquals(37,result.retryAfterSeconds()); assertNull(result.result());
        assertEquals(1,models.get()); assertEquals("FAILED",trace.states.getLast());
        result = new AgentEngine((m,a)->{throw new RuntimeException("secret-value");},(n,p,s)->"{}").run("test",null,new Trace());
        assertEquals("FAILED",result.state()); assertFalse(result.message().contains("secret-value"));
    }

    @Test
    void unavailableEvidenceAndDuplicateIdsCannotProduceSuccess() {
        assertEquals("FAILED",new AgentEngine((m,a)->done("[\"E99\"]"),(n,p,s)->"{}").run("test",null,new Trace()).state());
        var response = AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("c1","function","read_interview","{}"),
                new AssistantMessage.ToolCall("c1","function","read_interview","{}"))).build();
        var tools = new AtomicInteger();
        var result = new AgentEngine((m,a)->response,(n,p,s)->{tools.incrementAndGet();return "{}";}).run("test",null,new Trace());
        assertEquals("FAILED",result.state()); assertEquals(0,tools.get());
    }

    @Test
    void expiredTotalBudgetPreventsEvenFirstModelCall() {
        var calls = new AtomicInteger();
        try(var deadline = CallDeadline.within(Duration.ZERO)) {
            var result = new AgentEngine((m,a)->{calls.incrementAndGet();return done("[]");},(n,p,s)->"{}").run("test",null,new Trace());
            assertEquals("TIMED_OUT",result.state()); assertEquals(0,calls.get());
        }
        assertDoesNotThrow(CallDeadline::check);
    }
}
