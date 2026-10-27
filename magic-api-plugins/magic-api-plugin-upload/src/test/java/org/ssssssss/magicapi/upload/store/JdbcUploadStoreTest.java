package org.ssssssss.magicapi.upload.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcUploadStoreTest {

	static Stream<Arguments> databases() {
		return Stream.of(
				Arguments.of("H2 自动建表", null, DatabaseType.H2),
				Arguments.of("Oracle 建表语句", "Oracle", DatabaseType.ORACLE),
				Arguments.of("SQLServer 建表语句", "MSSQLServer", DatabaseType.SQLSERVER)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("databases")
	void checkpointSaveLoadReset(String name, String mode, DatabaseType ddl) {
		JdbcUploadStore store = TestDatabases.store(mode, ddl, new TestDatabases.MutableClock());

		assertThat(store.loadCheckpoint("report")).isNull();

		store.saveCheckpoint("report", 1000L);
		assertThat(store.loadCheckpoint("report")).isEqualTo(1000L);

		store.saveCheckpoint("report", new BigDecimal("2000"));
		assertThat(store.loadCheckpoint("report")).isEqualTo(new BigDecimal("2000"));

		Timestamp ts = Timestamp.valueOf("2026-10-27 18:00:00.5");
		store.saveCheckpoint("visit", ts);
		assertThat(store.loadCheckpoint("visit")).isEqualTo(ts);

		store.saveCheckpoint("order", "ZY0001");
		assertThat(store.loadCheckpoint("order")).isEqualTo("ZY0001");

		store.resetCheckpoint("report");
		assertThat(store.loadCheckpoint("report")).isNull();
		assertThat(store.loadCheckpoint("visit")).isEqualTo(ts);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("databases")
	void lockIsExclusiveAndExpires(String name, String mode, DatabaseType ddl) {
		TestDatabases.MutableClock clock = new TestDatabases.MutableClock();
		JdbcUploadStore store = TestDatabases.store(mode, ddl, clock);
		Duration timeout = Duration.ofMinutes(10);

		assertThat(store.tryLock("report", "A", timeout)).isTrue();
		assertThat(store.tryLock("report", "B", timeout)).isFalse();
		// 同一 owner 可以重入
		assertThat(store.tryLock("report", "A", timeout)).isTrue();
		// 不同任务互不影响
		assertThat(store.tryLock("visit", "B", timeout)).isTrue();

		// 续期后 9 分钟仍未过期
		clock.advance(Duration.ofMinutes(5));
		store.refreshLock("report", "A", timeout);
		clock.advance(Duration.ofMinutes(9));
		assertThat(store.tryLock("report", "B", timeout)).isFalse();

		// 过期后其他 owner 可以接管（进程崩溃没释放锁的情况）
		clock.advance(Duration.ofMinutes(2));
		assertThat(store.tryLock("report", "B", timeout)).isTrue();
		assertThat(store.tryLock("report", "A", timeout)).isFalse();

		// 只能释放自己的锁
		store.unlock("report", "A");
		assertThat(store.tryLock("report", "C", timeout)).isFalse();
		store.unlock("report", "B");
		assertThat(store.tryLock("report", "C", timeout)).isTrue();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("databases")
	void failedBatchesKeepDataForRetry(String name, String mode, DatabaseType ddl) {
		TestDatabases.MutableClock clock = new TestDatabases.MutableClock();
		JdbcUploadStore store = TestDatabases.store(mode, ddl, clock);

		store.saveFailedBatch(failed("report", 3, 2000, 3000, 1000));
		clock.advance(Duration.ofSeconds(1));
		store.saveFailedBatch(failed("report", 7, 6000, 7000, 1000));
		store.saveFailedBatch(failed("visit", 1, 0, 10, 10));

		List<FailedBatch> pending = store.listPendingFailedBatches("report", 0);
		assertThat(pending).extracting(FailedBatch::getBatchIndex).containsExactly(3, 7);
		FailedBatch first = pending.get(0);
		assertThat(first.getRows()).hasSize(1000);
		assertThat(first.getRows().get(0).get("id")).isEqualTo(2001);
		assertThat(first.getRows().get(0).get("name")).isEqualTo("患者2001");
		assertThat(first.getStartCheckpoint()).isEqualTo("2000");
		assertThat(store.listPendingFailedBatches("report", 1)).hasSize(1);

		store.updateFailedBatch(first.getId(), false, "HTTP 503");
		assertThat(store.listPendingFailedBatches("report", 0).get(0).getRetryCount()).isEqualTo(1);

		store.updateFailedBatch(first.getId(), true, "补传成功");
		assertThat(store.listPendingFailedBatches("report", 0)).extracting(FailedBatch::getBatchIndex).containsExactly(7);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("databases")
	void recordsAndBatchLogs(String name, String mode, DatabaseType ddl) {
		JdbcUploadStore store = TestDatabases.store(mode, ddl, new TestDatabases.MutableClock());

		for (int i = 1; i <= 3; i++) {
			UploadRecord r = new UploadRecord();
			r.setJobName("report");
			r.setStatus("SUCCESS");
			r.setTotalRows(i * 100);
			r.setStartTime(new Date(1_000_000L * i));
			r.setEndTime(new Date(1_000_000L * i + 500));
			r.setCostMillis(500);
			r.setMessage(repeat("长", 1500));
			store.saveRecord(r);
		}
		List<UploadRecord> records = store.listRecords("report", 2);
		assertThat(records).extracting(UploadRecord::getTotalRows).containsExactly(300L, 200L);
		assertThat(records.get(0).getMessage()).hasSize(JdbcUploadStore.MESSAGE_MAX);

		BatchLog log = new BatchLog();
		log.setJobName("report");
		log.setRecordId(records.get(0).getId());
		log.setBatchIndex(1);
		log.setRowCount(1000);
		log.setSuccess(true);
		log.setAttempts(2);
		log.setResponse("{\"code\":0,\"receipt\":\"R20261027\"}");
		store.saveBatchLog(log);
		Integer count = store.jdbc().queryForObject("SELECT COUNT(1) FROM magic_upload_batch_log WHERE success_flag = 1", Integer.class);
		assertThat(count).isEqualTo(1);
	}

	@Test
	void autoCreateIsIdempotentAndSkippedWhenDisabled() {
		org.springframework.jdbc.core.JdbcTemplate jdbc = TestDatabases.h2(null);
		new JdbcUploadStore(() -> jdbc, "magic_upload_", true).saveCheckpoint("a", 1L);
		// 第二个实例再次启动，不会因表已存在而失败
		JdbcUploadStore again = new JdbcUploadStore(() -> jdbc, "magic_upload_", true);
		assertThat(again.loadCheckpoint("a")).isEqualTo(1L);
		assertThat(again.getDatabaseType()).isEqualTo(DatabaseType.H2);

		org.springframework.jdbc.core.JdbcTemplate empty = TestDatabases.h2(null);
		JdbcUploadStore noInit = new JdbcUploadStore(() -> empty, "magic_upload_", false);
		assertThatThrownBy(() -> noInit.loadCheckpoint("a")).isInstanceOf(org.springframework.dao.DataAccessException.class);
	}

	@Test
	void missingDataSourceGivesClearError() {
		JdbcUploadStore store = new JdbcUploadStore(() -> null, "magic_upload_", true);
		assertThatThrownBy(() -> store.loadCheckpoint("a"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("magic-api.upload.datasource");
	}

	@Test
	void rejectsUnsafePrefix() {
		assertThatThrownBy(() -> new UploadSchema("x; drop table y;--")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new UploadSchema("1abc")).isInstanceOf(IllegalArgumentException.class);
		assertThat(new UploadSchema("his_up_").checkpointTable()).isEqualTo("his_up_checkpoint");
	}

	@Test
	void oracleIdentifiersFitIn30Chars() {
		UploadSchema schema = new UploadSchema("magic_upload_");
		for (String table : schema.tables()) {
			assertThat(table.length()).isLessThanOrEqualTo(30);
			for (String sql : schema.ddl(table, DatabaseType.ORACLE)) {
				if (sql.startsWith("CREATE INDEX ")) {
					String indexName = sql.substring("CREATE INDEX ".length(), sql.indexOf(' ', "CREATE INDEX ".length()));
					assertThat(indexName.length()).isLessThanOrEqualTo(30);
				}
			}
		}
	}

	@Test
	void rejectsOverlongCheckpoint() {
		JdbcUploadStore store = TestDatabases.store(null, DatabaseType.H2, new TestDatabases.MutableClock());
		assertThatThrownBy(() -> store.saveCheckpoint("a", repeat("x", 600))).isInstanceOf(IllegalArgumentException.class);
	}

	private static FailedBatch failed(String job, int index, int start, int end, int size) {
		FailedBatch f = new FailedBatch();
		f.setJobName(job);
		f.setBatchIndex(index);
		f.setRowCount(size);
		f.setStartCheckpoint(String.valueOf(start));
		f.setEndCheckpoint(String.valueOf(end));
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int id = start + 1; id <= start + size; id++) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", id);
			row.put("name", "患者" + id);
			rows.add(row);
		}
		f.setRows(rows);
		f.setMessage("HTTP 503");
		return f;
	}

	private static String repeat(String s, int times) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < times; i++) {
			sb.append(s);
		}
		return sb.toString();
	}
}
