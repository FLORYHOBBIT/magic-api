package org.ssssssss.magicapi.upload.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchEngineTest {

	private final List<Long> waits = new ArrayList<>();

	private final BatchEngine engine = new BatchEngine(waits::add);

	@Test
	void splitsIntoBatches() {
		FakeSource source = new FakeSource(10_000);
		FakeChannel channel = new FakeChannel();

		JobResult result = engine.run(options(), source, channel, null, null);

		assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(result.getTotalBatches()).isEqualTo(10);
		assertThat(result.getTotalRows()).isEqualTo(10_000);
		assertThat(result.getSuccessRows()).isEqualTo(10_000);
		assertThat(result.getEndCheckpoint()).isEqualTo(10_000);
		assertThat(channel.sentSizes).containsOnly(1000);
		assertThat(channel.firstIds).containsExactly(1, 1001, 2001, 3001, 4001, 5001, 6001, 7001, 8001, 9001);
	}

	@Test
	void lastBatchNotFullEndsWithoutExtraFetch() {
		FakeSource source = new FakeSource(2_500);
		FakeChannel channel = new FakeChannel();

		JobResult result = engine.run(options(), source, channel, null, null);

		assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(channel.sentSizes).containsExactly(1000, 1000, 500);
		assertThat(source.fetchCount.get()).isEqualTo(3);
	}

	@Test
	void emptySourceSucceedsWithNoBatches() {
		JobResult result = engine.run(options(), new FakeSource(0), new FakeChannel(), null, null);

		assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(result.getTotalBatches()).isZero();
		assertThat(result.getEndCheckpoint()).isNull();
	}

	@Test
	void retriesWithExponentialBackoffThenSucceeds() {
		FakeChannel channel = new FakeChannel();
		channel.failTimes.put(3, 2);

		JobResult result = engine.run(options().retry(3).retryBackoff(Duration.ofSeconds(1)),
				new FakeSource(5_000), channel, null, null);

		assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(result.getBatches().get(2).getAttempts()).isEqualTo(3);
		assertThat(waits).containsExactly(1000L, 2000L);
	}

	@Test
	void stopPolicyHaltsAndResumesFromFailedBatch() {
		FakeChannel channel = new FakeChannel();
		channel.alwaysFail.add(3);
		RecordingListener listener = new RecordingListener();

		JobResult first = engine.run(options().retry(2).failurePolicy(FailurePolicy.STOP),
				new FakeSource(10_000), channel, null, listener);

		assertThat(first.getStatus()).isEqualTo(JobStatus.FAILED);
		assertThat(first.getTotalBatches()).isEqualTo(3);
		assertThat(first.getSuccessBatches()).isEqualTo(2);
		assertThat(first.getBatches().get(2).getAttempts()).isEqualTo(3);
		assertThat(first.getEndCheckpoint()).isEqualTo(2000);
		assertThat(listener.savedCheckpoints).containsExactly(1000, 2000);
		assertThat(listener.failed).containsExactly("3:stop");

		// 对方恢复后，从断点继续，不会重复上传前两批
		FakeChannel recovered = new FakeChannel();
		JobResult second = engine.run(options(), new FakeSource(10_000), recovered, first.getEndCheckpoint(), null);

		assertThat(second.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(second.getTotalBatches()).isEqualTo(8);
		assertThat(recovered.firstIds.get(0)).isEqualTo(2001);
		assertThat(second.getEndCheckpoint()).isEqualTo(10_000);
	}

	@Test
	void skipPolicyRecordsFailedBatchAndContinues() {
		FakeChannel channel = new FakeChannel();
		channel.alwaysFail.add(3);
		RecordingListener listener = new RecordingListener();

		JobResult result = engine.run(options().retry(1).failurePolicy(FailurePolicy.SKIP),
				new FakeSource(10_000), channel, null, listener);

		assertThat(result.getStatus()).isEqualTo(JobStatus.PARTIAL);
		assertThat(result.getTotalBatches()).isEqualTo(10);
		assertThat(result.getFailedBatches()).isEqualTo(1);
		assertThat(result.getFailedRows()).isEqualTo(1000);
		assertThat(result.getEndCheckpoint()).isEqualTo(10_000);
		assertThat(listener.failed).containsExactly("3:skip");
		// 失败批次的起止断点可用于之后补传
		assertThat(listener.failedRanges).containsExactly("2000-3000");
	}

	@Test
	void rejectedResultIsNotRetried() {
		FakeChannel channel = new FakeChannel();
		channel.rejected.add(1);

		JobResult result = engine.run(options().retry(5), new FakeSource(100), channel, null, null);

		assertThat(result.getStatus()).isEqualTo(JobStatus.FAILED);
		assertThat(result.getBatches().get(0).getAttempts()).isEqualTo(1);
		assertThat(waits).isEmpty();
	}

	@Test
	void channelExceptionIsRetried() {
		AtomicInteger calls = new AtomicInteger();
		UploadChannel channel = batch -> {
			if (calls.incrementAndGet() == 1) {
				throw new java.net.SocketTimeoutException("Read timed out");
			}
			return SendResult.success();
		};

		JobResult result = engine.run(options().retry(1), new FakeSource(10), channel, null, null);

		assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(result.getBatches().get(0).getAttempts()).isEqualTo(2);
	}

	@Test
	void failsWhenCheckpointDoesNotAdvance() {
		BatchSource stuck = (checkpoint, size) -> new BatchData(rows(1, 1), 1);

		JobResult result = engine.run(options(), stuck, new FakeChannel(), 1, null);

		assertThat(result.getStatus()).isEqualTo(JobStatus.FAILED);
		assertThat(result.getMessage()).contains("断点没有推进");
	}

	@Test
	void stopsWhenCheckpointCannotBeSaved() {
		JobListener listener = new JobListener() {
			@Override
			public void onBatchSuccess(UploadBatch batch, BatchResult result) {
				if (batch.getIndex() == 2) {
					throw new IllegalStateException("db down");
				}
			}
		};

		JobResult result = engine.run(options(), new FakeSource(5_000), new FakeChannel(), null, listener);

		assertThat(result.getStatus()).isEqualTo(JobStatus.FAILED);
		assertThat(result.getEndCheckpoint()).isEqualTo(1000);
		assertThat(result.getMessage()).contains("保存断点失败");
	}

	@Test
	void stopsWhenFetchFails() {
		BatchSource broken = (checkpoint, size) -> {
			throw new IllegalStateException("ORA-00942: table or view does not exist");
		};

		JobResult result = engine.run(options(), broken, new FakeChannel(), null, null);

		assertThat(result.getStatus()).isEqualTo(JobStatus.FAILED);
		assertThat(result.getMessage()).contains("ORA-00942");
	}

	@Test
	void respectsMaxBatches() {
		FakeChannel channel = new FakeChannel();

		JobResult result = engine.run(options().maxBatches(3), new FakeSource(10_000), channel, null, null);

		assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(result.getTotalBatches()).isEqualTo(3);
		assertThat(result.getEndCheckpoint()).isEqualTo(3000);
	}

	@Test
	void listenerErrorsOutsideCheckpointDoNotBreakUpload() {
		JobListener listener = new JobListener() {
			@Override
			public void onJobStart(JobOptions options, Object startCheckpoint) {
				throw new IllegalStateException("boom");
			}

			@Override
			public void onJobEnd(JobOptions options, JobResult result) {
				throw new IllegalStateException("boom");
			}
		};

		JobResult result = engine.run(options(), new FakeSource(1_500), new FakeChannel(), null, listener);

		assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCESS);
	}

	@Test
	void backoffDoublesAndIsCapped() {
		JobOptions options = options().retryBackoff(Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(5));

		assertThat(options.backoffMillis(1)).isEqualTo(1000);
		assertThat(options.backoffMillis(2)).isEqualTo(2000);
		assertThat(options.backoffMillis(3)).isEqualTo(4000);
		assertThat(options.backoffMillis(4)).isEqualTo(5000);
		assertThat(options.backoffMillis(10)).isEqualTo(5000);
		assertThat(options().retryBackoff(Duration.ZERO).backoffMillis(3)).isZero();
	}

	@Test
	void parsesFailurePolicy() {
		assertThat(FailurePolicy.of(null)).isEqualTo(FailurePolicy.STOP);
		assertThat(FailurePolicy.of(" skip ")).isEqualTo(FailurePolicy.SKIP);
		assertThat(FailurePolicy.of("STOP")).isEqualTo(FailurePolicy.STOP);
		assertThatThrownBy(() -> FailurePolicy.of("ignore")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void validatesOptions() {
		assertThatThrownBy(() -> new JobOptions(" ")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> options().batchSize(0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> options().retry(-1)).isInstanceOf(IllegalArgumentException.class);
	}

	private static JobOptions options() {
		return new JobOptions("report").batchSize(1000).retry(0).retryBackoff(Duration.ZERO);
	}

	private static List<Map<String, Object>> rows(int fromId, int toId) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int id = fromId; id <= toId; id++) {
			Map<String, Object> row = new HashMap<>();
			row.put("id", id);
			rows.add(row);
		}
		return rows;
	}

	/**
	 * id 从 1 到 total 的递增数据，断点为最后一行的 id
	 */
	private static class FakeSource implements BatchSource {

		private final int total;

		private final AtomicInteger fetchCount = new AtomicInteger();

		FakeSource(int total) {
			this.total = total;
		}

		@Override
		public BatchData fetch(Object checkpoint, int batchSize) {
			fetchCount.incrementAndGet();
			int last = checkpoint == null ? 0 : (Integer) checkpoint;
			if (last >= total) {
				return null;
			}
			int to = Math.min(total, last + batchSize);
			return new BatchData(rows(last + 1, to), to);
		}
	}

	private static class FakeChannel implements UploadChannel {

		/**
		 * 批次序号 -> 前几次发送失败
		 */
		private final Map<Integer, Integer> failTimes = new HashMap<>();

		private final Set<Integer> alwaysFail = new HashSet<>();

		private final Set<Integer> rejected = new HashSet<>();

		private final List<Integer> sentSizes = new ArrayList<>();

		private final List<Object> firstIds = new ArrayList<>();

		@Override
		public SendResult send(UploadBatch batch) {
			if (rejected.contains(batch.getIndex())) {
				return SendResult.rejected("数据校验不通过", "{\"code\":400}");
			}
			if (alwaysFail.contains(batch.getIndex())) {
				return SendResult.failure("HTTP 503");
			}
			Integer times = failTimes.get(batch.getIndex());
			if (times != null && batch.getAttempt() <= times) {
				return SendResult.failure("HTTP 502");
			}
			sentSizes.add(batch.getSize());
			firstIds.add(batch.getRows().get(0).get("id"));
			return SendResult.success("{\"code\":0}");
		}
	}

	private static class RecordingListener implements JobListener {

		private final List<Object> savedCheckpoints = new ArrayList<>();

		private final List<String> failed = new ArrayList<>();

		private final List<String> failedRanges = new ArrayList<>();

		@Override
		public void onBatchSuccess(UploadBatch batch, BatchResult result) {
			savedCheckpoints.add(batch.getEndCheckpoint());
		}

		@Override
		public void onBatchFailed(UploadBatch batch, BatchResult result, boolean skipped) {
			failed.add(batch.getIndex() + ":" + (skipped ? "skip" : "stop"));
			failedRanges.add(batch.getStartCheckpoint() + "-" + batch.getEndCheckpoint());
		}
	}
}
