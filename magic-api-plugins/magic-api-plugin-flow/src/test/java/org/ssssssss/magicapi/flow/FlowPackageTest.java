package org.ssssssss.magicapi.flow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.ssssssss.magicapi.flow.engine.FlowEngine;
import org.ssssssss.magicapi.flow.model.FlowDefinitions;
import org.ssssssss.magicapi.flow.model.FlowInfo;
import org.ssssssss.magicapi.flow.service.FlowInfoMagicResourceStorage;
import org.ssssssss.magicapi.flow.service.FlowTemplates;
import org.ssssssss.magicapi.flow.support.AbstractFlowTest;
import org.ssssssss.magicapi.flow.support.FlowVariables;
import org.ssssssss.magicapi.upload.resource.UploadChannelInfo;
import org.ssssssss.magicapi.upload.resource.UploadChannelMagicResourceStorage;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 10 / 11：场景模板、配置包导出导入与差异项
 */
class FlowPackageTest extends AbstractFlowTest {

	@Autowired
	FlowEngine engine;

	@Autowired
	FlowTemplates templates;

	@Autowired
	FlowVariables variables;

	@Test
	void builtInTemplatesProduceValidFlows() throws Exception {
		MockMvc mvc = mvc();
		JsonNode list = data(mvc.perform(get("/magic/web/flow/templates")));
		assertThat(list.size()).isEqualTo(4);
		for (JsonNode template : list) {
			assertThat(template.has("definition")).isFalse();
			assertThat(template.get("params").size()).isGreaterThan(0);
			String definition = data(mvc.perform(post("/magic/web/flow/template/apply").param("id", template.get("id").asText()))).asText();
			engine.prepare(FlowDefinitions.parse(definition));
			assertThat(definition).doesNotContain("%{");
		}
		String custom = templates.apply("http-query", Collections.singletonMap("sql", "select 'x' as \"v\" from dual where a = #{input.a}"));
		assertThat(FlowDefinitions.parse(custom).findNode("q").getConfig().get("sql")).isEqualTo("select 'x' as \"v\" from dual where a = #{input.a}");
		JsonNode missing = json(mvc.perform(post("/magic/web/flow/template/apply").param("id", "nope")));
		assertThat(missing.get("code").asInt()).isEqualTo(5019);
	}

	@Test
	void exportImportMovesFlowsChannelsAndAsksForSecrets() throws Exception {
		saveChannel("insur", "http", options("url", "http://10.0.0.1/insur", "password", "Secret#1", "successField", "code", "successValues", "0"));
		FlowInfo flow = saveFlow("insur", "settle-upload", q("{`nodes`:["
				+ "{`id`:`start`,`type`:`timer`,`config`:{`cron`:`0 0 2 * * ?`}},"
				+ "{`id`:`send`,`type`:`call`,`config`:{`channel`:`insur`,`body`:`{org: '${var.org-code}'}`}}],"
				+ "`edges`:[{`source`:`start`,`target`:`send`}]}"), false);
		variables.save("org-code", "H001", false, "机构编码");
		MockMvc mvc = mvc();

		String base64 = data(mvc.perform(post("/magic/web/flow/package/export").contentType(MediaType.APPLICATION_JSON)
				.content("{\"ids\":[\"" + flow.getId() + "\"]}"))).asText();
		Map<String, String> entries = unzip(Base64.getDecoder().decode(base64));
		assertThat(entries.keySet()).contains("manifest.json", "flows/" + flow.getId() + ".ms");
		String channelFile = entries.keySet().stream().filter(it -> it.startsWith("channels/")).findFirst().get();
		// 密码不出包，地址变成差异项
		assertThat(entries.get(channelFile)).doesNotContain("Secret#1").doesNotContain("ENC(").contains("${var.insur.password}").contains("${var.insur.url}");
		JsonNode manifest = MAPPER.readTree(entries.get("manifest.json"));
		Map<String, JsonNode> declared = new LinkedHashMap<>();
		manifest.get("variables").forEach(it -> declared.put(it.get("name").asText(), it));
		assertThat(declared.keySet()).containsExactlyInAnyOrder("org-code", "insur.password", "insur.url");
		assertThat(declared.get("insur.password").get("secret").asBoolean()).isTrue();
		assertThat(declared.get("insur.url").get("example").asText()).isEqualTo("http://10.0.0.1/insur");
		assertThat(declared.get("org-code").get("description").asText()).isEqualTo("机构编码");
		assertThat(declared.get("org-code").get("example").asText()).isEqualTo("H001");

		// 模拟另一家医院：删掉流程、连接和差异项
		resources().delete(flow.getId());
		UploadChannelInfo channel = channel("insur");
		resources().delete(channel.getId());
		variables.remove("org-code");

		Map<String, Object> request = new LinkedHashMap<>();
		request.put("content", base64);
		JsonNode preview = data(mvc.perform(post("/magic/web/flow/package/preview").contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(request))));
		assertThat(preview.at("/flows/0/action").asText()).isEqualTo("create");
		assertThat(preview.at("/flows/0/path").asText()).isEqualTo("/insur/settle-upload");
		assertThat(preview.at("/channels/0/action").asText()).isEqualTo("create");
		assertThat(preview.get("variables").size()).isEqualTo(3);

		// 差异项没填完不导入
		JsonNode rejected = json(mvc.perform(post("/magic/web/flow/package/import").contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(request))));
		assertThat(rejected.get("code").asInt()).isEqualTo(5018);
		assertThat(rejected.get("message").asText()).contains("org-code").contains("insur.password");
		assertThat(resources().<FlowInfo>files(FlowInfoMagicResourceStorage.FOLDER)).noneMatch(it -> "settle-upload".equals(it.getPath()));

		Map<String, String> values = new LinkedHashMap<>();
		values.put("org-code", "H999");
		values.put("insur.password", "Other#2");
		values.put("insur.url", "http://192.168.9.9/insur");
		request.put("values", values);
		JsonNode imported = data(mvc.perform(post("/magic/web/flow/package/import").contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(request))));
		assertThat(imported.at("/flows/0/action").asText()).isEqualTo("create");
		assertThat(Files.exists(Paths.get(imported.get("backup").asText()))).isTrue();

		FlowInfo restored = resources().file(flow.getId());
		assertThat(restored).isNotNull();
		assertThat(restored.getScript()).contains("${var.org-code}");
		assertThat(resources().getGroupPath(restored.getGroupId())).isEqualTo("insur");
		UploadChannelInfo restoredChannel = channel("insur");
		assertThat(restoredChannel.getOptions().get("url")).isEqualTo("http://192.168.9.9/insur");
		assertThat(restoredChannel.getOptions().get("password")).isEqualTo("Other#2");
		// 落盘时密码加密
		try (java.util.stream.Stream<java.nio.file.Path> files = Files.walk(Paths.get(context.getEnvironment().getProperty("magic-api.resource.location"), "upload-channel"))) {
			String content = files.filter(Files::isRegularFile).map(p -> {
				try {
					return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
				} catch (java.io.IOException e) {
					throw new IllegalStateException(e);
				}
			}).filter(it -> it.contains("192.168.9.9")).findFirst().orElse("");
			assertThat(content).contains("ENC(").doesNotContain("Other#2");
		}
		assertThat(variables.value("org-code")).isEqualTo("H999");
		assertThat(variables.value("insur.password")).isEqualTo("Other#2");
		assertThat(variables.find("insur.password").getValue()).startsWith("ENC(");

		// 再导一次：覆盖，已配置的差异项可不填
		request.remove("values");
		JsonNode again = data(mvc.perform(post("/magic/web/flow/package/import").contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(request))));
		assertThat(again.at("/flows/0/action").asText()).isEqualTo("overwrite");
		assertThat(again.at("/channels/0/action").asText()).isEqualTo("overwrite");

		JsonNode vars = data(mvc.perform(get("/magic/web/flow/variables")));
		assertThat(vars.get("missing")).isEmpty();

		JsonNode bad = json(mvc.perform(post("/magic/web/flow/package/preview").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8)) + "\"}")));
		assertThat(bad.get("code").asInt()).isEqualTo(5018);
	}

	private static UploadChannelInfo channel(String name) {
		UploadChannelInfo found = resources().<UploadChannelInfo>files(UploadChannelMagicResourceStorage.FOLDER).stream()
				.filter(it -> name.equals(it.getName())).findFirst().orElse(null);
		return found == null ? null : resources().file(found.getId());
	}

	private static Map<String, String> unzip(byte[] bytes) throws Exception {
		Map<String, String> entries = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
				byte[] buffer = new byte[4096];
				int len;
				while ((len = zip.read(buffer)) != -1) {
					out.write(buffer, 0, len);
				}
				entries.put(entry.getName(), new String(out.toByteArray(), StandardCharsets.UTF_8));
			}
		}
		return entries;
	}
}
