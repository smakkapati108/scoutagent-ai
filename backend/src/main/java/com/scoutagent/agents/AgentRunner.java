package com.scoutagent.agents;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs one agent: a manual Claude tool-use loop. Each turn, Claude either calls tools (which are executed in
 * parallel against the database and model, and their results sent back) or finishes with its written analysis.
 */
@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
    private static final int MAX_TOOL_RESULT_CHARS = 60_000;

    public record AgentResult(String agent, String text, int toolCalls, int turns, long inputTokens, long outputTokens) {}

    public interface Listener {
        void onEvent(String type, String agent, Map<String, Object> data);
    }

    private final ObjectMapper mapper;
    private final String model;
    private final long maxTokens;
    private final int maxTurns;
    private final boolean refusalFallbacks;
    private volatile AnthropicClient client;

    public AgentRunner(ObjectMapper mapper,
                       @Value("${scout.agents.model}") String model,
                       @Value("${scout.agents.max-tokens}") long maxTokens,
                       @Value("${scout.agents.max-turns}") int maxTurns,
                       @Value("${scout.agents.refusal-fallbacks:true}") boolean refusalFallbacks) {
        this.mapper = mapper;
        this.model = model;
        this.maxTokens = maxTokens;
        this.maxTurns = maxTurns;
        this.refusalFallbacks = refusalFallbacks;
    }

    public String model() {
        return model;
    }

    /** True when an API key or auth token is visible in the environment (an `ant auth login` profile also works). */
    public boolean credentialsInEnvironment() {
        return notBlank(System.getenv("ANTHROPIC_API_KEY")) || notBlank(System.getenv("ANTHROPIC_AUTH_TOKEN"));
    }

    private AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) client = AnthropicOkHttpClient.fromEnv();
                c = client;
            }
        }
        return c;
    }

    public AgentResult run(String agent, String systemPrompt, String task, List<AgentTool> tools,
                           OutputConfig.Effort effort, Listener listener) {
        Map<String, AgentTool> byName = tools.stream().collect(Collectors.toMap(AgentTool::name, Function.identity()));

        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(systemPrompt)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder().effort(effort).build())
                // Caches the growing conversation prefix across turns of the loop.
                .cacheControl(CacheControlEphemeral.builder().build());
        if (refusalFallbacks) {
            params.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        tools.forEach(t -> params.addTool(t.toSdkTool()));
        params.addUserMessage(task);

        int toolCalls = 0;
        long inTokens = 0, outTokens = 0;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int turn = 1; turn <= maxTurns; turn++) {
                Message response = client().messages().create(params.build());
                inTokens += response.usage().inputTokens();
                outTokens += response.usage().outputTokens();
                params.addMessage(response);

                StopReason stop = response.stopReason().orElse(StopReason.END_TURN);
                if (StopReason.REFUSAL.equals(stop)) {
                    throw new IllegalStateException(agent + " request was declined by the model");
                }
                if (StopReason.MAX_TOKENS.equals(stop)) {
                    throw new IllegalStateException(agent + " hit max_tokens before finishing");
                }

                List<ToolUseBlock> calls = response.content().stream()
                        .flatMap(b -> b.toolUse().stream()).toList();
                String text = text(response);

                if (!StopReason.TOOL_USE.equals(stop) || calls.isEmpty()) {
                    if (StopReason.PAUSE_TURN.equals(stop)) continue;
                    listener.onEvent("agent_completed", agent, Map.of("turns", turn, "tool_calls", toolCalls));
                    return new AgentResult(agent, text, toolCalls, turn, inTokens, outTokens);
                }
                if (!text.isBlank()) listener.onEvent("agent_thought", agent, Map.of("text", text));

                // Execute this turn's tool calls concurrently; return all results in a single user message.
                List<Future<ContentBlockParam>> futures = new ArrayList<>();
                for (ToolUseBlock call : calls) {
                    toolCalls++;
                    futures.add(pool.submit(() -> execute(agent, call, byName, listener)));
                }
                List<ContentBlockParam> results = new ArrayList<>();
                for (Future<ContentBlockParam> f : futures) results.add(f.get());
                params.addMessage(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(results)
                        .build());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(agent + " was interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(agent + " tool execution failed", e.getCause());
        }
        throw new IllegalStateException(agent + " did not finish within " + maxTurns + " turns");
    }

    private ContentBlockParam execute(String agent, ToolUseBlock call, Map<String, AgentTool> tools, Listener listener) {
        JsonNode input = call._input().convert(JsonNode.class);
        Map<String, Object> callEvent = new LinkedHashMap<>();
        callEvent.put("id", call.id());
        callEvent.put("tool", call.name());
        callEvent.put("input", input);
        listener.onEvent("tool_call", agent, callEvent);

        long start = System.nanoTime();
        String content;
        boolean isError = false;
        try {
            AgentTool tool = tools.get(call.name());
            if (tool == null) throw new IllegalArgumentException("Unknown tool: " + call.name());
            content = mapper.writeValueAsString(tool.handler().apply(input));
            if (content.length() > MAX_TOOL_RESULT_CHARS) {
                throw new IllegalArgumentException("Result too large (" + content.length()
                        + " chars); narrow the query with filters or a smaller limit");
            }
        } catch (Exception e) {
            isError = true;
            content = "Error: " + e.getMessage();
            log.debug("Tool {} failed for {}", call.name(), agent, e);
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        listener.onEvent("tool_result", agent, Map.of("id", call.id(), "tool", call.name(), "ms", ms,
                "is_error", isError, "chars", content.length()));

        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(call.id())
                .content(content)
                .isError(isError)
                .build());
    }

    private static String text(Message m) {
        return m.content().stream().flatMap((ContentBlock b) -> b.text().stream())
                .map(t -> t.text()).collect(Collectors.joining("\n")).trim();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
