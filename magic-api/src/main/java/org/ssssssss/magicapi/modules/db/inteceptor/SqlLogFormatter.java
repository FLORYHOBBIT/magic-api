package org.ssssssss.magicapi.modules.db.inteceptor;

import org.springframework.jdbc.core.SqlParameter;
import org.springframework.jdbc.core.SqlParameterValue;

import java.math.BigDecimal;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

/** 仅展开日志文本，绝不修改 JDBC 执行的 SQL 或参数，也不读取流参数。 */
final class SqlLogFormatter {

	private SqlLogFormatter() {}

	static String format(String sql, Object[] parameters) {
		Object[] values = parameters == null ? new Object[0] : parameters;
		if (values.length > 0 && Arrays.stream(values).allMatch(value -> value instanceof Object[])) {
			StringBuilder batch = new StringBuilder();
			for (int i = 0; i < values.length; i++) {
				if (i > 0) batch.append('\n');
				String statement = formatOne(sql, (Object[]) values[i]);
				batch.append("-- 批次 ").append(i + 1).append('/').append(values.length).append('\n').append(statement);
				if (!statement.trim().endsWith(";")) batch.append(';');
			}
			return batch.toString();
		}
		return formatOne(sql, values);
	}

	private static String formatOne(String sql, Object[] values) {
		if (sql == null) return "";
		StringBuilder out = new StringBuilder(sql.length() + values.length * 16);
		int parameter = 0;
		for (int i = 0; i < sql.length();) {
			char c = sql.charAt(i);
			int end = -1;
			if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
				end = sql.indexOf('\n', i + 2);
				if (end < 0) end = sql.length();
			} else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
				end = sql.indexOf("*/", i + 2);
				end = end < 0 ? sql.length() : end + 2;
			} else if ((c == 'q' || c == 'Q') && i + 2 < sql.length() && sql.charAt(i + 1) == '\'') {
				char open = sql.charAt(i + 2);
				char close = open == '[' ? ']' : open == '(' ? ')' : open == '{' ? '}' : open == '<' ? '>' : open;
				end = sql.indexOf("" + close + '\'', i + 3);
				end = end < 0 ? sql.length() : end + 2;
			} else if (c == '\'' || c == '"' || c == '`' || c == '[') {
				char close = c == '[' ? ']' : c;
				end = i + 1;
				while (end < sql.length()) {
					if (sql.charAt(end++) == close) {
						if (end < sql.length() && sql.charAt(end) == close) end++;
						else break;
					}
				}
			} else if (c == '$') {
				int tagEnd = sql.indexOf('$', i + 1);
				if (tagEnd >= 0 && sql.substring(i + 1, tagEnd).matches("[A-Za-z_][A-Za-z_0-9]*|")) {
					String tag = sql.substring(i, tagEnd + 1);
					end = sql.indexOf(tag, tagEnd + 1);
					end = end < 0 ? sql.length() : end + tag.length();
				}
			}
			if (end >= 0) {
				out.append(sql, i, end);
				i = end;
			} else if (c == '?') {
				if (parameter >= values.length) throw new IllegalArgumentException("SQL 占位符多于绑定参数");
				out.append(literal(values[parameter++]));
				i++;
			} else {
				out.append(c);
				i++;
			}
		}
		if (parameter != values.length) throw new IllegalArgumentException("SQL 绑定参数多于占位符");
		return out.toString();
	}

	private static String literal(Object value) {
		if (value instanceof SqlParameterValue) return literal(((SqlParameterValue) value).getValue());
		if (value == null) return "NULL";
		if (value instanceof SqlParameter) return "NULL /* OUT 参数 */";
		if (value instanceof BigDecimal) return ((BigDecimal) value).toPlainString();
		if (value instanceof Number) return value.toString();
		if (value instanceof Boolean) return (Boolean) value ? "1" : "0";
		if (value instanceof java.sql.Date) return "DATE " + quote(value.toString());
		if (value instanceof Timestamp) return "TIMESTAMP " + quote(value.toString());
		if (value instanceof Time) return "TIME " + quote(value.toString());
		if (value instanceof Date) return "TIMESTAMP " + quote(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(value));
		if (value instanceof byte[]) {
			char[] hex = "0123456789ABCDEF".toCharArray();
			StringBuilder result = new StringBuilder("X'");
			for (byte b : (byte[]) value) result.append(hex[(b & 0xff) >>> 4]).append(hex[b & 0xf]);
			return result.append('\'').toString();
		}
		return quote(String.valueOf(value));
	}

	private static String quote(String value) {
		return "'" + value.replace("'", "''") + "'";
	}
}
