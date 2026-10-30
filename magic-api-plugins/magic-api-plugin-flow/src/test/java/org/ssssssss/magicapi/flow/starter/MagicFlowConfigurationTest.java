package org.ssssssss.magicapi.flow.starter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.ssssssss.magicapi.flow.service.FlowInfoMagicResourceStorage;
import org.ssssssss.magicapi.flow.service.FlowMagicDynamicRegistry;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MagicFlowConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(MagicFlowConfiguration.class));

	@Test
	void loadedByDefault() {
		runner.run(context -> {
			assertThat(context).hasSingleBean(FlowInfoMagicResourceStorage.class);
			assertThat(context).hasSingleBean(FlowMagicDynamicRegistry.class);
			assertThat(context.getBean(org.ssssssss.magicapi.flow.engine.FlowEngine.class).types())
					.containsExactlyInAnyOrder("query", "write", "script", "return");
			assertThat(context.getBean(MagicFlowConfiguration.class).plugin().getJavascriptFilename())
					.isEqualTo("magic-flow.1.0.0.iife.js");
		});
	}

	@Test
	void disabledByProperty() {
		runner.withPropertyValues("magic-api.flow.enable=false")
				.run(context -> assertThat(context).doesNotHaveBean(MagicFlowConfiguration.class));
	}

	/**
	 * 前端插件脚本必须位于 magic-editor/plugins 下，文件名与 plugin() 一致，编辑器才能加载
	 */
	@Test
	void pluginScriptIsPackaged() throws Exception {
		String name = new MagicFlowConfiguration(null).plugin().getJavascriptFilename();
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("magic-editor/plugins/" + name)) {
			assertThat(in).as("前端构建产物 magic-editor/plugins/" + name).isNotNull();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int len;
			while ((len = in.read(buffer)) != -1) {
				out.write(buffer, 0, len);
			}
			String script = new String(out.toByteArray(), StandardCharsets.UTF_8);
			assertThat(script).contains("var MagicFlow").contains("type: \"flow\"").contains("/flow/debug").contains("flow-debug");
		}
	}
}
