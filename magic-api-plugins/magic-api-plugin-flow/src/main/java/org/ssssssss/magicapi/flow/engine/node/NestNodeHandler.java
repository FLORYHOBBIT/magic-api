package org.ssssssss.magicapi.flow.engine.node;

import org.apache.commons.lang3.StringUtils;
import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.flow.engine.FlowNodeHandler;

import java.util.*;

/**
 * 主从组装：把主表行与多个子表行按关联字段组装为嵌套结构（如结算主单 + 费用明细 + 诊断）。
 * <pre>
 * config:
 *   master    表达式，主表行列表，默认 prev
 *   children  [{name: 子节点字段名, source: 子表行列表表达式, masterKey: 主表关联字段, childKey: 子表关联字段（默认同 masterKey）,
 *              single: true 时只取第一条作为对象（默认 false，为列表）, removeKey: 是否从子行中去掉关联字段（默认 false）}]
 *   keys      多字段关联时用逗号分隔，如 masterKey: "setl_id,psn_no"
 * </pre>
 * 输出：主表行的副本，每行增加子节点字段；没有子行时为空列表（single 时为 null）。
 */
public class NestNodeHandler implements FlowNodeHandler {

	public static final String TYPE = "nest";

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void check(FlowNodeContext node) {
		List<Map<String, Object>> children = node.mapList("children");
		if (children.isEmpty()) {
			throw node.invalid("children", "至少需要一个子表");
		}
		for (Map<String, Object> child : children) {
			for (String key : new String[]{"name", "source", "masterKey"}) {
				if (child.get(key) == null || StringUtils.isBlank(child.get(key).toString())) {
					throw node.invalid("children", "子表缺少 " + key);
				}
			}
		}
	}

	@Override
	public Object execute(FlowNodeContext node) {
		List<Object> masters = node.asList("master", node.evaluateConfig("master", "prev"));
		List<Map<String, Object>> result = new ArrayList<>(masters.size());
		for (Object master : masters) {
			if (!(master instanceof Map)) {
				throw node.invalid("master", "主表行必须是对象");
			}
			result.add(new LinkedHashMap<>(castMap(master)));
		}
		for (Map<String, Object> child : node.mapList("children")) {
			String name = child.get("name").toString().trim();
			String[] masterKeys = keys(child.get("masterKey").toString());
			String[] childKeys = child.get("childKey") == null || StringUtils.isBlank(child.get("childKey").toString())
					? masterKeys : keys(child.get("childKey").toString());
			if (childKeys.length != masterKeys.length) {
				throw node.invalid("children", "子表 " + name + " 的关联字段个数与主表不一致");
			}
			boolean single = Boolean.parseBoolean(String.valueOf(child.get("single")));
			boolean removeKey = Boolean.parseBoolean(String.valueOf(child.get("removeKey")));
			List<Object> rows = node.asList("children", node.evaluate(child.get("source").toString()));
			Map<String, List<Map<String, Object>>> index = new HashMap<>();
			for (Object row : rows) {
				if (!(row instanceof Map)) {
					throw node.invalid("children", "子表 " + name + " 的行必须是对象");
				}
				Map<String, Object> copy = new LinkedHashMap<>(castMap(row));
				String key = key(copy, childKeys);
				if (removeKey) {
					for (String k : childKeys) {
						copy.remove(k);
					}
				}
				index.computeIfAbsent(key, k -> new ArrayList<>()).add(copy);
			}
			for (Map<String, Object> master : result) {
				List<Map<String, Object>> matched = index.getOrDefault(key(master, masterKeys), Collections.<Map<String, Object>>emptyList());
				master.put(name, single ? (matched.isEmpty() ? null : matched.get(0)) : new ArrayList<>(matched));
			}
		}
		return result;
	}

	private static String[] keys(String text) {
		String[] keys = text.split(",");
		for (int i = 0; i < keys.length; i++) {
			keys[i] = keys[i].trim();
		}
		return keys;
	}

	/**
	 * 关联值统一按文本比较，避免主表 NUMBER、子表 VARCHAR 时对不上
	 */
	private static String key(Map<String, Object> row, String[] keys) {
		StringBuilder sb = new StringBuilder();
		for (String key : keys) {
			Object value = row.get(key);
			if (value instanceof java.math.BigDecimal) {
				value = ((java.math.BigDecimal) value).stripTrailingZeros().toPlainString();
			}
			sb.append(value).append('\u0001');
		}
		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Object value) {
		return (Map<String, Object>) value;
	}
}
