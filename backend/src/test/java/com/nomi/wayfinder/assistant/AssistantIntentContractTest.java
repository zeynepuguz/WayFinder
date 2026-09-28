package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.assistant.AssistantIntent.IntentType;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.entity.WalkingTolerance;
import com.nomi.wayfinder.planning.ReplanType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Python AI service returns AssistantIntent JSON. This JSON was produced by the Python
 * pydantic models (ai-service/app/schemas.py); if either side renames a field, this test breaks.
 */
class AssistantIntentContractTest {

    private static final String PYTHON_OUTPUT = """
            {"type":"REPLAN","plan":{"partySize":2,"budget":700,"walkingTolerance":"LOW","stops":["BREAKFAST"],
            "interests":["history"],"startTime":"10:30"},"edits":[{"type":"REMOVE_STOP","stopType":null,
            "interest":null,"targetText":"Ciya","targetStopType":"DINNER","targetIsCurrent":false}],
            "recommendType":null,"source":"ai"}
            """;

    @Test
    void backendReadsTheAiServiceResponse() {
        AssistantIntent intent = JsonMapper.builder().build().readValue(PYTHON_OUTPUT, AssistantIntent.class);

        assertThat(intent.type()).isEqualTo(IntentType.REPLAN);
        assertThat(intent.plan().budget()).isEqualTo(700);
        assertThat(intent.plan().walkingTolerance()).isEqualTo(WalkingTolerance.LOW);
        assertThat(intent.plan().stops()).containsExactly(StopType.BREAKFAST);
        assertThat(intent.plan().startTime()).isEqualTo(LocalTime.of(10, 30));
        assertThat(intent.edits().getFirst().type()).isEqualTo(ReplanType.REMOVE_STOP);
        assertThat(intent.edits().getFirst().targetStopType()).isEqualTo(StopType.DINNER);
        // Older AI service versions send neither
        assertThat(intent.date()).isNull();
        assertThat(intent.area()).isNull();
    }

    @Test
    void backendReadsDateAndArea() {
        AssistantIntent intent = JsonMapper.builder().build().readValue("""
                {"type":"PLAN_ROUTE","plan":{"partySize":2,"budget":700,"walkingTolerance":null,"stops":[],
                "interests":[],"startTime":"13:00"},"edits":[],"recommendType":null,"source":"ai",
                "date":"2026-09-28","area":"üsküdar"}
                """, AssistantIntent.class);

        assertThat(intent.date()).isEqualTo(java.time.LocalDate.of(2026, 9, 28));
        assertThat(intent.area()).isEqualTo("üsküdar");
        assertThat(intent.plan().startTime()).isEqualTo(LocalTime.of(13, 0));
    }
}
