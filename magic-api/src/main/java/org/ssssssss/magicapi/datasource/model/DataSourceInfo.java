package org.ssssssss.magicapi.datasource.model;

import org.ssssssss.magicapi.core.model.MagicEntity;

public class DataSourceInfo extends MagicEntity {

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

	@Override
	public MagicEntity simple() {
		DataSourceInfo dataSourceInfo = new DataSourceInfo();
		super.simple(dataSourceInfo);
		dataSourceInfo.setKey(this.key);
		dataSourceInfo.setPrimary(this.primary);
		return dataSourceInfo;
	}

	@Override
	public MagicEntity copy() {
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
