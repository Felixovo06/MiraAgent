package com.felix.miraagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.felix.miraagent.agent.ModelConfig;
import com.felix.miraagent.agent.AgentRuntime;
import com.felix.miraagent.agent.compression.CompressionPolicy;
import com.felix.miraagent.agent.compression.ContextCompressor;
import com.felix.miraagent.agent.compression.SummaryProperties;
import com.felix.miraagent.agent.compression.impl.DefaultContextCompressor;
import com.felix.miraagent.agent.impl.ConversationLoop;
import com.felix.miraagent.experience.BackgroundReview;
import com.felix.miraagent.agent.impl.DefaultAgentRuntime;
import com.felix.miraagent.memory.MemoryRetriever;
import com.felix.miraagent.memory.MemoryStore;
import com.felix.miraagent.memory.SerializedMemoryWriter;
import com.felix.miraagent.model.ModelClient;
import com.felix.miraagent.model.ModelProperties;
import com.felix.miraagent.prompt.PromptBuilder;
import com.felix.miraagent.prompt.impl.DefaultPromptBuilder;
import com.felix.miraagent.session.SessionStore;
import com.felix.miraagent.session.impl.InMemorySessionStore;
import com.felix.miraagent.skill.SkillIndexInjector;
import com.felix.miraagent.skill.SkillLoader;
import com.felix.miraagent.tools.ToolDispatcher;
import com.felix.miraagent.tools.ToolExecutionStore;
import com.felix.miraagent.tools.ToolRegistry;
import com.felix.miraagent.tools.artifact.ArtifactProperties;
import com.felix.miraagent.tools.artifact.FileToolResultCache;
import com.felix.miraagent.tools.artifact.ToolResultCache;
import com.felix.miraagent.tools.builtin.BuiltinTools;
import com.felix.miraagent.tools.builtin.FileReadToolHandler;
import com.felix.miraagent.tools.builtin.FileToolProperties;
import com.felix.miraagent.tools.builtin.FileWriteToolHandler;
import com.felix.miraagent.tools.builtin.ToolsProperties;
import com.felix.miraagent.tools.builtin.WebFetchToolHandler;
import com.felix.miraagent.tools.handlers.MemoryWriterToolHandler;
import com.felix.miraagent.tools.handlers.RecallMemoryToolHandler;
import com.felix.miraagent.tools.handlers.SkillManageToolHandler;
import com.felix.miraagent.tools.handlers.SkillViewToolHandler;
import com.felix.miraagent.tools.handlers.SkillsListToolHandler;
import com.felix.miraagent.skill.SkillManager;
import com.felix.miraagent.tools.impl.DefaultToolDispatcher;
import com.felix.miraagent.tools.impl.InMemoryToolExecutionStore;
import com.felix.miraagent.tools.impl.InMemoryToolRegistry;
import com.felix.miraagent.tools.impl.RiskThresholdToolPermissionPolicy;
import com.felix.miraagent.trace.TraceStore;
import com.felix.miraagent.trace.impl.InMemoryTraceStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

@Configuration
@EnableConfigurationProperties({ArtifactProperties.class, SummaryProperties.class,
        FileToolProperties.class, ToolsProperties.class})
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
    public ToolRegistry toolRegistry(Optional<MemoryRetriever> memoryRetriever,
                                     Optional<SerializedMemoryWriter> memoryWriter,
                                     Optional<SkillManager> skillManager,
                                     FileToolProperties fileToolProperties) {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        BuiltinTools.registerAll(registry);
        registry.register(WebFetchToolHandler.definition(), new WebFetchToolHandler());
        registry.register(FileReadToolHandler.definition(),
                new FileReadToolHandler(fileToolProperties.getBaseDir()));
        registry.register(FileWriteToolHandler.definition(),
                new FileWriteToolHandler(fileToolProperties.getBaseDir()));
        memoryRetriever.ifPresent(r ->
                registry.register(RecallMemoryToolHandler.definition(), new RecallMemoryToolHandler(r)));
        memoryWriter.ifPresent(w ->
                registry.register(MemoryWriterToolHandler.definition(), new MemoryWriterToolHandler(w)));
        skillManager.ifPresent(m -> {
            registry.register(SkillsListToolHandler.definition(), new SkillsListToolHandler(m));
            registry.register(SkillViewToolHandler.definition(), new SkillViewToolHandler(m));
            registry.register(SkillManageToolHandler.definition(), new SkillManageToolHandler(m));
        });
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
    public ToolResultCache fileToolResultCache(ArtifactProperties props, ObjectMapper objectMapper) {
        return new FileToolResultCache(props, objectMapper);
    }

    @Bean
    public ContextCompressor contextCompressor(SummaryProperties summaryProperties, ObjectMapper objectMapper) {
        return new DefaultContextCompressor(summaryProperties.getBaseDir(), objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(SkillIndexInjector.class)
    public SkillIndexInjector skillIndexInjector(SkillLoader skillLoader) {
        return new SkillIndexInjector(skillLoader);
    }

    @Bean
    public ConversationLoop conversationLoop(ModelClient modelClient, PromptBuilder promptBuilder,
                                             ToolRegistry toolRegistry, ToolDispatcher toolDispatcher,
                                             SessionStore sessionStore, TraceStore traceStore,
                                             ToolExecutionStore toolExecutionStore,
                                             Optional<MemoryStore> memoryStore,
                                             Optional<MemoryRetriever> memoryRetriever,
                                             Optional<SerializedMemoryWriter> memoryWriter,
                                             Optional<ToolResultCache> toolResultCache,
                                             Optional<ContextCompressor> compressor,
                                             SummaryProperties summaryProperties,
                                             Optional<SkillIndexInjector> skillIndexInjector,
                                             Optional<BackgroundReview> backgroundReview) {
        return new ConversationLoop(modelClient, promptBuilder, toolRegistry, toolDispatcher,
                sessionStore, traceStore, toolExecutionStore,
                memoryStore.orElse(null), memoryRetriever.orElse(null),
                memoryWriter.orElse(null),
                toolResultCache.orElse(null),
                compressor.orElse(null), CompressionPolicy.defaultPolicy(),
                summaryProperties.getBaseDir(),
                skillIndexInjector.orElse(null),
                backgroundReview.orElse(null));
    }

    @Bean
    public AgentRuntime agentRuntime(ConversationLoop conversationLoop, SessionStore sessionStore,
                                     ModelProperties modelProperties, ToolsProperties toolsProperties) {
        ModelConfig defaultModelConfig = ModelConfig.builder()
                .modelName(modelProperties.getName())
                .temperature(modelProperties.getTemperature())
                .maxTokens(modelProperties.getMaxTokens())
                .build();
        var permissionPolicy = new RiskThresholdToolPermissionPolicy(toolsProperties.getMaxRiskLevel());
        return new DefaultAgentRuntime(conversationLoop, sessionStore, defaultModelConfig, permissionPolicy);
    }
}
