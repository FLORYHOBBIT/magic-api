package org.ssssssss.magicapi.upload.store;

import java.util.Date;

/**
 * 一次上传任务的执行记录
 */
public class UploadRecord {

	private String id;

	private String jobName;

	private String status;

	private long totalRows;

	private long successRows;

	private long failedRows;

	private int totalBatches;

	private int failedBatches;

	private String startCheckpoint;

	private String endCheckpoint;

	private String message;

	private Date startTime;

	private Date endTime;

	private long costMillis;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getJobName() {
		return jobName;
	}

	public void setJobName(String jobName) {
		this.jobName = jobName;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public long getTotalRows() {
		return totalRows;
	}

	public void setTotalRows(long totalRows) {
		this.totalRows = totalRows;
	}

	public long getSuccessRows() {
		return successRows;
	}

	public void setSuccessRows(long successRows) {
		this.successRows = successRows;
	}

	public long getFailedRows() {
		return failedRows;
	}

	public void setFailedRows(long failedRows) {
		this.failedRows = failedRows;
	}

	public int getTotalBatches() {
		return totalBatches;
	}

	public void setTotalBatches(int totalBatches) {
		this.totalBatches = totalBatches;
	}

	public int getFailedBatches() {
		return failedBatches;
	}

	public void setFailedBatches(int failedBatches) {
		this.failedBatches = failedBatches;
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

	public Date getStartTime() {
		return startTime;
	}

	public void setStartTime(Date startTime) {
		this.startTime = startTime;
	}

	public Date getEndTime() {
		return endTime;
	}

	public void setEndTime(Date endTime) {
		this.endTime = endTime;
	}

	public long getCostMillis() {
		return costMillis;
	}

	public void setCostMillis(long costMillis) {
		this.costMillis = costMillis;
	}
}
