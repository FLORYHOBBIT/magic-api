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
