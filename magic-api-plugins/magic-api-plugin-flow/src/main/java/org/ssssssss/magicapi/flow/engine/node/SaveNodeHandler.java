package org.ssssssss.magicapi.flow.engine.node;

import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.flow.engine.FlowNodeHandler;
import org.ssssssss.magicapi.modules.db.SQLModule;
import org.ssssssss.magicapi.modules.db.Transaction;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 写入表：按字段把数据写入目标表，不用写 SQL，用于表对表同步、回写中间表。
 * <pre>
 * config:
 *   datasource   目标数据源 key，空为主数据源
 *   table        目标表名，可带模式名，如 his.t_settle
 *   rows         表达式，列表或单个对象，默认 prev
 *   mode         insert / update / upsert（默认：按主键先更新，没有再插入）/ replace（按主键先删除再插入）
 *   keys         主键字段，逗号分隔（update / upsert / replace 必填）
 *   columns      只写这些字段，逗号分隔；为空时写行中的全部字段
 *   exclude      不写这些字段，逗号分隔
 *   transaction  整批在一个事务中，默认 true
 * </pre>
 * 字段名取自行的键（先经字段映射节点改成目标表字段名），只允许字母、数字、下划线。
 * 输出：{inserted, updated, deleted, total}
 */
public class SaveNodeHandler implements FlowNodeHandler {

	public static final String TYPE = "save";

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");

	private static final Pattern TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*(\\.[A-Za-z_][A-Za-z0-9_$#]*)?");

	private static final String[] MODES = {"insert", "update", "upsert", "replace"};

	private final SQLModule sqlModule;

	public SaveNodeHandler(SQLModule sqlModule) {
		this.sqlModule = sqlModule;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void check(FlowNodeContext node) {
		String table = node.requireString("table");
		if (!table.contains("${var.") && !TABLE.matcher(table).matches()) {
			throw node.invalid("table", "只能是表名或 模式名.表名");
		}
		String mode = node.oneOf("mode", "upsert", MODES);
		if (!"insert".equals(mode)) {
			node.requireString("keys");
		}
		for (String key : new String[]{"keys", "columns", "exclude"}) {
			for (String column : list(node.string(key))) {
				if (!IDENTIFIER.matcher(column).matches()) {
					throw node.invalid(key, "字段名不合法：" + column);
				}
			}
		}
	}

	@Override
	public Object execute(FlowNodeContext node) {
		if (sqlModule == null) {
			throw new IllegalStateException("没有可用的 db 模块，请确认已配置数据源");
		}
		String table = node.requireString("table");
		if (!TABLE.matcher(table).matches()) {
			throw node.invalid("table", "只能是表名或 模式名.表名：" + table);
		}
		String mode = node.oneOf("mode", "upsert", MODES);
		List<String> keys = list(node.string("keys"));
		List<String> only = list(node.string("columns"));
		Set<String> exclude = new HashSet<>(lower(list(node.string("exclude"))));
		List<Object> rows = node.asList("rows", node.evaluateConfig("rows", "prev"));
		SQLModule module = sqlModule.getDynamicAttribute(node.string("datasource"));
		JdbcTemplate jdbc = module.getJdbcTemplate();
		Transaction transaction = node.bool("transaction", true) && !rows.isEmpty() ? module.transaction() : null;
		int inserted = 0;
		int updated = 0;
		int deleted = 0;
		int index = 0;
		try {
			for (Object item : rows) {
				index++;
				if (!(item instanceof Map)) {
					throw node.invalid("rows", "第 " + index + " 行不是对象");
				}
				Map<String, Object> row = columns((Map<?, ?>) item, only, exclude);
				for (String column : row.keySet()) {
					if (!IDENTIFIER.matcher(column).matches()) {
						throw node.invalid("rows", "第 " + index + " 行的字段名不合法：" + column + "，请先用字段映射改为目标表字段名");
					}
				}
				try {
					switch (mode) {
						case "insert":
							inserted += insert(jdbc, table, row);
							break;
						case "update":
							updated += update(jdbc, table, row, keys);
							break;
						case "replace":
							deleted += delete(jdbc, table, row, keys);
							inserted += insert(jdbc, table, row);
							break;
						default: {
							int count = update(jdbc, table, row, keys);
							if (count > 0) {
								updated += count;
							} else {
								inserted += insert(jdbc, table, row);
							}
						}
					}
				} catch (RuntimeException e) {
					throw new IllegalStateException("第 " + index + " 行写入 " + table + " 失败", e);
				}
			}
			if (transaction != null) {
				transaction.commit();
			}
		} catch (RuntimeException e) {
			if (transaction != null) {
				transaction.rollback();
			}
			throw e;
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("inserted", inserted);
		result.put("updated", updated);
		result.put("deleted", deleted);
		result.put("total", rows.size());
		return result;
	}

	private static Map<String, Object> columns(Map<?, ?> row, List<String> only, Set<String> exclude) {
		Map<String, Object> result = new LinkedHashMap<>();
		if (only.isEmpty()) {
			row.forEach((k, v) -> {
				String column = String.valueOf(k);
				if (!exclude.contains(column.toLowerCase(Locale.ROOT))) {
					result.put(column, v);
				}
			});
		} else {
			Map<String, Object> lower = new HashMap<>();
			row.forEach((k, v) -> lower.put(String.valueOf(k).toLowerCase(Locale.ROOT), v));
			for (String column : only) {
				if (!exclude.contains(column.toLowerCase(Locale.ROOT))) {
					result.put(column, lower.get(column.toLowerCase(Locale.ROOT)));
				}
			}
		}
		return result;
	}

	private static int insert(JdbcTemplate jdbc, String table, Map<String, Object> row) {
		if (row.isEmpty()) {
			return 0;
		}
		String columns = String.join(", ", row.keySet());
		String marks = StringUtils.repeat("?", ", ", row.size());
		return jdbc.update("INSERT INTO " + table + " (" + columns + ") VALUES (" + marks + ")", values(row.values()));
	}

	private static int update(JdbcTemplate jdbc, String table, Map<String, Object> row, List<String> keys) {
		List<Object> args = new ArrayList<>();
		StringBuilder set = new StringBuilder();
		Set<String> keySet = new HashSet<>(lower(keys));
		for (Map.Entry<String, Object> entry : row.entrySet()) {
			if (keySet.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
				continue;
			}
			if (set.length() > 0) {
				set.append(", ");
			}
			set.append(entry.getKey()).append(" = ?");
			args.add(entry.getValue());
		}
		if (set.length() == 0) {
			// 只有主键：存在即视为已更新
			return count(jdbc, table, row, keys) > 0 ? 1 : 0;
		}
		String where = where(row, keys, args);
		return jdbc.update("UPDATE " + table + " SET " + set + " WHERE " + where, values(args));
	}

	private static int delete(JdbcTemplate jdbc, String table, Map<String, Object> row, List<String> keys) {
		List<Object> args = new ArrayList<>();
		String where = where(row, keys, args);
		return jdbc.update("DELETE FROM " + table + " WHERE " + where, values(args));
	}

	private static int count(JdbcTemplate jdbc, String table, Map<String, Object> row, List<String> keys) {
		List<Object> args = new ArrayList<>();
		String where = where(row, keys, args);
		Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM " + table + " WHERE " + where, Integer.class, values(args));
		return count == null ? 0 : count;
	}

	private static String where(Map<String, Object> row, List<String> keys, List<Object> args) {
		Map<String, Object> lower = new HashMap<>();
		Map<String, String> names = new HashMap<>();
		row.forEach((k, v) -> {
			lower.put(k.toLowerCase(Locale.ROOT), v);
			names.put(k.toLowerCase(Locale.ROOT), k);
		});
		StringBuilder where = new StringBuilder();
		for (String key : keys) {
			String lk = key.toLowerCase(Locale.ROOT);
			if (!lower.containsKey(lk)) {
				throw new IllegalArgumentException("数据中没有主键字段 " + key);
			}
			Object value = lower.get(lk);
			if (value == null) {
				throw new IllegalArgumentException("主键字段 " + key + " 的值为空");
			}
			if (where.length() > 0) {
				where.append(" AND ");
			}
			where.append(names.get(lk)).append(" = ?");
			args.add(value);
		}
		return where.toString();
	}

	private static Object[] values(Collection<Object> values) {
		Object[] result = new Object[values.size()];
		int i = 0;
		for (Object value : values) {
			result[i++] = value instanceof Map || value instanceof Collection ? org.ssssssss.magicapi.flow.support.FlowJson.stringify(value) : value;
		}
		return result;
	}

	private static List<String> list(String text) {
		List<String> result = new ArrayList<>();
		if (text != null) {
			for (String item : text.split(",")) {
				if (!item.trim().isEmpty()) {
					result.add(item.trim());
				}
			}
		}
		return result;
	}

	private static List<String> lower(List<String> items) {
		List<String> result = new ArrayList<>();
		for (String item : items) {
			result.add(item.toLowerCase(Locale.ROOT));
		}
		return result;
	}
}
