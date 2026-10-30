package org.ssssssss.magicapi.flow.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.model.Group;
import org.ssssssss.magicapi.core.model.TreeNode;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.flow.model.FlowInfo;
import org.ssssssss.magicapi.flow.service.FlowInfoMagicResourceStorage;
import org.ssssssss.magicapi.upload.resource.UploadChannelInfo;
import org.ssssssss.magicapi.upload.resource.UploadChannelMagicResourceStorage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * 启动完整 magic-api（H2、文件资源），通过 magic-api 的资源服务保存流程与外部连接
 */
@SpringBootTest(classes = FlowTestApplication.class, properties = {
		"spring.datasource.url=jdbc:h2:mem:flow-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"magic-api.web=/magic/web",
		"magic-api.show-url=false",
		"magic-api.banner=false",
		"magic-api.upload.secret-key=test-secret"
})
public abstract class AbstractFlowTest {

	protected static final ObjectMapper MAPPER = new ObjectMapper();

	@TempDir
	protected static Path workspace;

	@DynamicPropertySource
	static void resource(DynamicPropertyRegistry registry) {
		String base = workspace.toAbsolutePath().toString().replace('\\', '/');
		registry.add("magic-api.resource.location", () -> base + "/resources");
		registry.add("magic-api.flow.variables-file", () -> base + "/variables.json");
		registry.add("magic-api.flow.backup-dir", () -> base + "/backups");
	}

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	protected WebApplicationContext context;

	protected MockMvc mvc() {
		return MockMvcBuilders.webAppContextSetup(context).build();
	}

	protected static MagicResourceService resources() {
		return MagicConfiguration.getMagicResourceService();
	}

	protected static String group(String type, String path) {
		TreeNode<Group> tree = resources().tree(type);
		if (tree != null && tree.getChildren() != null) {
			for (TreeNode<Group> child : tree.getChildren()) {
				if (child.getNode() != null && path.equals(child.getNode().getPath())) {
					return child.getNode().getId();
				}
			}
		}
		Group group = new Group();
		group.setName(path);
		group.setPath(path);
		group.setType(type);
		group.setParentId("0");
		resources().saveGroup(group);
		return group.getId();
	}

	protected static FlowInfo saveFlow(String groupPath, String path, String definition, boolean enabled) {
		String groupId = group(FlowInfoMagicResourceStorage.FOLDER, groupPath);
		FlowInfo info = resources().<FlowInfo>files(FlowInfoMagicResourceStorage.FOLDER).stream()
				.filter(it -> groupId.equals(it.getGroupId()) && path.equals(it.getPath()))
				.findFirst().map(FlowInfo::copy).orElseGet(FlowInfo::new);
		info.setGroupId(groupId);
		info.setName(path);
		info.setPath(path);
		info.setScript(definition);
		info.setEnabled(enabled);
		resources().saveFile(info);
		return info;
	}

	protected static UploadChannelInfo saveChannel(String name, String protocol, Map<String, Object> options) {
		String groupId = group(UploadChannelMagicResourceStorage.FOLDER, "conn");
		UploadChannelInfo info = resources().<UploadChannelInfo>files(UploadChannelMagicResourceStorage.FOLDER).stream()
				.filter(it -> name.equals(it.getName()))
				.findFirst().map(UploadChannelInfo::copy).orElseGet(UploadChannelInfo::new);
		info.setGroupId(groupId);
		info.setName(name);
		info.setProtocol(protocol);
		info.setOptions(new LinkedHashMap<>(options));
		if (info.getScript() == null) {
			info.setScript("return rows");
		}
		resources().saveFile(info);
		return info;
	}

	protected static Map<String, Object> options(Object... keyValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
		}
		return map;
	}

	protected static String body(ResultActions actions) throws Exception {
		return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	protected static JsonNode json(ResultActions actions) throws Exception {
		return MAPPER.readTree(body(actions));
	}

	/**
	 * 编辑器接口返回 {code, message, data}，断言成功并返回 data
	 */
	protected static JsonNode data(ResultActions actions) throws Exception {
		JsonNode node = json(actions);
		if (node.get("code").asInt() != 1) {
			throw new AssertionError("编辑器接口返回失败：" + node);
		}
		return node.get("data");
	}

	/**
	 * 把单引号写法转成 JSON，便于在 Java 字符串里写流程定义
	 */
	protected static String q(String text) {
		return text.replace('`', '"');
	}
}
