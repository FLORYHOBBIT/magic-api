package org.ssssssss.magicapi.flow.engine;

import org.apache.commons.lang3.StringUtils;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.flow.model.FlowDefinition;
import org.ssssssss.magicapi.flow.support.FlowVariables;

import java.util.*;

import static org.ssssssss.magicapi.flow.FlowJsonCodes.FLOW_NODE_CONFIG_INVALID;
import static org.ssssssss.magicapi.flow.FlowJsonCodes.FLOW_NODE_CONFIG_REQUIRED;

/**
 * 节点处理器看到的上下文：当前节点的配置（差异项已替换）+ 本次执行的变量。
 */
public class FlowNodeContext {

	private final FlowDefinition.Node node;

	private final FlowExecution execution;

	private final Map<String, Object> config;

	/**
	 * @param variables 差异项；为 null 时不替换（执行前检查）
	 */
	public FlowNodeContext(FlowDefinition.Node node, FlowExecution execution, FlowVariables variables) {
		this.node = node;
		this.execution = execution;
		this.config = variables == null ? node.getConfig() : variables.resolveDeep(node.getConfig());
	}

	public FlowNodeContext(FlowDefinition.Node node, FlowExecution execution) {
		this(node, execution, null);
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
		return config;
	}

	public Object raw(String key) {
		return config.get(key);
	}

	public String string(String key) {
		Object value = config.get(key);
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
		Object value = config.get(key);
		if (value == null || "".equals(value)) {
			return defaultValue;
		}
		return value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(value.toString());
	}

	public int integer(String key, int defaultValue) {
		Object value = config.get(key);
		if (value == null || "".equals(value.toString().trim())) {
			return defaultValue;
		}
		if (value instanceof Number) {
			return ((Number) value).intValue();
		}
		try {
			return Integer.parseInt(value.toString().trim());
		} catch (NumberFormatException e) {
			throw invalid(key, "必须是整数");
		}
	}

	public String oneOf(String key, String defaultValue, String... allowed) {
		String value = string(key, defaultValue);
		for (String it : allowed) {
			if (it.equalsIgnoreCase(value)) {
				return it;
			}
		}
		throw invalid(key, "只能是 " + String.join(" / ", allowed));
	}

	/**
	 * 配置项为对象（如请求头），键值都按字符串处理
	 */
	public Map<String, String> stringMap(String key) {
		Object value = config.get(key);
		Map<String, String> result = new LinkedHashMap<>();
		if (value == null || "".equals(value)) {
			return result;
		}
		if (!(value instanceof Map)) {
			throw invalid(key, "必须是对象");
		}
		((Map<?, ?>) value).forEach((k, v) -> {
			if (k != null && v != null && StringUtils.isNotBlank(v.toString())) {
				result.put(k.toString(), v.toString());
			}
		});
		return result;
	}

	/**
	 * 配置项为对象列表（如字段映射）
	 */
	@SuppressWarnings("unchecked")
	public List<Map<String, Object>> mapList(String key) {
		Object value = config.get(key);
		if (value == null || "".equals(value)) {
			return Collections.emptyList();
		}
		if (!(value instanceof List)) {
			throw invalid(key, "必须是列表");
		}
		List<Map<String, Object>> result = new ArrayList<>();
		for (Object item : (List<?>) value) {
			if (!(item instanceof Map)) {
				throw invalid(key, "每一项必须是对象");
			}
			result.add((Map<String, Object>) item);
		}
		return result;
	}

	public InvalidArgumentException invalid(String key, String reason) {
		return new InvalidArgumentException(FLOW_NODE_CONFIG_INVALID.format(label(), key, reason));
	}

	public Map<String, Object> variables() {
		return execution.getVariables();
	}

	/**
	 * 用当前变量计算 magic-script 表达式
	 */
	public Object evaluate(String expression) {
		return execution.evaluate(expression, null, scriptName());
	}

	public Object evaluate(String expression, Map<String, Object> extra) {
		return execution.evaluate(expression, extra, scriptName());
	}

	/**
	 * 计算配置项中的表达式，配置项为空时用默认表达式
	 */
	public Object evaluateConfig(String key, String defaultExpression) {
		String expression = string(key, defaultExpression);
		return expression == null ? null : evaluate(expression);
	}

	/**
	 * 把表达式结果转为列表：列表原样返回，单个对象包装为一项，null 为空列表
	 */
	public List<Object> asList(String key, Object value) {
		if (value == null) {
			return Collections.emptyList();
		}
		if (value instanceof List) {
			return (List<Object>) value;
		}
		if (value instanceof Collection) {
			return new ArrayList<>((Collection<?>) value);
		}
		if (value instanceof Object[]) {
			return Arrays.asList((Object[]) value);
		}
		if (value instanceof Map) {
			return Collections.singletonList(value);
		}
		throw invalid(key, "结果不是列表，而是 " + value.getClass().getSimpleName());
	}

	/**
	 * 日志与报错中显示的脚本名：流程名/节点名
	 */
	public String scriptName() {
		return execution.getFlowName() + "/" + label();
	}
}
