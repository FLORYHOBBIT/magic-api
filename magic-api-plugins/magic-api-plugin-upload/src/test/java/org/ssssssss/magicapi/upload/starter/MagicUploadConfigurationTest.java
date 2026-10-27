package org.ssssssss.magicapi.upload.starter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MagicUploadConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(MagicUploadConfiguration.class));

	@Test
	void loadedByDefault() {
		runner.run(context -> {
			assertThat(context).hasSingleBean(MagicUploadConfiguration.class);
			MagicUploadProperties properties = context.getBean(MagicUploadProperties.class);
			assertThat(properties.getDefaultBatchSize()).isEqualTo(1000);
			assertThat(properties.getDefaultRetry()).isEqualTo(3);
			assertThat(properties.isInitSchema()).isTrue();
			assertThat(context.getBean(MagicUploadConfiguration.class).plugin().getName())
					.isEqualTo(MagicUploadConfiguration.PLUGIN_NAME);
			assertThat(context).hasSingleBean(org.ssssssss.magicapi.upload.store.UploadStore.class);
			assertThat(context).hasSingleBean(org.ssssssss.magicapi.upload.service.UploadJobRunner.class);
			assertThat(context).hasSingleBean(org.ssssssss.magicapi.upload.script.UploadModule.class);
			assertThat(context).hasSingleBean(org.ssssssss.magicapi.upload.resource.UploadChannelMagicResourceStorage.class);
			assertThat(context).hasSingleBean(org.ssssssss.magicapi.upload.resource.UploadChannelMagicDynamicRegistry.class);
			assertThat(context).hasSingleBean(org.ssssssss.magicapi.upload.alert.UploadAlertService.class);
			assertThat(context.getBean(org.ssssssss.magicapi.upload.resource.UploadChannelEncryptor.class).isEnabled()).isFalse();
			assertThat(context.getBean(MagicUploadConfiguration.class).plugin().getJavascriptFilename())
					.isEqualTo("magic-upload.1.0.0.iife.js");
		});
	}

	@Test
	void bindsProperties() {
		runner.withPropertyValues(
				"magic-api.upload.datasource=report",
				"magic-api.upload.default-batch-size=500",
				"magic-api.upload.retry-backoff=2s",
				"magic-api.upload.lock-timeout=30m"
		).run(context -> {
			MagicUploadProperties properties = context.getBean(MagicUploadProperties.class);
			assertThat(properties.getDatasource()).isEqualTo("report");
			assertThat(properties.getDefaultBatchSize()).isEqualTo(500);
			assertThat(properties.getRetryBackoff()).isEqualTo(Duration.ofSeconds(2));
			assertThat(properties.getLockTimeout()).isEqualTo(Duration.ofMinutes(30));
		});
	}

	@Test
	void collectsCustomSignersNotifiersAndSecretKey() {
		runner.withPropertyValues("magic-api.upload.secret-key=abc", "magic-api.upload.alert.webhook.url=http://127.0.0.1:1/hook")
				.withBean("hisSigner", org.ssssssss.magicapi.upload.channel.UploadSigner.class, () -> new org.ssssssss.magicapi.upload.channel.UploadSigner() {
					@Override
					public String name() {
						return "his";
					}

					@Override
					public void sign(org.ssssssss.magicapi.upload.channel.SignContext context) {
					}
				})
				.withBean("smsNotifier", org.ssssssss.magicapi.upload.alert.UploadAlertNotifier.class, () -> alert -> {
				})
				.run(context -> {
					assertThat(context.getBean(org.ssssssss.magicapi.upload.resource.UploadChannelEncryptor.class).isEnabled()).isTrue();
					assertThat(context.getBean(org.ssssssss.magicapi.upload.channel.ChannelFactory.class).signerNames()).containsExactly("his");
					assertThat(context).hasNotFailed();
				});
	}

	@Test
	void disabledByProperty() {
		runner.withPropertyValues("magic-api.upload.enable=false")
				.run(context -> assertThat(context).doesNotHaveBean(MagicUploadConfiguration.class));
	}
}
