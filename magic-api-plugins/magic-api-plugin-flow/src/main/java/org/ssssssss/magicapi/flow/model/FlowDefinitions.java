package org.ssssssss.magicapi.flow.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.ssssssss.magicapi.flow.FlowJsonCodes.*;

/**
 * 流程定义的解析与结构校验。
 * <p>
 * 只校验结构（JSON 可解析、节点 id 唯一、节点有类型、连线指向存在的节点）。
 * 节点类型是否受支持、节点配置是否完整，由引擎在执行前校验。
 */
public final class FlowDefinitions {

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	private FlowDefinitions() {
	}

	public static FlowDefinition parse(String json) {
		if (StringUtils.isBlank(json)) {
			throw new InvalidArgumentException(FLOW_DEFINITION_REQUIRED);
		}
		FlowDefinition definition;
		try {
			definition = MAPPER.readValue(json, FlowDefinition.class);
		} catch (JsonProcessingException e) {
			String location = e.getLocation() == null ? "" : "（第 " + e.getLocation().getLineNr() + " 行第 " + e.getLocation().getColumnNr() + " 列）";
			throw new InvalidArgumentException(FLOW_DEFINITION_INVALID.format(e.getOriginalMessage() + location));
		} catch (IOException e) {
			throw new InvalidArgumentException(FLOW_DEFINITION_INVALID.format(firstLine(String.valueOf(e.getMessage()))));
		}
		if (definition == null) {
			throw new InvalidArgumentException(FLOW_DEFINITION_REQUIRED);
		}
		validate(definition);
		return definition;
	}

	public static void validate(FlowDefinition definition) {
		Set<String> ids = new HashSet<>();
		int index = 0;
		for (FlowDefinition.Node node : definition.getNodes()) {
			index++;
			if (StringUtils.isBlank(node.getId())) {
				throw new InvalidArgumentException(FLOW_NODE_ID_REQUIRED.format(index));
			}
			if (!ids.add(node.getId())) {
				throw new InvalidArgumentException(FLOW_NODE_ID_DUPLICATE.format(node.getId()));
			}
			if (StringUtils.isBlank(node.getType())) {
				throw new InvalidArgumentException(FLOW_NODE_TYPE_REQUIRED.format(label(node)));
			}
		}
		for (FlowDefinition.Edge edge : definition.getEdges()) {
			if (!ids.contains(edge.getSource()) || !ids.contains(edge.getTarget())) {
				throw new InvalidArgumentException(FLOW_EDGE_INVALID.format(edge.getSource(), edge.getTarget()));
			}
		}
	}

	public static String toJson(FlowDefinition definition) {
		try {
			return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(definition);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static String label(FlowDefinition.Node node) {
		return StringUtils.isNotBlank(node.getName()) ? node.getName() : node.getId();
	}

	private static String firstLine(String message) {
		int idx = message.indexOf('\n');
		return idx > 0 ? message.substring(0, idx) : message;
	}
}
