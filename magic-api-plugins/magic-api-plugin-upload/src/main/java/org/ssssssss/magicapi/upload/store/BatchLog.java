package org.ssssssss.magicapi.upload.store;

import java.util.Date;

/**
 * 单批上传日志（含对方回执）
 */
public class BatchLog {

	private String id;

	private String recordId;

	private String jobName;

	private int batchIndex;

	private int rowCount;

	private boolean success;

	private int attempts;

	private String startCheckpoint;

	private String endCheckpoint;

	private String message;

	private String response;

	private long costMillis;

	private Date createTime;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getRecordId() {
		return recordId;
	}

	public void setRecordId(String recordId) {
		this.recordId = recordId;
	}

	public String getJobName() {
		return jobName;
	}

	public void setJobName(String jobName) {
		this.jobName = jobName;
	}

	public int getBatchIndex() {
		return batchIndex;
	}

	public void setBatchIndex(int batchIndex) {
		this.batchIndex = batchIndex;
	}

	public int getRowCount() {
		return rowCount;
	}

	public void setRowCount(int rowCount) {
		this.rowCount = rowCount;
	}

	public boolean isSuccess() {
		return success;
	}

	public void setSuccess(boolean success) {
		this.success = success;
	}

	public int getAttempts() {
		return attempts;
	}

	public void setAttempts(int attempts) {
		this.attempts = attempts;
	}

	public String getStartCheckpoint() {
		return startCheckpoint;
	}

	public void setStartCheckpoint(String startCheckpoint) {
		this.startCheckpoint = startCheckpoint;
	}

	public String getEndCheckpoint() {
		return endCheckpoint;
	}

	public void setEndCheckpoint(String endCheckpoint) {
		this.endCheckpoint = endCheckpoint;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public String getResponse() {
		return response;
	}

	public void setResponse(String response) {
		this.response = response;
	}

	public long getCostMillis() {
		return costMillis;
	}

	public void setCostMillis(long costMillis) {
		this.costMillis = costMillis;
	}

	public Date getCreateTime() {
		return createTime;
	}

	public void setCreateTime(Date createTime) {
		this.createTime = createTime;
	}
}
