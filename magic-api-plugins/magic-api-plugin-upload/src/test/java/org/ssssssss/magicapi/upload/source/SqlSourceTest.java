package org.ssssssss.magicapi.upload.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.ssssssss.magicapi.modules.db.dialect.Dialect;
import org.ssssssss.magicapi.modules.db.dialect.OracleDialect;
import org.ssssssss.magicapi.modules.db.dialect.SQLServer2005Dialect;
import org.ssssssss.magicapi.modules.db.dialect.SQLServerDialect;
import org.ssssssss.magicapi.upload.engine.*;
import org.ssssssss.magicapi.upload.service.UploadJobRunner;
import org.ssssssss.magicapi.upload.store.DatabaseType;
import org.ssssssss.magicapi.upload.store.JdbcUploadStore;
import org.ssssssss.magicapi.upload.store.TestDatabases;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlSourceTest {

	private static final int TOTAL = 10_000;

	static Stream<Arguments> databases() {
		return Stream.of(
				Arguments.of("Oracle", "Oracle", new OracleDialect(), "NUMBER(19)", "VARCHAR2(50)"),
				Arguments.of("SQLServer 2012+", "MSSQLServer", new SQLServerDialect(), "BIGINT", "NVARCHAR(50)"),
				Arguments.of("SQLServer 2005", "MSSQLServer", new SQLServer2005Dialect(), "BIGINT", "NVARCHAR(50)")
		);
	}

	private static JdbcTemplate patients(String mode, String numberType, String textType) {
		JdbcTemplate jdbc = TestDatabases.h2(mode);
		jdbc.execute("CREATE TABLE patient (id " + numberType + " PRIMARY KEY, name " + textType + ", dept " + textType + ")");
		List<Object[]> args = new ArrayList<>();
		for (int i = 1; i <= TOTAL; i++) {
			args.add(new Object[]{i, "患者" + i, i % 2 == 0 ? "内科" : "外科"});
		}
		// 打乱插入顺序，确认排序由数据来源保证
		Collections.shuffle(args, new Random(7));
		jdbc.batchUpdate("INSERT INTO patient (id, name, dept) VALUES (?, ?, ?)", args);
		return jdbc;
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("databases")
	void keysetReadsAllRowsOnceInOrder(String name, String mode, Dialect dialect, String numberType, String textType) throws Exception {
		JdbcTemplate jdbc = patients(mode, numberType, textType);
		KeysetSqlSource source = new KeysetSqlSource(jdbc, dialect, "select id, name, dept from patient", null, "id");

		List<Long> ids = new ArrayList<>();
		List<Integer> sizes = new ArrayList<>();
		Object checkpoint = null;
		while (true) {
			BatchData data = source.fetch(checkpoint, 1000);
			if (data.isEmpty()) {
				break;
			}
			sizes.add(data.getRows().size());
			for (Map<String, Object> row : data.getRows()) {
				ids.add(((Number) row.get("id")).longValue());
				// 分页时额外带出的行号列已去掉
				assertThat(row.keySet()).hasSize(3);
			}
			checkpoint = data.getNextCheckpoint();
		}

		assertThat(sizes).hasSize(10).containsOnly(1000);
		assertThat(ids).hasSize(TOTAL).doesNotHaveDuplicates().isSorted();
		assertThat(ids.get(0)).isEqualTo(1L);
		assertThat(((Number) checkpoint).longValue()).isEqualTo(TOTAL);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("databases")
	void keysetSupportsVariablesAndSavedCheckpoint(String name, String mode, Dialect dialect, String numberType, String textType) throws Exception {
		JdbcTemplate jdbc = patients(mode, numberType, textType);
		Map<String, Object> vars = new HashMap<>();
		vars.put("dept", "内科");
		// 用户写了 ORDER BY 和分号也能正常工作
		KeysetSqlSource source = new KeysetSqlSource(jdbc, dialect,
				"select id, name from patient where dept = #{dept} order by name desc;", vars, "ID");

		BatchData first = source.fetch(new BigDecimal("9000"), 1000);

		assertThat(first.getRows()).hasSize(500);
		assertThat(((Number) first.getRows().get(0).get("ID")).longValue()).isEqualTo(9002L);
		assertThat(((Number) first.getNextCheckpoint()).longValue()).isEqualTo(10_000L);
		assertThat(source.fetch(first.getNextCheckpoint(), 1000).isEmpty()).isTrue();
	}

	/**
	 * H2 1.4.200 对 SQLServer2005 方言生成的 "TOP 100 PERCENT + ROW_NUMBER()" 在 offset 大于 0 时返回错误的行数，
	 * 属于 H2 的问题；该方言在真实 SQL Server 上需现场联调验证（见插件 README）。
	 */
	static Stream<Arguments> offsetDatabases() {
		return databases().filter(args -> !(args.get()[2] instanceof SQLServer2005Dialect));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("offsetDatabases")
	void offsetReadsAllRowsAndScopesCheckpoint(String name, String mode, Dialect dialect, String numberType, String textType) throws Exception {
		JdbcTemplate jdbc = patients(mode, numberType, textType);
		Map<String, Object> vars = new HashMap<>();
		vars.put("dept", "外科");
		OffsetSqlSource source = new OffsetSqlSource(jdbc, dialect,
				"select dept, id, name from patient where dept = #{dept}", vars, "dept, id desc");

		Set<Long> ids = new LinkedHashSet<>();
		Object checkpoint = null;
		int batches = 0;
		while (true) {
			BatchData data = source.fetch(checkpoint, 1500);
			if (data.isEmpty()) {
				break;
			}
			batches++;
			data.getRows().forEach(row -> ids.add(((Number) row.get("id")).longValue()));
			checkpoint = data.getNextCheckpoint();
		}

		assertThat(batches).isEqualTo(4);
		assertThat(ids).hasSize(5000);
		assertThat(ids.iterator().next()).isEqualTo(9999L);
		assertThat(source.isIncremental()).isFalse();

		// 参数变了（例如下个月），旧断点不再生效，从头开始
		vars.put("dept", "内科");
		OffsetSqlSource other = new OffsetSqlSource(jdbc, dialect,
				"select dept, id, name from patient where dept = #{dept}", vars, "dept, id desc");
		BatchData fresh = other.fetch(checkpoint, 10);
		assertThat(((Number) fresh.getRows().get(0).get("id")).longValue()).isEqualTo(10_000L);
	}

	@Test
	void keysetFailsClearlyWhenKeyMissingOrNull() {
		JdbcTemplate jdbc = patients("Oracle", "NUMBER(19)", "VARCHAR2(50)");
		KeysetSqlSource noKey = new KeysetSqlSource(jdbc, new OracleDialect(), "select name from patient", null, "id");
		assertThatThrownBy(() -> noKey.fetch(null, 10)).hasMessageContaining("id");

		KeysetSqlSource nullKey = new KeysetSqlSource(jdbc, new OracleDialect(),
				"select id, name, cast(null as number) as visit_no from patient", null, "visit_no");
		assertThatThrownBy(() -> nullKey.fetch(null, 10)).hasMessageContaining("为空");
	}

	@Test
	void validatesColumnsAndOrderBy() {
		JdbcTemplate jdbc = TestDatabases.h2("Oracle");
		assertThatThrownBy(() -> new KeysetSqlSource(jdbc, new OracleDialect(), "select 1 from dual", null, "id; drop table x"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OffsetSqlSource(jdbc, new OracleDialect(), "select 1 from dual", null, ""))
				.hasMessageContaining("排序字段");
		assertThatThrownBy(() -> new OffsetSqlSource(jdbc, new OracleDialect(), "select 1 from dual", null, "id) union select"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(SqlSupport.qualifyOrderBy("dept , id desc")).isEqualTo("upload_t.dept, upload_t.id DESC");
	}

	@Test
	void stripsOnlyTopLevelOrderBy() {
		assertThat(SqlSupport.stripTrailingOrderBy("select * from a order by id")).isEqualTo("select * from a");
		assertThat(SqlSupport.stripTrailingOrderBy("select * from a ORDER\n  BY id desc, name"))
				.isEqualTo("select * from a");
		String sub = "select * from (select * from a order by id) t";
		assertThat(SqlSupport.stripTrailingOrderBy(sub)).isEqualTo(sub);
		String literal = "select * from a where note = ' order by x'";
		assertThat(SqlSupport.stripTrailingOrderBy(literal)).isEqualTo(literal);
		String column = "select border_by from a";
		assertThat(SqlSupport.stripTrailingOrderBy(column)).isEqualTo(column);
	}

	@Test
	void listSourceBatchesAndScopes() throws Exception {
		List<Map<String, Object>> data = new ArrayList<>();
		for (int i = 1; i <= 2500; i++) {
			Map<String, Object> row = new HashMap<>();
			row.put("id", i);
			data.add(row);
		}
		ListSource source = new ListSource(data);

		BatchData b1 = source.fetch(null, 1000);
		BatchData b2 = source.fetch(b1.getNextCheckpoint(), 1000);
		BatchData b3 = source.fetch(b2.getNextCheckpoint(), 1000);
		BatchData b4 = source.fetch(b3.getNextCheckpoint(), 1000);

		assertThat(b1.getRows()).hasSize(1000);
		assertThat(b3.getRows()).hasSize(500);
		assertThat(b3.getRows().get(0).get("id")).isEqualTo(2001);
		assertThat(b4.isEmpty()).isTrue();

		// 数据变化后，旧断点不生效
		data.remove(0);
		assertThat(new ListSource(data).fetch(b2.getNextCheckpoint(), 10).getRows().get(0).get("id")).isEqualTo(2);

		assertThatThrownBy(() -> new ListSource(Arrays.asList(1, 2))).hasMessageContaining("不是对象");
	}

	@Test
	void runnerKeepsKeysetCheckpointButResetsOffsetCheckpoint() {
		JdbcTemplate jdbc = patients("Oracle", "NUMBER(19)", "VARCHAR2(50)");
		JdbcUploadStore store = TestDatabases.store("Oracle", DatabaseType.ORACLE, new TestDatabases.MutableClock());
		UploadJobRunner runner = new UploadJobRunner(store, new BatchEngine(), Duration.ofHours(1), false);
		CountingChannel channel = new CountingChannel();

		// 增量：断点保留，第二次没有新数据
		JobOptions keysetJob = new JobOptions("visit").batchSize(3000).retryBackoff(Duration.ZERO);
		runner.run(keysetJob, new KeysetSqlSource(jdbc, new OracleDialect(), "select * from patient", null, "id"), channel);
		assertThat(channel.rows).isEqualTo(TOTAL);
		assertThat(((Number) store.loadCheckpoint("visit")).longValue()).isEqualTo(TOTAL);
		JobResult again = runner.run(keysetJob, new KeysetSqlSource(jdbc, new OracleDialect(), "select * from patient", null, "id"), channel);
		assertThat(again.getTotalRows()).isZero();

		// 非增量：读完后清除断点，下次重新全量上传
		channel.rows = 0;
		JobOptions offsetJob = new JobOptions("monthly").batchSize(3000).retryBackoff(Duration.ZERO);
		runner.run(offsetJob, new OffsetSqlSource(jdbc, new OracleDialect(), "select * from patient", null, "id"), channel);
		assertThat(channel.rows).isEqualTo(TOTAL);
		assertThat(store.loadCheckpoint("monthly")).isNull();

		// 非增量中途失败：保留断点，下次续传
		channel.rows = 0;
		channel.failAtBatch = 3;
		JobResult failed = runner.run(offsetJob.retry(0), new OffsetSqlSource(jdbc, new OracleDialect(), "select * from patient", null, "id"), channel);
		assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
		assertThat(store.loadCheckpoint("monthly")).asString().endsWith(":6000");
		channel.failAtBatch = -1;
		runner.run(offsetJob, new OffsetSqlSource(jdbc, new OracleDialect(), "select * from patient", null, "id"), channel);
		assertThat(channel.rows).isEqualTo(TOTAL);
		assertThat(store.loadCheckpoint("monthly")).isNull();
	}

	private static class CountingChannel implements UploadChannel {

		int rows;

		int failAtBatch = -1;

		@Override
		public SendResult send(UploadBatch batch) {
			if (batch.getIndex() == failAtBatch) {
				return SendResult.failure("HTTP 503");
			}
			rows += batch.getSize();
			return SendResult.success();
		}
	}
}
