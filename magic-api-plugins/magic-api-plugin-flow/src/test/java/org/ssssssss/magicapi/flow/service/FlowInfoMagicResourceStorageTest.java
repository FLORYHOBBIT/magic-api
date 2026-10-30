package org.ssssssss.magicapi.flow.service;

import org.junit.jupiter.api.Test;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.flow.model.FlowDefinition;
import org.ssssssss.magicapi.flow.model.FlowDefinitions;
import org.ssssssss.magicapi.flow.model.FlowInfo;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowInfoMagicResourceStorageTest {

	static final String SIMPLE = "{\n" +
			"  \"nodes\": [\n" +
			"    {\"id\": \"q\", \"type\": \"query\", \"name\": \"查结算\", \"config\": {\"sql\": \"select 1\"}, \"ui\": {\"x\": 10, \"y\": 20}},\n" +
			"    {\"id\": \"r\", \"type\": \"return\", \"config\": {}}\n" +
			"  ],\n" +
			"  \"edges\": [{\"source\": \"q\", \"target\": \"r\"}],\n" +
			"  \"canvas\": {\"zoom\": 1}\n" +
			"}";

	private final FlowInfoMagicResourceStorage storage = new FlowInfoMagicResourceStorage();

	@Test
	void writeAndReadKeepsDefinitionVerbatim() {
		FlowInfo info = flow("settle-query", SIMPLE);
		info.setEnabled(true);
		info.setDescription("按病人查询结算记录");

		byte[] bytes = storage.write(info);
		String file = new String(bytes, StandardCharsets.UTF_8);
		// 元数据 + 分隔线 + 原样的流程定义（含画布字段），便于 diff 与 Git 同步
		assertThat(file).contains("\"enabled\" : true").contains("================================").endsWith(SIMPLE);

		FlowInfo read = storage.read(bytes);
		assertThat(read.isEnabled()).isTrue();
		assertThat(read.getDescription()).isEqualTo("按病人查询结算记录");
		assertThat(read.getScript()).isEqualTo(SIMPLE);
		assertThat(read).isEqualTo(info);
		assertThat(storage.folder()).isEqualTo("flow");
		assertThat(storage.requirePath()).isTrue();
	}

	@Test
	void parsesNodesEdgesAndIgnoresUnknownFields() {
		FlowDefinition definition = FlowDefinitions.parse(SIMPLE);
		assertThat(definition.getNodes()).extracting(FlowDefinition.Node::getId).containsExactly("q", "r");
		assertThat(definition.findNode("q").getConfig()).containsEntry("sql", "select 1");
		assertThat(definition.findNode("q").getUi()).containsEntry("x", 10);
		assertThat(definition.findNode("r").getConfig()).isEmpty();
		assertThat(definition.getEdges()).hasSize(1);
		assertThat(definition.findNode("missing")).isNull();
	}

	@Test
	void validateAcceptsEmptyFlowButRejectsBrokenOnes() {
		storage.validate(flow("empty", "{}"));
		storage.validate(flow("ok", SIMPLE));

		assertThatThrownBy(() -> storage.validate(flow("", SIMPLE))).isInstanceOf(InvalidArgumentException.class);
		assertThatThrownBy(() -> storage.validate(flow("p", " "))).hasMessageContaining("流程定义不能为空");
		assertThatThrownBy(() -> storage.validate(flow("p", "{nodes:"))).hasMessageContaining("流程定义格式错误");
		assertThatThrownBy(() -> storage.validate(flow("p", "{\"nodes\":[{\"type\":\"query\"}]}"))).hasMessageContaining("第 1 个节点缺少 id");
		assertThatThrownBy(() -> storage.validate(flow("p", "{\"nodes\":[{\"id\":\"a\",\"type\":\"query\"},{\"id\":\"a\",\"type\":\"query\"}]}")))
				.hasMessageContaining("节点 id 重复：a");
		assertThatThrownBy(() -> storage.validate(flow("p", "{\"nodes\":[{\"id\":\"a\",\"name\":\"查库\"}]}"))).hasMessageContaining("节点「查库」缺少类型");
		assertThatThrownBy(() -> storage.validate(flow("p", "{\"nodes\":[{\"id\":\"a\",\"type\":\"query\"}],\"edges\":[{\"source\":\"a\",\"target\":\"b\"}]}")))
				.hasMessageContaining("a → b");
	}

	static FlowInfo flow(String path, String definition) {
		FlowInfo info = new FlowInfo();
		info.setId(path + "-id");
		info.setName(path);
		info.setPath(path);
		info.setScript(definition);
		return info;
	}
}
