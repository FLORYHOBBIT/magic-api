package org.ssssssss.magicapi.flow.engine;

import org.apache.commons.lang3.StringUtils;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.flow.model.FlowDefinition;

import java.util.Map;

import static org.ssssssss.magicapi.flow.FlowJsonCodes.FLOW_NODE_CONFIG_INVALID;
import static org.ssssssss.magicapi.flow.FlowJsonCodes.FLOW_NODE_CONFIG_REQUIRED;

/**
 * 节点处理器看到的上下文：当前节点的配置 + 本次执行的变量。
 */
public class FlowNodeContext {

	private final FlowDefinition.Node node;

	private final FlowExecution execution;

	public FlowNodeContext(FlowDefinition.Node node, FlowExecution execution) {
		this.node = node;
		this.execution = execution;
	}

	public FlowDefinition.Node getNode() {
		return node;
	}

	public FlowExecution getExecution() {
		return execution;
	}

	public String label() {
		return StringUtils.isNotBlank(node.getName()) ? node.getName() : node.getId();
	}

	public Map<String, Object> config() {
		return node.getConfig();
	}

	public String string(String key) {
		Object value = node.getConfig().get(key);
		return value == null ? null : StringUtils.trimToNull(value.toString());
	}

	public String string(String key, String defaultValue) {
		String value = string(key);
		return value == null ? defaultValue : value;
	}

	public String requireString(String key) {
		String value = string(key);
		if (value == null) {
			throw new InvalidArgumentException(FLOW_NODE_CONFIG_REQUIRED.format(label(), key));
		}
		return value;
	}

	public boolean bool(String key, boolean defaultValue) {
		Object value = node.getConfig().get(key);
		if (value == null || "".equals(value)) {
			return defaultValue;
		}
		return value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(value.toString());
	}

	public String oneOf(String key, String defaultValue, String... allowed) {
		String value = string(key, defaultValue);
		for (String it : allowed) {
			if (it.equalsIgnoreCase(value)) {
				return it;
			}
		}
		throw new InvalidArgumentException(FLOW_NODE_CONFIG_INVALID.format(label(), key, "只能是 " + String.join(" / ", allowed)));
	}

	public Map<String, Object> variables() {
		return execution.getVariables();
	}

	/**
	 * 用当前变量计算 magic-script 表达式
	 */
	public Object evaluate(String expression) {
		return execution.evaluate(expression, scriptName());
	}

	public Object evaluate(String expression, Map<String, Object> extra) {
		return execution.evaluate(expression, extra, scriptName());
	}

	/**
	 * 日志与报错中显示的脚本名：流程名/节点名
	 */
	public String scriptName() {
		return execution.getFlowName() + "/" + label();
	}
}
