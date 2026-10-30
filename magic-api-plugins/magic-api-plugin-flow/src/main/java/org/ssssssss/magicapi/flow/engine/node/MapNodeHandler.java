package org.ssssssss.magicapi.flow.engine.node;

import org.apache.commons.lang3.StringUtils;
import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.flow.engine.FlowNodeHandler;
import org.ssssssss.magicapi.flow.support.FlowJson;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 字段映射：把查询结果的每一行转换为对方要求的字段名、类型和格式。
 * <pre>
 * config:
 *   source      表达式，列表或单个对象，默认 prev
 *   fields      [{name: 目标字段, value: 表达式（默认 row.目标字段）, type, format, default}]
 *               type：string / number / int / date / boolean；
 *               format：date 的输出格式（默认 yyyy-MM-dd HH:mm:ss），number 的小数位数
 *               default：值为空时的默认值（文本）
 *   keepOthers  是否保留未映射的原字段，默认 false
 * </pre>
 * 表达式中可用 row（当前行）、index（行号，从 0 开始）以及流程变量。
 */
public class MapNodeHandler implements FlowNodeHandler {

	public static final String TYPE = "map";

	private static final String[] TYPES = {"", "string", "number", "int", "date", "boolean"};

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void check(FlowNodeContext node) {
		List<Map<String, Object>> fields = node.mapList("fields");
		if (fields.isEmpty()) {
			throw node.invalid("fields", "至少需要一个字段");
		}
		Set<String> names = new HashSet<>();
		for (Map<String, Object> field : fields) {
			String name = field.get("name") == null ? null : StringUtils.trimToNull(field.get("name").toString());
			if (name == null) {
				throw node.invalid("fields", "字段缺少目标字段名");
			}
			if (!names.add(name)) {
				throw node.invalid("fields", "目标字段重复：" + name);
			}
			String type = field.get("type") == null ? "" : field.get("type").toString().trim();
			if (!Arrays.asList(TYPES).contains(type)) {
				throw node.invalid("fields", "字段 " + name + " 的类型只能是 string / number / int / date / boolean");
			}
		}
	}

	@Override
	public Object execute(FlowNodeContext node) {
		Object source = node.evaluateConfig("source", "prev");
		List<Map<String, Object>> fields = node.mapList("fields");
		boolean keepOthers = node.bool("keepOthers", false);
		if (source instanceof Map) {
			return mapRow(node, fields, keepOthers, source, 0);
		}
		List<Object> rows = node.asList("source", source);
		List<Object> result = new ArrayList<>(rows.size());
		int index = 0;
		for (Object row : rows) {
			result.add(mapRow(node, fields, keepOthers, row, index++));
		}
		return result;
	}

	private Map<String, Object> mapRow(FlowNodeContext node, List<Map<String, Object>> fields, boolean keepOthers, Object row, int index) {
		Map<String, Object> extra = new HashMap<>();
		extra.put("row", row);
		extra.put("index", index);
		Map<String, Object> result = new LinkedHashMap<>();
		if (keepOthers && row instanceof Map) {
			((Map<?, ?>) row).forEach((k, v) -> result.put(String.valueOf(k), v));
		}
		for (Map<String, Object> field : fields) {
			String name = field.get("name").toString().trim();
			String expression = field.get("value") == null ? null : StringUtils.trimToNull(field.get("value").toString());
			Object value;
			try {
				if (expression == null) {
					value = row instanceof Map ? ((Map<?, ?>) row).get(name) : null;
				} else {
					value = node.evaluate(expression, extra);
				}
				if ((value == null || "".equals(value)) && field.get("default") != null && !"".equals(field.get("default"))) {
					value = field.get("default");
				}
				value = convert(value, field.get("type") == null ? "" : field.get("type").toString().trim(),
						field.get("format") == null ? null : field.get("format").toString().trim());
			} catch (RuntimeException e) {
				throw new IllegalArgumentException("第 " + (index + 1) + " 行字段 " + name + " 转换失败：" + e.getMessage(), e);
			}
			result.put(name, value);
		}
		return result;
	}

	public static Object convert(Object value, String type, String format) {
		if (value == null || type == null || type.isEmpty()) {
			return value;
		}
		switch (type) {
			case "string":
				if (value instanceof Date || value instanceof java.time.temporal.Temporal) {
					return FlowJson.formatDate(value, format);
				}
				if (value instanceof BigDecimal) {
					return ((BigDecimal) value).toPlainString();
				}
				if (value instanceof Double || value instanceof Float) {
					return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
				}
				return String.valueOf(FlowJson.normalize(value));
			case "number": {
				if ("".equals(value)) {
					return null;
				}
				BigDecimal number = value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(value.toString().trim());
				if (format != null && !format.isEmpty()) {
					number = number.setScale(Integer.parseInt(format), RoundingMode.HALF_UP);
				}
				return number;
			}
			case "int":
				if ("".equals(value)) {
					return null;
				}
				return value instanceof Number ? ((Number) value).longValue() : new BigDecimal(value.toString().trim()).longValue();
			case "boolean":
				if (value instanceof Boolean) {
					return value;
				}
				String text = value.toString().trim();
				return "1".equals(text) || "true".equalsIgnoreCase(text) || "y".equalsIgnoreCase(text) || "是".equals(text);
			case "date":
				if (value instanceof String) {
					return FlowJson.formatDate(parseDate((String) value), format);
				}
				return FlowJson.formatDate(value, format);
			default:
				return value;
		}
	}

	private static Date parseDate(String text) {
		String value = text.trim();
		if (value.isEmpty()) {
			return null;
		}
		String[] patterns = {"yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd", "yyyyMMddHHmmss", "yyyyMMdd", "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd"};
		for (String pattern : patterns) {
			if (pattern.replace("'", "").length() != value.length()) {
				continue;
			}
			try {
				SimpleDateFormat format = new SimpleDateFormat(pattern);
				format.setLenient(false);
				return format.parse(value);
			} catch (ParseException ignored) {
				// 尝试下一个格式
			}
		}
		throw new IllegalArgumentException("无法识别的日期：" + text);
	}
}
