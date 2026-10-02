package com.example.interviewagent;

import com.example.interviewagent.agent.*;
import com.example.interviewagent.exception.*;
import com.example.interviewagent.controller.AgentController;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import org.springframework.ai.chat.messages.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
class MySqlAgentTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private AgentRepository repository;
    private final List<Long> ids = new ArrayList<>();
    @BeforeEach void setup() {
        String password = System.getenv("DB_PASSWORD");
        if (password == null || password.isBlank()) {
            var yaml = new YamlPropertiesFactoryBean(); yaml.setResources(new FileSystemResource("config/application-local.yml"));
            password = new StandardEnvironment().resolveRequiredPlaceholders(Objects.requireNonNull(yaml.getObject()).getProperty("spring.datasource.password"));
        }
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("DB_URL",
                "jdbc:mysql://127.0.0.1:3306/interview_agent?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=Asia/Shanghai"),
                System.getenv().getOrDefault("DB_USERNAME", "interview_app"), password);
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source); repository = proxy(new AgentRepository(jdbc));
    }
    @SuppressWarnings("unchecked") private <T> T proxy(T target) {
        var advice = new TransactionInterceptor(); advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true); factory.addAdvice(advice); return (T)factory.getProxy();
    }
    private AgentService service(AgentModel model) { return proxy(new AgentService(repository,new AgentEngine(model,(n,p,s)->"{\"value\":\"工具结果\"}"),null)); }
    private long create() { long id = repository.create(UUID.randomUUID().toString(),"测试复习目标",null).id(); ids.add(id); return id; }
    @AfterEach void cleanup() {
        if (jdbc == null) return;
        for (long id : ids) { jdbc.update("DELETE FROM agent_steps WHERE run_id = ?",id); jdbc.update("DELETE FROM agent_runs WHERE id = ?",id); }
    }

    @Test void createIsIdempotentAndChangedPayloadConflicts() {
        String key = UUID.randomUUID().toString();
        var service = service((m,a)->AgentEngineTest.done("[]"));
        var run = service.create(key,"目标",null); ids.add(run.id());
        assertEquals(run.id(),service.create(key.toUpperCase(Locale.ROOT),"目标",null).id());
        assertThrows(InterviewConflictException.class,()->service.create(key,"另一个目标",null));
        assertEquals("PENDING",repository.get(run.id()).state());
    }

    @Test void toolTracePersistsAcrossReloadAndRepeatedExecuteUsesNoModel() {
        long id = create(); var calls = new AtomicInteger();
        var service = service((m,a)-> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return calls.incrementAndGet()==1 ? AgentEngineTest.call("c1","read_interview","{}") : AgentEngineTest.done("[\"E1\"]");
        });
        var detail = service.execute(id); assertEquals("SUCCEEDED",detail.run().state()); assertEquals(3,detail.steps().size());
        assertTrue(detail.steps().get(1).output().contains("工具结果"));
        assertEquals(detail,new AgentRepository(jdbc).detail(id)); service.execute(id); assertEquals(2,calls.get());
    }

    @Test void simultaneousExecutionOnlyClaimsOneRun() throws Exception {
        long id = create(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var service = service((m,a)-> {
            calls.incrementAndGet(); entered.countDown();
            try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
            return AgentEngineTest.done("[]");
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(()->service.execute(id));
            try { assertTrue(entered.await(5,TimeUnit.SECONDS)); assertThrows(InterviewConflictException.class,()->service.execute(id)); }
            finally { release.countDown(); }
            assertEquals("SUCCEEDED",first.get(5,TimeUnit.SECONDS).run().state()); assertEquals(1,calls.get());
        }
    }

    @Test void expiredLeaseShowsInterruptedAndLateResultCannotReplaceIt() {
        long id = create(); var claim = repository.claim(id); repository.startStep(claim,"MODEL","chat","{}");
        jdbc.update("UPDATE agent_runs SET lease_until = TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id = ?",id);
        assertEquals("INTERRUPTED",repository.detail(id).run().state());
        assertEquals("FAILED",repository.detail(id).steps().getFirst().state()); assertNull(repository.claim(id));
        repository.finish(claim,new AgentEngine.Outcome("SUCCEEDED","晚到结果","完成",1,0,0));
        assertNull(repository.get(id).result());
        assertThrows(InterviewConflictException.class,()->repository.startStep(claim,"TOOL","read_interview","{}"));
    }

    @Test void apiReturnsSavedRateLimitTraceAndValidatesNewRequests() throws Exception {
        long id = create(); var calls = new AtomicInteger();
        var service = service((m,a)->{calls.incrementAndGet();throw new AiRateLimitException(17);});
        var mvc = MockMvcBuilders.standaloneSetup(new AgentController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
        var response = mvc.perform(post("/api/agent/runs/"+id+"/execute")).andReturn().getResponse();
        assertEquals(200,response.getStatus()); assertEquals("17",response.getHeader("Retry-After"));
        assertEquals("RATE_LIMITED",repository.get(id).state()); assertEquals("FAILED",repository.detail(id).steps().getFirst().state());
        mvc.perform(post("/api/agent/runs/"+id+"/execute")); assertEquals(1,calls.get());
        assertEquals(400,mvc.perform(post("/api/agent/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"bad\",\"prompt\":\"test\"}")).andReturn().getResponse().getStatus());
        assertEquals(404,mvc.perform(get("/api/agent/runs/9223372036854775807")).andReturn().getResponse().getStatus());
    }
}
