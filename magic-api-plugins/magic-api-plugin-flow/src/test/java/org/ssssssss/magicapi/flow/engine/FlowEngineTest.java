package org.ssssssss.magicapi.flow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.flow.model.FlowDefinition;
import org.ssssssss.magicapi.flow.model.FlowDefinitions;
import org.ssssssss.magicapi.flow.support.FlowTestApplication;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(classes = FlowTestApplication.class, properties = {
		"spring.datasource.url=jdbc:h2:mem:flow-engine;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"magic-api.web=/magic/web",
		"magic-api.show-url=false",
		"magic-api.banner=false"
})
class FlowEngineTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@TempDir
	static Path workspace;

	@DynamicPropertySource
	static void resource(DynamicPropertyRegistry registry) {
		registry.add("magic-api.resource.location", () -> workspace.toAbsolutePath().toString().replace('\\', '/'));
	}

	@Autowired
	FlowEngine engine;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectProvider<FlowNodeHandler> handlers;

	@Autowired
	WebApplicationContext context;

	@BeforeEach
	void schema() {
		jdbc.execute("drop table if exists SETTLE");
		jdbc.execute("drop table if exists SETTLE_LOG");
		// 大写列名模拟 Oracle，验证默认统一转小写
		jdbc.execute("create table SETTLE (SETTLE_ID varchar(20) primary key, PATIENT_ID varchar(20), AMOUNT decimal(10,2), UPLOAD_FLAG char(1))");
		jdbc.execute("create table SETTLE_LOG (SETTLE_ID varchar(20) primary key, MSG varchar(200))");
		jdbc.update("insert into SETTLE values ('S1', 'P1', 10.5, '0'), ('S2', 'P1', 20, '0'), ('S3', 'P2', 5, '1')");
	}

	@Test
	void queryScriptReturnWithTrace() {
		FlowResult result = run("{\"nodes\":["
				+ "{\"id\":\"q1\",\"type\":\"query\",\"name\":\"查结算\",\"config\":{\"sql\":\"select SETTLE_ID, AMOUNT from SETTLE where PATIENT_ID = #{input.patientId} order by SETTLE_ID\"}},"
				+ "{\"id\":\"sum\",\"type\":\"script\",\"name\":\"合计\",\"config\":{\"script\":\"var t = 0; for (r in q1) { t = t + r.amount }; vars.total = t; return t\"}},"
				+ "{\"id\":\"end\",\"type\":\"return\",\"config\":{\"value\":\"{total: vars.total, count: q1.size(), first: nodes['q1'][0].settle_id}\"}}"
				+ "],\"edges\":[{\"source\":\"q1\",\"target\":\"sum\"},{\"source\":\"sum\",\"target\":\"end\"}]}", input("patientId", "P1"));

		assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
		Map<?, ?> value = (Map<?, ?>) result.getValue();
		assertThat(new java.math.BigDecimal(value.get("total").toString())).isEqualByComparingTo("30.5");
		assertThat(value.get("count")).isEqualTo(2);
		assertThat(value.get("first")).isEqualTo("S1");
		assertThat(result.getTrace()).extracting(FlowTrace::getNodeId).containsExactly("q1", "sum", "end");
		assertThat(result.getTrace()).extracting(FlowTrace::getNext).containsExactly("sum", "end", null);
		assertThat(result.getTrace()).allMatch(FlowTrace::isSuccess);
		assertThat((List<?>) result.getTrace().get(0).getOutput()).hasSize(2);
	}

	@Test
	void queryResultModesAndColumnCase() {
		String flow = "{\"nodes\":["
				+ "{\"id\":\"one\",\"type\":\"query\",\"config\":{\"result\":\"one\",\"sql\":\"select * from SETTLE where SETTLE_ID = 'S1'\"}},"
				+ "{\"id\":\"val\",\"type\":\"query\",\"config\":{\"result\":\"value\",\"sql\":\"select PATIENT_ID from SETTLE where SETTLE_ID = 'S3'\"}},"
				+ "{\"id\":\"cnt\",\"type\":\"query\",\"config\":{\"result\":\"count\",\"sql\":\"select count(*) from SETTLE where UPLOAD_FLAG = '0'\"}},"
				+ "{\"id\":\"camel\",\"type\":\"query\",\"config\":{\"result\":\"one\",\"columnCase\":\"camel\",\"sql\":\"select SETTLE_ID from SETTLE where SETTLE_ID = 'S2'\"}},"
				+ "{\"id\":\"none\",\"type\":\"query\",\"config\":{\"result\":\"one\",\"sql\":\"select * from SETTLE where 1 = 0\"}},"
				+ "{\"id\":\"end\",\"type\":\"return\",\"config\":{\"value\":\"{one: one.patient_id, val: val, cnt: cnt, camel: camel.settleId, none: none == null}\"}}"
				+ "],\"edges\":[{\"source\":\"one\",\"target\":\"val\"},{\"source\":\"val\",\"target\":\"cnt\"},{\"source\":\"cnt\",\"target\":\"camel\"},"
				+ "{\"source\":\"camel\",\"target\":\"none\"},{\"source\":\"none\",\"target\":\"end\"}]}";

		FlowResult result = run(flow, null);

		assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
		assertThat((Map<String, Object>) result.getValue())
				.containsEntry("one", "P1").containsEntry("val", "P2").containsEntry("cnt", 2)
				.containsEntry("camel", "S2").containsEntry("none", true);
	}

	@Test
	void conditionalEdgesPickFirstTrueElseDefault() {
		String flow = "{\"nodes\":["
				+ "{\"id\":\"cnt\",\"type\":\"query\",\"config\":{\"result\":\"count\",\"sql\":\"select count(*) from SETTLE where PATIENT_ID = #{input.patientId}\"}},"
				+ "{\"id\":\"many\",\"type\":\"return\",\"config\":{\"value\":\"'many'\"}},"
				+ "{\"id\":\"one\",\"type\":\"return\",\"config\":{\"value\":\"'one'\"}},"
				+ "{\"id\":\"none\",\"type\":\"return\",\"config\":{\"value\":\"'none'\"}}"
				+ "],\"edges\":["
				+ "{\"source\":\"cnt\",\"target\":\"none\"},"
				+ "{\"source\":\"cnt\",\"target\":\"many\",\"condition\":\"prev > 1\"},"
				+ "{\"source\":\"cnt\",\"target\":\"one\",\"condition\":\"cnt == 1\"}]}";

		assertThat(run(flow, input("patientId", "P1")).getValue()).isEqualTo("many");
		assertThat(run(flow, input("patientId", "P2")).getValue()).isEqualTo("one");
		FlowResult none = run(flow, input("patientId", "X"));
		assertThat(none.getValue()).isEqualTo("none");
		assertThat(none.getTrace().get(0).getNext()).isEqualTo("none");
	}

	@Test
	void writeForEachRollsBackWholeBatchOnError() {
		String flow = "{\"nodes\":["
				+ "{\"id\":\"rows\",\"type\":\"script\",\"config\":{\"script\":\"return [{id: 'S1', msg: 'a'}, {id: 'S2', msg: 'b'}, {id: 'S1', msg: 'dup'}]\"}},"
				+ "{\"id\":\"w\",\"type\":\"write\",\"name\":\"写日志\",\"config\":{\"forEach\":\"rows\",\"sql\":\"insert into SETTLE_LOG (SETTLE_ID, MSG) values (#{row.id}, #{row.msg})\"}}"
				+ "],\"edges\":[{\"source\":\"rows\",\"target\":\"w\"}]}";

		FlowResult result = run(flow, null);

		assertThat(result.isSuccess()).isFalse();
		assertThat(result.getErrorNode()).isEqualTo("w");
		assertThat(result.getMessage()).contains("第 3 行写入失败");
		assertThat(result.getTrace().get(1).isSuccess()).isFalse();
		assertThat(jdbc.queryForObject("select count(*) from SETTLE_LOG", Integer.class)).isZero();
	}

	@Test
	void writeSingleAndForEachCommit() {
		String flow = "{\"nodes\":["
				+ "{\"id\":\"q\",\"type\":\"query\",\"config\":{\"sql\":\"select SETTLE_ID from SETTLE where UPLOAD_FLAG = '0'\"}},"
				+ "{\"id\":\"log\",\"type\":\"write\",\"config\":{\"forEach\":\"q\",\"sql\":\"insert into SETTLE_LOG values (#{row.settle_id}, #{'第' + (index + 1) + '条'})\"}},"
				+ "{\"id\":\"flag\",\"type\":\"write\",\"config\":{\"sql\":\"update SETTLE set UPLOAD_FLAG = '1' where UPLOAD_FLAG = '0'\"}}"
				+ "],\"edges\":[{\"source\":\"q\",\"target\":\"log\"},{\"source\":\"log\",\"target\":\"flag\"}]}";

		FlowResult result = run(flow, null);

		assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
		assertThat((Map<String, Object>) result.getTrace().get(1).getOutput()).containsEntry("affected", 2).containsEntry("executed", 2);
		assertThat((Map<String, Object>) result.getValue()).containsEntry("affected", 2);
		assertThat(jdbc.queryForList("select MSG from SETTLE_LOG order by MSG", String.class)).containsExactly("第1条", "第2条");
		assertThat(jdbc.queryForObject("select count(*) from SETTLE where UPLOAD_FLAG = '0'", Integer.class)).isZero();
	}

	@Test
	void failureEdgeReceivesError() {
		String flow = "{\"nodes\":["
				+ "{\"id\":\"bad\",\"type\":\"query\",\"name\":\"查不存在的表\",\"config\":{\"sql\":\"select * from NOT_EXISTS\"}},"
				+ "{\"id\":\"ok\",\"type\":\"return\",\"config\":{\"value\":\"'ok'\"}},"
				+ "{\"id\":\"handle\",\"type\":\"return\",\"config\":{\"value\":\"{code: -1, node: error.node, msg: error.message}\"}}"
				+ "],\"edges\":[{\"source\":\"bad\",\"target\":\"ok\"},{\"source\":\"bad\",\"target\":\"handle\",\"condition\":\"failure\"}]}";

		FlowResult result = run(flow, null);

		assertThat(result.isSuccess()).isTrue();
		Map<?, ?> value = (Map<?, ?>) result.getValue();
		assertThat(value.get("code")).isEqualTo(-1);
		assertThat(value.get("node")).isEqualTo("bad");
		assertThat(value.get("msg").toString()).containsIgnoringCase("NOT_EXISTS");
		assertThat(result.getTrace()).extracting(FlowTrace::isSuccess).containsExactly(false, true);
		assertThat(result.getTrace().get(0).getNext()).isEqualTo("handle");
	}

	@Test
	void scriptErrorWithoutFailureEdgeFailsFlow() {
		FlowResult result = run("{\"nodes\":[{\"id\":\"s\",\"type\":\"script\",\"name\":\"出错\",\"config\":{\"script\":\"return 1\"}},"
				+ "{\"id\":\"t\",\"type\":\"script\",\"config\":{\"script\":\"var x = null; return x.length()\"}}],"
				+ "\"edges\":[{\"source\":\"s\",\"target\":\"t\"}]}", null);

		assertThat(result.isSuccess()).isFalse();
		assertThat(result.getErrorNode()).isEqualTo("t");
		assertThat(result.getMessage()).isNotBlank();
	}

	@Test
	void configurationErrorsAreReportedBeforeRunning() {
		assertThatThrownBy(() -> run("{\"nodes\":[{\"id\":\"a\",\"type\":\"mq\",\"name\":\"发消息\"}]}", null))
				.isInstanceOf(InvalidArgumentException.class).hasMessageContaining("发消息").hasMessageContaining("mq");
		assertThatThrownBy(() -> run("{\"nodes\":[{\"id\":\"a\",\"type\":\"query\",\"name\":\"查库\"}]}", null))
				.hasMessageContaining("节点「查库」缺少配置项：sql");
		assertThatThrownBy(() -> run("{\"nodes\":[{\"id\":\"a\",\"type\":\"query\",\"config\":{\"sql\":\"select 1\",\"result\":\"page\"}}]}", null))
				.hasMessageContaining("result").hasMessageContaining("list / one / value / count");
		assertThatThrownBy(() -> run("{\"nodes\":[{\"id\":\"a\",\"type\":\"return\"},{\"id\":\"b\",\"type\":\"return\"}]}", null))
				.hasMessageContaining("起始节点").hasMessageContaining("a、b");
		assertThatThrownBy(() -> run("{\"nodes\":[{\"id\":\"a\",\"type\":\"return\"},{\"id\":\"b\",\"type\":\"return\"},{\"id\":\"c\",\"type\":\"return\"}],"
				+ "\"edges\":[{\"source\":\"a\",\"target\":\"b\"},{\"source\":\"a\",\"target\":\"c\",\"condition\":\"success\"}]}", null))
				.hasMessageContaining("多条无条件连线");
		assertThatThrownBy(() -> run("{\"nodes\":[]}", null)).hasMessageContaining("没有节点");
	}

	@Test
	void stepLimitStopsEndlessLoop() {
		FlowEngine limited = new FlowEngine(handlers.orderedStream().collect(Collectors.toList()));
		limited.setMaxSteps(50);
		FlowDefinition definition = FlowDefinitions.parse("{\"nodes\":["
				+ "{\"id\":\"start\",\"type\":\"script\",\"config\":{\"script\":\"return 0\"}},"
				+ "{\"id\":\"a\",\"type\":\"script\",\"name\":\"甲\",\"config\":{\"script\":\"return 1\"}},"
				+ "{\"id\":\"b\",\"type\":\"script\",\"name\":\"乙\",\"config\":{\"script\":\"return 2\"}}],"
				+ "\"edges\":[{\"source\":\"start\",\"target\":\"a\"},{\"source\":\"a\",\"target\":\"b\"},{\"source\":\"b\",\"target\":\"a\"}]}");

		FlowResult result = limited.run(definition, "loop", null, true);

		assertThat(result.isSuccess()).isFalse();
		assertThat(result.getMessage()).contains("50");
		assertThat(result.getTrace()).hasSize(50);
	}

	@Test
	void tracePreviewKeepsFirstRowsButNextNodeGetsAll() {
		StringBuilder values = new StringBuilder();
		for (int i = 0; i < 25; i++) {
			values.append(i == 0 ? "" : ",").append("('L").append(i).append("','x')");
		}
		jdbc.update("insert into SETTLE_LOG values " + values);

		FlowResult result = run("{\"nodes\":[{\"id\":\"q\",\"type\":\"query\",\"config\":{\"sql\":\"select * from SETTLE_LOG\"}},"
				+ "{\"id\":\"n\",\"type\":\"script\",\"config\":{\"script\":\"return q.size()\"}}],\"edges\":[{\"source\":\"q\",\"target\":\"n\"}]}", null);

		Map<?, ?> preview = (Map<?, ?>) result.getTrace().get(0).getOutput();
		assertThat(preview.get("total")).isEqualTo(25);
		assertThat((List<?>) preview.get("rows")).hasSize(FlowTrace.PREVIEW_ROWS);
		assertThat(result.getValue()).isEqualTo(25);
	}

	@Test
	void debugEndpointRunsUnsavedDefinition() throws Exception {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
		String definition = "{\"nodes\":[{\"id\":\"q\",\"type\":\"query\",\"config\":{\"result\":\"count\",\"sql\":\"select count(*) from SETTLE where PATIENT_ID = #{input.pid}\"}}]}";

		JsonNode ok = json(mvc.perform(post("/magic/web/flow/debug").param("definition", definition).param("input", "{\"pid\":\"P1\"}")));
		assertThat(ok.get("code").asInt()).as(ok.toString()).isEqualTo(1);
		assertThat(ok.at("/data/success").asBoolean()).isTrue();
		assertThat(ok.at("/data/value").asInt()).isEqualTo(2);
		assertThat(ok.at("/data/trace/0/nodeId").asText()).isEqualTo("q");
		assertThat(ok.at("/data/error").isMissingNode()).isTrue();

		JsonNode badInput = json(mvc.perform(post("/magic/web/flow/debug").param("definition", definition).param("input", "[1")));
		assertThat(badInput.get("code").asInt()).isEqualTo(5002);
		assertThat(badInput.get("message").asText()).contains("调试输入");

		JsonNode badType = json(mvc.perform(post("/magic/web/flow/validate").param("definition", "{\"nodes\":[{\"id\":\"a\",\"type\":\"mq\"}]}")));
		assertThat(badType.get("code").asInt()).isEqualTo(5008);
	}

	private FlowResult run(String definition, Map<String, Object> input) {
		return engine.run(FlowDefinitions.parse(definition), "test", input, true);
	}

	private static Map<String, Object> input(String key, Object value) {
		Map<String, Object> input = new HashMap<>();
		input.put(key, value);
		return input;
	}

	private static JsonNode json(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
		return MAPPER.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
	}
}
