package org.ssssssss.magicapi.upload.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ssssssss.magicapi.upload.engine.*;
import org.ssssssss.magicapi.upload.store.*;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class UploadJobRunnerTest {

	private JdbcUploadStore store;

	private UploadJobRunner runner;

	@BeforeEach
	void setUp() {
		store = TestDatabases.store("Oracle", DatabaseType.ORACLE, new TestDatabases.MutableClock());
		runner = new UploadJobRunner(store, new BatchEngine(millis -> {
		}), Duration.ofHours(2), false);
	}

	@Test
	void resumesFromCheckpointAfterFailure() {
		RecordingChannel channel = new RecordingChannel();
		channel.failBatches.add(4);

		JobResult first = runner.run(options(), new IdSource(10_000), channel);

		assertThat(first.getStatus()).isEqualTo(JobStatus.FAILED);
		assertThat(first.getSuccessBatches()).isEqualTo(3);
		assertThat(store.loadCheckpoint("report")).isEqualTo(3000L);

		// 对方恢复后再次执行：从第 4 批（id 3001）开始，不重复上传前 3 批
		RecordingChannel recovered = new RecordingChannel();
		JobResult second = runner.run(options(), new IdSource(10_000), recovered);

		assertThat(second.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(recovered.firstIds).first().isEqualTo(3001L);
		assertThat(second.getTotalBatches()).isEqualTo(7);
		assertThat(store.loadCheckpoint("report")).isEqualTo(10_000L);

		List<UploadRecord> records = runner.records("report", 10);
		assertThat(records).extracting(UploadRecord::getStatus).containsExactlyInAnyOrder("FAILED", "SUCCESS");
		Integer logs = store.jdbc().queryForObject("SELECT COUNT(1) FROM magic_upload_batch_log", Integer.class);
		assertThat(logs).isEqualTo(4 + 7);

		// 新数据到达后，下一次只上传新增部分
		RecordingChannel next = new RecordingChannel();
		JobResult third = runner.run(options(), new IdSource(10_500), next);
		assertThat(third.getTotalRows()).isEqualTo(500);
		assertThat(next.firstIds).containsExactly(10_001L);
	}

	@Test
	void skippedBatchesCanBeRetriedLater() {
		RecordingChannel channel = new RecordingChannel();
		channel.failBatches.add(3);
		channel.failBatches.add(8);

		JobResult result = runner.run(options().failurePolicy(FailurePolicy.SKIP), new IdSource(10_000), channel);

		assertThat(result.getStatus()).isEqualTo(JobStatus.PARTIAL);
		assertThat(result.getFailedBatches()).isEqualTo(2);
		assertThat(store.loadCheckpoint("report")).isEqualTo(10_000L);
		assertThat(runner.failedBatches("report", 0)).extracting(FailedBatch::getBatchIndex).containsExactly(3, 8);

		// 第一次补传：第 8 批仍然失败
		RecordingChannel partly = new RecordingChannel();
		partly.failBatches.add(8);
		RetryResult retry1 = runner.retryFailed(options(), partly, 0);
		assertThat(retry1.getSuccessBatches()).isEqualTo(1);
		assertThat(retry1.getFailedBatches()).isEqualTo(1);
		assertThat(partly.firstIds).containsExactly(2001L);
		List<FailedBatch> stillPending = runner.failedBatches("report", 0);
		assertThat(stillPending).extracting(FailedBatch::getBatchIndex).containsExactly(8);
		assertThat(stillPending.get(0).getRetryCount()).isEqualTo(1);

		// 第二次补传全部成功，发送的是当时保存的原始数据
		RecordingChannel ok = new RecordingChannel();
		RetryResult retry2 = runner.retryFailed(options(), ok, 0);
		assertThat(retry2.isSuccess()).isTrue();
		assertThat(ok.firstIds).containsExactly(7001L);
		assertThat(ok.sizes).containsExactly(1000);
		assertThat(runner.failedBatches("report", 0)).isEmpty();

		// 没有待补传批次时也返回成功
		assertThat(runner.retryFailed(options(), ok, 0).getTotalBatches()).isZero();
	}

	@Test
	void sameJobDoesNotRunConcurrently() {
		assertThat(store.tryLock("report", "other-instance", Duration.ofHours(1))).isTrue();

		RecordingChannel channel = new RecordingChannel();
		JobResult result = runner.run(options(), new IdSource(100), channel);

		assertThat(result.getStatus()).isEqualTo(JobStatus.SKIPPED);
		assertThat(channel.firstIds).isEmpty();

		store.unlock("report", "other-instance");
		assertThat(runner.run(options(), new IdSource(100), channel).getStatus()).isEqualTo(JobStatus.SUCCESS);
		// 执行结束后锁已释放
		assertThat(store.tryLock("report", "other-instance", Duration.ofHours(1))).isTrue();
	}

	@Test
	void emptyRunsAreNotRecordedByDefault() {
		runner.run(options(), new IdSource(0), new RecordingChannel());
		assertThat(runner.records("report", 10)).isEmpty();

		UploadJobRunner recording = new UploadJobRunner(store, new BatchEngine(), Duration.ofHours(2), true);
		recording.run(options(), new IdSource(0), new RecordingChannel());
		assertThat(runner.records("report", 10)).extracting(UploadRecord::getStatus).containsExactly("SUCCESS");
	}

	@Test
	void extraListenerErrorsDoNotAffectUpload() {
		JobListener broken = new JobListener() {
			@Override
			public void onBatchSuccess(UploadBatch batch, BatchResult result) {
				throw new IllegalStateException("webhook down");
			}
		};

		JobResult result = runner.run(options(), new IdSource(3000), new RecordingChannel(), Collections.singletonList(broken));

		assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(store.loadCheckpoint("report")).isEqualTo(3000L);
	}

	@Test
	void resetCheckpointStartsOver() {
		runner.run(options(), new IdSource(2000), new RecordingChannel());
		assertThat(runner.checkpoint("report")).isEqualTo(2000L);

		runner.resetCheckpoint("report");
		RecordingChannel channel = new RecordingChannel();
		runner.run(options(), new IdSource(2000), channel);
		assertThat(channel.firstIds).containsExactly(1L, 1001L);
	}

	private static JobOptions options() {
		return new JobOptions("report").batchSize(1000).retry(1).retryBackoff(Duration.ZERO);
	}

	/**
	 * id 从 1 到 total，断点为 Long
	 */
	private static class IdSource implements BatchSource {

		private final long total;

		IdSource(long total) {
			this.total = total;
		}

		@Override
		public BatchData fetch(Object checkpoint, int batchSize) {
			long last = checkpoint == null ? 0 : ((Number) checkpoint).longValue();
			if (last >= total) {
				return null;
			}
			long to = Math.min(total, last + batchSize);
			List<Map<String, Object>> rows = new ArrayList<>();
			for (long id = last + 1; id <= to; id++) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("id", id);
				row.put("name", "患者" + id);
				rows.add(row);
			}
			return new BatchData(rows, to);
		}
	}

	private static class RecordingChannel implements UploadChannel {

		private final Set<Integer> failBatches = new HashSet<>();

		private final List<Long> firstIds = new ArrayList<>();

		private final List<Integer> sizes = new ArrayList<>();

		@Override
		public SendResult send(UploadBatch batch) {
			if (failBatches.contains(batch.getIndex())) {
				return SendResult.failure("HTTP 503");
			}
			firstIds.add(((Number) batch.getRows().get(0).get("id")).longValue());
			sizes.add(batch.getSize());
			return SendResult.success("{\"code\":0}");
		}
	}
}
