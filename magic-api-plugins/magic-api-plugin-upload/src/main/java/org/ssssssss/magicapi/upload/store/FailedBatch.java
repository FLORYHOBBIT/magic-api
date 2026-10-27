package org.ssssssss.magicapi.upload.store;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 按 SKIP 策略跳过的失败批次，保存了原始数据用于补传
 */
public class FailedBatch {

	public static final String STATUS_PENDING = "PENDING";

	public static final String STATUS_DONE = "DONE";

	private String id;

	private String recordId;

	private String jobName;

	private int batchIndex;

	private int rowCount;

	private String startCheckpoint;

	private String endCheckpoint;

	private List<Map<String, Object>> rows;

	private String message;

	private String status = STATUS_PENDING;

	private int retryCount;

	private Date createTime;

	private Date updateTime;

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

	public List<Map<String, Object>> getRows() {
		return rows;
	}

	public void setRows(List<Map<String, Object>> rows) {
		this.rows = rows;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public int getRetryCount() {
		return retryCount;
	}

	public void setRetryCount(int retryCount) {
		this.retryCount = retryCount;
	}

	public Date getCreateTime() {
		return createTime;
	}

	public void setCreateTime(Date createTime) {
		this.createTime = createTime;
	}

	public Date getUpdateTime() {
		return updateTime;
	}

	public void setUpdateTime(Date updateTime) {
		this.updateTime = updateTime;
	}
}
