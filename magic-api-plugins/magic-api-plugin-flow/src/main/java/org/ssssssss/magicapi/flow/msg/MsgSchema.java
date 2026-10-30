package org.ssssssss.magicapi.flow.msg;

import org.ssssssss.magicapi.upload.store.DatabaseType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 消息表（与上传插件的表同库，默认 HIS 库）。索引：
 * <ul>
 *     <li>(msg_type, msg_id) 唯一：去重；顺序消费按类型取未完成消息</li>
 *     <li>(status, next_time)：领取待处理消息</li>
 *     <li>(cb_status, cb_next_time)：领取待回调消息（不需要回调的类型该列为空）</li>
 * </ul>
 */
public class MsgSchema {

	private static final Pattern PREFIX = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,15}");

	private final String prefix;

	private final String table;

	public MsgSchema(String prefix) {
		if (prefix == null || !PREFIX.matcher(prefix).matches()) {
			throw new IllegalArgumentException("表名前缀只能包含字母、数字、下划线，以字母开头，最长 16 位: " + prefix);
		}
		this.prefix = prefix;
		this.table = prefix + "msg";
	}

	public String table() {
		return table;
	}

	/**
	 * 建表与索引语句，按执行顺序返回；不支持的数据库抛 IllegalArgumentException
	 */
	public List<String> ddl(DatabaseType type) {
		if (type == DatabaseType.UNSUPPORTED) {
			throw new IllegalArgumentException("不支持自动建表的数据库，请手工建表");
		}
		List<String> sql = new ArrayList<>();
		sql.add("CREATE TABLE " + table + " ("
				+ "id " + str(type, 32) + " NOT NULL, "
				+ "msg_type " + str(type, 64) + " NOT NULL, "
				+ "msg_id " + str(type, 128) + " NOT NULL, "
				+ "seq " + bigint(type) + " NOT NULL, "
				+ "order_key " + str(type, 128) + ", "
				+ "status " + str(type, 16) + " NOT NULL, "
				+ "payload " + clob(type) + ", "
				+ "meta " + clob(type) + ", "
				+ "client_ip " + str(type, 64) + ", "
				+ "attempts " + integer(type) + ", "
				+ "next_time " + time(type) + ", "
				+ "owner " + str(type, 128) + ", "
				+ "lock_time " + time(type) + ", "
				+ "error " + str(type, 1000) + ", "
				+ "error_node " + str(type, 64) + ", "
				+ "trace " + clob(type) + ", "
				+ "result " + str(type, 2000) + ", "
				+ "receive_time " + time(type) + ", "
				+ "start_time " + time(type) + ", "
				+ "finish_time " + time(type) + ", "
				+ "cost_ms " + bigint(type) + ", "
				+ "cb_status " + str(type, 16) + ", "
				+ "cb_attempts " + integer(type) + ", "
				+ "cb_next_time " + time(type) + ", "
				+ "cb_error " + str(type, 1000) + ", "
				+ "cb_response " + str(type, 2000) + ", "
				+ "cb_time " + time(type) + ", "
				+ "PRIMARY KEY (id))");
		sql.add("CREATE UNIQUE INDEX " + prefix + "msg_uk ON " + table + " (msg_type, msg_id)");
		sql.add("CREATE INDEX " + prefix + "msg_ix ON " + table + " (status, next_time)");
		sql.add("CREATE INDEX " + prefix + "msg_cb ON " + table + " (cb_status, cb_next_time)");
		return sql;
	}

	private static String str(DatabaseType type, int length) {
		switch (type) {
			case ORACLE:
				return "VARCHAR2(" + length + " CHAR)";
			case SQLSERVER:
				return "NVARCHAR(" + length + ")";
			default:
				return "VARCHAR(" + length + ")";
		}
	}

	private static String bigint(DatabaseType type) {
		return type == DatabaseType.ORACLE ? "NUMBER(19)" : "BIGINT";
	}

	private static String integer(DatabaseType type) {
		return type == DatabaseType.ORACLE ? "NUMBER(10)" : "INT";
	}

	private static String clob(DatabaseType type) {
		return type == DatabaseType.SQLSERVER ? "NVARCHAR(MAX)" : "CLOB";
	}

	private static String time(DatabaseType type) {
		return type == DatabaseType.SQLSERVER ? "DATETIME2" : "TIMESTAMP";
	}
}
