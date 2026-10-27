package org.ssssssss.magicapi.upload.source;

import org.ssssssss.magicapi.modules.db.BoundSql;
import org.ssssssss.magicapi.modules.db.dialect.Dialect;
import org.ssssssss.magicapi.modules.db.dialect.OracleDialect;
import org.ssssssss.magicapi.modules.db.dialect.SQLServer2005Dialect;
import org.ssssssss.magicapi.modules.db.mybatis.MybatisParser;
import org.ssssssss.magicapi.modules.db.mybatis.SqlNode;
import org.ssssssss.magicapi.modules.db.mybatis.TextSqlNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * SQL 数据来源的公共处理
 */
final class SqlSupport {

	static final String ALIAS = "upload_t";

	private static final List<String> MYBATIS_TAGS = Arrays.asList("</where>", "</if>", "</trim>", "</set>", "</foreach>");

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");

	private static final Pattern ORDER_ITEM = Pattern.compile("([A-Za-z_][A-Za-z0-9_$#]*)(\\s+(?i:asc|desc))?");

	private SqlSupport() {
	}

	/**
	 * 解析 #{}、${} 与 MyBatis 标签，结果固定用于整次任务
	 */
	static ParsedSql parse(String sqlOrXml, Map<String, Object> variables) {
		if (sqlOrXml == null || sqlOrXml.trim().isEmpty()) {
			throw new IllegalArgumentException("SQL 不能为空");
		}
		Map<String, Object> vars = variables == null ? new HashMap<>() : new HashMap<>(variables);
		String sql;
		List<Object> params;
		if (MYBATIS_TAGS.stream().anyMatch(sqlOrXml::contains)) {
			SqlNode node = MybatisParser.parse(sqlOrXml);
			sql = node.getSql(vars);
			params = new ArrayList<>(node.getParameters());
		} else {
			params = new ArrayList<>();
			sql = TextSqlNode.parseSql(sqlOrXml, vars, params);
		}
		return new ParsedSql(stripTrailingOrderBy(stripSemicolon(sql.trim())), params);
	}

	private static String stripSemicolon(String sql) {
		String result = sql;
		while (result.endsWith(";")) {
			result = result.substring(0, result.length() - 1).trim();
		}
		return result;
	}

	/**
	 * 去掉最外层的 ORDER BY（排序由数据来源统一添加），子查询里的 ORDER BY 不受影响
	 */
	static String stripTrailingOrderBy(String sql) {
		String lower = sql.toLowerCase(Locale.ROOT);
		int depth = 0;
		boolean inString = false;
		int lastTopLevelOrderBy = -1;
		for (int i = 0; i < lower.length(); i++) {
			char c = lower.charAt(i);
			if (c == '\'') {
				inString = !inString;
			} else if (!inString) {
				if (c == '(') {
					depth++;
				} else if (c == ')') {
					depth--;
				} else if (depth == 0 && c == 'o' && lower.startsWith("order", i) && isBoundary(lower, i - 1)) {
					int j = i + 5;
					while (j < lower.length() && Character.isWhitespace(lower.charAt(j))) {
						j++;
					}
					if (j > i + 5 && lower.startsWith("by", j) && isBoundary(lower, j + 2)) {
						lastTopLevelOrderBy = i;
					}
				}
			}
		}
		return lastTopLevelOrderBy < 0 ? sql : sql.substring(0, lastTopLevelOrderBy).trim();
	}

	private static boolean isBoundary(String s, int index) {
		return index < 0 || index >= s.length() || !(Character.isLetterOrDigit(s.charAt(index)) || s.charAt(index) == '_');
	}

	static String requireIdentifier(String column, String what) {
		if (column == null || !IDENTIFIER.matcher(column.trim()).matches()) {
			throw new IllegalArgumentException(what + "只能是字段名（字母、数字、下划线），请在 SQL 中为它起别名: " + column);
		}
		return column.trim();
	}

	/**
	 * 校验并加上别名前缀，例如 "dept, id desc" -> "upload_t.dept, upload_t.id desc"
	 */
	static String qualifyOrderBy(String orderBy) {
		if (orderBy == null || orderBy.trim().isEmpty()) {
			throw new IllegalArgumentException("按 offset 分页时必须指定排序字段，否则分页结果不稳定");
		}
		StringBuilder sb = new StringBuilder();
		for (String item : orderBy.split(",")) {
			java.util.regex.Matcher m = ORDER_ITEM.matcher(item.trim());
			if (!m.matches()) {
				throw new IllegalArgumentException("排序字段格式不正确（示例: \"dept, id desc\"）: " + orderBy);
			}
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(ALIAS).append('.').append(m.group(1));
			if (m.group(2) != null) {
				sb.append(m.group(2).toUpperCase(Locale.ROOT));
			}
		}
		return sb.toString();
	}

	static List<Map<String, Object>> query(org.springframework.jdbc.core.JdbcTemplate jdbc, Dialect dialect,
										   String sql, List<Object> params, long offset, long limit) {
		BoundSql boundSql = new BoundSql(null, sql, new ArrayList<>(params), null);
		String pageSql = dialect.getPageSql(sql, boundSql, offset, limit);
		List<Map<String, Object>> rows = jdbc.queryForList(pageSql, boundSql.getParameters());
		String synthetic = syntheticColumn(dialect);
		if (synthetic != null) {
			for (Map<String, Object> row : rows) {
				row.remove(synthetic);
			}
		}
		return rows;
	}

	/**
	 * 方言分页时额外带出的行号列
	 */
	private static String syntheticColumn(Dialect dialect) {
		if (dialect instanceof OracleDialect) {
			return "ROW_ID";
		}
		if (dialect instanceof SQLServer2005Dialect) {
			return "__row_number__";
		}
		return null;
	}

	static String fingerprint(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 8; i++) {
				sb.append(String.format("%02x", digest[i]));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * 解析 "scope:offset" 形式的断点，scope 不同时返回 0
	 */
	static long offsetOf(Object checkpoint, String scope) {
		if (checkpoint instanceof String) {
			String value = (String) checkpoint;
			String prefix = scope + ":";
			if (value.startsWith(prefix)) {
				try {
					return Long.parseLong(value.substring(prefix.length()));
				} catch (NumberFormatException ignored) {
					return 0;
				}
			}
		}
		return 0;
	}

	static final class ParsedSql {

		final String sql;

		final List<Object> params;

		ParsedSql(String sql, List<Object> params) {
			this.sql = sql;
			this.params = Collections.unmodifiableList(params);
		}
	}
}
