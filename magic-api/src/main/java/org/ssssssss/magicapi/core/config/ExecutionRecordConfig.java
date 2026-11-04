package org.ssssssss.magicapi.core.config;

/**
 * 执行记录配置（接口测试、定时任务、上传通道测试的执行摘要）
 */
public class ExecutionRecordConfig {

	/**
	 * 是否记录执行摘要
	 */
	private boolean enable = true;

	/**
	 * 记录文件目录，相对路径相对于工作目录
	 */
	private String dir = "logs/execution-records";

	/**
	 * 每个资源保留的最近记录条数
	 */
	private int maxRecords = 200;

	/** 是否保存定时任务的详细日志（定时触发与手动测试） */
	private boolean logEnabled = true;

	/** 每次执行最多保存的字符数，超出后截断，避免大日志占满内存和磁盘 */
	private int maxLogChars = 262144;

	public boolean isLogEnabled() { return logEnabled; }
	public void setLogEnabled(boolean logEnabled) { this.logEnabled = logEnabled; }
	public int getMaxLogChars() { return maxLogChars; }
	public void setMaxLogChars(int maxLogChars) { this.maxLogChars = maxLogChars; }

	public boolean isEnable() {
		return enable;
	}

	public void setEnable(boolean enable) {
		this.enable = enable;
	}

	public String getDir() {
		return dir;
	}

	public void setDir(String dir) {
		this.dir = dir;
	}

	public int getMaxRecords() {
		return maxRecords;
	}

	public void setMaxRecords(int maxRecords) {
		this.maxRecords = maxRecords;
	}
}
