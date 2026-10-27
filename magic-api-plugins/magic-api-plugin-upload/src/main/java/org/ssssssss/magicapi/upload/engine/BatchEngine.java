package org.ssssssss.magicapi.upload.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 批量上传引擎
 * <p>
 * 只负责"读一批、发一批、按策略处理失败、推进断点"，不关心数据存在哪里、发到哪里。
 * 断点保存、上传记录、告警都通过 {@link JobListener} 接入。
 */
public class BatchEngine {

	private static final Logger logger = LoggerFactory.getLogger(BatchEngine.class);

	private final Sleeper sleeper;

	public BatchEngine() {
		this(Sleeper.DEFAULT);
	}

	public BatchEngine(Sleeper sleeper) {
		this.sleeper = sleeper == null ? Sleeper.DEFAULT : sleeper;
	}

	/**
	 * 执行上传任务
	 *
	 * @param options         任务参数
	 * @param source          数据来源
	 * @param channel         上传通道
	 * @param startCheckpoint 起始断点，首次执行为 null
	 * @param listener        执行回调，可为 null
	 */
	public JobResult run(JobOptions options, BatchSource source, UploadChannel channel, Object startCheckpoint, JobListener listener) {
		Objects.requireNonNull(options, "options");
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(channel, "channel");
		JobListener jobListener = listener == null ? JobListener.NONE : listener;
		String jobName = options.getJobName();

		JobResult result = new JobResult(jobName);
		result.setStartTime(System.currentTimeMillis());
		result.setStartCheckpoint(startCheckpoint);
		result.setEndCheckpoint(startCheckpoint);

		safeCall(() -> jobListener.onJobStart(options, startCheckpoint), jobName, "onJobStart");

		Object checkpoint = startCheckpoint;
		int index = 0;
		try {
			while (true) {
				if (options.getMaxBatches() > 0 && index >= options.getMaxBatches()) {
					result.setMessage("已达到本次最大批次数 " + options.getMaxBatches() + "，剩余数据下次继续");
					break;
				}
				BatchData data;
				try {
					data = source.fetch(checkpoint, options.getBatchSize());
				} catch (Exception e) {
					logger.error("上传任务[{}]读取数据失败，断点:{}", jobName, checkpoint, e);
					result.setStatus(JobStatus.FAILED);
					result.setMessage("读取数据失败: " + describe(e));
					return finish(options, result, jobListener);
				}
				if (data == null || data.isEmpty()) {
					result.setExhausted(true);
					break;
				}
				Object next = data.getNextCheckpoint();
				if (next == null || Objects.equals(next, checkpoint)) {
					result.setStatus(JobStatus.FAILED);
					result.setMessage("数据来源的断点没有推进（当前: " + checkpoint + "，下一批: " + next + "），请检查排序字段是否唯一且递增");
					return finish(options, result, jobListener);
				}
				index++;
				UploadBatch batch = new UploadBatch(jobName, index, data.getRows(), checkpoint, next);
				BatchResult batchResult = sendBatch(options, channel, batch);
				result.addBatch(batchResult);

				if (batchResult.isSuccess()) {
					try {
						jobListener.onBatchSuccess(batch, batchResult);
					} catch (Exception e) {
						// 断点没保存成功就继续，下次会重复上传这一批之后的数据，所以必须停止
						logger.error("上传任务[{}]第{}批已上传，但保存断点失败，任务停止", jobName, index, e);
						result.setStatus(JobStatus.FAILED);
						result.setMessage("第" + index + "批已上传，但保存断点失败: " + describe(e));
						return finish(options, result, jobListener);
					}
					checkpoint = next;
					result.setEndCheckpoint(checkpoint);
				} else if (options.getFailurePolicy() == FailurePolicy.SKIP) {
					logger.warn("上传任务[{}]第{}批失败已跳过: {}", jobName, index, batchResult.getMessage());
					try {
						jobListener.onBatchFailed(batch, batchResult, true);
					} catch (Exception e) {
						// 失败批次没记录下来就跳过，这批数据会丢失，所以必须停止
						logger.error("上传任务[{}]第{}批失败，且记录失败批次出错，任务停止", jobName, index, e);
						result.setStatus(JobStatus.FAILED);
						result.setMessage("第" + index + "批失败，且记录失败批次出错: " + describe(e));
						return finish(options, result, jobListener);
					}
					checkpoint = next;
					result.setEndCheckpoint(checkpoint);
				} else {
					logger.error("上传任务[{}]第{}批失败，任务停止，下次从这一批继续: {}", jobName, index, batchResult.getMessage());
					safeCall(() -> jobListener.onBatchFailed(batch, batchResult, false), jobName, "onBatchFailed");
					result.setStatus(JobStatus.FAILED);
					result.setMessage("第" + index + "批失败: " + batchResult.getMessage());
					return finish(options, result, jobListener);
				}

				if (data.getRows().size() < options.getBatchSize()) {
					// 不满一批说明已经是最后一批，省掉一次空查询
					result.setExhausted(true);
					break;
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			result.setStatus(JobStatus.FAILED);
			result.setMessage("任务被中断");
			return finish(options, result, jobListener);
		}
		result.setStatus(result.getFailedBatches() > 0 ? JobStatus.PARTIAL : JobStatus.SUCCESS);
		return finish(options, result, jobListener);
	}

	/**
	 * 发送单个批次，失败时按任务参数重试
	 */
	public BatchResult sendBatch(JobOptions options, UploadChannel channel, UploadBatch batch) throws InterruptedException {
		long start = System.currentTimeMillis();
		int maxAttempts = options.getRetry() + 1;
		SendResult last = null;
		int attempt = 0;
		while (attempt < maxAttempts) {
			attempt++;
			batch.setAttempt(attempt);
			try {
				last = channel.send(batch);
				if (last == null) {
					last = SendResult.failure("上传通道没有返回结果");
				}
			} catch (InterruptedException e) {
				throw e;
			} catch (Exception e) {
				logger.warn("上传任务[{}]第{}批第{}次发送异常: {}", batch.getJobName(), batch.getIndex(), attempt, describe(e));
				last = SendResult.failure(describe(e));
			}
			if (last.isSuccess() || !last.isRetryable()) {
				break;
			}
			if (attempt < maxAttempts) {
				long wait = options.backoffMillis(attempt);
				logger.info("上传任务[{}]第{}批第{}次发送失败，{}ms 后重试: {}", batch.getJobName(), batch.getIndex(), attempt, wait, last.getMessage());
				sleeper.sleep(wait);
			}
		}
		return new BatchResult(batch.getIndex(), batch.getSize(), last.isSuccess(), attempt,
				last.getMessage(), last.getResponse(), System.currentTimeMillis() - start);
	}

	private JobResult finish(JobOptions options, JobResult result, JobListener listener) {
		result.setEndTime(System.currentTimeMillis());
		safeCall(() -> listener.onJobEnd(options, result), options.getJobName(), "onJobEnd");
		logger.info("上传任务结束: {}", result);
		return result;
	}

	private void safeCall(ThrowingRunnable runnable, String jobName, String stage) {
		try {
			runnable.run();
		} catch (Exception e) {
			logger.warn("上传任务[{}]回调 {} 出错，不影响上传", jobName, stage, e);
		}
	}

	private static String describe(Throwable e) {
		String message = e.getMessage();
		return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
