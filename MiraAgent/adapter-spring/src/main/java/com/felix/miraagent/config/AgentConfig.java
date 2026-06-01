package com.felix.miraagent.config;

import com.felix.miraagent.agent.ModelConfig;
import com.felix.miraagent.agent.AgentRuntime;
import com.felix.miraagent.agent.impl.ConversationLoop;
import com.felix.miraagent.agent.impl.DefaultAgentRuntime;
import com.felix.miraagent.memory.MemoryRetriever;
import com.felix.miraagent.memory.MemoryStore;
import com.felix.miraagent.model.ModelClient;
import com.felix.miraagent.model.ModelProperties;
import com.felix.miraagent.prompt.PromptBuilder;
import com.felix.miraagent.prompt.impl.DefaultPromptBuilder;
import com.felix.miraagent.session.SessionStore;
import com.felix.miraagent.session.impl.InMemorySessionStore;
import com.felix.miraagent.tools.ToolDispatcher;
import com.felix.miraagent.tools.ToolExecutionStore;
import com.felix.miraagent.tools.ToolRegistry;
import com.felix.miraagent.tools.builtin.BuiltinTools;
import com.felix.miraagent.tools.handlers.RecallMemoryToolHandler;
import com.felix.miraagent.tools.impl.DefaultToolDispatcher;
import com.felix.miraagent.tools.impl.InMemoryToolExecutionStore;
import com.felix.miraagent.tools.impl.InMemoryToolRegistry;
import com.felix.miraagent.trace.TraceStore;
import com.felix.miraagent.trace.impl.InMemoryTraceStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

@Configuration
public class AgentConfig {

    @Bean
    @ConditionalOnMissingBean(SessionStore.class)
    public SessionStore inMemorySessionStore() {
        return new InMemorySessionStore();
    }

    @Bean
    @ConditionalOnMissingBean(TraceStore.class)
    public TraceStore inMemoryTraceStore() {
        return new InMemoryTraceStore();
    }

    @Bean
    @ConditionalOnMissingBean(ToolExecutionStore.class)
    public ToolExecutionStore inMemoryToolExecutionStore() {
        return new InMemoryToolExecutionStore();
    }

    @Bean
    @ConditionalOnMissingBean(ToolRegistry.class)
    public ToolRegistry toolRegistry(Optional<MemoryRetriever> memoryRetriever) {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        BuiltinTools.registerAll(registry);
        memoryRetriever.ifPresent(r ->
                registry.register(RecallMemoryToolHandler.definition(), new RecallMemoryToolHandler(r)));
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean(ToolDispatcher.class)
    public ToolDispatcher toolDispatcher(ToolRegistry toolRegistry) {
        return new DefaultToolDispatcher(toolRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(PromptBuilder.class)
    public PromptBuilder promptBuilder() {
        return new DefaultPromptBuilder();
    }

    @Bean
    public ConversationLoop conversationLoop(ModelClient modelClient, PromptBuilder promptBuilder,
                                             ToolRegistry toolRegistry, ToolDispatcher toolDispatcher,
                                             SessionStore sessionStore, TraceStore traceStore,
                                             ToolExecutionStore toolExecutionStore,
                                             Optional<MemoryStore> memoryStore,
                                             Optional<MemoryRetriever> memoryRetriever) {
        return new ConversationLoop(modelClient, promptBuilder, toolRegistry, toolDispatcher,
                sessionStore, traceStore, toolExecutionStore,
                memoryStore.orElse(null), memoryRetriever.orElse(null));
    }

    @Bean
    public AgentRuntime agentRuntime(ConversationLoop conversationLoop, SessionStore sessionStore,
                                     ModelProperties modelProperties) {
        ModelConfig defaultModelConfig = ModelConfig.builder()
                .modelName(modelProperties.getName())
                .temperature(modelProperties.getTemperature())
                .maxTokens(modelProperties.getMaxTokens())
                .build();
        return new DefaultAgentRuntime(conversationLoop, sessionStore, defaultModelConfig);
    }
}
