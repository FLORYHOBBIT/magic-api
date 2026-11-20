package org.ssssssss.magicapi.datasource.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.ssssssss.magicapi.core.model.MagicEntity;

import java.util.Objects;

public class DataSourceInfo extends MagicEntity {

	private static final String LEGACY_ORACLE_DRIVER = "oracle.jdbc.driver.OracleDriver";
	private static final String ORACLE_DRIVER = "oracle.jdbc.OracleDriver";

	/**
	 * URL
	 */
	private String url;

	/**
	 * 用户名
	 */
	private String username;

	/**
	 * 密码
	 */
	private String password;

	/**
	 * 数据源key
	 */
	private String key;

	/**
	 * 最多返回条数
	 */
	private int maxRows = -1;

	/**
	 * 驱动类
	 */
	private String driverClassName;

	/**
	 * 连接池类型
	 */
	private String type;

	/**
	 * 是否为主数据源：未指定 key 时（如消息中心、默认查询）使用它
	 */
	private boolean primary;

	public boolean isPrimary() {
		return primary;
	}

	public void setPrimary(boolean primary) {
		this.primary = primary;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public int getMaxRows() {
		return maxRows;
	}

	public void setMaxRows(int maxRows) {
		this.maxRows = maxRows;
	}

	public String getDriverClassName() {
		return driverClassName;
	}

	public void setDriverClassName(String driverClassName) {
		this.driverClassName = driverClassName;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public boolean hasSameJdbcTarget(DataSourceInfo other) {
		return Objects.equals(url, other.url) && Objects.equals(username, other.username)
				&& Objects.equals(jdbcDriverClassName(), other.jdbcDriverClassName());
	}

	public String jdbcDriverClassName() {
		if (LEGACY_ORACLE_DRIVER.equals(driverClassName) && url != null && url.startsWith("jdbc:oracle:")) {
			return ORACLE_DRIVER;
		}
		return driverClassName;
	}

	public DataSourceInfo withoutPassword() {
		DataSourceInfo detail = copy();
		detail.setPassword(null);
		return detail;
	}

	@Override
	public MagicEntity simple() {
		DataSourceInfo dataSourceInfo = new DataSourceInfo();
		super.simple(dataSourceInfo);
		dataSourceInfo.setKey(this.key);
		dataSourceInfo.setPrimary(this.primary);
		return dataSourceInfo;
	}

	@Override
	public DataSourceInfo copy() {
		DataSourceInfo dataSourceInfo = new DataSourceInfo();
		super.copyTo(dataSourceInfo);
		dataSourceInfo.setUsername(this.username);
		dataSourceInfo.setPassword(this.password);
		dataSourceInfo.setUrl(this.url);
		dataSourceInfo.setDriverClassName(this.driverClassName);
		dataSourceInfo.setType(this.type);
		dataSourceInfo.setMaxRows(this.maxRows);
		dataSourceInfo.setPrimary(this.primary);
		dataSourceInfo.setKey(this.key);
		return dataSourceInfo;
	}
}
