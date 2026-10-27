package org.ssssssss.magicapi.upload.engine;

import java.util.List;
import java.util.Map;

/**
 * 交给上传通道发送的一批数据
 */
public class UploadBatch {

	private final String jobName;

	/**
	 * 本次执行中的批次序号，从 1 开始
	 */
	private final int index;

	private final List<Map<String, Object>> rows;

	/**
	 * 本批开始前的断点
	 */
	private final Object startCheckpoint;

	/**
	 * 本批结束后的断点
	 */
	private final Object endCheckpoint;

	/**
	 * 当前是第几次尝试发送，从 1 开始
	 */
	private int attempt = 1;

	/**
	 * 批次创建时间，同一批重试时不变（文件通道用它生成文件名，避免重试产生多个文件）
	 */
	private final long createdAt = System.currentTimeMillis();

	public long getCreatedAt() {
		return createdAt;
	}

	public UploadBatch(String jobName, int index, List<Map<String, Object>> rows, Object startCheckpoint, Object endCheckpoint) {
		this.jobName = jobName;
		this.index = index;
		this.rows = rows;
		this.startCheckpoint = startCheckpoint;
		this.endCheckpoint = endCheckpoint;
	}

	public String getJobName() {
		return jobName;
	}

	public int getIndex() {
		return index;
	}

	public List<Map<String, Object>> getRows() {
		return rows;
	}

	public int getSize() {
		return rows.size();
	}

	public Object getStartCheckpoint() {
		return startCheckpoint;
	}

	public Object getEndCheckpoint() {
		return endCheckpoint;
	}

	public int getAttempt() {
		return attempt;
	}

	void setAttempt(int attempt) {
		this.attempt = attempt;
	}
}
