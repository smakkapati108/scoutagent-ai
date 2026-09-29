package com.scoutagent.agents;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A tool an agent can call: its JSON schema for Claude plus the Java handler that executes it. */
public record AgentTool(String name, String description, Map<String, Map<String, Object>> properties,
                        List<String> required, Function<JsonNode, Object> handler) {

    public Tool toSdkTool() {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        properties.forEach((k, schema) -> props.putAdditionalProperty(k, JsonValue.from(schema)));
        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(props.build())
                        .required(required)
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    /** Small builder so tool definitions read declaratively. */
    public static Builder named(String name, String description) {
        return new Builder(name, description);
    }

    public static final class Builder {
        private final String name, description;
        private final Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        private final List<String> required = new java.util.ArrayList<>();

        private Builder(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public Builder string(String field, String description, boolean isRequired) {
            return prop(field, Map.of("type", "string", "description", description), isRequired);
        }

        public Builder enumString(String field, String description, List<String> values, boolean isRequired) {
            return prop(field, Map.of("type", "string", "enum", values, "description", description), isRequired);
        }

        public Builder integer(String field, String description, boolean isRequired) {
            return prop(field, Map.of("type", "integer", "description", description), isRequired);
        }

        public Builder number(String field, String description, boolean isRequired) {
            return prop(field, Map.of("type", "number", "description", description), isRequired);
        }

        public Builder bool(String field, String description, boolean isRequired) {
            return prop(field, Map.of("type", "boolean", "description", description), isRequired);
        }

        private Builder prop(String field, Map<String, Object> schema, boolean isRequired) {
            props.put(field, schema);
            if (isRequired) required.add(field);
            return this;
        }

        public AgentTool handler(Function<JsonNode, Object> handler) {
            return new AgentTool(name, description, props, List.copyOf(required), handler);
        }
    }
}
