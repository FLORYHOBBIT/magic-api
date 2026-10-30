package org.ssssssss.magicapi.flow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.flow.model.FlowDefinitions;
import org.ssssssss.magicapi.flow.support.FlowJson;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.ssssssss.magicapi.flow.FlowJsonCodes.FLOW_TEMPLATE_NOT_FOUND;

/**
 * 场景模板：常见接口类型的流程骨架，填几个参数即生成流程。
 * <p>
 * 模板放在 classpath:magic-flow/templates/ 下，定义中的 %{参数} 在应用时替换。
 * 项目可以在自己的 jar 里放同名文件覆盖，或通过 {@link #addTemplate(String)} 增加。
 */
public class FlowTemplates {

	public static final List<String> BUILT_IN = Collections.unmodifiableList(Arrays.asList("http-query", "soap-service", "timer-upload", "table-sync", "msg-query", "msg-call"));

	private static final Pattern PLACEHOLDER = Pattern.compile("%\\{([A-Za-z0-9_]+)}");

	private final Map<String, Map<String, Object>> templates = new LinkedHashMap<>();

	public FlowTemplates() {
		for (String id : BUILT_IN) {
			addTemplate("magic-flow/templates/" + id + ".json");
		}
	}

	public void addTemplate(String classpathLocation) {
		try (InputStream in = FlowTemplates.class.getClassLoader().getResourceAsStream(classpathLocation)) {
			if (in == null) {
				throw new IllegalArgumentException("找不到模板文件 " + classpathLocation);
			}
			Map<String, Object> template = FlowJson.MAPPER.readValue(in, new TypeReference<LinkedHashMap<String, Object>>() {
			});
			templates.put(String.valueOf(template.get("id")), template);
		} catch (IOException e) {
			throw new IllegalStateException("读取模板文件 " + classpathLocation + " 失败", e);
		}
	}

	/**
	 * 模板列表（不含定义）
	 */
	public List<Map<String, Object>> list() {
		List<Map<String, Object>> result = new ArrayList<>();
		for (Map<String, Object> template : templates.values()) {
			Map<String, Object> item = new LinkedHashMap<>(template);
			item.remove("definition");
			result.add(item);
		}
		return result;
	}

	/**
	 * 按参数生成流程定义 JSON，未提供的参数用默认值
	 */
	public String apply(String id, Map<String, String> params) {
		Map<String, Object> template = templates.get(id);
		if (template == null) {
			throw new InvalidArgumentException(FLOW_TEMPLATE_NOT_FOUND.format(id));
		}
		Map<String, String> values = new HashMap<>();
		Object declared = template.get("params");
		if (declared instanceof List) {
			for (Object param : (List<?>) declared) {
				if (param instanceof Map) {
					Map<?, ?> p = (Map<?, ?>) param;
					values.put(String.valueOf(p.get("key")), p.get("default") == null ? "" : String.valueOf(p.get("default")));
				}
			}
		}
		if (params != null) {
			params.forEach((k, v) -> {
				if (v != null) {
					values.put(k, v);
				}
			});
		}
		Object definition = replace(template.get("definition"), values);
		String json = FlowJson.pretty(definition);
		// 校验生成结果的结构
		FlowDefinitions.parse(json);
		return json;
	}

	private static Object replace(Object value, Map<String, String> values) {
		if (value instanceof String) {
			Matcher matcher = PLACEHOLDER.matcher((String) value);
			StringBuffer sb = new StringBuffer();
			while (matcher.find()) {
				String replacement = values.getOrDefault(matcher.group(1), "");
				matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
			}
			matcher.appendTail(sb);
			return sb.toString();
		}
		if (value instanceof Map) {
			Map<Object, Object> copy = new LinkedHashMap<>();
			((Map<?, ?>) value).forEach((k, v) -> copy.put(k, replace(v, values)));
			return copy;
		}
		if (value instanceof List) {
			List<Object> copy = new ArrayList<>();
			for (Object item : (List<?>) value) {
				copy.add(replace(item, values));
			}
			return copy;
		}
		return value;
	}
}
