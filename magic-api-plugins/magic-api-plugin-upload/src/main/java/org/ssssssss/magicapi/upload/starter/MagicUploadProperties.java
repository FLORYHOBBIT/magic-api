package org.ssssssss.magicapi.upload.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 上传插件配置，前缀 magic-api.upload
 */
@ConfigurationProperties("magic-api.upload")
public class MagicUploadProperties {

	/**
	 * 是否启用上传插件
	 */
	private boolean enable = true;

	/**
	 * 断点表、记录表所在的数据源 key，为空时使用主数据源
	 */
	private String datasource;

	/**
	 * 启动时是否自动建表（仅支持 Oracle、SQLServer，以及测试用的 H2）
	 */
	private boolean initSchema = true;

	/**
	 * 表名前缀
	 */
	private String tablePrefix = "magic_upload_";

	/**
	 * 通道敏感字段加密密钥，生产环境必须配置
	 */
	private String secretKey;

	/**
	 * 默认每批条数
	 */
	private int defaultBatchSize = 1000;

	/**
	 * 默认每批失败后的重试次数
	 */
	private int defaultRetry = 3;

	/**
	 * 首次重试等待时间，之后按 2 倍递增
	 */
	private Duration retryBackoff = Duration.ofSeconds(1);

	/**
	 * 任务锁超时时间，超过后锁视为失效（防止进程崩溃导致锁无法释放）
	 */
	private Duration lockTimeout = Duration.ofHours(2);

	/**
	 * 没有数据可上传的执行是否也写执行记录。秒级、分钟级任务建议保持 false。
	 */
	private boolean recordEmptyRuns = false;

	public boolean isRecordEmptyRuns() {
		return recordEmptyRuns;
	}

	public void setRecordEmptyRuns(boolean recordEmptyRuns) {
		this.recordEmptyRuns = recordEmptyRuns;
	}

	public boolean isEnable() {
		return enable;
	}

	public void setEnable(boolean enable) {
		this.enable = enable;
	}

	public String getDatasource() {
		return datasource;
	}

	public void setDatasource(String datasource) {
		this.datasource = datasource;
	}

	public boolean isInitSchema() {
		return initSchema;
	}

	public void setInitSchema(boolean initSchema) {
		this.initSchema = initSchema;
	}

	public String getTablePrefix() {
		return tablePrefix;
	}

	public void setTablePrefix(String tablePrefix) {
		this.tablePrefix = tablePrefix;
	}

	public String getSecretKey() {
		return secretKey;
	}

	public void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

	public int getDefaultBatchSize() {
		return defaultBatchSize;
	}

	public void setDefaultBatchSize(int defaultBatchSize) {
		this.defaultBatchSize = defaultBatchSize;
	}

	public int getDefaultRetry() {
		return defaultRetry;
	}

	public void setDefaultRetry(int defaultRetry) {
		this.defaultRetry = defaultRetry;
	}

	public Duration getRetryBackoff() {
		return retryBackoff;
	}

	public void setRetryBackoff(Duration retryBackoff) {
		this.retryBackoff = retryBackoff;
	}

	public Duration getLockTimeout() {
		return lockTimeout;
	}

	public void setLockTimeout(Duration lockTimeout) {
		this.lockTimeout = lockTimeout;
	}
}
