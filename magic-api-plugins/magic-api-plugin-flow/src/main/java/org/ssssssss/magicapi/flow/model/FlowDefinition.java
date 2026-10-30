package org.ssssssss.magicapi.flow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程定义：保存在 {@link FlowInfo#getScript()} 中的 JSON 结构。
 * <pre>
 * {
 *   "nodes": [{"id": "n1", "type": "query", "name": "查结算", "config": {...}}],
 *   "edges": [{"source": "n1", "target": "n2", "condition": "success"}]
 * }
 * </pre>
 * 未识别的字段（如画布坐标）原样忽略，由前端自行保存在 node.ui 中。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FlowDefinition {

	private List<Node> nodes = new ArrayList<>();

	private List<Edge> edges = new ArrayList<>();

	public List<Node> getNodes() {
		return nodes;
	}

	public void setNodes(List<Node> nodes) {
		this.nodes = nodes == null ? new ArrayList<>() : nodes;
	}

	public List<Edge> getEdges() {
		return edges;
	}

	public void setEdges(List<Edge> edges) {
		this.edges = edges == null ? new ArrayList<>() : edges;
	}

	public Node findNode(String id) {
		for (Node node : nodes) {
			if (node.getId() != null && node.getId().equals(id)) {
				return node;
			}
		}
		return null;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Node {

		private String id;

		private String type;

		private String name;

		private Map<String, Object> config = new LinkedHashMap<>();

		/**
		 * 画布信息（坐标、尺寸等），引擎不使用
		 */
		private Map<String, Object> ui;

		public String getId() {
			return id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public String getType() {
			return type;
		}

		public void setType(String type) {
			this.type = type;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public Map<String, Object> getConfig() {
			return config;
		}

		public void setConfig(Map<String, Object> config) {
			this.config = config == null ? new LinkedHashMap<>() : config;
		}

		public Map<String, Object> getUi() {
			return ui;
		}

		public void setUi(Map<String, Object> ui) {
			this.ui = ui;
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Edge {

		private String source;

		private String target;

		public static final String SUCCESS = "success";

		public static final String FAILURE = "failure";

		/**
		 * 连线条件：
		 * <ul>
		 *     <li>为空或 success：默认连线，节点成功且没有其他条件命中时走这条</li>
		 *     <li>failure：节点执行出错时走这条，变量 error 中是错误信息</li>
		 *     <li>其他：magic-script 表达式，如 {@code prev.size() > 0}，按顺序第一个为真的生效</li>
		 * </ul>
		 */
		private String condition;

		@com.fasterxml.jackson.annotation.JsonIgnore
		public boolean isDefault() {
			return condition == null || condition.trim().isEmpty() || SUCCESS.equals(condition.trim());
		}

		@com.fasterxml.jackson.annotation.JsonIgnore
		public boolean isFailure() {
			return condition != null && FAILURE.equals(condition.trim());
		}

		public String getSource() {
			return source;
		}

		public void setSource(String source) {
			this.source = source;
		}

		public String getTarget() {
			return target;
		}

		public void setTarget(String target) {
			this.target = target;
		}

		public String getCondition() {
			return condition;
		}

		public void setCondition(String condition) {
			this.condition = condition;
		}
	}
}
