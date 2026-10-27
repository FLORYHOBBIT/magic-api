package org.ssssssss.magicapi.upload.starter;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.ssssssss.magicapi.core.config.MagicPluginConfiguration;
import org.ssssssss.magicapi.core.model.Plugin;
import org.ssssssss.magicapi.datasource.model.MagicDynamicDataSource;
import org.ssssssss.magicapi.upload.engine.BatchEngine;
import org.ssssssss.magicapi.upload.service.UploadJobRunner;
import org.ssssssss.magicapi.upload.store.JdbcUploadStore;
import org.ssssssss.magicapi.upload.store.UploadStore;

/**
 * 上传插件自动配置
 * <p>
 * 通过 magic-api.upload.enable=false 关闭
 */
@Configuration
@ConditionalOnProperty(prefix = "magic-api.upload", name = "enable", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MagicUploadProperties.class)
public class MagicUploadConfiguration implements MagicPluginConfiguration {

	public static final String PLUGIN_NAME = "数据上传";

	private final MagicUploadProperties properties;

	public MagicUploadConfiguration(MagicUploadProperties properties) {
		this.properties = properties;
	}

	public MagicUploadProperties getProperties() {
		return properties;
	}

	/**
	 * 存储使用 magic-api.upload.datasource 指定的数据源（为空时用主数据源），在第一次使用时才解析
	 */
	@Bean
	@ConditionalOnMissingBean
	public UploadStore magicUploadStore(ObjectProvider<MagicDynamicDataSource> dynamicDataSourceProvider) {
		String key = StringUtils.trimToNull(properties.getDatasource());
		return new JdbcUploadStore(() -> {
			MagicDynamicDataSource dynamicDataSource = dynamicDataSourceProvider.getIfAvailable();
			if (dynamicDataSource == null || dynamicDataSource.isEmpty()) {
				return null;
			}
			// 新建 JdbcTemplate，不继承数据源上配置的最大返回行数
			return new JdbcTemplate(dynamicDataSource.getDataSource(key).getDataSource());
		}, properties.getTablePrefix(), properties.isInitSchema());
	}

	@Bean
	@ConditionalOnMissingBean
	public BatchEngine magicUploadBatchEngine() {
		return new BatchEngine();
	}

	@Bean
	@ConditionalOnMissingBean
	public UploadJobRunner magicUploadJobRunner(UploadStore uploadStore, BatchEngine batchEngine) {
		return new UploadJobRunner(uploadStore, batchEngine, properties.getLockTimeout(), properties.isRecordEmptyRuns());
	}

	@Override
	public Plugin plugin() {
		return new Plugin(PLUGIN_NAME);
	}
}
