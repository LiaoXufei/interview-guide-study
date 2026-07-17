package interview.guide.modules.llmprovider.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LlmProviderEntity
 * 配置来自两个数据源：
 * 1. 表名 llm_provider_config，对应 LlmProviderEntity。运维通过前端页面或 REST API 管理，支持运行时增删改、API Key 加密落库。
 * 2. application.yml 里的 app.ai.providers.*，使用YAML 配置兜底
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "llm_provider_config")
public class LlmProviderEntity {

  // 供应商标识（也是缓存 key）
  @Id
  @Column(length = 64)
  private String id;

  // API 端点地址
  @Column(name = "base_url", nullable = false, length = 512)
  private String baseUrl;

  //AES-GCM 加密后的 API Key
  @Column(name = "api_key_ciphertext", nullable = false, length = 4096)
  private String apiKeyCiphertext;

  @Column(name = "api_key_nonce", nullable = false, length = 64)
  private String apiKeyNonce;

  // 聊天模型名
  @Column(nullable = false, length = 128)
  private String model;

  // 向量模型名（可选）
  @Column(name = "embedding_model", length = 128)
  private String embeddingModel;

  // 向量维度
  @Column(name = "embedding_dimensions")
  private Integer embeddingDimensions;

  // 该供应商是否支持 Embedding
  @Column(name = "supports_embedding", nullable = false)
  private boolean supportsEmbedding;

  // 默认温度参数为0.2
  private Double temperature;

  // 是否启用（软开关）
  @Column(nullable = false)
  private boolean enabled;

  // 是否内置 Provider（不可删除）
  @Column(nullable = false)
  private boolean builtin;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  void prePersist() {
    LocalDateTime now = LocalDateTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
