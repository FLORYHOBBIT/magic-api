package org.ssssssss.magicapi.upload.store;

import com.fasterxml.jackson.core.type.TypeReference;
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
 * 失败批次数据的保存格式
 * <p>
 * 日期时间统一转为 "yyyy-MM-dd HH:mm:ss.SSS" 字符串，Clob 转为字符串，
 * 其他非常规类型（如 oracle.sql.TIMESTAMP）转为 toString()。补传时这些字段是字符串。
 */
public final class PayloadCodec {

	public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";

	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final TypeReference<List<Map<String, Object>>> ROWS_TYPE = new TypeReference<List<Map<String, Object>>>() {
	};

	private PayloadCodec() {
	}

	public static String encode(List<Map<String, Object>> rows) {
		List<Map<String, Object>> normalized = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			normalized.add(normalizeMap(row));
		}
		try {
			return MAPPER.writeValueAsString(normalized);
		} catch (IOException e) {
			throw new IllegalStateException("失败批次数据序列化失败", e);
		}
	}

	public static List<Map<String, Object>> decode(String json) {
		if (json == null || json.isEmpty()) {
			return Collections.emptyList();
		}
		try {
			return MAPPER.readValue(json, ROWS_TYPE);
		} catch (IOException e) {
			throw new IllegalStateException("失败批次数据解析失败", e);
		}
	}

	private static Map<String, Object> normalizeMap(Map<?, ?> map) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			result.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
		}
		return result;
	}

	/**
	 * 把数据库值转换为适合 JSON 输出的值：日期转 "yyyy-MM-dd HH:mm:ss.SSS"，Clob 转字符串，其他非常规类型转 toString()
	 */
	public static Object normalize(Object value) {
		if (value == null || value instanceof String || value instanceof Boolean
				|| value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
				|| value instanceof BigDecimal || value instanceof BigInteger || value instanceof Double || value instanceof Float
				|| value instanceof byte[]) {
			return value;
		}
		if (value instanceof Date) {
			return new SimpleDateFormat(DATE_TIME_PATTERN).format((Date) value);
		}
		if (value instanceof LocalDateTime) {
			return DATE_TIME.format((LocalDateTime) value);
		}
		if (value instanceof LocalDate || value instanceof LocalTime) {
			return value.toString();
		}
		if (value instanceof TemporalAccessor) {
			return value.toString();
		}
		if (value instanceof Clob) {
			return readClob((Clob) value);
		}
		if (value instanceof Map) {
			return normalizeMap((Map<?, ?>) value);
		}
		if (value instanceof Collection) {
			List<Object> list = new ArrayList<>();
			for (Object item : (Collection<?>) value) {
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
}
