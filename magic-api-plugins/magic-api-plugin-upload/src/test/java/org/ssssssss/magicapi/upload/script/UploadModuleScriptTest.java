package org.ssssssss.magicapi.upload.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.ssssssss.magicapi.datasource.model.MagicDynamicDataSource;
import org.ssssssss.magicapi.modules.db.BoundSql;
import org.ssssssss.magicapi.modules.db.dialect.Dialect;
import org.ssssssss.magicapi.modules.db.dialect.DialectAdapter;
import org.ssssssss.magicapi.upload.channel.ChannelFactory;
import org.ssssssss.magicapi.upload.engine.BatchEngine;
import org.ssssssss.magicapi.upload.engine.JobResult;
import org.ssssssss.magicapi.upload.engine.JobStatus;
import org.ssssssss.magicapi.upload.service.RetryResult;
import org.ssssssss.magicapi.upload.service.UploadJobRunner;
import org.ssssssss.magicapi.upload.store.JdbcUploadStore;
import org.ssssssss.magicapi.upload.store.TestDatabases;
import org.ssssssss.magicapi.upload.store.UploadRecord;
import org.ssssssss.magicapi.upload.support.TestHttpServer;
import org.ssssssss.magicapi.upload.support.TestHttpServer.Response;
import org.ssssssss.magicapi.utils.ScriptManager;
import org.ssssssss.script.MagicResourceLoader;
import org.ssssssss.script.MagicScriptContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在 magic-script 中执行上传脚本：H2 数据库 → uploader 模块 → 本地 HTTP 服务
 */
class UploadModuleScriptTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private TestHttpServer server;

	private JdbcUploadStore store;

	@BeforeEach
	void setUp() throws Exception {
		server = new TestHttpServer();
		JdbcTemplate jdbc = TestDatabases.h2(null);
		jdbc.execute("CREATE TABLE report (id BIGINT PRIMARY KEY, org VARCHAR(20), name VARCHAR(50), visit_time TIMESTAMP)");
		List<Object[]> args = new ArrayList<>();
		for (int i = 1; i <= 2800; i++) {
			args.add(new Object[]{i, i <= 2500 ? "H001" : "H002", "患者" + i, java.sql.Timestamp.valueOf("2026-10-27 08:00:00")});
		}
		jdbc.batchUpdate("INSERT INTO report VALUES (?, ?, ?, ?)", args);

		MagicDynamicDataSource dataSource = new MagicDynamicDataSource();
		dataSource.put(jdbc.getDataSource());
		store = new JdbcUploadStore(() -> jdbc, "magic_upload_", true);
		UploadJobRunner runner = new UploadJobRunner(store, new BatchEngine(m -> {
		}), Duration.ofHours(1), false);
		DialectAdapter adapter = new DialectAdapter();
		// 模拟医院注册的自定义方言 Bean（H2 不在内置方言中）
		adapter.add(new H2Dialect());
		UploadModule module = new UploadModule(runner, new ChannelFactory(null), () -> dataSource, adapter,
				() -> null, 1000, 0, Duration.ZERO);
		MagicResourceLoader.addModule("uploader", module);
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	private Object execute(String script) {
		MagicScriptContext context = new MagicScriptContext();
		context.set("url", server.url("/upload"));
		return ScriptManager.executeScript(script, context);
	}

	@Test
	void uploadsIncrementallyWithCustomBodyAndSignature() throws Exception {
		server.respond(r -> Response.json(200, "{\"code\":\"0\"}"));
		String script = "import uploader;\n"
				+ "var org = 'H001';\n"
				+ "return uploader.job('report')\n"
				+ "    .source(\"select id, org, name, visit_time from report where org = #{org}\", 'id')\n"
				+ "    .http({url: url, successField: 'code', successValues: '0', headers: {'X-Org': org}})\n"
				+ "    .body(batch => {\n"
				+ "        var payload = {orgCode: org, count: batch.size, items: batch.rows};\n"
				+ "        batch.headers['X-Sign'] = uploader.md5(org + batch.index);\n"
				+ "        return payload;\n"
				+ "    })\n"
				+ "    .batchSize(500)\n"
				+ "    .run()";

		JobResult result = (JobResult) execute(script);

		assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(result.getTotalRows()).isEqualTo(2500);
		assertThat(result.getTotalBatches()).isEqualTo(5);
		assertThat(server.requests()).hasSize(5);
		TestHttpServer.Request first = server.requests().get(0);
		assertThat(first.header("X-Org")).isEqualTo("H001");
		assertThat(first.header("X-Sign")).isEqualTo(org.ssssssss.magicapi.upload.channel.SignFunctions.md5("H0011"));
		JsonNode body = MAPPER.readTree(first.body);
		assertThat(body.get("orgCode").asText()).isEqualTo("H001");
		assertThat(body.get("count").asInt()).isEqualTo(500);
		assertThat(body.get("items").get(0).get("NAME").asText()).isEqualTo("患者1");
		assertThat(body.get("items").get(0).get("VISIT_TIME").asText()).isEqualTo("2026-10-27 08:00:00.000");

		// 再次执行没有新数据
		JobResult again = (JobResult) execute(script);
		assertThat(again.getTotalRows()).isZero();
		assertThat(server.requests()).hasSize(5);

		assertThat(((Number) execute("import uploader; return uploader.checkpoint('report')")).longValue()).isEqualTo(2500L);
		@SuppressWarnings("unchecked")
		List<UploadRecord> records = (List<UploadRecord>) execute("import uploader; return uploader.records('report', 10)");
		assertThat(records).hasSize(1);
	}

	@Test
	void skipThenRetryFailedFromScript() {
		server.respond(r -> r.index == 2 ? Response.text(503, "busy") : Response.json(200, "{}"));
		String job = "uploader.job('skip-job')\n"
				+ "    .source('select id, name from report', 'id')\n"
				+ "    .http({url: url})\n"
				+ "    .batchSize(1000)\n"
				+ "    .onFailure('skip')";

		JobResult result = (JobResult) execute("import uploader; return " + job + ".run()");

		assertThat(result.getStatus()).isEqualTo(JobStatus.PARTIAL);
		assertThat(result.getFailedBatches()).isEqualTo(1);
		assertThat((List<?>) execute("import uploader; return uploader.failed('skip-job', 10)")).hasSize(1);

		RetryResult retry = (RetryResult) execute("import uploader; return " + job + ".retryFailed()");
		assertThat(retry.isSuccess()).isTrue();
		assertThat(retry.getSuccessRows()).isEqualTo(1000);
		assertThat((List<?>) execute("import uploader; return uploader.failed('skip-job', 10)")).isEmpty();
	}

	@Test
	void pageSourceUploadsAllAndResetsCheckpoint() {
		JobResult result = (JobResult) execute("import uploader;\n"
				+ "return uploader.job('monthly').page(\"select id, name from report where org = #{org}\", 'id desc', {org: 'H002'})\n"
				+ "    .http({url: url}).batchSize(100).run()");

		assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(result.getTotalRows()).isEqualTo(300);
		assertThat(execute("import uploader; return uploader.checkpoint('monthly')")).isNull();
	}

	@Test
	void dataSourceFromScriptList() throws Exception {
		JobResult result = (JobResult) execute("import uploader;\n"
				+ "var list = [{a: 1}, {a: 2}, {a: 3}];\n"
				+ "return uploader.job('list-job').data(list).http({url: url}).batchSize(2).run()");

		assertThat(result.getTotalBatches()).isEqualTo(2);
		assertThat(MAPPER.readTree(server.requests().get(1).body).get(0).get("a").asInt()).isEqualTo(3);
	}

	@Test
	void fileChannelFromScript(@TempDir Path dir) throws Exception {
		String target = dir.toString().replace("\\", "/");
		JobResult result = (JobResult) execute("import uploader;\n"
				+ "return uploader.job('csv-job').source('select id, name from report where id <= 1500', 'id')\n"
				+ "    .file({dir: '" + target + "', format: 'csv', fileName: '{job}_{batch}.{ext}'}).run()");

		assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCESS);
		List<String> files;
		try (Stream<Path> list = Files.list(dir)) {
			files = list.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
		}
		assertThat(files).containsExactly("csv-job_0001.csv", "csv-job_0002.csv");
		List<String> lines = Files.readAllLines(dir.resolve("csv-job_0002.csv"), StandardCharsets.UTF_8);
		assertThat(lines.get(0)).isEqualTo("ID,NAME");
		assertThat(lines).hasSize(501);
	}

	@Test
	void signHelpersAndJson() {
		assertThat(execute("import uploader; return uploader.sha256('abc')"))
				.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
		assertThat(execute("import uploader; return uploader.sortedQuery({b: 2, a: 1})")).isEqualTo("a=1&b=2");
		assertThat(execute("import uploader; return uploader.json({a: 1})")).isEqualTo("{\"a\":1}");
	}

	/**
	 * 测试用 H2 方言
	 */
	static class H2Dialect implements Dialect {

		@Override
		public boolean match(String jdbcUrl) {
			return jdbcUrl.contains(":h2:");
		}

		@Override
		public String getPageSql(String sql, BoundSql boundSql, long offset, long limit) {
			boundSql.addParameter(limit);
			boundSql.addParameter(offset);
			return sql + "\n LIMIT ? OFFSET ?";
		}
	}
}
