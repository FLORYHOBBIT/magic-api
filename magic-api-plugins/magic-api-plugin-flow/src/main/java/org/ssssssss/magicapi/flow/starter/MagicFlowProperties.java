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
