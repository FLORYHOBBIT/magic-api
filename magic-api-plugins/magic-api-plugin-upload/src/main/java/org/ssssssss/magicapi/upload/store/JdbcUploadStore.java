package org.ssssssss.magicapi.upload.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 基于 JDBC 的存储实现
 * <p>
 * 数据源在第一次使用时才解析（magic-api 界面里配置的数据源在启动后才注册），建表也在第一次使用时执行。
 * 锁和时间字段使用应用服务器时间，多实例部署时各服务器需要做时间同步。
 */
public class JdbcUploadStore implements UploadStore {

	private static final Logger logger = LoggerFactory.getLogger(JdbcUploadStore.class);

	static final int MESSAGE_MAX = 1000;

	static final int CHECKPOINT_MAX = 512;

	static final int RESPONSE_MAX = 65536;

	private final Supplier<JdbcTemplate> jdbcSupplier;

	private final UploadSchema schema;

	private final boolean initSchema;

	private final Clock clock;

	private volatile JdbcTemplate jdbc;

	private volatile DatabaseType databaseType;

	public JdbcUploadStore(Supplier<JdbcTemplate> jdbcSupplier, String tablePrefix, boolean initSchema) {
		this(jdbcSupplier, tablePrefix, initSchema, Clock.systemDefaultZone());
	}

	public JdbcUploadStore(Supplier<JdbcTemplate> jdbcSupplier, String tablePrefix, boolean initSchema, Clock clock) {
		this.jdbcSupplier = jdbcSupplier;
		this.schema = new UploadSchema(tablePrefix);
		this.initSchema = initSchema;
		this.clock = clock;
	}

	public UploadSchema getSchema() {
		return schema;
	}

	public DatabaseType getDatabaseType() {
		jdbc();
		return databaseType;
	}

	// ---------- 断点 ----------

	@Override
	public Object loadCheckpoint(String jobName) {
		List<Object> list = jdbc().query("SELECT checkpoint_type, checkpoint_value FROM " + schema.checkpointTable() + " WHERE job_name = ?",
				(rs, i) -> CheckpointCodec.decode(rs.getString(1), rs.getString(2)), jobName);
		return list.isEmpty() ? null : list.get(0);
	}

	@Override
	public void saveCheckpoint(String jobName, Object checkpoint) {
		CheckpointCodec.Encoded encoded = CheckpointCodec.encode(checkpoint);
		if (encoded.getValue() != null && encoded.getValue().length() > CHECKPOINT_MAX) {
			throw new IllegalArgumentException("断点值过长（超过 " + CHECKPOINT_MAX + " 个字符），请换用更短的排序字段");
		}
		Timestamp now = now();
		int updated = jdbc().update("UPDATE " + schema.checkpointTable() + " SET checkpoint_type = ?, checkpoint_value = ?, update_time = ? WHERE job_name = ?",
				encoded.getType(), encoded.getValue(), now, jobName);
		if (updated == 0) {
			jdbc().update("INSERT INTO " + schema.checkpointTable() + " (job_name, checkpoint_type, checkpoint_value, update_time) VALUES (?, ?, ?, ?)",
					jobName, encoded.getType(), encoded.getValue(), now);
		}
	}

	@Override
	public void resetCheckpoint(String jobName) {
		jdbc().update("DELETE FROM " + schema.checkpointTable() + " WHERE job_name = ?", jobName);
	}

	// ---------- 锁 ----------

	@Override
	public boolean tryLock(String jobName, String owner, Duration timeout) {
		Timestamp now = now();
		Timestamp expire = new Timestamp(now.getTime() + timeout.toMillis());
		// 锁已过期或本来就是自己持有时，直接接管；条件更新保证同一时刻只有一个 owner 成功
		int updated = jdbc().update("UPDATE " + schema.lockTable() + " SET owner = ?, lock_time = ?, expire_time = ? WHERE job_name = ? AND (expire_time < ? OR owner = ?)",
				owner, now, expire, jobName, now, owner);
		if (updated > 0) {
			return true;
		}
		try {
			jdbc().update("INSERT INTO " + schema.lockTable() + " (job_name, owner, lock_time, expire_time) VALUES (?, ?, ?, ?)",
					jobName, owner, now, expire);
			return true;
		} catch (DataIntegrityViolationException e) {
			// 主键冲突：锁被其他 owner 持有且未过期
			return false;
		}
	}

	@Override
	public void refreshLock(String jobName, String owner, Duration timeout) {
		Timestamp expire = new Timestamp(now().getTime() + timeout.toMillis());
		jdbc().update("UPDATE " + schema.lockTable() + " SET expire_time = ? WHERE job_name = ? AND owner = ?", expire, jobName, owner);
	}

	@Override
	public void unlock(String jobName, String owner) {
		jdbc().update("DELETE FROM " + schema.lockTable() + " WHERE job_name = ? AND owner = ?", jobName, owner);
	}

	// ---------- 记录 ----------

	@Override
	public void saveRecord(UploadRecord r) {
		if (r.getId() == null) {
			r.setId(newId());
		}
		jdbc().update("INSERT INTO " + schema.recordTable() + " (id, job_name, status, total_rows, success_rows, failed_rows, total_batches, failed_batches, "
						+ "start_checkpoint, end_checkpoint, message, start_time, end_time, cost_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				r.getId(), r.getJobName(), r.getStatus(), r.getTotalRows(), r.getSuccessRows(), r.getFailedRows(), r.getTotalBatches(), r.getFailedBatches(),
				truncate(r.getStartCheckpoint(), CHECKPOINT_MAX), truncate(r.getEndCheckpoint(), CHECKPOINT_MAX), truncate(r.getMessage(), MESSAGE_MAX),
				toTimestamp(r.getStartTime()), toTimestamp(r.getEndTime()), r.getCostMillis());
	}

	@Override
	public void saveBatchLog(BatchLog b) {
		if (b.getId() == null) {
			b.setId(newId());
		}
		jdbc().update("INSERT INTO " + schema.batchLogTable() + " (id, record_id, job_name, batch_index, row_count, success_flag, attempts, "
						+ "start_checkpoint, end_checkpoint, message, response, cost_ms, create_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				b.getId(), b.getRecordId(), b.getJobName(), b.getBatchIndex(), b.getRowCount(), b.isSuccess() ? 1 : 0, b.getAttempts(),
				truncate(b.getStartCheckpoint(), CHECKPOINT_MAX), truncate(b.getEndCheckpoint(), CHECKPOINT_MAX), truncate(b.getMessage(), MESSAGE_MAX),
				truncate(b.getResponse(), RESPONSE_MAX), b.getCostMillis(), now());
	}

	@Override
	public void saveFailedBatch(FailedBatch f) {
		if (f.getId() == null) {
			f.setId(newId());
		}
		Timestamp now = now();
		// 数据不截断：截断会导致补传时丢数据
		jdbc().update("INSERT INTO " + schema.failedBatchTable() + " (id, record_id, job_name, batch_index, row_count, start_checkpoint, end_checkpoint, "
						+ "payload, message, status, retry_count, create_time, update_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				f.getId(), f.getRecordId(), f.getJobName(), f.getBatchIndex(), f.getRowCount(),
				truncate(f.getStartCheckpoint(), CHECKPOINT_MAX), truncate(f.getEndCheckpoint(), CHECKPOINT_MAX),
				PayloadCodec.encode(f.getRows()), truncate(f.getMessage(), MESSAGE_MAX), FailedBatch.STATUS_PENDING, f.getRetryCount(), now, now);
	}

	@Override
	public List<FailedBatch> listPendingFailedBatches(String jobName, int limit) {
		RowMapper<FailedBatch> mapper = (rs, i) -> {
			FailedBatch f = new FailedBatch();
			f.setId(rs.getString("id"));
			f.setRecordId(rs.getString("record_id"));
			f.setJobName(rs.getString("job_name"));
			f.setBatchIndex(rs.getInt("batch_index"));
			f.setRowCount(rs.getInt("row_count"));
			f.setStartCheckpoint(rs.getString("start_checkpoint"));
			f.setEndCheckpoint(rs.getString("end_checkpoint"));
			f.setRows(PayloadCodec.decode(readText(rs, "payload")));
			f.setMessage(rs.getString("message"));
			f.setStatus(rs.getString("status"));
			f.setRetryCount(rs.getInt("retry_count"));
			f.setCreateTime(rs.getTimestamp("create_time"));
			f.setUpdateTime(rs.getTimestamp("update_time"));
			return f;
		};
		return queryLimited("SELECT * FROM " + schema.failedBatchTable() + " WHERE job_name = ? AND status = ? ORDER BY create_time, batch_index",
				mapper, limit, jobName, FailedBatch.STATUS_PENDING);
	}

	@Override
	public void updateFailedBatch(String id, boolean done, String message) {
		if (done) {
			jdbc().update("UPDATE " + schema.failedBatchTable() + " SET status = ?, message = ?, update_time = ? WHERE id = ?",
					FailedBatch.STATUS_DONE, truncate(message, MESSAGE_MAX), now(), id);
		} else {
			jdbc().update("UPDATE " + schema.failedBatchTable() + " SET retry_count = retry_count + 1, message = ?, update_time = ? WHERE id = ?",
					truncate(message, MESSAGE_MAX), now(), id);
		}
	}

	@Override
	public List<UploadRecord> listRecords(String jobName, int limit) {
		RowMapper<UploadRecord> mapper = (rs, i) -> {
			UploadRecord r = new UploadRecord();
			r.setId(rs.getString("id"));
			r.setJobName(rs.getString("job_name"));
			r.setStatus(rs.getString("status"));
			r.setTotalRows(rs.getLong("total_rows"));
			r.setSuccessRows(rs.getLong("success_rows"));
			r.setFailedRows(rs.getLong("failed_rows"));
			r.setTotalBatches(rs.getInt("total_batches"));
			r.setFailedBatches(rs.getInt("failed_batches"));
			r.setStartCheckpoint(rs.getString("start_checkpoint"));
			r.setEndCheckpoint(rs.getString("end_checkpoint"));
			r.setMessage(rs.getString("message"));
			r.setStartTime(rs.getTimestamp("start_time"));
			r.setEndTime(rs.getTimestamp("end_time"));
			r.setCostMillis(rs.getLong("cost_ms"));
			return r;
		};
		return queryLimited("SELECT * FROM " + schema.recordTable() + " WHERE job_name = ? ORDER BY start_time DESC",
				mapper, limit, jobName);
	}

	// ---------- 内部 ----------

	/**
	 * 用 Statement.setMaxRows 限制条数，避免为每种数据库拼分页 SQL
	 */
	private <T> List<T> queryLimited(String sql, RowMapper<T> mapper, int limit, Object... args) {
		JdbcTemplate limited = new JdbcTemplate(jdbc().getDataSource());
		limited.setMaxRows(limit > 0 ? limit : -1);
		return limited.query(sql, mapper, args);
	}

	/**
	 * 存储使用的 JdbcTemplate，首次调用时解析数据源并建表
	 */
	public JdbcTemplate jdbc() {
		JdbcTemplate template = this.jdbc;
		if (template == null) {
			synchronized (this) {
				template = this.jdbc;
				if (template == null) {
					template = jdbcSupplier.get();
					if (template == null) {
						throw new IllegalStateException("上传插件找不到可用的数据源，请检查 magic-api.upload.datasource 配置");
					}
					this.databaseType = detectType(template);
					if (initSchema) {
						createTablesIfAbsent(template, this.databaseType);
					}
					this.jdbc = template;
				}
			}
		}
		return template;
	}

	private DatabaseType detectType(JdbcTemplate template) {
		String productName = template.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
		DatabaseType type = DatabaseType.fromProductName(productName);
		logger.info("上传插件存储数据库: {} ({})", productName, type);
		return type;
	}

	private void createTablesIfAbsent(JdbcTemplate template, DatabaseType type) {
		for (String table : schema.tables()) {
			if (tableExists(template, table)) {
				continue;
			}
			if (type == DatabaseType.UNSUPPORTED) {
				logger.warn("上传插件不支持在当前数据库自动建表，请手工创建表 {}（建表语句见插件文档）", table);
				continue;
			}
			for (String sql : schema.ddl(table, type)) {
				logger.info("上传插件建表: {}", sql);
				template.execute(sql);
			}
		}
	}

	private boolean tableExists(JdbcTemplate template, String table) {
		try {
			template.queryForObject("SELECT COUNT(1) FROM " + table + " WHERE 1 = 0", Integer.class);
			return true;
		} catch (DataAccessException e) {
			return false;
		}
	}

	private Timestamp now() {
		return new Timestamp(clock.millis());
	}

	private static Timestamp toTimestamp(java.util.Date date) {
		return date == null ? null : new Timestamp(date.getTime());
	}

	static String newId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	static String truncate(String value, int max) {
		if (value == null || value.length() <= max) {
			return value;
		}
		return value.substring(0, max);
	}

	private static String readText(ResultSet rs, String column) throws SQLException {
		Object value = rs.getObject(column);
		if (value == null) {
			return null;
		}
		if (value instanceof Clob) {
			Clob clob = (Clob) value;
			long length = clob.length();
			return length == 0 ? "" : clob.getSubString(1, (int) length);
		}
		return value.toString();
	}
}
