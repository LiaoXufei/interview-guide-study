package interview.guide.modules.llmprovider.service;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderBootstrapService {

  private final LlmProviderProperties properties;
  private final LlmProviderRepository providerRepository;
  private final LlmGlobalSettingRepository globalSettingRepository;
  private final ApiKeyEncryptionService encryptionService;

  @PostConstruct
  @Transactional
  public void seedProvidersIfNecessary() {
    // 把 application.yml 里的 providers 写进 DB
    if (providerRepository.count() == 0) {  // 这个判断很关键——只在第一次启动时迁移，避免每次启动覆盖运维在网页上改的配置。
      seedProviders();
    }
    // 兜底默认 Chat/Embedding Provider
    ensureGlobalSetting();
  }

  /**
   * 迁移 providers
   */
  private void seedProviders() {
    Map<String, ProviderConfig> providers = properties.getProviders();
    if (providers == null || providers.isEmpty()) {
      log.warn("No app.ai.providers seed configuration found");
      return;
    }

    providers.forEach((id, config) -> {
      if (isBlank(id) || config == null || isBlank(config.getBaseUrl()) || isBlank(config.getModel())) {
        log.warn("Skip invalid provider seed: id={}", id);
        return;
      }

      // 1. 把 YAML 里的 API Key 加密（AES-GCM）
      ApiKeyEncryptionService.EncryptedValue encrypted =
          encryptionService.encrypt(config.getApiKey() != null ? config.getApiKey() : "");
      boolean supportsEmbedding = Boolean.TRUE.equals(config.getSupportsEmbedding())
          || !isBlank(config.getEmbeddingModel());

      // 2. 构建数据库实体
      LlmProviderEntity entity = LlmProviderEntity.builder()
          .id(id)                           // 唯一标识"dashscope"
          .baseUrl(config.getBaseUrl())     // 接口地址"https://dashscope.aliyuncs.com/..."
          .apiKeyNonce(encrypted.nonce())   // 加密后的密文
          .apiKeyCiphertext(encrypted.ciphertext()) // 加密后的密文
          .model(config.getModel())         // 模型名称
          .embeddingModel(trimOrNull(config.getEmbeddingModel())) // 嵌入模型名称
          .embeddingDimensions(resolveEmbeddingDimensions(config.getEmbeddingDimensions()))
          .supportsEmbedding(supportsEmbedding)
          .temperature(config.getTemperature())
          .enabled(true)            // 是否启用
          .builtin(true)            // 是否内置
          .build();

      // 3. 存入数据库
      providerRepository.save(entity);
    });
    log.info("Seeded {} LLM providers from application configuration", providerRepository.count());
  }

  /**
   * 兜底初始化全局设置
   */
  private void ensureGlobalSetting() {
    if (globalSettingRepository.existsById(LlmGlobalSettingEntity.SINGLETON_ID)) {
      return;
    }
    String defaultChatProvider = resolveExistingProvider(
        properties.getDefaultProvider(),
        providerRepository.findAll().stream().findFirst().map(LlmProviderEntity::getId).orElse("dashscope")
    );
    String configuredEmbeddingProvider = !isBlank(properties.getDefaultEmbeddingProvider())
        ? properties.getDefaultEmbeddingProvider()
        : defaultChatProvider;
    String defaultEmbeddingProvider = resolveExistingEmbeddingProvider(configuredEmbeddingProvider, defaultChatProvider);

    globalSettingRepository.save(LlmGlobalSettingEntity.builder()
        .id(LlmGlobalSettingEntity.SINGLETON_ID)
        .defaultChatProviderId(defaultChatProvider)
        .defaultEmbeddingProviderId(defaultEmbeddingProvider)
        .build());
    log.info("Initialized LLM global setting: chatProvider={}, embeddingProvider={}",
        defaultChatProvider, defaultEmbeddingProvider);
  }

  private String resolveExistingProvider(String preferredProvider, String fallbackProvider) {
    if (!isBlank(preferredProvider) && providerRepository.existsById(preferredProvider)) {
      return preferredProvider;
    }
    return fallbackProvider;
  }

  private String resolveExistingEmbeddingProvider(String preferredProvider, String fallbackProvider) {
    return providerRepository.findById(preferredProvider)
        .filter(this::canProvideEmbedding)
        .map(LlmProviderEntity::getId)
        .orElseGet(() -> providerRepository.findAll().stream()
            .filter(this::canProvideEmbedding)
            .findFirst()
            .map(LlmProviderEntity::getId)
            .orElse(fallbackProvider));
  }

  private boolean canProvideEmbedding(LlmProviderEntity provider) {
    return provider.isEnabled()
        && provider.isSupportsEmbedding()
        && !isBlank(provider.getEmbeddingModel());
  }

  private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
    if (configuredDimensions != null && configuredDimensions > 0) {
      return configuredDimensions;
    }
    return properties.getEmbeddingDimensions();
  }

  private String trimOrNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
