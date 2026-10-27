package org.ssssssss.magicapi.upload.store;

import java.util.Locale;

/**
 * 自动建表支持的数据库类型
 */
public enum DatabaseType {

	ORACLE,

	SQLSERVER,

	/**
	 * 测试使用
	 */
	H2,

	UNSUPPORTED;

	/**
	 * 根据 DatabaseMetaData.getDatabaseProductName() 识别
	 */
	public static DatabaseType fromProductName(String productName) {
		if (productName == null) {
			return UNSUPPORTED;
		}
		String name = productName.toLowerCase(Locale.ROOT);
		if (name.contains("oracle")) {
			return ORACLE;
		}
		if (name.contains("sql server")) {
			return SQLSERVER;
		}
		if (name.equals("h2")) {
			return H2;
		}
		return UNSUPPORTED;
	}
}
