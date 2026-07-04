package interview.guide.common.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RustFS (S3兼容) 存储配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.storage")
//Spring Boot 在启动时，把 Environment 里的属性按 prefix 映射到 Java 对象的字段，并做类型转换
//作用：把 app.storage.* 转成强类型的 StorageConfigProperties，供 S3 客户端和文件服务使用。
public class StorageConfigProperties {

    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucket;
    private String region = "us-east-1";
    private Duration apiCallTimeout = Duration.ofSeconds(60);
    private Duration apiCallAttemptTimeout = Duration.ofSeconds(20);
    private boolean autoCreateBucket = true;
}
