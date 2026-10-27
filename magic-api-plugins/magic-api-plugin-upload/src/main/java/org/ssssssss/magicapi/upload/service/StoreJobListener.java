package org.ssssssss.magicapi.upload.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.magicapi.upload.engine.*;
import org.ssssssss.magicapi.upload.store.*;

import java.time.Duration;
import java.util.Date;
import java.util.Objects;

/**
 * 将引擎的执行过程写入 {@link UploadStore}
 * <p>
 * 断点保存、失败批次保存失败时抛出异常（引擎会停止任务）；批次日志、执行记录、锁续期失败只记录日志。
 */
class StoreJobListener implements JobListener {

	private static final Logger logger = LoggerFactory.getLogger(StoreJobListener.class);

	private final UploadStore store;

	private final String recordId;

	private final String owner;

	private final Duration lockTimeout;

	private final boolean recordEmptyRuns;

	StoreJobListener(UploadStore store, String recordId, String owner, Duration lockTimeout, boolean recordEmptyRuns) {
		this.store = store;
		this.recordId = recordId;
		this.owner = owner;
		this.lockTimeout = lockTimeout;
		this.recordEmptyRuns = recordEmptyRuns;
	}

	@Override
	public void onBatchSuccess(UploadBatch batch, BatchResult result) {
		store.saveCheckpoint(batch.getJobName(), batch.getEndCheckpoint());
		saveBatchLog(batch, result);
		try {
			store.refreshLock(batch.getJobName(), owner, lockTimeout);
		} catch (Exception e) {
			logger.warn("上传任务[{}]续期任务锁失败", batch.getJobName(), e);
		}
	}

	@Override
	public void onBatchFailed(UploadBatch batch, BatchResult result, boolean skipped) {
		if (skipped) {
			FailedBatch failed = new FailedBatch();
			failed.setRecordId(recordId);
			failed.setJobName(batch.getJobName());
			failed.setBatchIndex(batch.getIndex());
			failed.setRowCount(batch.getSize());
			failed.setStartCheckpoint(text(batch.getStartCheckpoint()));
			failed.setEndCheckpoint(text(batch.getEndCheckpoint()));
			failed.setRows(batch.getRows());
			failed.setMessage(result.getMessage());
			store.saveFailedBatch(failed);
		}
		saveBatchLog(batch, result);
	}

	@Override
	public void onJobEnd(JobOptions options, JobResult result) {
		if (!recordEmptyRuns && result.getStatus() == JobStatus.SUCCESS && result.getTotalBatches() == 0) {
			// 秒级、分钟级任务大多数时候没有新数据，不记录空跑，避免记录表快速膨胀
			return;
		}
		UploadRecord record = new UploadRecord();
		record.setId(recordId);
		record.setJobName(result.getJobName());
		record.setStatus(result.getStatus().name());
		record.setTotalRows(result.getTotalRows());
		record.setSuccessRows(result.getSuccessRows());
		record.setFailedRows(result.getFailedRows());
		record.setTotalBatches(result.getTotalBatches());
		record.setFailedBatches(result.getFailedBatches());
		record.setStartCheckpoint(text(result.getStartCheckpoint()));
		record.setEndCheckpoint(text(result.getEndCheckpoint()));
		record.setMessage(result.getMessage());
		record.setStartTime(new Date(result.getStartTime()));
		record.setEndTime(new Date(result.getEndTime()));
		record.setCostMillis(result.getCostMillis());
		store.saveRecord(record);
	}

	private void saveBatchLog(UploadBatch batch, BatchResult result) {
		try {
			BatchLog log = new BatchLog();
			log.setRecordId(recordId);
			log.setJobName(batch.getJobName());
			log.setBatchIndex(batch.getIndex());
			log.setRowCount(batch.getSize());
			log.setSuccess(result.isSuccess());
			log.setAttempts(result.getAttempts());
			log.setStartCheckpoint(text(batch.getStartCheckpoint()));
			log.setEndCheckpoint(text(batch.getEndCheckpoint()));
			log.setMessage(result.getMessage());
			log.setResponse(result.getResponse());
			log.setCostMillis(result.getCostMillis());
			store.saveBatchLog(log);
		} catch (Exception e) {
			logger.warn("上传任务[{}]保存第{}批日志失败", batch.getJobName(), batch.getIndex(), e);
		}
	}

	private static String text(Object value) {
		return value == null ? null : Objects.toString(value);
	}
}
