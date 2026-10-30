package org.ssssssss.magicapi.flow.proc;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 一次存储过程调用的描述：表单模式（过程名 + 参数表，自动生成按名传参的调用）或手写语句模式（语句中用 :name 引用参数）。
 * <p>
 * 过程名、参数名只允许标识符，所有值都通过 ? 绑定。
 */
public class ProcSpec {

	public static final String[] MODES = {"form", "sql"};

	/**
	 * 函数返回值在结果中的键
	 */
	public static final String RETURN = "return";

	private static final String IDENTIFIER = "[A-Za-z][A-Za-z0-9_$#]*";

	private static final Pattern NAME = Pattern.compile(IDENTIFIER + "(\\." + IDENTIFIER + "){0,2}");

	private static final Pattern PARAM_NAME = Pattern.compile(IDENTIFIER);

	private String datasource;

	private String mode = "form";

	private String name;

	private String sql;

	private List<ProcParam> params = new ArrayList<>();

	/**
	 * 函数返回值类型；为空表示过程
	 */
	private String returnType;

	private String returnParse;

	/**
	 * 游标列名：lower（默认） / camel / upper / raw
	 */
	private String columnCase = "lower";

	/**
	 * 超时秒数，0 不限制
	 */
	private int timeout;

	public String getDatasource() {
		return datasource;
	}

	public ProcSpec setDatasource(String datasource) {
		this.datasource = datasource;
		return this;
	}

	public String getMode() {
		return mode;
	}

	public ProcSpec setMode(String mode) {
		this.mode = mode == null || mode.trim().isEmpty() ? "form" : mode.trim().toLowerCase(Locale.ROOT);
		return this;
	}

	public String getName() {
		return name;
	}

	public ProcSpec setName(String name) {
		this.name = name == null ? null : name.trim();
		return this;
	}

	public String getSql() {
		return sql;
	}

	public ProcSpec setSql(String sql) {
		this.sql = sql;
		return this;
	}

	public List<ProcParam> getParams() {
		return params;
	}

	public ProcSpec setParams(List<ProcParam> params) {
		this.params = params == null ? new ArrayList<>() : new ArrayList<>(params);
		return this;
	}

	public ProcSpec param(ProcParam param) {
		this.params.add(param);
		return this;
	}

	public String getReturnType() {
		return returnType;
	}

	public ProcSpec setReturnType(String returnType) {
		this.returnType = returnType == null || returnType.trim().isEmpty() ? null : returnType.trim().toLowerCase(Locale.ROOT);
		return this;
	}

	public String getReturnParse() {
		return returnParse;
	}

	public ProcSpec setReturnParse(String returnParse) {
		this.returnParse = returnParse;
		return this;
	}

	public String getColumnCase() {
		return columnCase;
	}

	public ProcSpec setColumnCase(String columnCase) {
		this.columnCase = columnCase == null || columnCase.trim().isEmpty() ? "lower" : columnCase.trim().toLowerCase(Locale.ROOT);
		return this;
	}

	public int getTimeout() {
		return timeout;
	}

	public ProcSpec setTimeout(int timeout) {
		this.timeout = Math.max(0, timeout);
		return this;
	}

	/**
	 * 参数行的取值方式：流程节点中 value 是表达式，脚本中 value 是值
	 */
	public interface Values {

		boolean has(Map<String, Object> row);

		Object get(Map<String, Object> row);
	}

	/**
	 * 脚本调用：参数行有 value 键就传值（包括 null）
	 */
	public static final Values LITERAL = new Values() {
		@Override
		public boolean has(Map<String, Object> row) {
			return row.containsKey("value");
		}

		@Override
		public Object get(Map<String, Object> row) {
			return row.get("value");
		}
	};

	/**
	 * 从配置构造：datasource、mode、name、sql、params[{name, mode, type, value, parse}]、returnType、returnParse、columnCase、timeout
	 */
	@SuppressWarnings("unchecked")
	public static ProcSpec from(Map<String, Object> config, Values values) {
		ProcSpec spec = new ProcSpec()
				.setDatasource(text(config.get("datasource")))
				.setMode(text(config.get("mode")))
				.setName(text(config.get("name")))
				.setSql(text(config.get("sql")))
				.setReturnType(text(config.get("returnType")))
				.setReturnParse(text(config.get("returnParse")))
				.setColumnCase(text(config.get("columnCase")));
		Object timeout = config.get("timeout");
		if (timeout != null && !timeout.toString().trim().isEmpty()) {
			try {
				spec.setTimeout(timeout instanceof Number ? ((Number) timeout).intValue() : Integer.parseInt(timeout.toString().trim()));
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("超时必须是整数秒");
			}
		}
		Object params = config.get("params");
		if (params != null && !"".equals(params)) {
			if (!(params instanceof Collection)) {
				throw new IllegalArgumentException("params 必须是列表");
			}
			for (Object item : (Collection<?>) params) {
				if (!(item instanceof Map)) {
					throw new IllegalArgumentException("params 的每一项必须是对象");
				}
				Map<String, Object> row = (Map<String, Object>) item;
				String paramName = text(row.get("name"));
				if (paramName == null) {
					continue;
				}
				String mode = text(row.get("mode"));
				boolean input = mode == null || !"out".equalsIgnoreCase(mode);
				boolean has = input && values.has(row);
				spec.param(new ProcParam(paramName, mode, text(row.get("type")), has ? values.get(row) : null, has, text(row.get("parse"))));
			}
		}
		return spec;
	}

	private static String text(Object value) {
		if (value == null) {
			return null;
		}
		String s = value.toString().trim();
		return s.isEmpty() ? null : s;
	}

	/**
	 * 检查配置，生成可执行语句与绑定顺序
	 */
	public ProcStatement build() {
		if (!Arrays.asList(MODES).contains(mode)) {
			throw new IllegalArgumentException("调用方式只能是 form（表单）或 sql（手写语句）");
		}
		if (!Arrays.asList("lower", "camel", "upper", "raw").contains(columnCase)) {
			throw new IllegalArgumentException("游标列名只能是 lower / camel / upper / raw");
		}
		Map<String, ProcParam> byName = new LinkedHashMap<>();
		for (ProcParam p : params) {
			check(p);
			if (byName.put(p.getName().toUpperCase(Locale.ROOT), p) != null) {
				throw new IllegalArgumentException("参数 " + p.getName() + " 重复");
			}
		}
		return "sql".equals(mode) ? buildSql(byName) : buildForm();
	}

	private ProcStatement buildForm() {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("请填写过程名，如 pkg_name.proc_name");
		}
		if (!NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("过程名 " + name + " 不合法，只能是 过程、包.过程 或 用户.包.过程");
		}
		List<ProcParam> bindings = new ArrayList<>();
		StringBuilder sb = new StringBuilder("begin ");
		if (returnType != null) {
			ProcParam ret = new ProcParam(RETURN, "out", returnType, null, false, returnParse);
			check(ret);
			bindings.add(ret);
			sb.append("? := ");
		}
		sb.append(name).append('(');
		boolean first = true;
		for (ProcParam p : params) {
			if (!p.isPassed()) {
				continue;
			}
			if (!first) {
				sb.append(", ");
			}
			first = false;
			sb.append(p.getName()).append(" => ?");
			bindings.add(p);
		}
		sb.append("); end;");
		return new ProcStatement(sb.toString(), bindings);
	}

	private ProcStatement buildSql(Map<String, ProcParam> byName) {
		if (sql == null || sql.trim().isEmpty()) {
			throw new IllegalArgumentException("请填写调用语句，如 begin pkg.proc(:a, :b); end;");
		}
		List<ProcParam> bindings = new ArrayList<>();
		String text = sql.trim();
		StringBuilder sb = new StringBuilder();
		int i = 0;
		int n = text.length();
		while (i < n) {
			char c = text.charAt(i);
			if (c == '\'' || c == '"') {
				int end = skipQuoted(text, i, c);
				sb.append(text, i, end);
				i = end;
			} else if (c == '-' && i + 1 < n && text.charAt(i + 1) == '-') {
				int end = text.indexOf('\n', i);
				end = end < 0 ? n : end;
				sb.append(text, i, end);
				i = end;
			} else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
				int end = text.indexOf("*/", i + 2);
				end = end < 0 ? n : end + 2;
				sb.append(text, i, end);
				i = end;
			} else if (c == ':' && i + 1 < n && Character.isLetter(text.charAt(i + 1))) {
				int end = i + 1;
				while (end < n && isIdentifierPart(text.charAt(end))) {
					end++;
				}
				String paramName = text.substring(i + 1, end);
				ProcParam p = byName.get(paramName.toUpperCase(Locale.ROOT));
				if (p == null) {
					throw new IllegalArgumentException("语句中的 :" + paramName + " 没有在参数表中定义");
				}
				bindings.add(p);
				sb.append('?');
				i = end;
			} else {
				sb.append(c);
				i++;
			}
		}
		// 匿名块需要以分号结束，{call ...} 不需要
		String result = sb.toString();
		if (result.regionMatches(true, 0, "begin", 0, 5) || result.regionMatches(true, 0, "declare", 0, 7)) {
			if (!result.endsWith(";")) {
				result = result + ";";
			}
		}
		return new ProcStatement(result, bindings);
	}

	private static int skipQuoted(String text, int start, char quote) {
		int i = start + 1;
		while (i < text.length()) {
			if (text.charAt(i) == quote) {
				if (quote == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
					i += 2;
					continue;
				}
				return i + 1;
			}
			i++;
		}
		return text.length();
	}

	private static boolean isIdentifierPart(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
	}

	private static void check(ProcParam p) {
		if (p.getName() == null || !PARAM_NAME.matcher(p.getName()).matches()) {
			throw new IllegalArgumentException("参数名 " + p.getName() + " 不合法，只能是字母开头的字母、数字、下划线");
		}
		if (!Arrays.asList(ProcParam.MODES).contains(p.getMode())) {
			throw new IllegalArgumentException("参数 " + p.getName() + " 的方向只能是 in / out / inout");
		}
		if (!Arrays.asList(ProcParam.TYPES).contains(p.getType())) {
			throw new IllegalArgumentException("参数 " + p.getName() + " 的类型只能是 " + String.join(" / ", ProcParam.TYPES));
		}
		if (!Arrays.asList(ProcParam.PARSES).contains(p.getParse())) {
			throw new IllegalArgumentException("参数 " + p.getName() + " 的处理方式只能是 string / json / xml");
		}
		if ("cursor".equals(p.getType()) && !"out".equals(p.getMode())) {
			throw new IllegalArgumentException("参数 " + p.getName() + " 是游标，只能作为出参");
		}
	}

	/**
	 * 生成的语句与 ? 对应的参数（同一参数可出现多次）
	 */
	public static class ProcStatement {

		private final String sql;

		private final List<ProcParam> bindings;

		ProcStatement(String sql, List<ProcParam> bindings) {
			this.sql = sql;
			this.bindings = Collections.unmodifiableList(bindings);
		}

		public String getSql() {
			return sql;
		}

		public List<ProcParam> getBindings() {
			return bindings;
		}
	}
}
