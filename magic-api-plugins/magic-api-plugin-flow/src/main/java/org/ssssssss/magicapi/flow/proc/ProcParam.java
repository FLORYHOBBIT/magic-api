package org.ssssssss.magicapi.flow.proc;

import java.sql.Types;
import java.util.Locale;

/**
 * 存储过程的一个参数。
 * <pre>
 *   name   参数名（表单模式为过程的形参名，手写模式为语句中 :name 的名字）
 *   mode   in / out / inout
 *   type   varchar / number / integer / date / timestamp / clob / blob / cursor
 *   value  入参值（已求值）；hasValue=false 时表单模式不传该参数，由过程使用默认值
 *   parse  出参处理：string（默认） / json / xml；游标出参固定为行列表
 * </pre>
 */
public class ProcParam {

	public static final String[] MODES = {"in", "out", "inout"};

	public static final String[] TYPES = {"varchar", "number", "integer", "date", "timestamp", "clob", "blob", "cursor"};

	public static final String[] PARSES = {"string", "json", "xml"};

	/**
	 * Oracle 游标类型（oracle.jdbc.OracleTypes.CURSOR），不引入驱动依赖
	 */
	public static final int ORACLE_CURSOR = -10;

	private final String name;

	private final String mode;

	private final String type;

	private final Object value;

	private final boolean hasValue;

	private final String parse;

	public ProcParam(String name, String mode, String type, Object value, boolean hasValue, String parse) {
		this.name = name;
		this.mode = mode == null ? "in" : mode.toLowerCase(Locale.ROOT);
		this.type = type == null ? "varchar" : type.toLowerCase(Locale.ROOT);
		this.value = value;
		this.hasValue = hasValue;
		this.parse = parse == null || parse.isEmpty() ? "string" : parse.toLowerCase(Locale.ROOT);
	}

	public static ProcParam in(String name, String type, Object value) {
		return new ProcParam(name, "in", type, value, true, null);
	}

	public static ProcParam out(String name, String type, String parse) {
		return new ProcParam(name, "out", type, null, false, parse);
	}

	public String getName() {
		return name;
	}

	public String getMode() {
		return mode;
	}

	public String getType() {
		return type;
	}

	public Object getValue() {
		return value;
	}

	public boolean hasValue() {
		return hasValue;
	}

	public String getParse() {
		return parse;
	}

	public boolean isIn() {
		return !"out".equals(mode);
	}

	public boolean isOut() {
		return !"in".equals(mode);
	}

	/**
	 * 表单模式是否出现在调用里：出参一定出现，入参只有给了值才出现
	 */
	public boolean isPassed() {
		return isOut() || hasValue;
	}

	public int sqlType() {
		return sqlType(type);
	}

	public static int sqlType(String type) {
		switch (type) {
			case "number":
			case "integer":
				return Types.NUMERIC;
			case "date":
			case "timestamp":
				return Types.TIMESTAMP;
			case "clob":
				return Types.CLOB;
			case "blob":
				return Types.BLOB;
			case "cursor":
				return ORACLE_CURSOR;
			default:
				return Types.VARCHAR;
		}
	}

	@Override
	public String toString() {
		return name + " " + mode + " " + type;
	}
}
