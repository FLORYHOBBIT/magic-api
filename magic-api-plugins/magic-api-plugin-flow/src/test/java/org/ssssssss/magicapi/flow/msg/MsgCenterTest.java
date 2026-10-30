package org.ssssssss.magicapi.flow.msg;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.ssssssss.magicapi.flow.model.FlowInfo;
import org.ssssssss.magicapi.flow.support.AbstractFlowTest;
import org.ssssssss.magicapi.flow.support.MockHttpServer;
import org.ssssssss.magicapi.upload.alert.UploadAlert;
import org.ssssssss.magicapi.upload.alert.UploadAlertNotifier;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 2～7：消息触发节点、HTTP / WebService 接收、异步消费、重试与死信、顺序消费、回调、告警
 */
@TestPropertySource(properties = {
		"magic-api.flow.msg.poll-interval=100ms",
		"magic-api.upload.alert.silence=0s",
		"magic-api.upload.alert.hospital=测试医院"
})
@Import(MsgCenterTest.Alerts.class)
class MsgCenterTest extends AbstractFlowTest {

	static final List<UploadAlert> ALERTS = new CopyOnWriteArrayList<>();

	@TestConfiguration
	static class Alerts {
		@Bean
		UploadAlertNotifier recordingNotifier() {
			return ALERTS::add;
		}
	}

	private MockHttpServer server;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.execute("drop table if exists LIS_RESULT");
		jdbc.execute("create table LIS_RESULT (MSG_ID varchar(64), VAL varchar(64), ATTEMPT int)");
		jdbc.execute("drop table if exists ORDER_LOG");
		jdbc.execute("create table ORDER_LOG (ID int auto_increment primary key, PID varchar(20), N int)");
		// 首次运行时表还没建，先触发建表
		context.getBean(JdbcMsgStore.class).jdbc();
		jdbc.execute("delete from magic_flow_msg");
		ALERTS.clear();
		server = new MockHttpServer();
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	private static String msgFlow(String trigger) {
		return q("{`nodes`:["
				+ "{`id`:`start`,`type`:`msg`,`config`:" + trigger + "},"
				+ "{`id`:`save`,`type`:`write`,`config`:{`sql`:`insert into LIS_RESULT values (#{input.msgId}, #{input.body?.value}, #{input.attempt})`}}],"
				+ "`edges`:[{`source`:`start`,`target`:`save`}]}");
	}

	private static void await(String what, BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("等待超时：" + what);
			}
			Thread.sleep(50);
		}
	}

	private String status(String type, String msgId) {
		List<String> list = jdbc.queryForList("select status from magic_flow_msg where msg_type = ? and msg_id = ?", String.class, type, msgId);
		return list.isEmpty() ? null : list.get(0);
	}

	private MvcResult send(MockMvc mvc, String path, String json) throws Exception {
		return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.getBytes(StandardCharsets.UTF_8))).andReturn();
	}

	@Test
	void httpReceiveRepliesImmediatelyAndConsumesAsync() throws Exception {
		FlowInfo info = saveFlow("msg", "lis-result", msgFlow(q("{`type`:`lis.result`}")), true);
		MockMvc mvc = mvc();

		MvcResult first = send(mvc, "/msg/lis.result", "{\"msgId\":\"M1\",\"value\":\"5.2\"}");
		assertThat(first.getResponse().getStatus()).as(first.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
		assertThat(first.getResponse().getContentAsString(StandardCharsets.UTF_8)).startsWith("{\"code\"");
		JsonNode reply = MAPPER.readTree(first.getResponse().getContentAsString(StandardCharsets.UTF_8));
		assertThat(reply.get("code").asInt()).isZero();
		assertThat(reply.get("msgId").asText()).isEqualTo("M1");
		assertThat(reply.get("duplicate").asBoolean()).isFalse();

		// 只有消息 id（查询参数，无报文）
		MvcResult onlyId = mvc.perform(post("/msg/lis.result").param("msgId", "M2")).andReturn();
		assertThat(onlyId.getResponse().getStatus()).isEqualTo(200);

		await("两条消息处理完", () -> "SUCCESS".equals(status("lis.result", "M1")) && "SUCCESS".equals(status("lis.result", "M2")));
		assertThat(jdbc.queryForList("select MSG_ID, VAL from LIS_RESULT order by MSG_ID")).hasSize(2);
		assertThat(jdbc.queryForObject("select VAL from LIS_RESULT where MSG_ID = 'M1'", String.class)).isEqualTo("5.2");

		// 重复消息：回执成功，不重复处理
		JsonNode dup = MAPPER.readTree(send(mvc, "/msg/lis.result", "{\"msgId\":\"M1\",\"value\":\"9\"}").getResponse().getContentAsString(StandardCharsets.UTF_8));
		assertThat(dup.get("duplicate").asBoolean()).isTrue();
		Thread.sleep(300);
		assertThat(jdbc.queryForObject("select count(*) from LIS_RESULT", Integer.class)).isEqualTo(2);

		// 类型在报文里（POST /msg）
		assertThat(send(mvc, "/msg", "{\"type\":\"lis.result\",\"msgId\":\"M3\"}").getResponse().getStatus()).isEqualTo(200);
		// 缺消息 id、未知类型
		assertThat(send(mvc, "/msg/lis.result", "{\"value\":1}").getResponse().getStatus()).isEqualTo(400);
		assertThat(send(mvc, "/msg/unknown", "{\"msgId\":\"X\"}").getResponse().getStatus()).isEqualTo(404);

		// 运行状态显示接收地址；面板接口
		JsonNode status = data(mvc.perform(get("/magic/web/flow/status").param("id", info.getId())));
		assertThat(status.get("triggers").get(0).asText()).contains("消息 lis.result").contains("POST /msg/lis.result").contains("sendMessage");
		JsonNode list = data(mvc.perform(get("/magic/web/flow/msg/list").param("type", "lis.result")));
		assertThat(list.get("total").asInt()).isEqualTo(3);
		JsonNode stats = data(mvc.perform(get("/magic/web/flow/msg/stats")));
		assertThat(stats.get("types").get(0).get("flowName").asText()).isEqualTo("lis-result");

		// 停用后不再接收；已入库的消息保留
		saveFlow("msg", "lis-result", info.getScript(), false);
		assertThat(send(mvc, "/msg/lis.result", "{\"msgId\":\"M9\"}").getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void tokenIpAndCustomReply() throws Exception {
		saveFlow("msg", "secure", msgFlow(q("{`type`:`secure`,`auth`:`token`,`token`:`${var.msg-token:abc}`,"
				+ "`msgId`:`input.headers['x-msg-id']`,`reply`:`<ack><id>{{msgId}}</id></ack>`,`replyFormat`:`xml`}")), true);
		saveFlow("msg", "ip-only", msgFlow(q("{`type`:`ip-only`,`allowIps`:`10.1.*`}")), true);
		MockMvc mvc = mvc();

		assertThat(mvc.perform(post("/msg/secure").header("x-msg-id", "H1")).andReturn().getResponse().getStatus()).isEqualTo(401);
		MvcResult ok = mvc.perform(post("/msg/secure").header("X-Token", "abc").header("x-msg-id", "H1")).andReturn();
		assertThat(ok.getResponse().getStatus()).isEqualTo(200);
		assertThat(ok.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("<ack><id>H1</id></ack>");
		// 令牌不保存到消息表
		String meta = jdbc.queryForObject("select meta from magic_flow_msg where msg_type = 'secure'", String.class);
		assertThat(meta).contains("x-msg-id").doesNotContain("abc");

		assertThat(send(mvc, "/msg/ip-only", "{\"msgId\":\"I1\"}").getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void failureRetriesThenDeadAlertsCallbackAndManualRetry() throws Exception {
		saveChannel("ack", "http", options("url", server.url("/ack"), "successField", "code", "successValues", "0"));
		saveFlow("msg", "flaky", msgFlow(q("{`type`:`flaky`,`maxRetries`:`1`,`backoff`:`0`,`alertDead`:true,"
				+ "`callback`:`both`,`callbackChannel`:`ack`,`callbackBody`:`{\\`msgId\\`:\\`{{msgId}}\\`,\\`status\\`:\\`{{status}}\\`,\\`error\\`:\\`{{error}}\\`}`}"))
				.replace("LIS_RESULT values", "NOT_EXISTS values"), true);
		MockMvc mvc = mvc();
		send(mvc, "/msg/flaky", "{\"msgId\":\"F1\"}");

		await("转死信", () -> "DEAD".equals(status("flaky", "F1")));
		Map<String, Object> row = jdbc.queryForMap("select attempts, error, error_node, trace from magic_flow_msg where msg_id = 'F1'");
		assertThat(((Number) row.get("ATTEMPTS")).intValue()).isEqualTo(2);
		assertThat(row.get("ERROR").toString()).containsIgnoringCase("NOT_EXISTS");
		assertThat(row.get("ERROR_NODE")).isEqualTo("save");
		JsonNode trace = MAPPER.readTree(row.get("TRACE").toString());
		assertThat(trace.get(1).get("nodeId").asText()).isEqualTo("save");
		assertThat(trace.get(1).get("success").asBoolean()).isFalse();

		await("死信告警", () -> !ALERTS.isEmpty());
		assertThat(ALERTS.get(0).getTitle()).isEqualTo("[测试医院] 消息类型 flaky 有消息成为死信");
		assertThat(ALERTS.get(0).getText()).contains("F1");

		await("死信回调", () -> !server.requests().isEmpty());
		assertThat(server.requests().get(0).body).contains("\"msgId\":\"F1\"").contains("\"status\":\"DEAD\"").contains("NOT_EXISTS");
		await("回调成功", () -> "SUCCESS".equals(jdbc.queryForObject("select cb_status from magic_flow_msg where msg_id = 'F1'", String.class)));

		// 修复后人工重试，成功后再次回调
		saveFlow("msg", "flaky", msgFlow(q("{`type`:`flaky`,`callback`:`both`,`callbackChannel`:`ack`,`callbackBody`:`{\\`msgId\\`:\\`{{msgId}}\\`,\\`status\\`:\\`{{status}}\\`}`}")), true);
		String id = jdbc.queryForObject("select id from magic_flow_msg where msg_id = 'F1'", String.class);
		assertThat(data(mvc.perform(post("/magic/web/flow/msg/retry").param("ids", id))).asInt()).isEqualTo(1);
		await("重试成功", () -> "SUCCESS".equals(status("flaky", "F1")));
		await("成功回调", () -> server.requests().size() >= 2 && "SUCCESS".equals(jdbc.queryForObject("select cb_status from magic_flow_msg where msg_id = 'F1'", String.class)));
		assertThat(server.requests().get(1).body).contains("\"status\":\"SUCCESS\"");

		JsonNode detail = data(mvc.perform(get("/magic/web/flow/msg/detail").param("id", id)));
		assertThat(detail.get("status").asText()).isEqualTo("SUCCESS");
		assertThat(detail.get("payload").asText()).isEqualTo("{\"msgId\":\"F1\"}");
	}

	@Test
	void callbackFailureRetriesThenAlerts() throws Exception {
		server.respond(r -> MockHttpServer.Response.json(200, "{\"code\":1,\"message\":\"拒收\"}"));
		saveChannel("ack2", "http", options("url", server.url("/ack"), "successField", "code", "successValues", "0", "messageField", "message"));
		saveFlow("msg", "cb", msgFlow(q("{`type`:`cb`,`callback`:`success`,`callbackChannel`:`ack2`,`callbackBody`:`{\\`id\\`:\\`{{msgId}}\\`}`,"
				+ "`callbackRetries`:`1`,`callbackBackoff`:`0`,`alertCallback`:true}")), true);
		saveFlow("msg", "no-cb", msgFlow(q("{`type`:`no-cb`}")), true);
		MockMvc mvc = mvc();
		send(mvc, "/msg/cb", "{\"msgId\":\"C1\"}");
		send(mvc, "/msg/no-cb", "{\"msgId\":\"N1\"}");

		await("回调最终失败", () -> "FAILED".equals(jdbc.queryForObject("select cb_status from magic_flow_msg where msg_id = 'C1'", String.class)));
		assertThat(status("cb", "C1")).isEqualTo("SUCCESS");
		assertThat(server.requests()).hasSize(2);
		await("回调失败告警", () -> ALERTS.stream().anyMatch(a -> a.getTitle().contains("cb 回调失败")));
		// 不需要回调的类型没有回调状态
		await("no-cb 处理完", () -> "SUCCESS".equals(status("no-cb", "N1")));
		assertThat(jdbc.queryForObject("select cb_status from magic_flow_msg where msg_id = 'N1'", String.class)).isNull();

		// 重发回调
		server.respond(r -> MockHttpServer.Response.json(200, "{\"code\":0}"));
		String id = jdbc.queryForObject("select id from magic_flow_msg where msg_id = 'C1'", String.class);
		assertThat(data(mvc.perform(post("/magic/web/flow/msg/callback").param("ids", id))).asInt()).isEqualTo(1);
		await("重发成功", () -> "SUCCESS".equals(jdbc.queryForObject("select cb_status from magic_flow_msg where msg_id = 'C1'", String.class)));
	}

	@Test
	void orderedByKeyKeepsOrderAndDeadBlocksGroup() throws Exception {
		saveFlow("msg", "adt", q("{`nodes`:["
				+ "{`id`:`start`,`type`:`msg`,`config`:{`type`:`adt`,`order`:`key`,`orderKey`:`input.body.pid`,`concurrency`:`4`,`maxRetries`:`2`,`backoff`:`0`}},"
				+ "{`id`:`check`,`type`:`validate`,`config`:{`source`:`[input]`,`mode`:`fail`,`rules`:[{`rule`:`expr`,`value`:`!(row.body.fail == 'once' && row.attempt < 2) && row.body.fail != 'always'`}]}},"
				+ "{`id`:`log`,`type`:`write`,`config`:{`sql`:`insert into ORDER_LOG (PID, N) values (#{input.body.pid}, #{input.body.n})`}}],"
				+ "`edges`:[{`source`:`start`,`target`:`check`},{`source`:`check`,`target`:`log`}]}"), true);
		MockMvc mvc = mvc();
		// P1 第 1 条先失败一次再成功；后面两条必须等它
		send(mvc, "/msg/adt", "{\"msgId\":\"a1\",\"pid\":\"P1\",\"n\":1,\"fail\":\"once\"}");
		send(mvc, "/msg/adt", "{\"msgId\":\"a2\",\"pid\":\"P1\",\"n\":2}");
		send(mvc, "/msg/adt", "{\"msgId\":\"b1\",\"pid\":\"P2\",\"n\":1}");
		send(mvc, "/msg/adt", "{\"msgId\":\"a3\",\"pid\":\"P1\",\"n\":3}");

		await("全部完成", () -> jdbc.queryForObject("select count(*) from ORDER_LOG", Integer.class) == 4);
		assertThat(jdbc.queryForList("select N from ORDER_LOG where PID = 'P1' order by ID", Integer.class)).containsExactly(1, 2, 3);

		// 死信阻塞：P3 第 1 条一直失败，第 2 条不处理；P4 不受影响
		send(mvc, "/msg/adt", "{\"msgId\":\"c1\",\"pid\":\"P3\",\"n\":1,\"fail\":\"always\"}");
		send(mvc, "/msg/adt", "{\"msgId\":\"c2\",\"pid\":\"P3\",\"n\":2}");
		send(mvc, "/msg/adt", "{\"msgId\":\"d1\",\"pid\":\"P4\",\"n\":1}");
		await("c1 死信", () -> "DEAD".equals(status("adt", "c1")));
		await("P4 完成", () -> "SUCCESS".equals(status("adt", "d1")));
		Thread.sleep(400);
		assertThat(status("adt", "c2")).isEqualTo("PENDING");

		// 忽略死信后放行
		String id = jdbc.queryForObject("select id from magic_flow_msg where msg_id = 'c1'", String.class);
		data(mvc.perform(post("/magic/web/flow/msg/ignore").param("ids", id)));
		await("c2 完成", () -> "SUCCESS".equals(status("adt", "c2")));
	}

	@Test
	void webServiceGenericAndCustomOperation() throws Exception {
		saveFlow("msg", "ws-lis", msgFlow(q("{`type`:`lis.result`}")), true);
		saveFlow("msg", "hip-a", msgFlow(q("{`type`:`hip.a`,`receive`:`soap`,`service`:`HIP`,`operation`:`HIPMessageServer`,`params`:`action,message`,"
				+ "`typeFrom`:`'hip.' + input.action`,`msgId`:`input.xml.Request.Header.MsgId`}")), true);
		saveFlow("msg", "hip-b", msgFlow(q("{`type`:`hip.b`,`receive`:`soap`,`service`:`HIP`,`operation`:`HIPMessageServer`,`params`:`action,message`,"
				+ "`typeFrom`:`'hip.' + input.action`,`msgId`:`input.xml.Request.Header.MsgId`}")), true);
		MockMvc mvc = mvc();

		// 通用 WebService：sendMessage(type, msgId, payload)，SOAP 1.2
		String wsdl = body(mvc.perform(get("/ws/msg").param("wsdl", "")));
		assertThat(wsdl).contains("<wsdl:operation name=\"sendMessage\">").contains("name=\"payload\"");
		String generic = "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:m=\"http://flow.magicapi.ssssssss.org/msg\"><soap:Body>"
				+ "<m:sendMessage><type>lis.result</type><msgId>W1</msgId><payload>{\"value\":\"7.1\"}</payload></m:sendMessage></soap:Body></soap:Envelope>";
		MvcResult r1 = mvc.perform(post("/ws/msg").contentType("application/soap+xml;charset=UTF-8").content(generic.getBytes(StandardCharsets.UTF_8))).andReturn();
		assertThat(r1.getResponse().getStatus()).as(r1.getResponse().getContentAsString(StandardCharsets.UTF_8) + " / " + r1.getResolvedException()).isEqualTo(200);
		assertThat(r1.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("&lt;code&gt;0&lt;/code&gt;").contains("W1");

		// 自定义方法：两个类型共用 HIPMessageServer(action, message)，按 action 区分，消息 id 在报文 XML 里
		String custom = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body><HIPMessageServer>"
				+ "<action>b</action><message>&lt;Request&gt;&lt;Header&gt;&lt;MsgId&gt;X9&lt;/MsgId&gt;&lt;/Header&gt;&lt;/Request&gt;</message>"
				+ "</HIPMessageServer></soapenv:Body></soapenv:Envelope>";
		MvcResult r2 = mvc.perform(post("/ws/HIP").contentType("text/xml;charset=UTF-8").content(custom.getBytes(StandardCharsets.UTF_8))).andReturn();
		assertThat(r2.getResponse().getStatus()).as(r2.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
		await("自定义方法的消息处理完", () -> "SUCCESS".equals(status("hip.b", "X9")));
		await("通用入口的消息处理完", () -> "SUCCESS".equals(status("lis.result", "W1")));
		assertThat(jdbc.queryForObject("select VAL from LIS_RESULT where MSG_ID = 'W1'", String.class)).isEqualTo("7.1");

		// 未知 action → Client Fault
		MvcResult r3 = mvc.perform(post("/ws/HIP").contentType("text/xml;charset=UTF-8")
				.content(custom.replace("<action>b</action>", "<action>z</action>").getBytes(StandardCharsets.UTF_8))).andReturn();
		assertThat(r3.getResponse().getStatus()).isEqualTo(500);
		assertThat(r3.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("soap:Client").contains("未知的消息类型");
	}

	@Test
	void typeConflictIsReported() throws Exception {
		saveFlow("msg", "one", msgFlow(q("{`type`:`dup`}")), true);
		FlowInfo second = saveFlow("msg", "two", msgFlow(q("{`type`:`dup`}")), true);
		JsonNode status = data(mvc().perform(get("/magic/web/flow/status").param("id", second.getId())));
		assertThat(status.get("errors").get(0).asText()).contains("消息类型 dup 已被流程「one」使用");
	}
}
