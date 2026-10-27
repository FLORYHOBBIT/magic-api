package org.ssssssss.magicapi.upload.engine;

/**
 * 上传任务执行过程的回调
 * <p>
 * 存储层通过它保存断点、记录与失败批次；告警层通过它发送通知。
 * 回调中抛出的异常：onBatchSuccess 中的异常会中止任务（断点没保存成功时不能继续，否则会重复上传），
 * 其余回调的异常只记录日志，不影响上传。
 */
public interface JobListener {

	JobListener NONE = new JobListener() {
	};

	/**
	 * 任务开始
	 */
	default void onJobStart(JobOptions options, Object startCheckpoint) throws Exception {
	}

	/**
	 * 某批上传成功（含重试后成功），实现方应在此保存断点
	 */
	default void onBatchSuccess(UploadBatch batch, BatchResult result) throws Exception {
	}

	/**
	 * 某批重试后仍失败
	 *
	 * @param skipped true 表示按 SKIP 策略跳过，任务继续；false 表示任务将停止
	 */
	default void onBatchFailed(UploadBatch batch, BatchResult result, boolean skipped) throws Exception {
	}

	/**
	 * 任务结束（无论成功或失败）
	 */
	default void onJobEnd(JobOptions options, JobResult result) throws Exception {
	}
}
