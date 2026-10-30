package org.ssssssss.magicapi.flow.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 集成流程配置，前缀 magic-api.flow
 */
@ConfigurationProperties(prefix = "magic-api.flow")
public class MagicFlowProperties {

	/**
	 * 是否启用集成流程
	 */
	private boolean enable = true;

	/**
	 * 对外 HTTP 接口的路径前缀（在 magic-api.prefix 之后），默认与接口相同
	 */
	private String httpPrefix = "";

	/**
	 * 对外 WebService 的路径前缀（在 magic-api.prefix 之后）
	 */
	private String soapPrefix = "/ws";

	/**
	 * 定时流程的线程数
	 */
	private int schedulerPoolSize = 4;

	/**
	 * 单次执行的最大步数（防止连线成环）
	 */
	private int maxSteps = 10000;

	/**
	 * 每个流程在内存中保留的最近运行记录条数
	 */
	private int recordSize = 50;

	/**
	 * 差异项（各医院不同的地址、账号、机构编码等）保存位置，不随配置包导出
	 */
	private String variablesFile = "./magic-flow/variables.json";

	/**
	 * 配置包导入前的备份目录
	 */
	private String backupDir = "./magic-flow/backups";

	/**
	 * 差异项的默认值，也可以直接写在 application.yml：magic-api.flow.vars.org-code=H001
	 */
	private Map<String, String> vars = new LinkedHashMap<>();

	/**
	 * 消息中心
	 */
	private final Msg msg = new Msg();

	public Msg getMsg() {
		return msg;
	}

	/**
	 * 消息中心配置，前缀 magic-api.flow.msg。每种消息类型的重试、顺序、回调、告警在流程的「消息」触发节点上配置。
	 */
	public static class Msg {

		/**
		 * 是否启用消息中心（接收入口与消费调度）
		 */
		private boolean enable = true;

		/**
		 * 消息表所在数据源 key；为空时沿用 magic-api.upload.datasource，再为空用主数据源
		 */
		private String datasource;

		/**
		 * 表名前缀，表名为 前缀 + msg
		 */
		private String tablePrefix = "magic_flow_";

		/**
		 * 首次使用时自动建表；DBA 不允许程序建表时设为 false 并手工建表
		 */
		private boolean initSchema = true;

		/**
		 * HTTP 接收地址前缀（在 magic-api.prefix 之后），地址为 前缀/类型
		 */
		private String httpPath = "/msg";

		/**
		 * 通用 WebService 的服务名，地址为 magic-api.flow.soap-prefix + /服务名
		 */
		private String soapService = "msg";

		/**
		 * 轮询待处理消息的间隔；收到消息时会立即唤醒，不必等轮询
		 */
		private java.time.Duration pollInterval = java.time.Duration.ofSeconds(1);

		/**
		 * 消费线程数（所有类型共用，每种类型另有并发数上限）
		 */
		private int threads = 8;

		/**
		 * 处理中超过该时长视为超时（服务宕机、线程卡死），恢复为待重试
		 */
		private java.time.Duration processingTimeout = java.time.Duration.ofMinutes(10);

		/**
		 * 成功、已忽略的消息保留天数，0 不清理；死信不自动删除
		 */
		private int retainDays = 30;

		/**
		 * 清理时间，6 位 cron
		 */
		private String cleanupCron = "0 30 3 * * ?";

		/**
		 * 告警检查（积压、延迟）间隔
		 */
		private java.time.Duration alertInterval = java.time.Duration.ofMinutes(1);

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

		public String getTablePrefix() {
			return tablePrefix;
		}

		public void setTablePrefix(String tablePrefix) {
			this.tablePrefix = tablePrefix;
		}

		public boolean isInitSchema() {
			return initSchema;
		}

		public void setInitSchema(boolean initSchema) {
			this.initSchema = initSchema;
		}

		public String getHttpPath() {
			return httpPath;
		}

		public void setHttpPath(String httpPath) {
			this.httpPath = httpPath;
		}

		public String getSoapService() {
			return soapService;
		}

		public void setSoapService(String soapService) {
			this.soapService = soapService;
		}

		public java.time.Duration getPollInterval() {
			return pollInterval;
		}

		public void setPollInterval(java.time.Duration pollInterval) {
			this.pollInterval = pollInterval;
		}

		public int getThreads() {
			return threads;
		}

		public void setThreads(int threads) {
			this.threads = threads;
		}

		public java.time.Duration getProcessingTimeout() {
			return processingTimeout;
		}

		public void setProcessingTimeout(java.time.Duration processingTimeout) {
			this.processingTimeout = processingTimeout;
		}

		public int getRetainDays() {
			return retainDays;
		}

		public void setRetainDays(int retainDays) {
			this.retainDays = retainDays;
		}

		public String getCleanupCron() {
			return cleanupCron;
		}

		public void setCleanupCron(String cleanupCron) {
			this.cleanupCron = cleanupCron;
		}

		public java.time.Duration getAlertInterval() {
			return alertInterval;
		}

		public void setAlertInterval(java.time.Duration alertInterval) {
			this.alertInterval = alertInterval;
		}
	}

	public boolean isEnable() {
		return enable;
	}

	public void setEnable(boolean enable) {
		this.enable = enable;
	}

	public String getHttpPrefix() {
		return httpPrefix;
	}

	public void setHttpPrefix(String httpPrefix) {
		this.httpPrefix = httpPrefix;
	}

	public String getSoapPrefix() {
		return soapPrefix;
	}

	public void setSoapPrefix(String soapPrefix) {
		this.soapPrefix = soapPrefix;
	}

	public int getSchedulerPoolSize() {
		return schedulerPoolSize;
	}

	public void setSchedulerPoolSize(int schedulerPoolSize) {
		this.schedulerPoolSize = schedulerPoolSize;
	}

	public int getMaxSteps() {
		return maxSteps;
	}

	public void setMaxSteps(int maxSteps) {
		this.maxSteps = maxSteps;
	}

	public int getRecordSize() {
		return recordSize;
	}

	public void setRecordSize(int recordSize) {
		this.recordSize = recordSize;
	}

	public String getVariablesFile() {
		return variablesFile;
	}

	public void setVariablesFile(String variablesFile) {
		this.variablesFile = variablesFile;
	}

	public String getBackupDir() {
		return backupDir;
	}

	public void setBackupDir(String backupDir) {
		this.backupDir = backupDir;
	}

	public Map<String, String> getVars() {
		return vars;
	}

	public void setVars(Map<String, String> vars) {
		this.vars = vars == null ? new LinkedHashMap<>() : vars;
	}
}
