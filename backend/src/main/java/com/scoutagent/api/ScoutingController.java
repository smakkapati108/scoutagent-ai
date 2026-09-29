package com.scoutagent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scoutagent.agents.AgentRunner;
import com.scoutagent.agents.ScoutingOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scouting")
public class ScoutingController {

    private static final Logger log = LoggerFactory.getLogger(ScoutingController.class);

    private final ScoutingOrchestrator orchestrator;
    private final AgentRunner runner;
    private final ObjectMapper mapper;

    public ScoutingController(ScoutingOrchestrator orchestrator, AgentRunner runner, ObjectMapper mapper) {
        this.orchestrator = orchestrator;
        this.runner = runner;
        this.mapper = mapper;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("model", runner.model(), "credentials_in_environment", runner.credentialsInEnvironment());
    }

    /**
     * Runs the three-agent pipeline and streams its progress as server-sent events: run_started, agent_started,
     * tool_call, tool_result, agent_thought, agent_completed, agent_output, report_completed, and error.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String home, @RequestParam String away,
                             @RequestParam(required = false) String focus) {
        SseEmitter emitter = new SseEmitter(10 * 60_000L);
        Thread.ofVirtual().name("scouting-run").start(() -> {
            AgentRunner.Listener listener = (type, agent, data) -> send(emitter, type, agent, data);
            try {
                orchestrator.run(new ScoutingOrchestrator.ReportRequest(home, away, focus), listener);
                emitter.complete();
            } catch (Exception e) {
                log.warn("Scouting run failed", e);
                send(emitter, "error", "orchestrator", Map.of("message", describe(e)));
                emitter.complete();
            }
        });
        return emitter;
    }

    @GetMapping("/reports")
    public List<Map<String, Object>> reports(@RequestParam(defaultValue = "20") int limit) {
        return orchestrator.listReports(Math.min(Math.max(limit, 1), 100));
    }

    @GetMapping("/reports/{id}")
    public Map<String, Object> report(@PathVariable long id) {
        return orchestrator.findReport(id);
    }

    private void send(SseEmitter emitter, String type, String agent, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("agent", agent);
        payload.put("at", System.currentTimeMillis());
        payload.put("data", data);
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(type).data(mapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("Client disconnected from scouting stream: {}", e.getMessage());
        }
    }

    private String describe(Exception e) {
        String name = e.getClass().getSimpleName();
        if (name.contains("Credential") || name.contains("Unauthorized")) {
            return "Anthropic credentials are missing or invalid. Set ANTHROPIC_API_KEY (or run `ant auth login`) "
                    + "and restart the backend.";
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return e.getMessage() != null ? e.getMessage() : root.toString();
    }
}
