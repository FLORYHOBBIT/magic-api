package org.ssssssss.magicapi.flow.proc;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 读取 Oracle 存储过程的参数定义（DBMS_UTILITY.NAME_RESOLVE 解析同义词与 用户.包.过程，再查 ALL_ARGUMENTS），
 * 用于编辑器「读取参数」和脚本简写调用时推断出参。结果按 数据源+过程名 缓存 5 分钟。
 */
public class ProcMetadata {

	private static final long TTL = 5 * 60 * 1000L;

	private static final String ARGUMENTS_SQL = "select overload, position, argument_name, in_out, data_type, pls_type, defaulted from all_arguments"
			+ " where owner = ? and object_name = ? and nvl(package_name, '-') = ? and data_level = 0"
			+ " order by to_number(nvl(overload, '0')), position";

	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	/**
	 * 过程的全部重载，每个重载为参数列表（函数返回值 position = 0）
	 */
	public List<List<Argument>> overloads(JdbcTemplate jdbcTemplate, String cacheKey, String name) {
		if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_$#]*(\\.[A-Za-z][A-Za-z0-9_$#]*){0,2}")) {
			throw new IllegalArgumentException("过程名 " + name + " 不合法，只能是 过程、包.过程 或 用户.包.过程");
		}
		String key = (cacheKey == null ? "" : cacheKey) + "|" + name.toUpperCase(Locale.ROOT);
		Cached cached = cache.get(key);
		if (cached != null && System.currentTimeMillis() - cached.time < TTL) {
			return cached.overloads;
		}
		List<List<Argument>> overloads;
		try {
			overloads = jdbcTemplate.execute((ConnectionCallback<List<List<Argument>>>) con -> load(con, name));
		} catch (DataAccessException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			throw new ProcException("读取过程 " + name + " 的参数失败：" + String.valueOf(cause.getMessage()).trim(), e);
		}
		cache.put(key, new Cached(overloads));
		return overloads;
	}

	public void evict() {
		cache.clear();
	}

	static List<List<Argument>> load(Connection con, String name) throws SQLException {
		String owner;
		String packageName;
		String objectName;
		try (CallableStatement cs = con.prepareCall("begin dbms_utility.name_resolve(?, 1, ?, ?, ?, ?, ?, ?); end;")) {
			cs.setString(1, name);
			cs.registerOutParameter(2, Types.VARCHAR);
			cs.registerOutParameter(3, Types.VARCHAR);
			cs.registerOutParameter(4, Types.VARCHAR);
			cs.registerOutParameter(5, Types.VARCHAR);
			cs.registerOutParameter(6, Types.NUMERIC);
			cs.registerOutParameter(7, Types.NUMERIC);
			cs.execute();
			owner = cs.getString(2);
			String part1 = cs.getString(3);
			String part2 = cs.getString(4);
			int part1Type = cs.getInt(6);
			// 9 = 包；其余（7 过程、8 函数）为独立过程，名称可能在 part1 或 part2
			if (part1Type == 9) {
				packageName = part1;
				objectName = part2;
			} else {
				packageName = null;
				objectName = part2 != null ? part2 : part1;
			}
		} catch (SQLException e) {
			if (e.getErrorCode() == 6564 || e.getErrorCode() == 6550) {
				throw new ProcException("找不到过程 " + name + "，或当前用户没有执行权限");
			}
			throw e;
		}
		if (objectName == null) {
			throw new ProcException(name + " 是包，请写成 包名.过程名");
		}
		Map<String, List<Argument>> overloads = new LinkedHashMap<>();
		try (PreparedStatement ps = con.prepareStatement(ARGUMENTS_SQL)) {
			ps.setString(1, owner);
			ps.setString(2, objectName);
			ps.setString(3, packageName == null ? "-" : packageName);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String overload = rs.getString(1) == null ? "0" : rs.getString(1);
					List<Argument> args = overloads.computeIfAbsent(overload, k -> new ArrayList<>());
					int position = rs.getInt(2);
					String argName = rs.getString(3);
					String dataType = rs.getString(5);
					// 无参过程在 ALL_ARGUMENTS 中有一行空记录
					if (dataType == null || (argName == null && position > 0)) {
						continue;
					}
					args.add(new Argument(position, argName == null ? ProcSpec.RETURN : argName.toLowerCase(Locale.ROOT),
							mode(rs.getString(4)), dataType, rs.getString(6), "Y".equals(rs.getString(7))));
				}
			}
		}
		if (overloads.isEmpty()) {
			throw new ProcException("找不到过程 " + name + " 的参数定义（" + owner + "." + (packageName == null ? "" : packageName + ".") + objectName + "）");
		}
		return new ArrayList<>(overloads.values());
	}

	private static String mode(String inOut) {
		if ("OUT".equals(inOut)) {
			return "out";
		}
		return "IN/OUT".equals(inOut) ? "inout" : "in";
	}

	/**
	 * Oracle 类型 → 本插件的类型；不支持（BOOLEAN、记录、集合、对象、RAW）返回 null
	 */
	static String typeOf(String dataType, String plsType) {
		String t = dataType == null ? "" : dataType.toUpperCase(Locale.ROOT);
		String pls = plsType == null ? "" : plsType.toUpperCase(Locale.ROOT);
		if (t.contains("INTEGER") || pls.equals("INTEGER") || pls.equals("PLS_INTEGER") || pls.equals("BINARY_INTEGER") || pls.equals("NATURAL") || pls.equals("POSITIVE")) {
			return "integer";
		}
		switch (t) {
			case "VARCHAR2":
			case "VARCHAR":
			case "CHAR":
			case "NVARCHAR2":
			case "NCHAR":
			case "LONG":
			case "ROWID":
			case "UROWID":
				return "varchar";
			case "NUMBER":
			case "FLOAT":
			case "BINARY_FLOAT":
			case "BINARY_DOUBLE":
				return "number";
			case "DATE":
				return "date";
			case "CLOB":
			case "NCLOB":
				return "clob";
			case "BLOB":
				return "blob";
			case "REF CURSOR":
				return "cursor";
			default:
				return t.startsWith("TIMESTAMP") ? "timestamp" : null;
		}
	}

	/**
	 * 按入参推断调用：给了的入参传值；没给的入参有默认值就不传，否则传 null；出参全部注册。
	 *
	 * @param inputs 入参（参数名不区分大小写）
	 * @param parses 出参处理方式，如 {rb_out_parm: 'xml'}；未指定为 string
	 */
	public ProcSpec infer(JdbcTemplate jdbcTemplate, String cacheKey, String name, Map<String, ?> inputs, Map<String, String> parses) {
		Map<String, Object> given = new LinkedHashMap<>();
		if (inputs != null) {
			inputs.forEach((k, v) -> given.put(k.toUpperCase(Locale.ROOT), v));
		}
		Map<String, String> parseOf = new HashMap<>();
		if (parses != null) {
			parses.forEach((k, v) -> parseOf.put(k.toUpperCase(Locale.ROOT), v));
		}
		List<List<Argument>> overloads = overloads(jdbcTemplate, cacheKey, name);
		List<Argument> chosen = choose(overloads, given.keySet());
		if (chosen == null) {
			Set<String> known = new LinkedHashSet<>();
			overloads.get(0).forEach(a -> known.add(a.getName().toUpperCase(Locale.ROOT)));
			List<String> unknown = new ArrayList<>(given.keySet());
			unknown.removeAll(known);
			throw new ProcException("过程 " + name + " 没有参数 " + String.join("、", unknown).toLowerCase(Locale.ROOT)
					+ "，现有参数：" + String.join("、", known).toLowerCase(Locale.ROOT));
		}
		ProcSpec spec = new ProcSpec().setName(name);
		for (Argument a : chosen) {
			String upper = a.getName().toUpperCase(Locale.ROOT);
			if (a.getPosition() == 0) {
				if (a.getType() == null) {
					throw new ProcException("函数 " + name + " 的返回类型 " + a.getDataType() + " 不支持，请改用包装过程");
				}
				spec.setReturnType(a.getType()).setReturnParse(parseOf.get(ProcSpec.RETURN.toUpperCase(Locale.ROOT)));
				continue;
			}
			boolean has = given.containsKey(upper);
			if ("in".equals(a.getMode()) && !has && a.isDefaulted()) {
				continue;
			}
			if (a.getType() == null) {
				throw new ProcException("过程 " + name + " 的参数 " + a.getName() + " 类型 " + a.getDataType() + " 不支持，请改用包装过程或手写语句");
			}
			if ("out".equals(a.getMode())) {
				spec.param(ProcParam.out(a.getName(), a.getType(), parseOf.get(upper)));
			} else {
				spec.param(new ProcParam(a.getName(), a.getMode(), a.getType(), given.get(upper), true, parseOf.get(upper)));
			}
		}
		return spec;
	}

	private static List<Argument> choose(List<List<Argument>> overloads, Set<String> given) {
		for (List<Argument> args : overloads) {
			Set<String> inputs = new HashSet<>();
			args.forEach(a -> {
				if (a.getPosition() > 0 && !"out".equals(a.getMode())) {
					inputs.add(a.getName().toUpperCase(Locale.ROOT));
				}
			});
			if (inputs.containsAll(given)) {
				return args;
			}
		}
		return null;
	}

	private static class Cached {

		final long time = System.currentTimeMillis();

		final List<List<Argument>> overloads;

		Cached(List<List<Argument>> overloads) {
			this.overloads = Collections.unmodifiableList(overloads);
		}
	}

	/**
	 * 一个参数的定义
	 */
	public static class Argument {

		private final int position;

		private final String name;

		private final String mode;

		private final String dataType;

		private final String type;

		private final boolean defaulted;

		public Argument(int position, String name, String mode, String dataType, String plsType, boolean defaulted) {
			this.position = position;
			this.name = name;
			this.mode = mode;
			this.dataType = dataType;
			this.type = typeOf(dataType, plsType);
			this.defaulted = defaulted;
		}

		public int getPosition() {
			return position;
		}

		public String getName() {
			return name;
		}

		public String getMode() {
			return mode;
		}

		public String getDataType() {
			return dataType;
		}

		/**
		 * 本插件的类型，不支持时为 null
		 */
		public String getType() {
			return type;
		}

		public boolean isDefaulted() {
			return defaulted;
		}

		public Map<String, Object> toMap() {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("position", position);
			map.put("name", name);
			map.put("mode", mode);
			map.put("type", type);
			map.put("dataType", dataType);
			map.put("defaulted", defaulted);
			return map;
		}
	}
}
