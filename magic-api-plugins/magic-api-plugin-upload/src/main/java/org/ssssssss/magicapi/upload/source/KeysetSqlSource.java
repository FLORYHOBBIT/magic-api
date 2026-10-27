package org.ssssssss.magicapi.upload.source;

import org.springframework.jdbc.core.JdbcTemplate;
import org.ssssssss.magicapi.modules.db.dialect.Dialect;
import org.ssssssss.magicapi.upload.engine.BatchData;
import org.ssssssss.magicapi.upload.engine.BatchSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按唯一且递增的字段分批读取（keyset 分页）
 * <p>
 * 生成的 SQL：<code>SELECT * FROM (原SQL) upload_t WHERE upload_t.key &gt; ? ORDER BY upload_t.key</code>，再由方言限制条数。
 * 每批的速度不会随已读条数变慢；断点为最后一行的字段值，跨次执行保留，下次只读取新增数据。
 * <p>
 * 字段必须唯一（如自增主键、序列、唯一流水号）。用非唯一字段（如更新时间）时，批次边界上值相同的行可能被漏掉。
 */
public class KeysetSqlSource implements BatchSource {

	private final JdbcTemplate jdbc;

	private final Dialect dialect;

	private final SqlSupport.ParsedSql parsed;

	private final String keyColumn;

	public KeysetSqlSource(JdbcTemplate jdbc, Dialect dialect, String sql, Map<String, Object> variables, String keyColumn) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
		this.dialect = Objects.requireNonNull(dialect, "dialect");
		this.keyColumn = SqlSupport.requireIdentifier(keyColumn, "递增字段");
		this.parsed = SqlSupport.parse(sql, variables);
	}

	@Override
	public BatchData fetch(Object checkpoint, int batchSize) {
		List<Object> params = new ArrayList<>(parsed.params);
		StringBuilder sql = new StringBuilder("SELECT * FROM (\n").append(parsed.sql).append("\n) ").append(SqlSupport.ALIAS);
		if (checkpoint != null) {
			sql.append(" WHERE ").append(SqlSupport.ALIAS).append('.').append(keyColumn).append(" > ?");
			params.add(checkpoint);
		}
		sql.append(" ORDER BY ").append(SqlSupport.ALIAS).append('.').append(keyColumn);
		List<Map<String, Object>> rows = SqlSupport.query(jdbc, dialect, sql.toString(), params, 0, batchSize);
		if (rows.isEmpty()) {
			return new BatchData(rows, checkpoint);
		}
		Map<String, Object> last = rows.get(rows.size() - 1);
		if (!last.containsKey(keyColumn)) {
			throw new IllegalStateException("查询结果中没有递增字段 " + keyColumn + "，请检查 SQL 的查询列");
		}
		Object lastKey = last.get(keyColumn);
		if (lastKey == null) {
			throw new IllegalStateException("递增字段 " + keyColumn + " 的值为空，请在 SQL 中过滤掉空值");
		}
		return new BatchData(rows, lastKey);
	}

	String getSql() {
		return parsed.sql;
	}

	List<Object> getParams() {
		return parsed.params;
	}
}
