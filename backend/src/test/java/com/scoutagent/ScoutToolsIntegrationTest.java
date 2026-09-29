package com.scoutagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scoutagent.agents.AgentTool;
import com.scoutagent.agents.ScoutTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises every agent tool the way Claude would call it (JSON in, JSON out) against the seeded database.
 * Requires Postgres from docker-compose (`docker compose up -d`).
 */
@SpringBootTest
class ScoutToolsIntegrationTest {

    @Autowired ScoutTools tools;
    @Autowired ObjectMapper mapper;

    private static final Map<String, String> SAMPLE_INPUTS = Map.of(
            "get_team_season_summary", "{\"team\":\"BOS\"}",
            "get_recent_games", "{\"team\":\"BOS\",\"limit\":5}",
            "get_head_to_head", "{\"team\":\"BOS\",\"opponent\":\"DEN\"}",
            "query_game_logs", "{\"team\":\"BOS\",\"venue\":\"away\",\"rest_days\":0,\"sort_by\":\"margin\",\"limit\":5}",
            "get_league_rankings", "{\"metric\":\"net_rating\"}",
            "get_situational_splits", "{\"team\":\"DEN\"}",
            "get_performance_trend", "{\"team\":\"DEN\"}",
            "compare_matchup", "{\"team\":\"BOS\",\"opponent\":\"DEN\"}",
            "get_win_probability", "{\"home_team\":\"BOS\",\"away_team\":\"DEN\",\"home_net_rating_delta\":3}");

    @Test
    void everyToolBuildsASchemaAndReturnsJson() throws Exception {
        Map<String, AgentTool> all = new LinkedHashMap<>();
        Stream.of(tools.analystTools(), tools.scoutTools(), tools.plannerTools())
                .flatMap(List::stream).forEach(t -> all.putIfAbsent(t.name(), t));
        assertTrue(all.keySet().containsAll(SAMPLE_INPUTS.keySet()));

        for (AgentTool tool : all.values()) {
            assertNotNull(tool.toSdkTool());
            JsonNode input = mapper.readTree(SAMPLE_INPUTS.get(tool.name()));
            String json = mapper.writeValueAsString(tool.handler().apply(input));
            assertFalse(json.isBlank(), tool.name());
            assertTrue(json.length() < 60_000, tool.name() + " result too large: " + json.length());
        }
    }

    @Test
    void badInputsRaiseReadableErrors() {
        AgentTool summary = tools.analystTools().get(0);
        var ex = assertThrows(IllegalArgumentException.class,
                () -> summary.handler().apply(mapper.readTree("{\"team\":\"ZZZ\"}")));
        assertTrue(ex.getMessage().contains("Valid:"));
    }
}
