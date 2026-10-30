package org.ssssssss.magicapi.flow.engine.node;

import org.apache.commons.lang3.StringUtils;
import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.flow.engine.FlowNodeException;
import org.ssssssss.magicapi.flow.engine.FlowNodeHandler;
import org.ssssssss.script.parsing.ast.literal.BooleanLiteral;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 数据校验：上传前检查必填、长度、格式，把不合格的行分出来（可写回错误原因），不让整批被对方拒绝。
 * <pre>
 * config:
 *   source  表达式，列表，默认 prev
 *   rules   [{field: 字段名, rule: required / maxLength / regex / expr, value: 长度或正则或表达式, message: 提示}]
 *           expr 规则的表达式中可用 row、value（字段值），结果为真表示通过
 *   mode    split（默认：输出 {valid, invalid}，流程继续）/ fail（有不合格行时节点失败，走失败连线）
 * </pre>
 * 输出：{valid: 合格行, invalid: [{row, errors: [提示]}], total, invalidCount}
 */
public class ValidateNodeHandler implements FlowNodeHandler {

	public static final String TYPE = "validate";

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void check(FlowNodeContext node) {
		List<Map<String, Object>> rules = node.mapList("rules");
		if (rules.isEmpty()) {
			throw node.invalid("rules", "至少需要一条规则");
		}
		for (Map<String, Object> rule : rules) {
			String type = Objects.toString(rule.get("rule"), "required");
			if (!Arrays.asList("required", "maxLength", "regex", "expr").contains(type)) {
				throw node.invalid("rules", "规则只能是 required / maxLength / regex / expr");
			}
			if (!"expr".equals(type) && StringUtils.isBlank(Objects.toString(rule.get("field"), null))) {
				throw node.invalid("rules", "规则缺少字段名");
			}
			if (!"required".equals(type) && StringUtils.isBlank(Objects.toString(rule.get("value"), null))) {
				throw node.invalid("rules", "规则 " + type + " 缺少 value");
			}
			if ("regex".equals(type)) {
				Pattern.compile(rule.get("value").toString());
			}
		}
		node.oneOf("mode", "split", "split", "fail");
	}

	@Override
	public Object execute(FlowNodeContext node) {
		List<Object> rows = node.asList("source", node.evaluateConfig("source", "prev"));
		List<Map<String, Object>> rules = node.mapList("rules");
		List<Object> valid = new ArrayList<>();
		List<Map<String, Object>> invalid = new ArrayList<>();
		int index = 0;
		for (Object row : rows) {
			List<String> errors = new ArrayList<>();
			for (Map<String, Object> rule : rules) {
				String error = apply(node, rule, row, index);
				if (error != null) {
					errors.add(error);
				}
			}
			if (errors.isEmpty()) {
				valid.add(row);
			} else {
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("row", row);
				item.put("errors", errors);
				item.put("message", String.join("；", errors));
				invalid.add(item);
			}
			index++;
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("valid", valid);
		result.put("invalid", invalid);
		result.put("total", rows.size());
		result.put("invalidCount", invalid.size());
		if (!invalid.isEmpty() && "fail".equals(node.oneOf("mode", "split", "split", "fail"))) {
			throw new FlowNodeException(invalid.size() + " 行数据校验不通过，第一行：" + invalid.get(0).get("message"), result, false);
		}
		return result;
	}

	private static String apply(FlowNodeContext node, Map<String, Object> rule, Object row, int index) {
		String type = Objects.toString(rule.get("rule"), "required");
		String field = Objects.toString(rule.get("field"), null);
		Object value = field != null && row instanceof Map ? ((Map<?, ?>) row).get(field) : null;
		String text = value == null ? null : value.toString();
		String message = StringUtils.defaultIfBlank(Objects.toString(rule.get("message"), null), null);
		switch (type) {
			case "required":
				return StringUtils.isBlank(text) ? (message == null ? field + " 不能为空" : message) : null;
			case "maxLength": {
				int max = Integer.parseInt(rule.get("value").toString().trim());
				return text != null && text.length() > max ? (message == null ? field + " 超过 " + max + " 位" : message) : null;
			}
			case "regex":
				return text != null && !text.isEmpty() && !Pattern.compile(rule.get("value").toString()).matcher(text).matches()
						? (message == null ? field + " 格式不正确" : message) : null;
			default: {
				Map<String, Object> extra = new HashMap<>();
				extra.put("row", row);
				extra.put("value", value);
				extra.put("index", index);
				Object ok = node.evaluate(rule.get("value").toString(), extra);
				return BooleanLiteral.isTrue(ok) ? null : (message == null ? "不满足 " + rule.get("value") : message);
			}
		}
	}
}
