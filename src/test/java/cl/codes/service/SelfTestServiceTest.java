package cl.codes.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SelfTestServiceTest {

    @Test
    void preservesExistingJsonFieldNamesForSelfTestResults() {
        SelfTestService.SelfTestResult result = new SelfTestService.SelfTestResult(
                LocalDateTime.of(2026, 1, 2, 3, 4),
                "OK",
                List.of(new SelfTestService.CheckResult("database", true, "available"))
        );

        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(result);

        assertEquals("OK", json.path("estadoGeneral").asText());
        assertEquals("database", json.path("checks").get(0).path("nombre").asText());
        assertEquals("available", json.path("checks").get(0).path("detalle").asText());
        assertFalse(json.has("overallStatus"));
    }
}
