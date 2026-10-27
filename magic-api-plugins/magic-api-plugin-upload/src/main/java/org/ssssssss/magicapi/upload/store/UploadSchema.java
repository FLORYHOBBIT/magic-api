package org.ssssssss.magicapi.upload.store;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 上传插件使用的表
 * <ul>
 *     <li>checkpoint：每个任务的断点</li>
 *     <li>lock：任务锁，防止同一任务并发执行</li>
 *     <li>record：每次执行的汇总记录</li>
 *     <li>batch_log：每一批的上传结果与回执</li>
 *     <li>failed_batch：跳过的失败批次及其数据，用于补传</li>
 * </ul>
 */
public class UploadSchema {

	private static final Pattern PREFIX = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,15}");

	private final String checkpointTable;

	private final String lockTable;

	private final String recordTable;

	private final String batchLogTable;

	private final String failedBatchTable;

	private final String prefix;

	public UploadSchema(String prefix) {
		if (prefix == null || !PREFIX.matcher(prefix).matches()) {
			throw new IllegalArgumentException("表名前缀只能包含字母、数字、下划线，以字母开头，最长 16 位: " + prefix);
		}
		this.prefix = prefix;
		this.checkpointTable = prefix + "checkpoint";
		this.lockTable = prefix + "lock";
		this.recordTable = prefix + "record";
		this.batchLogTable = prefix + "batch_log";
		this.failedBatchTable = prefix + "failed_batch";
	}

	public String checkpointTable() {
		return checkpointTable;
	}

	public String lockTable() {
		return lockTable;
	}

	public String recordTable() {
		return recordTable;
	}

	public String batchLogTable() {
		return batchLogTable;
	}

	public String failedBatchTable() {
		return failedBatchTable;
	}

	public List<String> tables() {
		List<String> tables = new ArrayList<>();
		tables.add(checkpointTable);
		tables.add(lockTable);
		tables.add(recordTable);
		tables.add(batchLogTable);
		tables.add(failedBatchTable);
		return tables;
	}

	/**
	 * 某张表的建表语句（含索引），按执行顺序返回
	 */
	public List<String> ddl(String table, DatabaseType type) {
		Types t = new Types(type);
		List<String> sql = new ArrayList<>();
		if (table.equals(checkpointTable)) {
			sql.add("CREATE TABLE " + checkpointTable + " ("
					+ "job_name " + t.str(128) + " NOT NULL, "
					+ "checkpoint_type " + t.str(16) + ", "
					+ "checkpoint_value " + t.str(512) + ", "
					+ "update_time " + t.time() + ", "
					+ "PRIMARY KEY (job_name))");
		} else if (table.equals(lockTable)) {
			sql.add("CREATE TABLE " + lockTable + " ("
					+ "job_name " + t.str(128) + " NOT NULL, "
					+ "owner " + t.str(128) + " NOT NULL, "
					+ "lock_time " + t.time() + ", "
					+ "expire_time " + t.time() + " NOT NULL, "
					+ "PRIMARY KEY (job_name))");
		} else if (table.equals(recordTable)) {
			sql.add("CREATE TABLE " + recordTable + " ("
					+ "id " + t.str(32) + " NOT NULL, "
					+ "job_name " + t.str(128) + " NOT NULL, "
					+ "status " + t.str(16) + ", "
					+ "total_rows " + t.bigint() + ", "
					+ "success_rows " + t.bigint() + ", "
					+ "failed_rows " + t.bigint() + ", "
					+ "total_batches " + t.integer() + ", "
					+ "failed_batches " + t.integer() + ", "
					+ "start_checkpoint " + t.str(512) + ", "
					+ "end_checkpoint " + t.str(512) + ", "
					+ "message " + t.str(1000) + ", "
					+ "start_time " + t.time() + ", "
					+ "end_time " + t.time() + ", "
					+ "cost_ms " + t.bigint() + ", "
					+ "PRIMARY KEY (id))");
			sql.add("CREATE INDEX " + prefix + "record_ix ON " + recordTable + " (job_name, start_time)");
		} else if (table.equals(batchLogTable)) {
			sql.add("CREATE TABLE " + batchLogTable + " ("
					+ "id " + t.str(32) + " NOT NULL, "
					+ "record_id " + t.str(32) + ", "
					+ "job_name " + t.str(128) + " NOT NULL, "
					+ "batch_index " + t.integer() + ", "
					+ "row_count " + t.integer() + ", "
					+ "success_flag " + t.integer() + ", "
					+ "attempts " + t.integer() + ", "
					+ "start_checkpoint " + t.str(512) + ", "
					+ "end_checkpoint " + t.str(512) + ", "
					+ "message " + t.str(1000) + ", "
					+ "response " + t.clob() + ", "
					+ "cost_ms " + t.bigint() + ", "
					+ "create_time " + t.time() + ", "
					+ "PRIMARY KEY (id))");
			sql.add("CREATE INDEX " + prefix + "batch_ix ON " + batchLogTable + " (record_id)");
		} else if (table.equals(failedBatchTable)) {
			sql.add("CREATE TABLE " + failedBatchTable + " ("
					+ "id " + t.str(32) + " NOT NULL, "
					+ "record_id " + t.str(32) + ", "
					+ "job_name " + t.str(128) + " NOT NULL, "
					+ "batch_index " + t.integer() + ", "
					+ "row_count " + t.integer() + ", "
					+ "start_checkpoint " + t.str(512) + ", "
					+ "end_checkpoint " + t.str(512) + ", "
					+ "payload " + t.clob() + ", "
					+ "message " + t.str(1000) + ", "
					+ "status " + t.str(16) + ", "
					+ "retry_count " + t.integer() + ", "
					+ "create_time " + t.time() + ", "
					+ "update_time " + t.time() + ", "
					+ "PRIMARY KEY (id))");
			sql.add("CREATE INDEX " + prefix + "failed_ix ON " + failedBatchTable + " (job_name, status)");
		} else {
			throw new IllegalArgumentException("未知的表: " + table);
		}
		return sql;
	}

	private static class Types {

		private final DatabaseType type;

		Types(DatabaseType type) {
			if (type == DatabaseType.UNSUPPORTED) {
				throw new IllegalArgumentException("不支持自动建表的数据库，请手工建表");
			}
			this.type = type;
		}

		String str(int length) {
			switch (type) {
				case ORACLE:
					return "VARCHAR2(" + length + " CHAR)";
				case SQLSERVER:
					return "NVARCHAR(" + length + ")";
				default:
					return "VARCHAR(" + length + ")";
			}
		}

		String bigint() {
			return type == DatabaseType.ORACLE ? "NUMBER(19)" : "BIGINT";
		}

		String integer() {
			return type == DatabaseType.ORACLE ? "NUMBER(10)" : "INT";
		}

		String clob() {
			return type == DatabaseType.SQLSERVER ? "NVARCHAR(MAX)" : "CLOB";
		}

		String time() {
			return type == DatabaseType.SQLSERVER ? "DATETIME2" : "TIMESTAMP";
		}
	}
}
