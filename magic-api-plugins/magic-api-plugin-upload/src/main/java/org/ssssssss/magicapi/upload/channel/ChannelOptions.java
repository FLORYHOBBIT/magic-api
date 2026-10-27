package org.ssssssss.magicapi.upload.channel;

import java.time.Duration;
import java.util.*;

/**
 * 从脚本对象或通道配置中读取参数
 */
public class ChannelOptions {

	private final Map<String, Object> values;

	public ChannelOptions(Map<String, ?> values) {
		this.values = new LinkedHashMap<>();
		if (values != null) {
			values.forEach((k, v) -> this.values.put(String.valueOf(k), v));
		}
	}

	public Map<String, Object> raw() {
		return values;
	}

	public boolean has(String key) {
		Object value = values.get(key);
		return value != null && !(value instanceof CharSequence && value.toString().trim().isEmpty());
	}

	public String string(String key) {
		return string(key, null);
	}

	public String string(String key, String defaultValue) {
		Object value = values.get(key);
		if (value == null) {
			return defaultValue;
		}
		String text = value.toString().trim();
		return text.isEmpty() ? defaultValue : text;
	}

	public String required(String key, String what) {
		String value = string(key);
		if (value == null) {
			throw new IllegalArgumentException(what + "（" + key + "）不能为空");
		}
		return value;
	}

	public int integer(String key, int defaultValue) {
		Object value = values.get(key);
		if (value == null || value.toString().trim().isEmpty()) {
			return defaultValue;
		}
		if (value instanceof Number) {
			return ((Number) value).intValue();
		}
		try {
			return Integer.parseInt(value.toString().trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(key + " 必须是整数: " + value);
		}
	}

	/**
	 * 毫秒数
	 */
	public Duration millis(String key, Duration defaultValue) {
		Object value = values.get(key);
		if (value == null || value.toString().trim().isEmpty()) {
			return defaultValue;
		}
		long millis;
		if (value instanceof Number) {
			millis = ((Number) value).longValue();
		} else {
			try {
				millis = Long.parseLong(value.toString().trim());
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException(key + " 必须是毫秒数: " + value);
			}
		}
		return Duration.ofMillis(millis);
	}

	public boolean bool(String key, boolean defaultValue) {
		Object value = values.get(key);
		if (value == null || value.toString().trim().isEmpty()) {
			return defaultValue;
		}
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		return "true".equalsIgnoreCase(value.toString().trim()) || "1".equals(value.toString().trim());
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> map(String key) {
		Object value = values.get(key);
		if (value == null || (value instanceof CharSequence && value.toString().trim().isEmpty())) {
			return Collections.emptyMap();
		}
		if (value instanceof CharSequence) {
			try {
				value = new com.fasterxml.jackson.databind.ObjectMapper().readValue(value.toString(), Map.class);
			} catch (Exception e) {
				throw new IllegalArgumentException(key + " 不是合法的 JSON 对象，例如 {\"X-Token\": \"abc\"}: " + value);
			}
		}
		if (!(value instanceof Map)) {
			throw new IllegalArgumentException(key + " 必须是对象，例如 {\"X-Token\": \"abc\"}");
		}
		Map<String, Object> result = new LinkedHashMap<>();
		((Map<Object, Object>) value).forEach((k, v) -> result.put(String.valueOf(k), v));
		return result;
	}

	/**
	 * 支持数组或逗号分隔的字符串
	 */
	public List<String> list(String key) {
		Object value = values.get(key);
		List<String> result = new ArrayList<>();
		if (value == null) {
			return result;
		}
		if (value instanceof Collection) {
			for (Object item : (Collection<?>) value) {
				if (item != null && !item.toString().trim().isEmpty()) {
					result.add(item.toString().trim());
				}
			}
		} else if (value.getClass().isArray()) {
			for (Object item : (Object[]) value) {
				if (item != null && !item.toString().trim().isEmpty()) {
					result.add(item.toString().trim());
				}
			}
		} else {
			for (String item : value.toString().split(",")) {
				if (!item.trim().isEmpty()) {
					result.add(item.trim());
				}
			}
		}
		return result;
	}

	/**
	 * 业务成功判定规则：successField、successValues、messageField、retryOnBusinessFailure
	 */
	public ResponseRule responseRule() {
		return new ResponseRule()
				.successField(string("successField"))
				.successValues(list("successValues"))
				.messageField(string("messageField"))
				.retryOnBusinessFailure(bool("retryOnBusinessFailure", false));
	}
}
