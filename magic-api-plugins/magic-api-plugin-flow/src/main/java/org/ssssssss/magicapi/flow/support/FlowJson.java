package org.ssssssss.magicapi.flow.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Clob;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.*;

/**
 * 报文中的值统一处理：日期转 "yyyy-MM-dd HH:mm:ss"，Clob 转字符串，数值保持精度。
 */
public final class FlowJson {

	public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);

	public static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

	private FlowJson() {
	}

	public static String stringify(Object value) {
		try {
			return MAPPER.writeValueAsString(normalize(value));
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("转换 JSON 失败：" + e.getOriginalMessage(), e);
		}
	}

	public static String pretty(Object value) {
		try {
			return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(normalize(value));
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("转换 JSON 失败：" + e.getOriginalMessage(), e);
		}
	}

	public static Object parse(String json) {
		if (json == null || json.trim().isEmpty()) {
			return null;
		}
		try {
			return MAPPER.readValue(json, Object.class);
		} catch (JsonProcessingException e) {
			String location = e.getLocation() == null ? "" : "（第 " + e.getLocation().getLineNr() + " 行第 " + e.getLocation().getColumnNr() + " 列）";
			throw new IllegalArgumentException("JSON 格式错误：" + e.getOriginalMessage() + location, e);
		}
	}

	/**
	 * 尝试解析 JSON，不是 JSON 时返回 null
	 */
	public static Object tryParse(String text) {
		if (text == null) {
			return null;
		}
		String trimmed = text.trim();
		if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
			return null;
		}
		try {
			return MAPPER.readValue(trimmed, Object.class);
		} catch (IOException e) {
			return null;
		}
	}

	public static String formatDate(Object value, String pattern) {
		String p = pattern == null || pattern.isEmpty() ? DATE_TIME_PATTERN : pattern;
		if (value instanceof Date) {
			return new SimpleDateFormat(p).format((Date) value);
		}
		if (value instanceof LocalDateTime) {
			return DateTimeFormatter.ofPattern(p).format((LocalDateTime) value);
		}
		if (value instanceof LocalDate) {
			return DateTimeFormatter.ofPattern(p).format(((LocalDate) value).atStartOfDay());
		}
		return value == null ? null : value.toString();
	}

	/**
	 * 把数据库值转换为适合报文输出的值
	 */
	public static Object normalize(Object value) {
		if (value == null || value instanceof String || value instanceof Boolean
				|| value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
				|| value instanceof BigDecimal || value instanceof BigInteger || value instanceof Double || value instanceof Float) {
			return value;
		}
		if (value instanceof Date) {
			return new SimpleDateFormat(DATE_TIME_PATTERN).format((Date) value);
		}
		if (value instanceof LocalDateTime) {
			return DATE_TIME.format((LocalDateTime) value);
		}
		if (value instanceof LocalDate || value instanceof LocalTime || value instanceof TemporalAccessor) {
			return value.toString();
		}
		if (value instanceof Clob) {
			return readClob((Clob) value);
		}
		if (value instanceof byte[]) {
			return Base64.getEncoder().encodeToString((byte[]) value);
		}
		if (value instanceof Map) {
			Map<String, Object> result = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
				result.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
			}
			return result;
		}
		if (value instanceof Collection) {
			List<Object> list = new ArrayList<>();
			for (Object item : (Collection<?>) value) {
				list.add(normalize(item));
			}
			return list;
		}
		if (value instanceof Object[]) {
			List<Object> list = new ArrayList<>();
			for (Object item : (Object[]) value) {
				list.add(normalize(item));
			}
			return list;
		}
		if (value instanceof Number) {
			return new BigDecimal(value.toString());
		}
		return value.toString();
	}

	private static String readClob(Clob clob) {
		try (Reader reader = clob.getCharacterStream()) {
			StringBuilder sb = new StringBuilder();
			char[] buffer = new char[4096];
			int len;
			while ((len = reader.read(buffer)) != -1) {
				sb.append(buffer, 0, len);
			}
			return sb.toString();
		} catch (SQLException | IOException e) {
			throw new IllegalStateException("读取 Clob 字段失败", e);
		}
	}

	/**
	 * 截断用于记录的文本
	 */
	public static String abbreviate(Object value, int max) {
		if (value == null) {
			return null;
		}
		String text;
		try {
			text = value instanceof String ? (String) value : stringify(value);
		} catch (Exception e) {
			text = String.valueOf(value);
		}
		return text.length() > max ? text.substring(0, max) + "…（共 " + text.length() + " 字）" : text;
	}
}
