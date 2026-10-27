package org.ssssssss.magicapi.upload.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.magicapi.upload.engine.*;
import org.ssssssss.magicapi.upload.store.FailedBatch;
import org.ssssssss.magicapi.upload.store.UploadRecord;
import org.ssssssss.magicapi.upload.store.UploadStore;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 上传任务执行入口：加锁 → 读取断点 → 执行引擎 → 写记录 → 释放锁
 */
public class UploadJobRunner {

	private static final Logger logger = LoggerFactory.getLogger(UploadJobRunner.class);

	private static final String RETRY_LOCK_SUFFIX = "#retry";

	private static final String PROCESS_NAME = ManagementFactory.getRuntimeMXBean().getName();

	private final UploadStore store;

	private final BatchEngine engine;

	private final Duration lockTimeout;

	private final boolean recordEmptyRuns;

	public UploadJobRunner(UploadStore store, BatchEngine engine, Duration lockTimeout, boolean recordEmptyRuns) {
		this.store = store;
		this.engine = engine;
		this.lockTimeout = lockTimeout;
		this.recordEmptyRuns = recordEmptyRuns;
	}

	private org.ssssssss.magicapi.upload.alert.UploadAlertService alertService;

	/**
	 * 任务失败、部分失败、补传失败时告警
	 */
	public void setAlertService(org.ssssssss.magicapi.upload.alert.UploadAlertService alertService) {
		this.alertService = alertService;
	}

	public UploadStore getStore() {
		return store;
	}

	public JobResult run(JobOptions options, BatchSource source, UploadChannel channel) {
		return run(options, source, channel, Collections.emptyList());
	}

	/**
	 * 执行上传任务。同一任务已在执行时（本实例其他线程或其他实例）直接返回 SKIPPED。
	 *
	 * @param extraListeners 额外的回调（如告警），其异常不影响上传
	 */
	public JobResult run(JobOptions options, BatchSource source, UploadChannel channel, List<JobListener> extraListeners) {
		String jobName = options.getJobName();
		String owner = newOwner();
		if (!store.tryLock(jobName, owner, lockTimeout)) {
			logger.info("上传任务[{}]正在执行中，本次跳过", jobName);
			return JobResult.skipped(jobName, "任务正在执行中（本实例其他线程或其他实例），本次跳过");
		}
		try {
			Object checkpoint = store.loadCheckpoint(jobName);
			String recordId = UUID.randomUUID().toString().replace("-", "");
			List<JobListener> others = new java.util.ArrayList<>(extraListeners == null ? Collections.<JobListener>emptyList() : extraListeners);
			if (alertService != null) {
				others.add(alertService.listener());
			}
			JobListener listener = new CompositeJobListener(
					new StoreJobListener(store, recordId, owner, lockTimeout, recordEmptyRuns), others);
			JobResult result = engine.run(options, source, channel, checkpoint, listener);
			if (!source.isIncremental() && result.isExhausted() && result.getStatus() != JobStatus.FAILED) {
				// offset 分页、列表来源：本轮数据已全部处理，下次从头开始
				try {
					store.resetCheckpoint(jobName);
				} catch (Exception e) {
					logger.warn("上传任务[{}]清除断点失败，下次执行可能读不到数据，请手工调用 resetCheckpoint", jobName, e);
				}
			}
			return result;
		} finally {
			try {
				store.unlock(jobName, owner);
			} catch (Exception e) {
				logger.warn("上传任务[{}]释放任务锁失败，锁将在 {} 后过期", jobName, lockTimeout, e);
			}
		}
	}

	/**
	 * 补传之前按 SKIP 策略跳过的失败批次
	 *
	 * @param options 重试次数、退避时间取自这里，批大小不生效（按原批次补传）
	 * @param limit   本次最多补传多少批，0 表示全部
	 */
	public RetryResult retryFailed(JobOptions options, UploadChannel channel, int limit) {
		String jobName = options.getJobName();
		String lockName = jobName + RETRY_LOCK_SUFFIX;
		String owner = newOwner();
		if (!store.tryLock(lockName, owner, lockTimeout)) {
			return RetryResult.skipped(jobName, "补传正在执行中，本次跳过");
		}
		RetryResult result = new RetryResult(jobName);
		try {
			List<FailedBatch> pending = store.listPendingFailedBatches(jobName, limit);
			for (FailedBatch failed : pending) {
				UploadBatch batch = new UploadBatch(jobName, failed.getBatchIndex(), failed.getRows(),
						failed.getStartCheckpoint(), failed.getEndCheckpoint());
				BatchResult batchResult;
				try {
					batchResult = engine.sendBatch(options, channel, batch);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
				if (batchResult.isSuccess()) {
					store.updateFailedBatch(failed.getId(), true, "补传成功");
					result.success(failed.getRowCount());
				} else {
					store.updateFailedBatch(failed.getId(), false, batchResult.getMessage());
					result.failure(failed.getBatchIndex(), failed.getRowCount(), batchResult.getMessage());
				}
				store.refreshLock(lockName, owner, lockTimeout);
			}
			logger.info("上传任务[{}]补传结束: {}", jobName, result);
			if (alertService != null) {
				try {
					alertService.retryEnded(result);
				} catch (Exception e) {
					logger.warn("上传任务[{}]补传告警失败", jobName, e);
				}
			}
			return result;
		} finally {
			try {
				store.unlock(lockName, owner);
			} catch (Exception e) {
				logger.warn("上传任务[{}]释放补传锁失败", jobName, e);
			}
		}
	}

	public void resetCheckpoint(String jobName) {
		store.resetCheckpoint(jobName);
	}

	public Object checkpoint(String jobName) {
		return store.loadCheckpoint(jobName);
	}

	public List<UploadRecord> records(String jobName, int limit) {
		return store.listRecords(jobName, limit);
	}

	public List<FailedBatch> failedBatches(String jobName, int limit) {
		return store.listPendingFailedBatches(jobName, limit);
	}

	private static String newOwner() {
		String process = PROCESS_NAME.length() > 100 ? PROCESS_NAME.substring(0, 100) : PROCESS_NAME;
		return process + ":" + UUID.randomUUID().toString().substring(0, 8);
	}
}
