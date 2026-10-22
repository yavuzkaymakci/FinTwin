package com.fintwin.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintwin.TestcontainersConfiguration;
import com.fintwin.ml.MlServiceClient;
import com.fintwin.twin.dto.TwinView;
import com.fintwin.twin.projection.TwinFeatures;
import com.fintwin.twin.service.TwinNotFoundException;
import com.fintwin.twin.service.TwinQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared context of the assistant tests: real database (Testcontainers), scripted chat model instead of an LLM,
 * mocked ML service client and twin queries. Customers 1001 and 1002 have distinguishable twins.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AssistantTestSupport.Model.class})
abstract class AssistantTestSupport {

    static final long ALICE = 1001;
    static final long BOB = 1002;

    @TestConfiguration
    static class Model {
        @Bean
        @Primary
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ScriptedChatModel model;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @MockitoBean
    TwinQueryService twins;

    @MockitoBean
    MlServiceClient ml;

    @BeforeEach
    void twinsOfAliceAndBob() {
        model.reset();
        given(twins.current(anyLong())).willAnswer(inv -> {
            long id = inv.getArgument(0);
            if (id == ALICE) {
                return twin(ALICE, "WHITE_COLLAR", 45_000, Map.of("RESTAURANT", 3_000, "GROCERY", 9_000));
            }
            if (id == BOB) {
                return twin(BOB, "STUDENT", 777_777, Map.of("RESTAURANT", 99_999, "TRAVEL", 55_555));
            }
            throw new TwinNotFoundException(id);
        });
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM assistant.ai_interaction");
        jdbc.update("DELETE FROM assistant.conversation");
        jdbc.update("DELETE FROM spring_ai_chat_memory");
        jdbc.update("DELETE FROM simulation.simulation_run");
        jdbc.update("DELETE FROM simulation.scenario WHERE NOT preset");
    }

    static TwinView twin(long id, String segment, int checking, Map<String, Integer> spending) {
        TreeMap<String, BigDecimal> categories = new TreeMap<>();
        spending.forEach((k, v) -> categories.put(k, BigDecimal.valueOf(v)));
        BigDecimal total = categories.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        TwinFeatures features = new TwinFeatures(Instant.parse("2026-09-30T18:00:00Z"), BigDecimal.valueOf(checking),
                BigDecimal.valueOf(10_000), BigDecimal.ZERO, BigDecimal.valueOf(12_000), BigDecimal.valueOf(40_000),
                0.3, BigDecimal.ZERO, BigDecimal.valueOf(12_000), 0, BigDecimal.valueOf(4_000), total,
                new TreeMap<>(), categories, 0.05, BigDecimal.valueOf(52_000), 6, 0.04, 0.0, BigDecimal.valueOf(2_400),
                0.046, 0, null, BigDecimal.valueOf(38_000), 1.4);
        return new TwinView(id, Instant.parse("2026-09-30T18:00:00Z"), "CURRENT", 100, Instant.now(), segment,
                "ISTANBUL", 35, LocalDate.of(2020, 1, 1), List.of(), List.of(), features);
    }

    MockHttpServletRequestBuilder chat(String assistant, String user, String role, Long customerId, String body) {
        MockHttpServletRequestBuilder request = post("/api/assistants/" + assistant + "/chat")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Demo-User", user).header("X-Demo-Role", role);
        return customerId == null ? request : request.header("X-Demo-Customer-Id", customerId);
    }

    String body(String conversationId, String message) throws Exception {
        return json.writeValueAsString(conversationId == null ? Map.of("message", message)
                : Map.of("conversationId", conversationId, "message", message));
    }

    JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
