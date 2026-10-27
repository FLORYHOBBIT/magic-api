package org.ssssssss.magicapi.upload.starter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.ssssssss.magicapi.core.config.MagicPluginConfiguration;
import org.ssssssss.magicapi.core.model.Plugin;

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

	@Override
	public Plugin plugin() {
		return new Plugin(PLUGIN_NAME);
	}
}
