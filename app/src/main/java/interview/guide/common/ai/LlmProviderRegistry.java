package interview.guide.common.ai;

import com.openai.client.OpenAIClient;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.AdvisorConfig;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing and caching LLM providers.
 * Supports dynamic creation of ChatClient based on provider configurations.
 */
@Component
@Slf4j
public class LlmProviderRegistry {

    private final LlmProviderProperties properties;
    // 用 ConcurrentHashMap 做 ChatClient 对象的缓存
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();
    private final Map<String, OpenAiChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();
    private final LlmProviderRepository providerRepository;
    private final LlmGlobalSettingRepository globalSettingRepository;
    private final ApiKeyEncryptionService encryptionService;

    private final ToolCallingManager toolCallingManager;
    private final ObservationRegistry observationRegistry;
    private final ToolCallback interviewSkillsToolCallback;
    private static final Map<String, String> RECOMMENDED_EMBEDDING_MODELS = Map.of(
        "dashscope", "text-embedding-v3",
        "glm", "embedding-3",
        "zhipu", "embedding-3",
        "baidu", "Embedding-V1",
        "minimax", "embo-01"
    );

    @Autowired
    public LlmProviderRegistry(
            LlmProviderProperties properties,
            LlmProviderRepository providerRepository,
            LlmGlobalSettingRepository globalSettingRepository,
            ApiKeyEncryptionService encryptionService,
            @Autowired(required = false) ToolCallingManager toolCallingManager,
            @Autowired(required = false) ObservationRegistry observationRegistry,
            @Autowired(required = false) @Qualifier("interviewSkillsToolCallback") ToolCallback interviewSkillsToolCallback) {
        this.properties = properties;
        this.providerRepository = providerRepository;
        this.globalSettingRepository = globalSettingRepository;
        this.encryptionService = encryptionService;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry;
        this.interviewSkillsToolCallback = interviewSkillsToolCallback;
    }

    public LlmProviderRegistry(
            LlmProviderProperties properties,
            ToolCallingManager toolCallingManager,
            ObservationRegistry observationRegistry,
            ToolCallback interviewSkillsToolCallback) {
        this(properties, null, null, null, toolCallingManager, observationRegistry, interviewSkillsToolCallback);
    }

    /**
     * 根据指定的 provider ID 获取 ChatClient。
     * 如果缓存中不存在，则根据该 provider 的配置创建新的 ChatClient。
     *
     * @param providerId 供应商 ID（如 "dashscope"、"lmstudio"）
     * @return ChatClient 实例
     * @throws IllegalArgumentException 如果 providerId 未知
     */
    public ChatClient getChatClient(String providerId) {
        return clientCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new client for provider: {}", id);
            return createChatClient(id);
        });
    }

    /**
     * 根据配置 app.ai.default-provider 获取默认 ChatClient。
     *
     * @return 默认 ChatClient 实例
     */
    public ChatClient getDefaultChatClient() {
        return getChatClient(resolveDefaultChatProviderId());
    }

    /**
     * 根据指定的 provider 获取 ChatClient，如果 providerId 为 null 或空白则回退到默认供应商。
     * 使用的key为 providerId
     */
    public ChatClient getChatClientOrDefault(String providerId) {
        if (providerId != null && !providerId.isBlank()) {
            return getChatClient(providerId);
        }
        return getDefaultChatClient();
    }

    /**
     * 从缓存clientCache中获取ChatClient，如果没有则创建一个新的ChatClient。
     * 使用的key为 providerId + ":plain"
     */
    public ChatClient getPlainChatClient(String providerId) {
        String id = resolveProviderId(providerId);
        return clientCache.computeIfAbsent(id + ":plain", key -> createPlainChatClient(id));
    }

    /**
     * 从缓存clientCache中获取VoiceChatClient，如果没有则创建一个新的VoiceChatClient。
     * 使用的key为 providerId + ":voice"
     */
    public ChatClient getVoiceChatClient(String providerId) {
        String id = resolveProviderId(providerId);
        return clientCache.computeIfAbsent(id + ":voice", key -> createVoiceChatClient(id));
    }

    /**
     * 清空缓存，重新加载所有 provider。
     * 下一次业务调用 getChatClient() 时再 computeIfAbsent 重建。
     */
    public void reload() {
        int size = clientCache.size() + chatModelCache.size() + embeddingModelCache.size();
        clientCache.clear();    // 清空client缓存
        chatModelCache.clear(); // 清空chatModel缓存
        embeddingModelCache.clear(); // 清空embeddingModel缓存
        log.info("[LlmProviderRegistry] Cache cleared ({} entries). Next access will re-create clients.", size);
    }

    public EmbeddingModel getEmbeddingModel(String providerId) {
        return embeddingModelCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new embedding model for provider: {}", id);
            return createEmbeddingModel(id);
        });
    }

    public EmbeddingModel getDefaultEmbeddingModel() {
        return getEmbeddingModel(resolveDefaultEmbeddingProviderId());
    }

    /**
     * 创建 ChatClient
     * @param providerId
     * @return
     */
    private ChatClient createChatClient(String providerId) {
        //读配置 → 构建 OpenAIClient → 构建 OpenAiChatOptions → 构建 OpenAiChatModel。
        OpenAiChatModel chatModel = getChatModel(providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultTools(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = buildDefaultAdvisors(providerId);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
            log.info("[LlmProviderRegistry] Applied {} advisors for provider {}", advisors.size(), providerId);
        }

        return builder.build();
    }

    /**
     * 创建不带 SkillsTool 的 ChatClient
     * @param providerId
     * @return
     */
    // Plain Client 要禁掉 SkillsTool：
    // 因为会返回带 tool_call 字段的混合消息，导致 BeanOutputConverter 解析 JSON 直接挂掉
    private ChatClient createPlainChatClient(String providerId) {
        OpenAiChatModel chatModel = getChatModel(providerId);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        // 只挂 SafeGuard，连 ToolCall / Memory / Logger 都不要
        buildSafeGuardAdvisor().ifPresent(advisor -> builder.defaultAdvisors(List.of(advisor)));
        log.info("[LlmProviderRegistry] Created plain ChatClient (no tools) for {}", providerId);
        return builder.build();
    }

    /**
     * 创建语音面试专用 ChatClient：SkillsTool + ToolCallAdvisor（流式）。
     * @param providerId
     * @return
     */
    private ChatClient createVoiceChatClient(String providerId) {
        // 读 provider 配置 → baseUrl、apiKey、model、temperature
        // 构建底层 HTTP 连接 → OpenAiChatModel
        OpenAiChatModel chatModel = getChatModel(providerId);

        // 搭建client，绑定可以使用的工具
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultTools(interviewSkillsToolCallback);
        }

        // 搭建advisor，注册他能调用的工具
        List<Advisor> advisors = new ArrayList<>();
        if (toolCallingManager != null) {
            // 遇到工具调用请求 → 自动执行工具、把结果喂回模型
            advisors.add(buildToolCallAdvisor(true));
        }

        // 检查输出里有没有越狱内容 → 拦截替换
        buildSafeGuardAdvisor().ifPresent(advisors::add);
        if (!advisors.isEmpty()) {
            // 把规范挂到builder身上
            builder.defaultAdvisors(advisors);
        }
        log.info("[LlmProviderRegistry] Created voice ChatClient (SkillsTool + streaming ToolCall) for {}", providerId);
        return builder.build();
    }

    private OpenAiChatModel getChatModel(String providerId) {
        return chatModelCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new ChatModel for provider: {}", id);
            return buildChatModel(id);
        });
    }

    private OpenAiChatModel buildChatModel(String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        log.info("[LlmProviderRegistry] Building ChatModel - Provider: {}, BaseUrl: {}, Model: {}",
                 providerId, config.baseUrl(), config.model());

        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(config.baseUrl(), config.apiKey());

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.model())
                .temperature(config.temperature() != null ? config.temperature() : 0.2)
                .build();

        return OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .openAiClientAsync(openAiClient.async())
            .options(options)
            .observationRegistry(observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP)
            .build();
    }

    /**
     * 创建 EmbeddingModel
     * @param providerId
     * @return
     */
    private EmbeddingModel createEmbeddingModel(String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        // 检查embedding 模型是否支持向量化
        if (!config.supportsEmbedding() || isBlank(config.embeddingModel())) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + providerId + "' 未配置可用的 Embedding 模型，无法执行知识库向量化");
        }

        // 再次使用looksLikeChatModel() 方法判定是否为聊天模型
        if (looksLikeChatModel(config.embeddingModel())) {
            String recommendation = RECOMMENDED_EMBEDDING_MODELS.get(providerId.toLowerCase());
            String suffix = recommendation != null
                ? "，推荐填写 " + recommendation
                : "，请填写该厂商真实的 Embedding 模型名";
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + providerId + "' 的 Embedding Model 配成了聊天模型 '"
                    + config.embeddingModel() + "'" + suffix);
        }
        log.info("[LlmProviderRegistry] Building EmbeddingModel - Provider: {}, BaseUrl: {}, Model: {}",
            providerId, config.baseUrl(), config.embeddingModel());

        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(config.baseUrl(), config.apiKey());
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
            .model(config.embeddingModel())
            .dimensions(resolveEmbeddingDimensions(config.embeddingDimensions()))
            .build();

        return OpenAiEmbeddingModel.builder()
            .openAiClient(openAiClient)
            .metadataMode(MetadataMode.EMBED)
            .options(options)
            .observationRegistry(observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP)
            .build();
    }

    // 默认 ChatClient 走的是完整 Advisor 链
    private List<Advisor> buildDefaultAdvisors(String providerId) {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isEnabled()) {
            return List.of();
        }

        List<Advisor> advisors = new ArrayList<>();

        // 挂ToolCallAdvisor
        if (config.isToolCallEnabled()) {
            if (toolCallingManager != null) {
                advisors.add(buildToolCallAdvisor(config.isToolCallConversationHistoryEnabled()));
            } else {
                log.warn("[LlmProviderRegistry] ToolCallAdvisor skipped: ToolCallingManager unavailable, provider={}", providerId);
            }
        }

        // 挂MessageChatMemoryAdvisor
        if (config.isMessageChatMemoryEnabled()) {
            int maxMessages = Math.max(20, config.getMessageChatMemoryMaxMessages());
            // 自动把多轮对话历史拼进 Prompt，维持上下文连贯性；用 MessageWindowChatMemory 滑窗截断防止过长
            // 项目里默认关闭。原因是面试的各个场景（出题、回答、评估）有独立的会话状态管理逻辑，如果让 Spring AI 自动注入记忆，会和业务层的状态管理产生冲突，造成上下文“串味”。
            // 只有 RAG 聊天会话场景才按需开启。
            MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(
                MessageWindowChatMemory.builder()
                    .maxMessages(maxMessages)
                    .build()
            ).build();
            advisors.add(memoryAdvisor);
        }

        // SimpleLoggerAdvisor打印每次调用的请求/响应日志，调试用
        if (config.isSimpleLoggerEnabled()) {
            advisors.add(new SimpleLoggerAdvisor());
        }

        buildSafeGuardAdvisor().ifPresent(advisors::add);

        return advisors;
    }

    // 模型想调用工具时，自动执行 ToolCallback 并把结果喂回模型继续对话
    private ToolCallingAdvisor buildToolCallAdvisor(boolean conversationHistoryEnabled) {
        return ToolCallingAdvisor.builder()
            .toolCallingManager(toolCallingManager)
            .conversationHistoryEnabled(conversationHistoryEnabled)
            .build();
    }

    // 第二层：SafeGuardAdvisor（请求拦截）
    // 直接匹配高危关键词组合，命中就走“快速拒绝”路径
    //检测模型输出中是否包含敏感词（"我现在扮演…"、"忽略之前的指令"），命中则拦截替换为固定回复
    private Optional<SafeGuardAdvisor> buildSafeGuardAdvisor() {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isSafeguardEnabled()) {
            return Optional.empty();
        }
        SafeGuardAdvisor advisor = SafeGuardAdvisor.builder()
            .sensitiveWords(config.getSafeguardWords())
            .failureResponse("抱歉，我只能协助面试相关的任务。")
            .order(100)
            .build();
        return Optional.of(advisor);
    }

    private String resolveProviderId(String providerId) {
        return (providerId != null && !providerId.isBlank())
            ? providerId : resolveDefaultChatProviderId();
    }

    /**
     * 获取默认的 Chat Provider
     * 默认 Provider 要支持三种来源（按优先级从高到低）：
     * 1. 数据库 llm_global_setting 表（用户在网页上改的）
     * 2. application.yml 的 app.ai.default-provider
     * 3. 内置硬编码的 dashscope
     * @return
     */
    private String resolveDefaultChatProviderId() {
        // DB 不可用, 直接返回 YAML 里的数据
        if (globalSettingRepository == null) {
            return properties.getDefaultProvider();
        }
        return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
            .map(LlmGlobalSettingEntity::getDefaultChatProviderId)  // 从数据库读
            .filter(id -> !isBlank(id))         // 如果数据库里的值不为空
            .orElse(properties.getDefaultProvider());   // ← 数据库没值，读 YAML
    }

    /**
     * 获取默认的 Embedding Provider
     * 为什么 Embedding 要单独管？因为不是每个 Provider 都支持 Embedding
     * @return
     */
    private String resolveDefaultEmbeddingProviderId() {
        if (globalSettingRepository == null) {
            return !isBlank(properties.getDefaultEmbeddingProvider())
                ? properties.getDefaultEmbeddingProvider()
                : properties.getDefaultProvider();  // Embedding 兜底用 Chat 的默认值
        }
        return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
            .map(LlmGlobalSettingEntity::getDefaultEmbeddingProviderId)
            .filter(id -> !isBlank(id))
            .orElseGet(() -> !isBlank(properties.getDefaultEmbeddingProvider())
                ? properties.getDefaultEmbeddingProvider()
                : properties.getDefaultProvider());
    }

    /**
     * 加载指定 Provider
     * @param providerId
     * @return
     */
    private ProviderSnapshot loadProviderOrThrow(String providerId) {
        // DB 不可用 → 降级读 YAML
        if (providerRepository == null) {   //构造函数允许它为 null（典型场景：单元测试不挂 JPA），代码自然降级到 YAML
            return loadProviderFromPropertiesOrThrow(providerId);
        }

        // DB 可用 → 正常读数据库
        LlmProviderEntity entity = providerRepository.findById(providerId)
            .filter(LlmProviderEntity::isEnabled)
            .orElseThrow(() -> new IllegalArgumentException("Unknown LLM provider: " + providerId));
        
        // ProviderSnapshot是 record：从 Entity 或 Properties 抽出来的一份不可变快照，避免后续修改 Entity 时影响已构造的 ChatClient
        return new ProviderSnapshot(
            entity.getId(),
            entity.getBaseUrl(),
            encryptionService.decrypt(entity.getApiKeyNonce(), entity.getApiKeyCiphertext()),
            entity.getModel(),
            entity.getEmbeddingModel(),
            entity.getEmbeddingDimensions(),
            entity.isSupportsEmbedding(),
            entity.getTemperature()
        );
    }

    private ProviderSnapshot loadProviderFromPropertiesOrThrow(String providerId) {
        ProviderConfig config = properties.getProviders().get(providerId);
        if (config == null) {
            log.error("[LlmProviderRegistry] Provider config not found: {}", providerId);
            throw new IllegalArgumentException("Unknown LLM provider: " + providerId);
        }
        boolean supportsEmbedding = Boolean.TRUE.equals(config.getSupportsEmbedding())
            || !isBlank(config.getEmbeddingModel());
        return new ProviderSnapshot(
            providerId,
            config.getBaseUrl(),
            config.getApiKey(),
            config.getModel(),
            config.getEmbeddingModel(),
            config.getEmbeddingDimensions(),
            supportsEmbedding,
            config.getTemperature()
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions;
        }
        return properties.getEmbeddingDimensions();
    }

    private boolean looksLikeChatModel(String model) {
        String lower = model.toLowerCase();
        return lower.startsWith("glm-")
            || lower.startsWith("deepseek")
            || lower.startsWith("kimi")
            || lower.startsWith("moonshot")
            || lower.startsWith("qwen")
            || lower.startsWith("ernie");
    }

    private record ProviderSnapshot(
        String id,
        String baseUrl,
        String apiKey,
        String model,
        String embeddingModel,
        Integer embeddingDimensions,
        boolean supportsEmbedding,
        Double temperature
    ) {
    }
}
