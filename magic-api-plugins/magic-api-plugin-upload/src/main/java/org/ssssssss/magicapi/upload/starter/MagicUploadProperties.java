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

	private final Alert alert = new Alert();

	public Alert getAlert() {
		return alert;
	}

	/**
	 * 告警配置，前缀 magic-api.upload.alert
	 */
	public static class Alert {

		/**
		 * 是否启用告警（关闭后日志告警也不再输出）
		 */
		private boolean enable = true;

		/**
		 * 医院名称，显示在告警标题中
		 */
		private String hospital;

		/**
		 * 同一任务同一类告警的静默时间
		 */
		private Duration silence = Duration.ofMinutes(10);

		/**
		 * 部分批次失败（SKIP 策略）时是否告警
		 */
		private boolean alertOnPartial = true;

		private final Webhook webhook = new Webhook();

		public boolean isEnable() {
			return enable;
		}

		public void setEnable(boolean enable) {
			this.enable = enable;
		}

		public String getHospital() {
			return hospital;
		}

		public void setHospital(String hospital) {
			this.hospital = hospital;
		}

		public Duration getSilence() {
			return silence;
		}

		public void setSilence(Duration silence) {
			this.silence = silence;
		}

		public boolean isAlertOnPartial() {
			return alertOnPartial;
		}

		public void setAlertOnPartial(boolean alertOnPartial) {
			this.alertOnPartial = alertOnPartial;
		}

		public Webhook getWebhook() {
			return webhook;
		}
	}

	/**
	 * Webhook 告警，前缀 magic-api.upload.alert.webhook，配置 url 后启用
	 */
	public static class Webhook {

		private String url;

		/**
		 * dingtalk、wecom、feishu、generic
		 */
		private String type = "generic";

		/**
		 * 钉钉、飞书机器人的加签密钥
		 */
		private String secret;

		private Duration timeout = Duration.ofSeconds(5);

		public String getUrl() {
			return url;
		}

		public void setUrl(String url) {
			this.url = url;
		}

		public String getType() {
			return type;
		}

		public void setType(String type) {
			this.type = type;
		}

		public String getSecret() {
			return secret;
		}

		public void setSecret(String secret) {
			this.secret = secret;
		}

		public Duration getTimeout() {
			return timeout;
		}

		public void setTimeout(Duration timeout) {
			this.timeout = timeout;
		}
	}

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
