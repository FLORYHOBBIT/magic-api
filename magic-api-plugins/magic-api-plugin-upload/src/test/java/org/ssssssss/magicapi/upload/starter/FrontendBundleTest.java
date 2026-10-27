package org.ssssssss.magicapi.upload.starter;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 前端插件脚本必须位于 magic-editor/plugins 下，文件名与 {@link MagicUploadConfiguration#plugin()} 一致，编辑器才能加载
 */
class FrontendBundleTest {

	@Test
	void pluginScriptIsPackaged() throws Exception {
		String name = new MagicUploadConfiguration(new MagicUploadProperties(), null).plugin().getJavascriptFilename();
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("magic-editor/plugins/" + name)) {
			assertThat(in).as("前端构建产物 magic-editor/plugins/" + name).isNotNull();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int len;
			while ((len = in.read(buffer)) != -1) {
				out.write(buffer, 0, len);
			}
			String script = new String(out.toByteArray(), StandardCharsets.UTF_8);
			// 全局变量名与资源类型需与后端一致
			assertThat(script).contains("var MagicUpload").contains("upload-channel").contains("/upload/channel/test");
		}
	}
}
