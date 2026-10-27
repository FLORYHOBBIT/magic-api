package org.ssssssss.magicapi.upload.source;

import org.springframework.jdbc.core.JdbcTemplate;
import org.ssssssss.magicapi.modules.db.dialect.Dialect;
import org.ssssssss.magicapi.upload.engine.BatchData;
import org.ssssssss.magicapi.upload.engine.BatchSource;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按 offset 分页读取，用于没有唯一递增字段的数据（如按月汇总的统计结果）
 * <p>
 * 每次执行都读取 SQL 的全部结果。断点只用于中断后续传：数据读完后清除，下次从头开始。
 * 断点带有 SQL 与参数的指纹，SQL 或参数变化（例如换了月份）时自动从头开始。
 * <p>
 * 读取过程中源数据发生增删时，分页可能重复或遗漏，适合读取不再变化的数据。
 */
public class OffsetSqlSource implements BatchSource {

	private final JdbcTemplate jdbc;

	private final Dialect dialect;

	private final SqlSupport.ParsedSql parsed;

	private final String orderBy;

	private final String scope;

	public OffsetSqlSource(JdbcTemplate jdbc, Dialect dialect, String sql, Map<String, Object> variables, String orderBy) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
		this.dialect = Objects.requireNonNull(dialect, "dialect");
		this.orderBy = SqlSupport.qualifyOrderBy(orderBy);
		this.parsed = SqlSupport.parse(sql, variables);
		this.scope = SqlSupport.fingerprint(parsed.sql + "|" + this.orderBy + "|" + parsed.params);
	}

	@Override
	public BatchData fetch(Object checkpoint, int batchSize) {
		long offset = SqlSupport.offsetOf(checkpoint, scope);
		String sql = "SELECT * FROM (\n" + parsed.sql + "\n) " + SqlSupport.ALIAS + " ORDER BY " + orderBy;
		List<Map<String, Object>> rows = SqlSupport.query(jdbc, dialect, sql, parsed.params, offset, batchSize);
		return new BatchData(rows, scope + ":" + (offset + rows.size()));
	}

	@Override
	public boolean isIncremental() {
		return false;
	}

	String getScope() {
		return scope;
	}
}
