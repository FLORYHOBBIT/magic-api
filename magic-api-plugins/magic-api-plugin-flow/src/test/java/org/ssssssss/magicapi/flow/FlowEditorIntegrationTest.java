package org.ssssssss.magicapi.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.ssssssss.magicapi.flow.model.FlowInfo;
import org.ssssssss.magicapi.flow.service.FlowMagicDynamicRegistry;
import org.ssssssss.magicapi.flow.support.FlowTestApplication;
import org.ssssssss.magicapi.utils.ROT13Utils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 按编辑器的真实协议操作：新建流程分组 → 保存流程 → 重启应用 → 流程仍在且已注册。
 */
class FlowEditorIntegrationTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String DEFINITION = "{\"nodes\":[{\"id\":\"start\",\"type\":\"script\",\"name\":\"开始\",\"config\":{\"script\":\"return 1\"}}],\"edges\":[]}";

	@TempDir
	Path workspace;

	@Test
	void savedFlowSurvivesRestart() throws Exception {
		String flowId;
		try (ConfigurableApplicationContext context = start()) {
			MockMvc mvc = mvc(context);

			// 1. 新建分组（类型 flow，与前端 resource.type 一致）
			String groupId = data(mvc.perform(post("/magic/web/resource/folder/save")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\":\"医保\",\"path\":\"insur\",\"type\":\"flow\",\"parentId\":\"0\"}")))
					.asText();
			assertThat(groupId).isNotBlank();

			// 2. 按编辑器格式保存流程：元数据 JSON + 分隔线 + 流程定义，整体 ROT13
			String file = "{\"name\":\"结算查询\",\"path\":\"settle\",\"groupId\":\"" + groupId + "\",\"enabled\":true,\"description\":\"Task1 演示\"}"
					+ "\r\n================================\r\n" + DEFINITION;
			flowId = data(mvc.perform(post("/magic/web/resource/file/flow/save")
					.contentType(MediaType.TEXT_PLAIN)
					.content(ROT13Utils.encrypt(file)))).asText();
			assertThat(flowId).isNotBlank();

			// 3. 定义不合法时拒绝保存
			String broken = "{\"name\":\"坏流程\",\"path\":\"bad\",\"groupId\":\"" + groupId + "\"}"
					+ "\r\n================================\r\n{\"nodes\":[{\"id\":\"a\"}]}";
			JsonNode rejected = json(mvc.perform(post("/magic/web/resource/file/flow/save")
					.contentType(MediaType.TEXT_PLAIN)
					.content(ROT13Utils.encrypt(broken))));
			assertThat(rejected.get("code").asInt()).isEqualTo(5005);
			assertThat(rejected.get("message").asText()).contains("缺少类型");

			// 4. 校验接口
			JsonNode validated = json(mvc.perform(post("/magic/web/flow/validate").param("id", flowId)));
			assertThat(validated.get("code").asInt()).isEqualTo(1);
			assertThat(validated.get("data").asText()).contains("\"start\"");

			assertThat(context.getBean(FlowMagicDynamicRegistry.class).getMapping("/insur/settle")).isNotNull();

			// 5. magic-api 自带的全量导出包含流程文件
			byte[] zip = mvc.perform(post("/magic/web/download")).andReturn().getResponse().getContentAsByteArray();
			java.util.List<String> entries = new java.util.ArrayList<>();
			try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
				for (java.util.zip.ZipEntry e; (e = in.getNextEntry()) != null; ) {
					entries.add(e.getName());
				}
			}
			assertThat(entries).anyMatch(n -> n.startsWith("flow/") && n.endsWith("结算查询.ms"));
		}

		// 文件按 magic-api 资源结构落盘
		try (Stream<Path> files = Files.walk(workspace)) {
			assertThat(files.map(p -> workspace.relativize(p).toString().replace('\\', '/')))
					.anyMatch(p -> p.startsWith("flow/") && p.endsWith("结算查询.ms"));
		}

		// 6. 重启后仍在，内容、启用状态不变，并重新注册
		try (ConfigurableApplicationContext context = start()) {
			MockMvc mvc = mvc(context);
			JsonNode detail = data(mvc.perform(get("/magic/web/resource/file/" + flowId)));
			assertThat(detail.get("name").asText()).isEqualTo("结算查询");
			assertThat(detail.get("enabled").asBoolean()).isTrue();
			assertThat(detail.get("script").asText()).isEqualTo(DEFINITION);

			FlowInfo registered = context.getBean(FlowMagicDynamicRegistry.class).getMapping("/insur/settle");
			assertThat(registered).isNotNull();
			assertThat(registered.getDescription()).isEqualTo("Task1 演示");
		}
	}

	private ConfigurableApplicationContext start() {
		return new SpringApplicationBuilder(FlowTestApplication.class)
				.properties(
						"server.port=0",
						"spring.datasource.url=jdbc:h2:mem:flow;DB_CLOSE_DELAY=-1",
						"spring.datasource.driver-class-name=org.h2.Driver",
						"magic-api.web=/magic/web",
						"magic-api.resource.type=file",
						"magic-api.resource.location=" + workspace.toAbsolutePath().toString().replace('\\', '/'),
						"magic-api.show-url=false",
						"magic-api.banner=false")
				.run();
	}

	private MockMvc mvc(ConfigurableApplicationContext context) {
		return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).build();
	}

	private JsonNode json(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
		return MAPPER.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

	private JsonNode data(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
		JsonNode node = json(actions);
		assertThat(node.get("code").asInt()).as(node.toString()).isEqualTo(1);
		return node.get("data");
	}
}
