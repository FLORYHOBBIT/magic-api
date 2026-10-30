package org.ssssssss.magicapi.flow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.ssssssss.magicapi.flow.engine.FlowEngine;
import org.ssssssss.magicapi.flow.engine.FlowResult;
import org.ssssssss.magicapi.flow.model.FlowDefinitions;
import org.ssssssss.magicapi.flow.model.FlowInfo;
import org.ssssssss.magicapi.flow.support.AbstractFlowTest;
import org.ssssssss.magicapi.flow.support.FlowVariables;
import org.ssssssss.magicapi.flow.support.MockHttpServer;
import org.ssssssss.magicapi.flow.support.MockHttpServer.Response;
import org.ssssssss.magicapi.flow.trigger.FlowJobService;
import org.ssssssss.magicapi.upload.engine.JobResult;
import org.ssssssss.magicapi.upload.engine.JobStatus;
import org.ssssssss.magicapi.upload.resource.UploadChannelInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 5 / 7 / 8：调用外部系统、定时分批上传与回写、表对表同步、差异项
 */
class FlowUploadTest extends AbstractFlowTest {

	private MockHttpServer server;

	@Autowired
	FlowEngine engine;

	@Autowired
	FlowJobService jobService;

	@Autowired
	FlowVariables variables;

	@BeforeEach
	void setUp() throws Exception {
		server = new MockHttpServer();
		jdbc.execute("drop table if exists SETTLE");
		jdbc.execute("drop table if exists SETTLE_COPY");
		jdbc.execute("create table SETTLE (ID int primary key, PATIENT_ID varchar(20), AMOUNT decimal(10,2), UPLOAD_FLAG char(1), UPLOAD_MSG varchar(200))");
		jdbc.execute("create table SETTLE_COPY (ID int primary key, PATIENT_ID varchar(20), AMOUNT decimal(10,2))");
		jdbc.update("insert into SETTLE values (1, 'P1', 10.5, '0', null), (2, 'P1', 20, '0', null), (3, 'P2', 5, '0', null), (4, 'P3', 1, '1', null)");
		saveChannel("province", "http", options("url", server.url("/upload"), "successField", "code", "successValues", "0",
				"messageField", "msg", "headers", options("X-App", "${var.app-id}"), "password", "P@ss"));
		variables.save("app-id", "APP001", false, "对方分配的应用编号");
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	@Test
	void callNodeSendsBodyHeadersAndParsesResponse() {
		server.respond(r -> Response.json(200, "{\"code\":0,\"data\":{\"token\":\"T-1\"}}"));
		FlowResult result = run(q("{`nodes`:["
				+ "{`id`:`rows`,`type`:`query`,`config`:{`sql`:`select ID, AMOUNT from SETTLE where PATIENT_ID = 'P1' order by ID`}},"
				+ "{`id`:`send`,`type`:`call`,`config`:{`channel`:`province`,`body`:`{org: '${var.upload-test-org:H001}', list: rows}`,"
				+ "`headers`:{`X-Count`:`{{rows.size()}}`},`params`:{`ts`:`1700000000`}}},"
				+ "{`id`:`end`,`type`:`return`,`config`:{`value`:`send.data.data.token`}}],"
				+ "`edges`:[{`source`:`rows`,`target`:`send`},{`source`:`send`,`target`:`end`}]}"));

		assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
		assertThat(result.getValue()).isEqualTo("T-1");
		MockHttpServer.Request request = server.requests().get(0);
		assertThat(request.headers.get("x-app")).isEqualTo("APP001");
		assertThat(request.headers.get("x-count")).isEqualTo("2");
		assertThat(request.query).isEqualTo("ts=1700000000");
		assertThat(request.body).isEqualTo("{\"org\":\"H001\",\"list\":[{\"id\":1,\"amount\":10.50},{\"id\":2,\"amount\":20.00}]}");
	}

	@Test
	void businessFailureGoesToFailureEdgeWithResponse() {
		server.respond(r -> Response.json(200, "{\"code\":1,\"msg\":\"机构编码不存在\"}"));
		FlowResult result = run(q("{`nodes`:["
				+ "{`id`:`send`,`type`:`call`,`config`:{`channel`:`province`,`body`:`{a: 1}`,`retry`:`3`,`retryInterval`:`10`}},"
				+ "{`id`:`ok`,`type`:`return`,`config`:{`value`:`'ok'`}},"
				+ "{`id`:`ng`,`type`:`return`,`config`:{`value`:`{msg: error.message, code: send.data.code, retryable: error.output.retryable}`}}],"
				+ "`edges`:[{`source`:`send`,`target`:`ok`},{`source`:`send`,`target`:`ng`,`condition`:`failure`}]}"));

		Map<?, ?> value = (Map<?, ?>) result.getValue();
		assertThat(value.get("msg").toString()).contains("机构编码不存在");
		assertThat(value.get("code")).isEqualTo(1);
		assertThat(value.get("retryable")).isEqualTo(false);
		// 业务拒绝不重试
		assertThat(server.requests()).hasSize(1);
	}

	@Test
	void serverErrorIsRetriedAndLoginTokenIsCached() {
		AtomicInteger calls = new AtomicInteger();
		server.respond(r -> calls.incrementAndGet() < 3 ? Response.json(503, "busy") : Response.json(200, "{\"code\":0,\"token\":\"T-" + calls.get() + "\"}"));
		String flow = q("{`nodes`:["
				+ "{`id`:`login`,`type`:`call`,`config`:{`channel`:`province`,`body`:`{user: 'u'}`,`retry`:`3`,`retryInterval`:`10`,`cacheSeconds`:`60`}},"
				+ "{`id`:`end`,`type`:`return`,`config`:{`value`:`login.data.token`}}],"
				+ "`edges`:[{`source`:`login`,`target`:`end`}]}");

		assertThat(run(flow).getValue()).isEqualTo("T-3");
		assertThat(server.requests()).hasSize(3);
		// 缓存期内不再调用
		assertThat(run(flow).getValue()).isEqualTo("T-3");
		assertThat(server.requests()).hasSize(3);
	}

	@Test
	void callForEachSendsRowByRow() {
		server.respond(r -> r.body.contains("\"id\":2") ? Response.json(200, "{\"code\":9,\"msg\":\"bad\"}") : Response.json(200, "{\"code\":0}"));
		FlowResult result = run(q("{`nodes`:["
				+ "{`id`:`rows`,`type`:`query`,`config`:{`sql`:`select ID from SETTLE where UPLOAD_FLAG = '0' order by ID`}},"
				+ "{`id`:`send`,`type`:`call`,`config`:{`channel`:`province`,`forEach`:`rows`,`body`:`{id: item.id, seq: index}`}},"
				+ "{`id`:`back`,`type`:`write`,`config`:{`forEach`:`send.results`,`sql`:`update SETTLE set UPLOAD_FLAG = #{row.success ? '1' : '2'}, UPLOAD_MSG = #{row.message} where ID = #{row.item.id}`}}],"
				+ "`edges`:[{`source`:`rows`,`target`:`send`},{`source`:`send`,`target`:`back`}]}"));

		assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
		assertThat(server.requests()).hasSize(3);
		assertThat(jdbc.queryForList("select UPLOAD_FLAG from SETTLE order by ID", String.class)).containsExactly("1", "2", "1", "1");
	}

	@Test
	void timerPendingModeUploadsInBatchesAndWritesBack() throws Exception {
		server.respond(r -> r.body.contains("\"id\":3") ? Response.json(200, "{\"code\":1,\"msg\":\"金额不合法\"}") : Response.json(200, "{\"code\":0}"));
		FlowInfo info = saveFlow("job", "settle-upload", q("{`nodes`:["
				+ "{`id`:`start`,`type`:`timer`,`config`:{`cron`:`0 0 3 * * ?`,`mode`:`pending`,`sql`:`select ID, PATIENT_ID, AMOUNT from SETTLE where UPLOAD_FLAG = '0'`,`keyColumn`:`ID`,`batchSize`:`2`}},"
				+ "{`id`:`send`,`type`:`call`,`config`:{`channel`:`province`,`body`:`{batch: input.batch.index, list: input.rows}`}},"
				+ "{`id`:`ok`,`type`:`write`,`config`:{`forEach`:`input.rows`,`sql`:`update SETTLE set UPLOAD_FLAG = '1' where ID = #{row.id}`}},"
				+ "{`id`:`ng`,`type`:`write`,`config`:{`forEach`:`input.rows`,`sql`:`update SETTLE set UPLOAD_FLAG = '2', UPLOAD_MSG = #{error.message} where ID = #{row.id}`}}],"
				+ "`edges`:[{`source`:`start`,`target`:`send`},{`source`:`send`,`target`:`ok`},{`source`:`send`,`target`:`ng`,`condition`:`failure`}]}"), true);
		MockMvc mvc = mvc();

		JsonNode status = data(mvc.perform(get("/magic/web/flow/status").param("id", info.getId())));
		assertThat(status.get("triggers").get(0).asText()).isEqualTo("定时 0 0 3 * * ?");

		JsonNode run = data(mvc.perform(post("/magic/web/flow/run").param("id", info.getId())));
		assertThat(run.get("status").asText()).isEqualTo("SUCCESS");
		assertThat(server.requests()).hasSize(2);
		assertThat(server.requests().get(0).body).contains("\"batch\":1").contains("\"id\":1").contains("\"id\":2");
		assertThat(jdbc.queryForList("select UPLOAD_FLAG from SETTLE order by ID", String.class)).containsExactly("1", "1", "2", "1");
		assertThat(jdbc.queryForObject("select UPLOAD_MSG from SETTLE where ID = 3", String.class)).contains("金额不合法");

		// pending 模式不保留断点：重新置为未上传后，下次还会读到
		jdbc.update("update SETTLE set UPLOAD_FLAG = '0' where ID = 3");
		server.respond(r -> Response.json(200, "{\"code\":0}"));
		JobResult again = jobService.run(resources().file(info.getId()), "manual");
		assertThat(again.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(jdbc.queryForObject("select UPLOAD_FLAG from SETTLE where ID = 3", String.class)).isEqualTo("1");

		JsonNode afterStatus = data(mvc.perform(get("/magic/web/flow/status").param("id", info.getId())));
		assertThat(afterStatus.at("/job/records").size()).isGreaterThanOrEqualTo(2);
	}

	@Test
	void timerKeysetSyncsTableIncrementally() throws Exception {
		FlowInfo info = saveFlow("job", "settle-sync", q("{`nodes`:["
				+ "{`id`:`start`,`type`:`timer`,`config`:{`cron`:`0 0 3 * * ?`,`mode`:`keyset`,`sql`:`select ID, PATIENT_ID, AMOUNT from SETTLE`,`keyColumn`:`ID`,`batchSize`:`2`}},"
				+ "{`id`:`save`,`type`:`save`,`config`:{`table`:`SETTLE_COPY`,`keys`:`id`,`rows`:`input.rows`,`mode`:`upsert`}}],"
				+ "`edges`:[{`source`:`start`,`target`:`save`}]}"), true);

		JobResult first = jobService.run(resources().file(info.getId()), "manual");
		assertThat(first.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(jdbc.queryForObject("select count(*) from SETTLE_COPY", Integer.class)).isEqualTo(4);

		// 增量：只同步新增的行；已有行更新
		jdbc.update("insert into SETTLE values (5, 'P5', 50, '0', null)");
		jdbc.update("update SETTLE_COPY set AMOUNT = 0 where ID = 1");
		JobResult second = jobService.run(resources().file(info.getId()), "manual");
		assertThat(second.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(jdbc.queryForObject("select count(*) from SETTLE_COPY", Integer.class)).isEqualTo(5);
		assertThat(jdbc.queryForObject("select AMOUNT from SETTLE_COPY where ID = 1", java.math.BigDecimal.class)).isEqualByComparingTo("0");

		// 清除断点后全量重跑：upsert 更新已有行
		data(mvc().perform(post("/magic/web/flow/reset-checkpoint").param("id", info.getId())));
		jobService.run(resources().file(info.getId()), "manual");
		assertThat(jdbc.queryForObject("select AMOUNT from SETTLE_COPY where ID = 1", java.math.BigDecimal.class)).isEqualByComparingTo("10.5");
	}

	@Test
	void skipPolicyRecordsFailedBatchAndRetryResendsIt() throws Exception {
		server.respond(r -> r.body.contains("\"id\":3") ? Response.json(503, "busy") : Response.json(200, "{\"code\":0}"));
		FlowInfo info = saveFlow("job", "settle-skip", q("{`nodes`:["
				+ "{`id`:`start`,`type`:`timer`,`config`:{`cron`:`0 0 3 * * ?`,`mode`:`keyset`,`sql`:`select ID from SETTLE`,`keyColumn`:`ID`,`batchSize`:`2`,`failurePolicy`:`skip`}},"
				+ "{`id`:`send`,`type`:`call`,`config`:{`channel`:`province`,`body`:`{list: input.rows}`}}],"
				+ "`edges`:[{`source`:`start`,`target`:`send`}]}"), true);
		MockMvc mvc = mvc();

		JsonNode run = data(mvc.perform(post("/magic/web/flow/run").param("id", info.getId())));
		assertThat(run.get("status").asText()).isEqualTo("PARTIAL");
		JsonNode status = data(mvc.perform(get("/magic/web/flow/status").param("id", info.getId())));
		assertThat(status.at("/job/failedBatches").size()).isEqualTo(1);
		assertThat(status.at("/job/failedBatches/0/message").asText()).contains("503");

		server.respond(r -> Response.json(200, "{\"code\":0}"));
		JsonNode retry = data(mvc.perform(post("/magic/web/flow/retry-failed").param("id", info.getId())));
		assertThat(retry.get("successBatches").asInt()).isEqualTo(1);
		assertThat(data(mvc.perform(get("/magic/web/flow/status").param("id", info.getId()))).at("/job/failedBatches").size()).isZero();
		assertThat(server.requests().get(server.requests().size() - 1).body).contains("\"id\":3").contains("\"id\":4");
	}

	@Test
	void variablesAreResolvedAndMissingOnesReported() {
		FlowResult ok = run(q("{`nodes`:[{`id`:`end`,`type`:`return`,`config`:{`value`:`'${var.app-id}' + '-' + '${var.not-set:默认}'`}}]}"));
		assertThat(ok.getValue()).isEqualTo("APP001-默认");

		FlowResult missing = run(q("{`nodes`:[{`id`:`end`,`type`:`return`,`config`:{`value`:`'${var.hospital-code}'`}}]}"));
		assertThat(missing.isSuccess()).isFalse();
		assertThat(missing.getMessage()).contains("差异项 var.hospital-code 没有配置");

		variables.save("hospital-code", "H9", true, "医院编码");
		assertThat(run(q("{`nodes`:[{`id`:`end`,`type`:`return`,`config`:{`value`:`'${var.hospital-code}'`}}]}")).getValue()).isEqualTo("H9");
		// 密文落盘时已加密，列表中不回显
		assertThat(variables.list()).filteredOn(it -> "hospital-code".equals(it.get("name")))
				.allSatisfy(it -> assertThat(it.get("value")).isNull());
		assertThat(variables.find("hospital-code").getValue()).startsWith("ENC(");
	}

	@Test
	void unknownChannelIsAConfigurationError() {
		FlowResult result = run(q("{`nodes`:[{`id`:`send`,`type`:`call`,`config`:{`channel`:`nope`}}]}"));
		assertThat(result.isSuccess()).isFalse();
		assertThat(result.getMessage()).contains("找不到外部连接「nope」");
		assertThatThrownBy(() -> engine.prepare(FlowDefinitions.parse(q("{`nodes`:[{`id`:`send`,`type`:`call`,`config`:{}}]}"))))
				.hasMessageContaining("缺少配置项：channel");
	}

	private FlowResult run(String definition) {
		return engine.run(FlowDefinitions.parse(definition), "test", null, true);
	}

	@SuppressWarnings("unused")
	private static List<String> names(JsonNode array) {
		return MAPPER.convertValue(array, MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
	}
}
