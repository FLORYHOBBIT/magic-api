package org.ssssssss.magicapi.upload.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.magicapi.upload.engine.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 依次调用多个监听器
 * <p>
 * 主监听器（存储）的异常原样抛给引擎，由引擎决定是否停止任务；
 * 其他监听器（告警、自定义回调）的异常只记录日志，不影响上传。
 */
class CompositeJobListener implements JobListener {

	private static final Logger logger = LoggerFactory.getLogger(CompositeJobListener.class);

	private final JobListener primary;

	private final List<JobListener> others = new ArrayList<>();

	CompositeJobListener(JobListener primary, List<JobListener> others) {
		this.primary = primary;
		if (others != null) {
			for (JobListener other : others) {
				if (other != null) {
					this.others.add(other);
				}
			}
		}
	}

	@Override
	public void onJobStart(JobOptions options, Object startCheckpoint) throws Exception {
		primary.onJobStart(options, startCheckpoint);
		for (JobListener listener : others) {
			quietly(listener, l -> l.onJobStart(options, startCheckpoint));
		}
	}

	@Override
	public void onBatchSuccess(UploadBatch batch, BatchResult result) throws Exception {
		primary.onBatchSuccess(batch, result);
		for (JobListener listener : others) {
			quietly(listener, l -> l.onBatchSuccess(batch, result));
		}
	}

	@Override
	public void onBatchFailed(UploadBatch batch, BatchResult result, boolean skipped) throws Exception {
		primary.onBatchFailed(batch, result, skipped);
		for (JobListener listener : others) {
			quietly(listener, l -> l.onBatchFailed(batch, result, skipped));
		}
	}

	@Override
	public void onJobEnd(JobOptions options, JobResult result) throws Exception {
		try {
			primary.onJobEnd(options, result);
		} finally {
			for (JobListener listener : others) {
				quietly(listener, l -> l.onJobEnd(options, result));
			}
		}
	}

	private static void quietly(JobListener listener, ListenerCall call) {
		try {
			call.call(listener);
		} catch (Exception e) {
			logger.warn("上传回调 {} 出错，不影响上传", listener.getClass().getName(), e);
		}
	}

	@FunctionalInterface
	private interface ListenerCall {
		void call(JobListener listener) throws Exception;
	}
}
