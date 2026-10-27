package org.ssssssss.magicapi.upload.starter;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.ssssssss.magicapi.core.config.MagicPluginConfiguration;
import org.ssssssss.magicapi.core.model.Plugin;
import org.ssssssss.magicapi.core.web.MagicControllerRegister;
import org.ssssssss.magicapi.datasource.model.MagicDynamicDataSource;
import org.ssssssss.magicapi.modules.db.dialect.Dialect;
import org.ssssssss.magicapi.modules.db.dialect.DialectAdapter;
import org.ssssssss.magicapi.upload.alert.LogAlertNotifier;
import org.ssssssss.magicapi.upload.alert.UploadAlertNotifier;
import org.ssssssss.magicapi.upload.alert.UploadAlertService;
import org.ssssssss.magicapi.upload.alert.WebhookAlertNotifier;
import org.ssssssss.magicapi.upload.channel.ChannelFactory;
import org.ssssssss.magicapi.upload.channel.UploadSigner;
import org.ssssssss.magicapi.upload.engine.BatchEngine;
import org.ssssssss.magicapi.upload.resource.*;
import org.ssssssss.magicapi.upload.script.UploadModule;
import org.ssssssss.magicapi.upload.service.UploadJobRunner;
import org.ssssssss.magicapi.upload.store.JdbcUploadStore;
import org.ssssssss.magicapi.upload.store.UploadStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

	private static final Logger logger = LoggerFactory.getLogger(MagicUploadConfiguration.class);

	private final MagicUploadProperties properties;

	private final ObjectProvider<UploadChannelProvider> channelProvider;

	public MagicUploadConfiguration(MagicUploadProperties properties, ObjectProvider<UploadChannelProvider> channelProvider) {
		this.properties = properties;
		this.channelProvider = channelProvider;
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
	public UploadAlertService magicUploadAlertService(ObjectProvider<List<UploadAlertNotifier>> notifiersProvider) {
		MagicUploadProperties.Alert alert = properties.getAlert();
		List<UploadAlertNotifier> notifiers = new ArrayList<>();
		if (alert.isEnable()) {
			notifiers.add(new LogAlertNotifier());
			MagicUploadProperties.Webhook webhook = alert.getWebhook();
			if (StringUtils.isNotBlank(webhook.getUrl())) {
				notifiers.add(new WebhookAlertNotifier(webhook.getUrl(), webhook.getType(), webhook.getSecret(), webhook.getTimeout()));
			}
			notifiers.addAll(notifiersProvider.getIfAvailable(Collections::emptyList));
		}
		return new UploadAlertService(notifiers, alert.getHospital(), alert.getSilence(), alert.isAlertOnPartial());
	}

	@Bean
	@ConditionalOnMissingBean
	public UploadJobRunner magicUploadJobRunner(UploadStore uploadStore, BatchEngine batchEngine, UploadAlertService alertService) {
		UploadJobRunner runner = new UploadJobRunner(uploadStore, batchEngine, properties.getLockTimeout(), properties.isRecordEmptyRuns());
		runner.setAlertService(alertService);
		return runner;
	}

	@Bean
	@ConditionalOnMissingBean
	public ChannelFactory magicUploadChannelFactory(ObjectProvider<List<UploadSigner>> signersProvider) {
		return new ChannelFactory(signersProvider.getIfAvailable(Collections::emptyList));
	}

	@Bean
	@ConditionalOnMissingBean
	public UploadChannelEncryptor magicUploadChannelEncryptor() {
		AesUploadChannelEncryptor encryptor = new AesUploadChannelEncryptor(properties.getSecretKey());
		if (!encryptor.isEnabled()) {
			logger.warn("未配置 magic-api.upload.secret-key，上传通道中的密码、密钥将以明文保存，生产环境请务必配置");
		}
		return encryptor;
	}

	@Bean
	@ConditionalOnMissingBean
	public UploadChannelMagicResourceStorage magicUploadChannelStorage(ChannelFactory channelFactory, UploadChannelEncryptor encryptor) {
		return new UploadChannelMagicResourceStorage(channelFactory, encryptor);
	}

	@Bean
	@ConditionalOnMissingBean
	public UploadChannelMagicDynamicRegistry magicUploadChannelRegistry(UploadChannelMagicResourceStorage storage) {
		return new UploadChannelMagicDynamicRegistry(storage);
	}

	@Bean
	@ConditionalOnMissingBean
	public UploadChannelProvider magicUploadChannelProvider(UploadChannelMagicDynamicRegistry registry,
															UploadChannelMagicResourceStorage storage,
															ChannelFactory channelFactory) {
		return new UploadChannelProvider(registry, storage, channelFactory);
	}

	@Bean
	@ConditionalOnMissingBean
	public UploadModule magicUploadModule(UploadJobRunner runner, ChannelFactory channelFactory,
										  ObjectProvider<MagicDynamicDataSource> dynamicDataSourceProvider,
										  ObjectProvider<List<Dialect>> dialectsProvider) {
		// 与 SQL 模块相同：内置方言 + 用户注册的方言 Bean
		DialectAdapter dialectAdapter = new DialectAdapter();
		dialectsProvider.getIfAvailable(Collections::emptyList).forEach(dialectAdapter::add);
		return new UploadModule(runner, channelFactory, dynamicDataSourceProvider::getIfAvailable, dialectAdapter,
				channelProvider::getIfAvailable, properties.getDefaultBatchSize(), properties.getDefaultRetry(), properties.getRetryBackoff());
	}

	@Override
	public Plugin plugin() {
		return new Plugin(PLUGIN_NAME, "MagicUpload", "magic-upload.1.0.0.iife.js");
	}

	@Override
	public MagicControllerRegister controllerRegister() {
		return (mapping, configuration) -> mapping.registerController(new UploadChannelController(configuration, channelProvider.getObject()));
	}
}
