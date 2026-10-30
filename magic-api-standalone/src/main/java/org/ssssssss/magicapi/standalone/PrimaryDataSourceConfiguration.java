package org.ssssssss.magicapi.standalone;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 只有配置了 spring.datasource.url 时才启用 Spring Boot 的主数据源自动配置。
 * <p>
 * 默认情况下 DataSourceAutoConfiguration 在没有 url 且没有内嵌数据库时会启动失败，
 * 因此在主类中排除它，在这里按需导入。
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@Import(DataSourceAutoConfiguration.class)
public class PrimaryDataSourceConfiguration {
}
