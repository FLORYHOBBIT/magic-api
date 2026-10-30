package org.ssssssss.magicapi.flow.proc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;
import org.ssssssss.magicapi.flow.support.FlowJson;
import org.ssssssss.magicapi.flow.support.XmlCodec;
import org.ssssssss.magicapi.flow.trigger.FlowJobService;

import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 执行存储过程：按类型绑定入参、注册出参，执行后按类型读取并按设置解析。
 * <p>
 * 兼容 Oracle 11g：clob 入参一律建临时 CLOB 绑定（不受 32k 限制），游标按 Oracle 类型码 -10 注册，不依赖 Oracle 驱动类。
 * 通过 JdbcTemplate 取连接，在 db.transaction 中调用时参与同一事务。
 */
public class ProcExecutor {

	private static final Logger logger = LoggerFactory.getLogger(ProcExecutor.class);

	private static final String[] DATE_PATTERNS = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd", "yyyyMMddHHmmss", "yyyyMMdd"};

	/**
	 * 返回出参 Map（键为参数名，函数返回值为 return）
	 */
	public Map<String, Object> execute(JdbcTemplate jdbcTemplate, ProcSpec spec) {
		ProcSpec.ProcStatement statement = spec.build();
		long start = System.currentTimeMillis();
		try {
			Map<String, Object> result = jdbcTemplate.execute((ConnectionCallback<Map<String, Object>>) con -> call(con, spec, statement));
			logger.debug("调用存储过程 {} 耗时 {}ms", statement.getSql(), System.currentTimeMillis() - start);
			return result;
		} catch (DataAccessException e) {
			SQLException cause = findSqlException(e);
			String message = cause == null ? e.getMessage() : cause.getMessage();
			throw new ProcException("调用存储过程失败：" + (message == null ? e.toString() : message.trim()) + "（语句：" + statement.getSql() + "）", e);
		}
	}

	Map<String, Object> call(Connection con, ProcSpec spec, ProcSpec.ProcStatement statement) throws SQLException {
		List<Object> temporaries = new ArrayList<>();
		List<ProcParam> bindings = statement.getBindings();
		try (CallableStatement cs = con.prepareCall(statement.getSql())) {
			if (spec.getTimeout() > 0) {
				cs.setQueryTimeout(spec.getTimeout());
			}
			for (int i = 0; i < bindings.size(); i++) {
				ProcParam p = bindings.get(i);
				if (p.isIn()) {
					bind(con, cs, i + 1, p, temporaries);
				}
				if (p.isOut()) {
					cs.registerOutParameter(i + 1, p.sqlType());
				}
			}
			cs.execute();
			Map<String, Object> result = new LinkedHashMap<>();
			Set<String> read = new HashSet<>();
			for (int i = 0; i < bindings.size(); i++) {
				ProcParam p = bindings.get(i);
				if (p.isOut() && read.add(p.getName().toUpperCase(Locale.ROOT))) {
					result.put(p.getName(), parse(p, read(cs, i + 1, p, spec.getColumnCase())));
				}
			}
			return result;
		} finally {
			for (Object temporary : temporaries) {
				try {
					if (temporary instanceof Clob) {
						((Clob) temporary).free();
					} else if (temporary instanceof Blob) {
						((Blob) temporary).free();
					}
				} catch (SQLException | AbstractMethodError ignored) {
					// 驱动不支持释放时由连接关闭回收
				}
			}
		}
	}

	private static void bind(Connection con, CallableStatement cs, int index, ProcParam p, List<Object> temporaries) throws SQLException {
		Object value = p.getValue();
		if (value == null || (value instanceof String && ((String) value).isEmpty() && !"varchar".equals(p.getType()) && !"clob".equals(p.getType()))) {
			cs.setNull(index, p.sqlType());
			return;
		}
		switch (p.getType()) {
			case "number":
				cs.setBigDecimal(index, toDecimal(p, value));
				break;
			case "integer":
				cs.setLong(index, toDecimal(p, value).longValueExact());
				break;
			case "date":
			case "timestamp":
				cs.setTimestamp(index, toTimestamp(p, value));
				break;
			case "clob":
				if (value instanceof Clob) {
					cs.setClob(index, (Clob) value);
				} else {
					Clob clob = con.createClob();
					temporaries.add(clob);
					clob.setString(1, toText(value));
					cs.setClob(index, clob);
				}
				break;
			case "blob":
				if (value instanceof Blob) {
					cs.setBlob(index, (Blob) value);
				} else {
					Blob blob = con.createBlob();
					temporaries.add(blob);
					blob.setBytes(1, value instanceof byte[] ? (byte[]) value : toText(value).getBytes(StandardCharsets.UTF_8));
					cs.setBlob(index, blob);
				}
				break;
			default:
				cs.setString(index, toText(value));
		}
	}

	private static Object read(CallableStatement cs, int index, ProcParam p, String columnCase) throws SQLException {
		switch (p.getType()) {
			case "number":
				return cs.getBigDecimal(index);
			case "integer": {
				long value = cs.getLong(index);
				return cs.wasNull() ? null : value;
			}
			case "date":
			case "timestamp":
				return cs.getTimestamp(index);
			case "clob": {
				Clob clob = cs.getClob(index);
				return clob == null ? null : readClob(clob);
			}
			case "blob": {
				Blob blob = cs.getBlob(index);
				if (blob == null) {
					return null;
				}
				try {
					return blob.getBytes(1, (int) blob.length());
				} finally {
					freeQuietly(blob);
				}
			}
			case "cursor": {
				Object value = cs.getObject(index);
				return value instanceof ResultSet ? readRows((ResultSet) value, columnCase) : null;
			}
			default:
				return cs.getString(index);
		}
	}

	static List<Map<String, Object>> readRows(ResultSet rs, String columnCase) throws SQLException {
		List<Map<String, Object>> rows = new ArrayList<>();
		try {
			ResultSetMetaData meta = rs.getMetaData();
			int count = meta.getColumnCount();
			String[] labels = new String[count];
			for (int i = 0; i < count; i++) {
				labels[i] = JdbcUtils.lookupColumnName(meta, i + 1);
			}
			while (rs.next()) {
				Map<String, Object> row = new LinkedHashMap<>();
				for (int i = 0; i < count; i++) {
					Object value = JdbcUtils.getResultSetValue(rs, i + 1);
					if (value instanceof Clob) {
						value = readClob((Clob) value);
					} else if (value instanceof Blob) {
						Blob blob = (Blob) value;
						value = blob.getBytes(1, (int) blob.length());
					}
					row.put(labels[i], value);
				}
				rows.add(FlowJobService.convertCase(row, columnCase));
			}
		} finally {
			rs.close();
		}
		return rows;
	}

	private static Object parse(ProcParam p, Object value) {
		if (!(value instanceof String) || "string".equals(p.getParse())) {
			return value;
		}
		String text = (String) value;
		if (text.trim().isEmpty()) {
			return null;
		}
		try {
			return "json".equals(p.getParse()) ? FlowJson.parse(text) : XmlCodec.parse(text);
		} catch (RuntimeException e) {
			throw new ProcException("出参 " + p.getName() + " 不是合法的 " + p.getParse().toUpperCase(Locale.ROOT) + "：" + e.getMessage()
					+ "，内容：" + FlowJson.abbreviate(text, 200), e);
		}
	}

	private static String readClob(Clob clob) throws SQLException {
		try (Reader reader = clob.getCharacterStream()) {
			StringBuilder sb = new StringBuilder();
			char[] buffer = new char[8192];
			int n;
			while ((n = reader.read(buffer)) > 0) {
				sb.append(buffer, 0, n);
			}
			return sb.toString();
		} catch (java.io.IOException e) {
			throw new SQLException("读取 CLOB 失败：" + e.getMessage(), e);
		} finally {
			freeQuietly(clob);
		}
	}

	private static void freeQuietly(Object lob) {
		try {
			if (lob instanceof Clob) {
				((Clob) lob).free();
			} else if (lob instanceof Blob) {
				((Blob) lob).free();
			}
		} catch (SQLException | AbstractMethodError ignored) {
			// 忽略
		}
	}

	private static String toText(Object value) {
		if (value instanceof String) {
			return (String) value;
		}
		if (value instanceof Map || value instanceof Collection || value instanceof Object[]) {
			return FlowJson.stringify(value);
		}
		if (value instanceof java.util.Date || value instanceof LocalDateTime || value instanceof LocalDate) {
			return FlowJson.formatDate(value, null);
		}
		return value.toString();
	}

	private static BigDecimal toDecimal(ProcParam p, Object value) {
		if (value instanceof BigDecimal) {
			return (BigDecimal) value;
		}
		if (value instanceof Number) {
			return new BigDecimal(value.toString());
		}
		if (value instanceof Boolean) {
			return (Boolean) value ? BigDecimal.ONE : BigDecimal.ZERO;
		}
		try {
			return new BigDecimal(value.toString().trim());
		} catch (NumberFormatException e) {
			throw new ProcException("入参 " + p.getName() + " 不是数字：" + FlowJson.abbreviate(value, 50), e);
		}
	}

	private static Timestamp toTimestamp(ProcParam p, Object value) {
		if (value instanceof Timestamp) {
			return (Timestamp) value;
		}
		if (value instanceof java.util.Date) {
			return new Timestamp(((java.util.Date) value).getTime());
		}
		if (value instanceof LocalDateTime) {
			return Timestamp.valueOf((LocalDateTime) value);
		}
		if (value instanceof LocalDate) {
			return Timestamp.valueOf(((LocalDate) value).atStartOfDay());
		}
		if (value instanceof Number) {
			return new Timestamp(((Number) value).longValue());
		}
		String text = value.toString().trim();
		for (String pattern : DATE_PATTERNS) {
			if (pattern.replace("'", "").length() != text.length()) {
				continue;
			}
			SimpleDateFormat format = new SimpleDateFormat(pattern);
			format.setLenient(false);
			try {
				return new Timestamp(format.parse(text).getTime());
			} catch (ParseException ignored) {
				// 试下一个格式
			}
		}
		throw new ProcException("入参 " + p.getName() + " 不是日期（支持 yyyy-MM-dd HH:mm:ss、yyyy-MM-dd、yyyyMMdd 等）：" + FlowJson.abbreviate(value, 50));
	}

	private static SQLException findSqlException(Throwable e) {
		Throwable t = e;
		while (t != null) {
			if (t instanceof SQLException) {
				return (SQLException) t;
			}
			t = t.getCause();
		}
		return null;
	}
}
