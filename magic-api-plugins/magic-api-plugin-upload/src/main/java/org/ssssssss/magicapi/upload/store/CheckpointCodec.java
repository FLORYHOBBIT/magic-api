package org.ssssssss.magicapi.upload.store;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;

/**
 * 断点值与数据库字符串之间的转换，保留类型，便于下次作为 SQL 参数使用
 */
public final class CheckpointCodec {

	public static final String TYPE_LONG = "long";

	public static final String TYPE_DECIMAL = "decimal";

	public static final String TYPE_TIMESTAMP = "timestamp";

	public static final String TYPE_STRING = "string";

	private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);

	private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

	private CheckpointCodec() {
	}

	public static Encoded encode(Object value) {
		if (value == null) {
			return new Encoded(null, null);
		}
		if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
			return new Encoded(TYPE_LONG, value.toString());
		}
		if (value instanceof BigInteger) {
			BigInteger big = (BigInteger) value;
			boolean fitsLong = big.compareTo(LONG_MIN) >= 0 && big.compareTo(LONG_MAX) <= 0;
			return new Encoded(fitsLong ? TYPE_LONG : TYPE_DECIMAL, big.toString());
		}
		if (value instanceof BigDecimal) {
			return new Encoded(TYPE_DECIMAL, ((BigDecimal) value).toPlainString());
		}
		if (value instanceof Double || value instanceof Float) {
			return new Encoded(TYPE_DECIMAL, BigDecimal.valueOf(((Number) value).doubleValue()).toPlainString());
		}
		if (value instanceof Timestamp) {
			return new Encoded(TYPE_TIMESTAMP, value.toString());
		}
		if (value instanceof Date) {
			return new Encoded(TYPE_TIMESTAMP, new Timestamp(((Date) value).getTime()).toString());
		}
		if (value instanceof LocalDateTime) {
			return new Encoded(TYPE_TIMESTAMP, Timestamp.valueOf((LocalDateTime) value).toString());
		}
		if (value instanceof LocalDate) {
			return new Encoded(TYPE_TIMESTAMP, Timestamp.valueOf(((LocalDate) value).atStartOfDay()).toString());
		}
		return new Encoded(TYPE_STRING, value.toString());
	}

	public static Object decode(String type, String value) {
		if (value == null) {
			return null;
		}
		if (type == null || TYPE_STRING.equals(type)) {
			return value;
		}
		switch (type) {
			case TYPE_LONG:
				return Long.valueOf(value);
			case TYPE_DECIMAL:
				return new BigDecimal(value);
			case TYPE_TIMESTAMP:
				return Timestamp.valueOf(value);
			default:
				return value;
		}
	}

	public static final class Encoded {

		private final String type;

		private final String value;

		Encoded(String type, String value) {
			this.type = type;
			this.value = value;
		}

		public String getType() {
			return type;
		}

		public String getValue() {
			return value;
		}
	}
}
