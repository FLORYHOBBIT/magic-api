package org.ssssssss.magicapi.core.record;

/**
 * 一次执行的摘要记录（不包含脚本输出）
 */
public class ExecutionRecord {

	/**
	 * 资源 ID
	 */
	private String id;

	/**
	 * 资源类型：api / task / upload
	 */
	private String type;

	/**
	 * 资源展示名称（脚本路径）
	 */
	private String name;

	/**
	 * 触发来源：test（编辑器测试）/ cron（定时触发）
	 */
	private String source;

	/**
	 * 开始时间（毫秒）
	 */
	private long startTime;

	/**
	 * 耗时（毫秒）
	 */
	private long costMillis;

	private boolean success;

	/**
	 * 失败时的错误摘要
	 */
	private String message;

	private String runId;
	private boolean logAvailable;
	private boolean logTruncated;

	public String getRunId() { return runId; }
	public void setRunId(String runId) { this.runId = runId; }
	public boolean isLogAvailable() { return logAvailable; }
	public void setLogAvailable(boolean logAvailable) { this.logAvailable = logAvailable; }
	public boolean isLogTruncated() { return logTruncated; }
	public void setLogTruncated(boolean logTruncated) { this.logTruncated = logTruncated; }

	public ExecutionRecord() {
	}

	public ExecutionRecord(String id, String type, String name, String source, long startTime, long costMillis, boolean success, String message) {
		this.id = id;
		this.type = type;
		this.name = name;
		this.source = source;
		this.startTime = startTime;
		this.costMillis = costMillis;
		this.success = success;
		this.message = message;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public long getStartTime() {
		return startTime;
	}

	public void setStartTime(long startTime) {
		this.startTime = startTime;
	}

	public long getCostMillis() {
		return costMillis;
	}

	public void setCostMillis(long costMillis) {
		this.costMillis = costMillis;
	}

	public boolean isSuccess() {
		return success;
	}

	public void setSuccess(boolean success) {
		this.success = success;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}
}
