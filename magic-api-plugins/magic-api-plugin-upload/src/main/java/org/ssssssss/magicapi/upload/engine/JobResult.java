package org.ssssssss.magicapi.upload.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次上传任务的汇总结果，脚本中 run() 的返回值
 */
public class JobResult {

	private final String jobName;

	private JobStatus status = JobStatus.RUNNING;

	private String message;

	private int totalRows;

	private int successRows;

	private int failedRows;

	private int successBatches;

	private int failedBatches;

	/**
	 * 本次开始时的断点
	 */
	private Object startCheckpoint;

	/**
	 * 本次结束时的断点（下次从这里继续）
	 */
	private Object endCheckpoint;

	private long startTime;

	private long endTime;

	/**
	 * 数据来源已全部读完（区别于达到最大批次数、失败中止）
	 */
	private boolean exhausted;

	public boolean isExhausted() {
		return exhausted;
	}

	void setExhausted(boolean exhausted) {
		this.exhausted = exhausted;
	}

	private final List<BatchResult> batches = new ArrayList<>();

	public JobResult(String jobName) {
		this.jobName = jobName;
	}

	public static JobResult skipped(String jobName, String message) {
		JobResult result = new JobResult(jobName);
		long now = System.currentTimeMillis();
		result.startTime = now;
		result.endTime = now;
		result.status = JobStatus.SKIPPED;
		result.message = message;
		return result;
	}

	void addBatch(BatchResult batch) {
		batches.add(batch);
		totalRows += batch.getSize();
		if (batch.isSuccess()) {
			successRows += batch.getSize();
			successBatches++;
		} else {
			failedRows += batch.getSize();
			failedBatches++;
		}
	}

	public String getJobName() {
		return jobName;
	}

	public JobStatus getStatus() {
		return status;
	}

	void setStatus(JobStatus status) {
		this.status = status;
	}

	public boolean isSuccess() {
		return status == JobStatus.SUCCESS;
	}

	public String getMessage() {
		return message;
	}

	void setMessage(String message) {
		this.message = message;
	}

	public int getTotalRows() {
		return totalRows;
	}

	public int getSuccessRows() {
		return successRows;
	}

	public int getFailedRows() {
		return failedRows;
	}

	public int getSuccessBatches() {
		return successBatches;
	}

	public int getFailedBatches() {
		return failedBatches;
	}

	public int getTotalBatches() {
		return batches.size();
	}

	public Object getStartCheckpoint() {
		return startCheckpoint;
	}

	void setStartCheckpoint(Object startCheckpoint) {
		this.startCheckpoint = startCheckpoint;
	}

	public Object getEndCheckpoint() {
		return endCheckpoint;
	}

	void setEndCheckpoint(Object endCheckpoint) {
		this.endCheckpoint = endCheckpoint;
	}

	public long getStartTime() {
		return startTime;
	}

	void setStartTime(long startTime) {
		this.startTime = startTime;
	}

	public long getEndTime() {
		return endTime;
	}

	void setEndTime(long endTime) {
		this.endTime = endTime;
	}

	public long getCostMillis() {
		return endTime - startTime;
	}

	public List<BatchResult> getBatches() {
		return Collections.unmodifiableList(batches);
	}

	@Override
	public String toString() {
		return "JobResult{" +
				"job='" + jobName + '\'' +
				", status=" + status +
				", rows=" + successRows + "/" + totalRows +
				", batches=" + successBatches + "/" + batches.size() +
				", checkpoint=" + startCheckpoint + "->" + endCheckpoint +
				", cost=" + getCostMillis() + "ms" +
				(message == null ? "" : ", message='" + message + '\'') +
				'}';
	}
}
