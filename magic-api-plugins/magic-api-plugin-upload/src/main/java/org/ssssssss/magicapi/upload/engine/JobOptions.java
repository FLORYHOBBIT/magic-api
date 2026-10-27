package org.ssssssss.magicapi.upload.engine;

import java.time.Duration;

/**
 * 一次上传任务的执行参数
 */
public class JobOptions {

	private final String jobName;

	private int batchSize = 1000;

	/**
	 * 每批失败后的重试次数（不含首次发送）
	 */
	private int retry = 3;

	/**
	 * 首次重试的等待时间，之后每次翻倍
	 */
	private Duration retryBackoff = Duration.ofSeconds(1);

	/**
	 * 单次重试等待的上限
	 */
	private Duration maxBackoff = Duration.ofMinutes(1);

	private FailurePolicy failurePolicy = FailurePolicy.STOP;

	/**
	 * 本次执行最多上传的批次数，0 表示不限制
	 */
	private int maxBatches = 0;

	public JobOptions(String jobName) {
		if (jobName == null || jobName.trim().isEmpty()) {
			throw new IllegalArgumentException("任务名不能为空");
		}
		this.jobName = jobName.trim();
	}

	public String getJobName() {
		return jobName;
	}

	public int getBatchSize() {
		return batchSize;
	}

	public JobOptions batchSize(int batchSize) {
		if (batchSize <= 0) {
			throw new IllegalArgumentException("批大小必须大于 0");
		}
		this.batchSize = batchSize;
		return this;
	}

	public int getRetry() {
		return retry;
	}

	public JobOptions retry(int retry) {
		if (retry < 0) {
			throw new IllegalArgumentException("重试次数不能小于 0");
		}
		this.retry = retry;
		return this;
	}

	public Duration getRetryBackoff() {
		return retryBackoff;
	}

	public JobOptions retryBackoff(Duration retryBackoff) {
		this.retryBackoff = retryBackoff == null ? Duration.ZERO : retryBackoff;
		return this;
	}

	public Duration getMaxBackoff() {
		return maxBackoff;
	}

	public JobOptions maxBackoff(Duration maxBackoff) {
		this.maxBackoff = maxBackoff == null ? Duration.ofMinutes(1) : maxBackoff;
		return this;
	}

	public FailurePolicy getFailurePolicy() {
		return failurePolicy;
	}

	public JobOptions failurePolicy(FailurePolicy failurePolicy) {
		this.failurePolicy = failurePolicy == null ? FailurePolicy.STOP : failurePolicy;
		return this;
	}

	public int getMaxBatches() {
		return maxBatches;
	}

	public JobOptions maxBatches(int maxBatches) {
		if (maxBatches < 0) {
			throw new IllegalArgumentException("最大批次数不能小于 0");
		}
		this.maxBatches = maxBatches;
		return this;
	}

	/**
	 * 第 attempt 次失败后、下次重试前的等待毫秒数（attempt 从 1 开始）
	 */
	long backoffMillis(int attempt) {
		long base = retryBackoff.toMillis();
		if (base <= 0) {
			return 0;
		}
		long max = maxBackoff.toMillis();
		long value = base;
		for (int i = 1; i < attempt && value < max; i++) {
			value = value * 2;
		}
		return Math.min(value, max);
	}
}
