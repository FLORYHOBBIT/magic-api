package org.ssssssss.magicapi.flow.msg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.ssssssss.magicapi.upload.store.DatabaseType;

import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.ssssssss.magicapi.flow.msg.MsgStatus.*;

/**
 * 消息表的读写。所有状态变更都是带条件的 UPDATE（按 id + 原状态 / 领取人），多台服务共用一张表时同一条消息只会被一台领取。
 */
public class JdbcMsgStore {

	private static final Logger logger = LoggerFactory.getLogger(JdbcMsgStore.class);

	/**
	 * 按类型顺序消费时的分组键（Oracle 把空串当 NULL，不能用空串）
	 */
	public static final String WHOLE_TYPE = "*";

	private static final String LIST_COLUMNS = "id, msg_type, msg_id, seq, order_key, status, client_ip, attempts, next_time, owner, lock_time, "
			+ "error, error_node, receive_time, start_time, finish_time, cost_ms, cb_status, cb_attempts, cb_next_time, cb_error, cb_time";

	private static final String ALL_COLUMNS = LIST_COLUMNS + ", payload, meta, trace, result, cb_response";

	private final Supplier<JdbcTemplate> jdbcSupplier;

	private final MsgSchema schema;

	private final boolean initSchema;

	private final Clock clock;

	private final AtomicLong lastSeq = new AtomicLong();

	private volatile JdbcTemplate jdbc;

	private volatile DatabaseType databaseType;

	public JdbcMsgStore(Supplier<JdbcTemplate> jdbcSupplier, String tablePrefix, boolean initSchema) {
		this(jdbcSupplier, tablePrefix, initSchema, Clock.systemDefaultZone());
	}

	public JdbcMsgStore(Supplier<JdbcTemplate> jdbcSupplier, String tablePrefix, boolean initSchema, Clock clock) {
		this.jdbcSupplier = jdbcSupplier;
		this.schema = new MsgSchema(tablePrefix);
		this.initSchema = initSchema;
		this.clock = clock;
	}

	public MsgSchema schema() {
		return schema;
	}

	private String t() {
		return schema.table();
	}

	// ------------------------------------------------------------------ 接收

	/**
	 * 入库。同类型同消息 id 已存在时不插入，返回已有消息并标记 duplicate。
	 */
	public Received receive(FlowMessage message) {
		long now = clock.millis();
		message.setId(UUID.randomUUID().toString().replace("-", ""));
		message.setSeq(nextSeq(now));
		message.setStatus(PENDING);
		message.setReceiveTime(new Date(now));
		message.setNextTime(new Date(now));
		message.setAttempts(0);
		try {
			jdbc().update("INSERT INTO " + t() + " (id, msg_type, msg_id, seq, order_key, status, payload, meta, client_ip, attempts, next_time, receive_time) "
							+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
					message.getId(), message.getType(), message.getMsgId(), message.getSeq(), message.getOrderKey(), PENDING,
					message.getPayload(), message.getMeta(), truncate(message.getClientIp(), 64), ts(now), ts(now));
			return new Received(message, false);
		} catch (DataIntegrityViolationException e) {
			FlowMessage existing = findByMsgId(message.getType(), message.getMsgId());
			if (existing == null) {
				throw e;
			}
			return new Received(existing, true);
		}
	}

	/**
	 * 接收序号：毫秒时间 × 1000 + 序号，同一台服务内严格递增
	 */
	long nextSeq(long now) {
		long base = now * 1000;
		return lastSeq.updateAndGet(prev -> Math.max(prev + 1, base));
	}

	// ------------------------------------------------------------------ 领取与处理

	/**
	 * 不要求顺序的类型：到期的待处理消息 id，按接收顺序
	 */
	public List<String> due(String type, int limit) {
		return limited("SELECT id FROM " + t() + " WHERE status IN (?, ?) AND next_time <= ? AND msg_type = ? ORDER BY seq",
				(rs, i) -> rs.getString(1), limit, PENDING, RETRY, ts(clock.millis()), type);
	}

	/**
	 * 顺序消费：每个分组只取最前面（seq 最小）一条未完成消息，它到期且未被领取时才返回。
	 *
	 * @param blockOnDead 死信是否阻塞后面的消息
	 * @param window      最多查看的未完成消息条数
	 */
	public List<String> dueHeads(String type, boolean blockOnDead, int limit, int window) {
		long now = clock.millis();
		List<Object[]> rows = limited("SELECT id, order_key, status, next_time FROM " + t() + " WHERE msg_type = ? AND status IN (" + placeholders(blocking(blockOnDead)) + ") ORDER BY seq",
				(rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4)}, window,
				concat(type, blocking(blockOnDead)));
		Set<String> seen = new HashSet<>();
		List<String> result = new ArrayList<>();
		for (Object[] row : rows) {
			String key = row[1] == null ? WHOLE_TYPE : (String) row[1];
			if (!seen.add(key)) {
				continue;
			}
			String status = (String) row[2];
			Timestamp next = (Timestamp) row[3];
			if ((PENDING.equals(status) || RETRY.equals(status)) && (next == null || next.getTime() <= now)) {
				result.add((String) row[0]);
				if (result.size() >= limit) {
					break;
				}
			}
		}
		return result;
	}

	/**
	 * 领取一条消息（attempts + 1）。顺序消费时同组前面还有未完成消息则领取失败。
	 *
	 * @param ordered     是否顺序消费
	 * @param blockOnDead 顺序消费时死信是否阻塞
	 * @return 领取成功后的消息（含报文）；已被他人领取或不满足顺序时返回 null
	 */
	public FlowMessage claim(String id, String owner, boolean ordered, boolean blockOnDead) {
		Timestamp now = ts(clock.millis());
		int updated;
		if (ordered) {
			FlowMessage message = find(id);
			if (message == null) {
				return null;
			}
			List<String> blocking = blocking(blockOnDead);
			List<Object> args = new ArrayList<>(Arrays.asList(PROCESSING, owner, now, now, id, PENDING, RETRY, message.getType(),
					message.getOrderKey() == null ? WHOLE_TYPE : message.getOrderKey(), message.getSeq()));
			args.addAll(blocking);
			// 子查询包一层派生表：MySQL 不允许 UPDATE 的子查询直接引用目标表（别名避开 Oracle 保留字 PRIOR）
			updated = jdbc().update("UPDATE " + t() + " SET status = ?, owner = ?, lock_time = ?, start_time = ?, attempts = attempts + 1 "
					+ "WHERE id = ? AND status IN (?, ?) AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM " + t()
					+ " WHERE msg_type = ? AND order_key = ? AND seq < ? AND status IN (" + placeholders(blocking) + ")) earlier)", args.toArray());
		} else {
			updated = jdbc().update("UPDATE " + t() + " SET status = ?, owner = ?, lock_time = ?, start_time = ?, attempts = attempts + 1 "
					+ "WHERE id = ? AND status IN (?, ?)", PROCESSING, owner, now, now, id, PENDING, RETRY);
		}
		return updated == 1 ? find(id) : null;
	}

	/**
	 * 处理成功
	 *
	 * @param callback 需要回调时为 true，回调状态置为待回调
	 */
	public boolean success(String id, String owner, String result, long costMs, boolean callback) {
		long now = clock.millis();
		return jdbc().update("UPDATE " + t() + " SET status = ?, finish_time = ?, cost_ms = ?, result = ?, error = NULL, error_node = NULL, trace = NULL, "
						+ "owner = NULL, cb_status = ?, cb_attempts = 0, cb_next_time = ? WHERE id = ? AND owner = ? AND status = ?",
				SUCCESS, ts(now), costMs, truncate(result, 2000), callback ? CB_PENDING : null, callback ? ts(now) : null, id, owner, PROCESSING) == 1;
	}

	/**
	 * 处理失败：nextTime 为 null 时转死信
	 */
	public boolean fail(String id, String owner, String error, String errorNode, String trace, long costMs, Date nextTime, boolean callback) {
		long now = clock.millis();
		boolean dead = nextTime == null;
		return jdbc().update("UPDATE " + t() + " SET status = ?, next_time = ?, finish_time = ?, cost_ms = ?, error = ?, error_node = ?, trace = ?, owner = NULL, "
						+ "cb_status = ?, cb_attempts = 0, cb_next_time = ? WHERE id = ? AND owner = ? AND status = ?",
				dead ? DEAD : RETRY, dead ? null : new Timestamp(nextTime.getTime()), dead ? ts(now) : null, costMs, truncate(error, 1000),
				truncate(errorNode, 64), trace, dead && callback ? CB_PENDING : null, dead && callback ? ts(now) : null, id, owner, PROCESSING) == 1;
	}

	/**
	 * 处理中超过时限（服务宕机、线程卡死）的消息恢复为待重试
	 */
	public int recoverTimeout(long timeoutMillis) {
		long now = clock.millis();
		int count = jdbc().update("UPDATE " + t() + " SET status = ?, next_time = ?, owner = NULL, error = ? WHERE status = ? AND lock_time < ?",
				RETRY, ts(now), "处理超时（超过 " + timeoutMillis / 1000 + " 秒未完成），已恢复为待重试", PROCESSING, ts(now - timeoutMillis));
		int callbacks = jdbc().update("UPDATE " + t() + " SET cb_status = ? WHERE cb_status = ? AND cb_next_time < ?", CB_RETRY, CB_SENDING, ts(now));
		if (count > 0 || callbacks > 0) {
			logger.warn("消息中心：{} 条处理超时的消息、{} 条回调超时的消息已恢复为待重试", count, callbacks);
		}
		return count;
	}

	// ------------------------------------------------------------------ 回调

	public List<String> dueCallbacks(int limit) {
		return limited("SELECT id FROM " + t() + " WHERE cb_status IN (?, ?) AND cb_next_time <= ? ORDER BY cb_next_time",
				(rs, i) -> rs.getString(1), limit, CB_PENDING, CB_RETRY, ts(clock.millis()));
	}

	/**
	 * 领取一条待回调消息，cb_next_time 改为超时时间（超时未完成由 recoverTimeout 恢复）
	 */
	public FlowMessage claimCallback(String id, long timeoutMillis) {
		long now = clock.millis();
		int updated = jdbc().update("UPDATE " + t() + " SET cb_status = ?, cb_next_time = ?, cb_attempts = cb_attempts + 1 WHERE id = ? AND cb_status IN (?, ?)",
				CB_SENDING, ts(now + timeoutMillis), id, CB_PENDING, CB_RETRY);
		return updated == 1 ? find(id) : null;
	}

	/**
	 * @param nextTime 失败且还要重试时的下次时间；成功或不再重试时为 null
	 */
	public void callbackDone(String id, boolean success, String error, String response, Date nextTime) {
		long now = clock.millis();
		String status = success ? CB_SUCCESS : nextTime == null ? CB_FAILED : CB_RETRY;
		jdbc().update("UPDATE " + t() + " SET cb_status = ?, cb_next_time = ?, cb_error = ?, cb_response = ?, cb_time = ? WHERE id = ? AND cb_status = ?",
				status, nextTime == null ? null : new Timestamp(nextTime.getTime()), success ? null : truncate(error, 1000), truncate(response, 2000), ts(now), id, CB_SENDING);
	}

	// ------------------------------------------------------------------ 人工处理

	/**
	 * 死信、已忽略、待重试的消息立即重新处理，重试次数清零
	 */
	public int retry(Collection<String> ids) {
		long now = clock.millis();
		return batch(ids, id -> jdbc().update("UPDATE " + t() + " SET status = ?, attempts = 0, next_time = ?, finish_time = NULL WHERE id = ? AND status IN (?, ?, ?)",
				PENDING, ts(now), id, DEAD, IGNORED, RETRY));
	}

	/**
	 * 忽略未完成的消息（顺序消费时放行后面的消息）
	 */
	public int ignore(Collection<String> ids) {
		long now = clock.millis();
		return batch(ids, id -> jdbc().update("UPDATE " + t() + " SET status = ?, finish_time = ? WHERE id = ? AND status IN (?, ?, ?)",
				IGNORED, ts(now), id, PENDING, RETRY, DEAD));
	}

	/**
	 * 重新回调（已有回调记录的消息）
	 */
	public int resendCallback(Collection<String> ids) {
		long now = clock.millis();
		return batch(ids, id -> jdbc().update("UPDATE " + t() + " SET cb_status = ?, cb_attempts = 0, cb_next_time = ? WHERE id = ? AND cb_status IN (?, ?, ?)",
				CB_PENDING, ts(now), id, CB_SUCCESS, CB_FAILED, CB_RETRY));
	}

	// ------------------------------------------------------------------ 查询

	public FlowMessage find(String id) {
		List<FlowMessage> list = jdbc().query("SELECT " + ALL_COLUMNS + " FROM " + t() + " WHERE id = ?", this::map, id);
		return list.isEmpty() ? null : list.get(0);
	}

	public FlowMessage findByMsgId(String type, String msgId) {
		List<FlowMessage> list = jdbc().query("SELECT " + ALL_COLUMNS + " FROM " + t() + " WHERE msg_type = ? AND msg_id = ?", this::map, type, msgId);
		return list.isEmpty() ? null : list.get(0);
	}

	/**
	 * 分页查询（不含报文、轨迹），按接收顺序倒序
	 */
	public Page page(Query query) {
		StringBuilder where = new StringBuilder(" WHERE 1 = 1");
		List<Object> args = new ArrayList<>();
		if (query.type != null) {
			where.append(" AND msg_type = ?");
			args.add(query.type);
		}
		if (query.status != null) {
			where.append(" AND status = ?");
			args.add(query.status);
		}
		if (query.cbStatus != null) {
			where.append(" AND cb_status = ?");
			args.add(query.cbStatus);
		}
		if (query.msgId != null) {
			where.append(" AND msg_id = ?");
			args.add(query.msgId);
		}
		if (query.from != null) {
			where.append(" AND receive_time >= ?");
			args.add(new Timestamp(query.from.getTime()));
		}
		if (query.to != null) {
			where.append(" AND receive_time < ?");
			args.add(new Timestamp(query.to.getTime()));
		}
		Long total = jdbc().queryForObject("SELECT COUNT(1) FROM " + t() + where, Long.class, args.toArray());
		int offset = Math.max(0, query.offset);
		int size = query.size <= 0 ? 20 : Math.min(query.size, 500);
		// 用 setMaxRows 取到 offset + size 条再跳过，避免为每种数据库拼分页 SQL（面板只翻前几页）
		List<FlowMessage> rows = limited("SELECT " + LIST_COLUMNS + " FROM " + t() + where + " ORDER BY seq DESC", this::map, offset + size, args.toArray());
		return new Page(total == null ? 0 : total, rows.size() > offset ? new ArrayList<>(rows.subList(offset, rows.size())) : Collections.emptyList());
	}

	/**
	 * 各类型未完成消息的统计（只查未完成状态，走 (status, next_time) 索引）
	 */
	public Map<String, TypeStats> stats() {
		Map<String, TypeStats> result = new TreeMap<>();
		jdbc().query("SELECT msg_type, status, COUNT(1), MIN(receive_time) FROM " + t() + " WHERE status IN (?, ?, ?, ?) GROUP BY msg_type, status",
				(ResultSet rs) -> {
					TypeStats stats = result.computeIfAbsent(rs.getString(1), TypeStats::new);
					String status = rs.getString(2);
					long count = rs.getLong(3);
					Timestamp oldest = rs.getTimestamp(4);
					if (PENDING.equals(status) || RETRY.equals(status)) {
						stats.backlog += count;
						if (oldest != null && (stats.oldest == null || oldest.before(stats.oldest))) {
							stats.oldest = new Date(oldest.getTime());
						}
						if (RETRY.equals(status)) {
							stats.retrying += count;
						}
					} else if (PROCESSING.equals(status)) {
						stats.processing += count;
					} else {
						stats.dead += count;
					}
				}, PENDING, RETRY, PROCESSING, DEAD);
		jdbc().query("SELECT msg_type, COUNT(1) FROM " + t() + " WHERE cb_status = ? GROUP BY msg_type",
				(ResultSet rs) -> {
					result.computeIfAbsent(rs.getString(1), TypeStats::new).callbackFailed = rs.getLong(2);
				}, CB_FAILED);
		return result;
	}

	/**
	 * 删除早于 before 完成的成功、已忽略消息（回调未完成的保留），每次最多删 batchSize 条，返回删除条数
	 */
	public int cleanup(Date before, int batchSize) {
		List<String> ids = limited("SELECT id FROM " + t() + " WHERE status IN (?, ?) AND finish_time < ? AND (cb_status IS NULL OR cb_status IN (?, ?))",
				(rs, i) -> rs.getString(1), batchSize, SUCCESS, IGNORED, new Timestamp(before.getTime()), CB_SUCCESS, CB_FAILED);
		if (ids.isEmpty()) {
			return 0;
		}
		List<Object[]> args = new ArrayList<>();
		ids.forEach(id -> args.add(new Object[]{id}));
		jdbc().batchUpdate("DELETE FROM " + t() + " WHERE id = ?", args);
		return ids.size();
	}

	// ------------------------------------------------------------------ 内部

	/**
	 * 首次调用时解析数据源并建表
	 */
	public JdbcTemplate jdbc() {
		JdbcTemplate template = this.jdbc;
		if (template == null) {
			synchronized (this) {
				template = this.jdbc;
				if (template == null) {
					template = jdbcSupplier.get();
					if (template == null) {
						throw new IllegalStateException("消息中心找不到可用的数据源，请检查 magic-api.flow.msg.datasource 或 magic-api.upload.datasource 配置");
					}
					String productName = template.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
					this.databaseType = DatabaseType.fromProductName(productName);
					if (initSchema) {
						createTableIfAbsent(template);
					}
					this.jdbc = template;
				}
			}
		}
		return template;
	}

	private void createTableIfAbsent(JdbcTemplate template) {
		try {
			template.queryForObject("SELECT COUNT(1) FROM " + t() + " WHERE 1 = 0", Integer.class);
			return;
		} catch (DataAccessException ignored) {
			// 表不存在
		}
		if (databaseType == DatabaseType.UNSUPPORTED) {
			logger.warn("消息中心不支持在当前数据库自动建表，请手工创建表 {}（建表语句见插件文档）", t());
			return;
		}
		for (String sql : schema.ddl(databaseType)) {
			logger.info("消息中心建表: {}", sql);
			template.execute(sql);
		}
	}

	private <T> List<T> limited(String sql, RowMapper<T> mapper, int limit, Object... args) {
		JdbcTemplate limited = new JdbcTemplate(jdbc().getDataSource());
		limited.setMaxRows(limit > 0 ? limit : -1);
		return limited.query(sql, mapper, args);
	}

	private static List<String> blocking(boolean blockOnDead) {
		return blockOnDead ? Arrays.asList(PENDING, RETRY, PROCESSING, DEAD) : Arrays.asList(PENDING, RETRY, PROCESSING);
	}

	private static String placeholders(List<?> values) {
		return String.join(", ", Collections.nCopies(values.size(), "?"));
	}

	private static Object[] concat(Object first, List<?> rest) {
		List<Object> args = new ArrayList<>();
		args.add(first);
		args.addAll(rest);
		return args.toArray();
	}

	private static int batch(Collection<String> ids, java.util.function.ToIntFunction<String> action) {
		int count = 0;
		if (ids != null) {
			for (String id : new LinkedHashSet<>(ids)) {
				count += action.applyAsInt(id);
			}
		}
		return count;
	}

	private FlowMessage map(ResultSet rs, int row) throws SQLException {
		FlowMessage m = new FlowMessage();
		m.setId(rs.getString("id"));
		m.setType(rs.getString("msg_type"));
		m.setMsgId(rs.getString("msg_id"));
		m.setSeq(rs.getLong("seq"));
		m.setOrderKey(rs.getString("order_key"));
		m.setStatus(rs.getString("status"));
		m.setClientIp(rs.getString("client_ip"));
		m.setAttempts(rs.getInt("attempts"));
		m.setNextTime(date(rs.getTimestamp("next_time")));
		m.setOwner(rs.getString("owner"));
		m.setLockTime(date(rs.getTimestamp("lock_time")));
		m.setError(rs.getString("error"));
		m.setErrorNode(rs.getString("error_node"));
		m.setReceiveTime(date(rs.getTimestamp("receive_time")));
		m.setStartTime(date(rs.getTimestamp("start_time")));
		m.setFinishTime(date(rs.getTimestamp("finish_time")));
		m.setCostMs(rs.getLong("cost_ms"));
		m.setCbStatus(rs.getString("cb_status"));
		m.setCbAttempts(rs.getInt("cb_attempts"));
		m.setCbNextTime(date(rs.getTimestamp("cb_next_time")));
		m.setCbError(rs.getString("cb_error"));
		m.setCbTime(date(rs.getTimestamp("cb_time")));
		if (hasColumn(rs, "payload")) {
			m.setPayload(text(rs, "payload"));
			m.setMeta(text(rs, "meta"));
			m.setTrace(text(rs, "trace"));
			m.setResult(rs.getString("result"));
			m.setCbResponse(rs.getString("cb_response"));
		}
		return m;
	}

	private static boolean hasColumn(ResultSet rs, String column) throws SQLException {
		int count = rs.getMetaData().getColumnCount();
		for (int i = 1; i <= count; i++) {
			if (column.equalsIgnoreCase(rs.getMetaData().getColumnLabel(i))) {
				return true;
			}
		}
		return false;
	}

	private static String text(ResultSet rs, String column) throws SQLException {
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

	private static Date date(Timestamp timestamp) {
		return timestamp == null ? null : new Date(timestamp.getTime());
	}

	private static Timestamp ts(long millis) {
		return new Timestamp(millis);
	}

	static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}

	/**
	 * 入库结果
	 */
	public static class Received {

		private final FlowMessage message;

		private final boolean duplicate;

		Received(FlowMessage message, boolean duplicate) {
			this.message = message;
			this.duplicate = duplicate;
		}

		public FlowMessage getMessage() {
			return message;
		}

		public boolean isDuplicate() {
			return duplicate;
		}
	}

	/**
	 * 列表查询条件，空字段不过滤
	 */
	public static class Query {

		public String type;

		public String status;

		public String cbStatus;

		public String msgId;

		public Date from;

		public Date to;

		public int offset;

		public int size = 20;
	}

	public static class Page {

		private final long total;

		private final List<FlowMessage> rows;

		Page(long total, List<FlowMessage> rows) {
			this.total = total;
			this.rows = rows;
		}

		public long getTotal() {
			return total;
		}

		public List<FlowMessage> getRows() {
			return rows;
		}
	}

	/**
	 * 某类型的未完成消息统计
	 */
	public static class TypeStats {

		private final String type;

		long backlog;

		long retrying;

		long processing;

		long dead;

		long callbackFailed;

		Date oldest;

		TypeStats(String type) {
			this.type = type;
		}

		public String getType() {
			return type;
		}

		/**
		 * 待处理 + 待重试
		 */
		public long getBacklog() {
			return backlog;
		}

		public long getRetrying() {
			return retrying;
		}

		public long getProcessing() {
			return processing;
		}

		public long getDead() {
			return dead;
		}

		public long getCallbackFailed() {
			return callbackFailed;
		}

		/**
		 * 最早一条待处理消息的接收时间
		 */
		public Date getOldest() {
			return oldest;
		}
	}
}
