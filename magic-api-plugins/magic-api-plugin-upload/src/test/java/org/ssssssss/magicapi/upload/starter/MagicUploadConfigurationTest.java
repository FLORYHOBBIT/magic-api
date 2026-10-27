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
	void disabledByProperty() {
		runner.withPropertyValues("magic-api.upload.enable=false")
				.run(context -> assertThat(context).doesNotHaveBean(MagicUploadConfiguration.class));
	}
}
