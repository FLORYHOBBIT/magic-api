package org.ssssssss.magicapi.upload.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 补传失败批次的结果
 */
public class RetryResult {

	private final String jobName;

	private boolean skipped;

	private String message;

	private int successBatches;

	private int failedBatches;

	private int successRows;

	private int failedRows;

	private final List<String> errors = new ArrayList<>();

	public RetryResult(String jobName) {
		this.jobName = jobName;
	}

	static RetryResult skipped(String jobName, String message) {
		RetryResult result = new RetryResult(jobName);
		result.skipped = true;
		result.message = message;
		return result;
	}

	void success(int rows) {
		successBatches++;
		successRows += rows;
	}

	void failure(int batchIndex, int rows, String error) {
		failedBatches++;
		failedRows += rows;
		errors.add("第" + batchIndex + "批: " + error);
	}

	public String getJobName() {
		return jobName;
	}

	public boolean isSkipped() {
		return skipped;
	}

	public String getMessage() {
		return message;
	}

	public int getSuccessBatches() {
		return successBatches;
	}

	public int getFailedBatches() {
		return failedBatches;
	}

	public int getSuccessRows() {
		return successRows;
	}

	public int getFailedRows() {
		return failedRows;
	}

	public int getTotalBatches() {
		return successBatches + failedBatches;
	}

	/**
	 * 没有待补传的批次时也视为成功
	 */
	public boolean isSuccess() {
		return !skipped && failedBatches == 0;
	}

	public List<String> getErrors() {
		return Collections.unmodifiableList(errors);
	}

	@Override
	public String toString() {
		return "RetryResult{job='" + jobName + "', success=" + successBatches + ", failed=" + failedBatches
				+ (message == null ? "" : ", message='" + message + "'") + "}";
	}
}
