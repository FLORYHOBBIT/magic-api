package org.ssssssss.magicapi.flow.msg;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.ssssssss.magicapi.flow.support.AbstractFlowTest;
import org.ssssssss.magicapi.flow.support.MockHttpServer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 9 验收：用场景模板配置消息消费，只配置不写脚本。
 * <ul>
 *     <li>LIS 报告：只推送消息 id → 查 HIS 表取详情 → 回写已读；查不到详情时重试、转死信，补数据后人工重试成功</li>
 *     <li>PACS 报告：老系统调用自定义 WebService 方法 → 按消息 id 调对方接口取详情 → 入库 → 回调对方</li>
 *     <li>导出配置包：接收令牌、回调地址成为差异项</li>
 * </ul>
 */
@TestPropertySource(properties = "magic-api.flow.msg.poll-interval=100ms")
class MsgAcceptanceTest extends AbstractFlowTest {

	private MockHttpServer pacs;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.execute("drop table if exists lis_report");
		jdbc.execute("create table lis_report (report_no varchar(32) primary key, patient_id varchar(20), read_flag varchar(1))");
		jdbc.update("insert into lis_report values ('R1', 'P1', '0')");
		jdbc.execute("drop table if exists pacs_report");
		jdbc.execute("create table pacs_report (report_no varchar(32), content varchar(200))");
		context.getBean(JdbcMsgStore.class).jdbc();
		jdbc.execute("delete from magic_flow_msg");
		pacs = new MockHttpServer();
	}

	@AfterEach
	void tearDown() {
		pacs.close();
	}

	private String apply(MockMvc mvc, String template, Map<String, Object> params) throws Exception {
		return data(mvc.perform(post("/magic/web/flow/template/apply").param("id", template).param("params", MAPPER.writeValueAsString(params)))).asText();
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
		return column("status", type, msgId);
	}

	private String column(String column, String type, String msgId) {
		java.util.List<String> list = jdbc.queryForList("select " + column + " from magic_flow_msg where msg_type = ? and msg_id = ?", String.class, type, msgId);
		return list.isEmpty() ? null : list.get(0);
	}

	@Test
	void lisReportOnlyMsgIdLookupTableRetryDeadThenManualRetry() throws Exception {
		MockMvc mvc = mvc();
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("type", "lis.report");
		params.put("querySql", "select * from lis_report where report_no = #{input.msgId}");
		params.put("writeSql", "update lis_report set read_flag = '1' where report_no = #{d.report_no}");
		String definition = apply(mvc, "msg-query", params)
				// 测试中缩短重试等待
				.replace("\"60,300,1800\"", "\"0\"").replace("\"maxRetries\" : \"5\"", "\"maxRetries\" : \"1\"");
		saveFlow("msg", "lis-report", definition, true);

		// 只有消息 id
		assertThat(mvc.perform(post("/msg/lis.report").contentType(MediaType.APPLICATION_JSON).content("{\"msgId\":\"R1\"}")).andReturn().getResponse().getStatus()).isEqualTo(200);
		await("R1 处理成功", () -> "SUCCESS".equals(status("lis.report", "R1")));
		assertThat(jdbc.queryForObject("select read_flag from lis_report where report_no = 'R1'", String.class)).isEqualTo("1");

		// 消息先到、数据后到：查不到详情 → 重试 → 死信
		mvc.perform(post("/msg/lis.report").param("msgId", "R2"));
		await("R2 死信", () -> "DEAD".equals(status("lis.report", "R2")));
		assertThat(jdbc.queryForObject("select error from magic_flow_msg where msg_id = 'R2'", String.class)).contains("按消息 id 查不到详情");

		// 数据补上后在消息中心重试
		jdbc.update("insert into lis_report values ('R2', 'P2', '0')");
		JsonNode list = data(mvc.perform(get("/magic/web/flow/msg/list").param("type", "lis.report").param("status", "DEAD")));
		assertThat(list.get("total").asInt()).isEqualTo(1);
		data(mvc.perform(post("/magic/web/flow/msg/retry").param("ids", list.get("rows").get(0).get("id").asText())));
		await("R2 重试成功", () -> "SUCCESS".equals(status("lis.report", "R2")));
		assertThat(jdbc.queryForObject("select read_flag from lis_report where report_no = 'R2'", String.class)).isEqualTo("1");
	}

	@Test
	void pacsLegacyWebServiceLookupInterfaceAndCallback() throws Exception {
		pacs.respond(r -> r.path.equals("/detail")
				? MockHttpServer.Response.json(200, "{\"code\":0,\"data\":{\"reportNo\":\"PX1\",\"content\":\"未见异常\"}}")
				: MockHttpServer.Response.json(200, "{\"code\":0}"));
		saveChannel("pacs-detail", "http", options("url", pacs.url("/detail"), "successField", "code", "successValues", "0"));
		saveChannel("pacs-ack", "http", options("url", pacs.url("/ack"), "successField", "code", "successValues", "0"));
		MockMvc mvc = mvc();
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("type", "pacs.report");
		params.put("channel", "pacs-detail");
		params.put("writeSql", "insert into pacs_report values (#{detail.data.data.reportNo}, #{detail.data.data.content})");
		params.put("callback", "success");
		params.put("callbackChannel", "pacs-ack");
		String definition = apply(mvc, "msg-call", params);
		// 老系统：只能调用 PacsService.notify(xml)，消息 id 在 XML 里；接收令牌直接写在节点上
		definition = definition.replace("\"type\" : \"pacs.report\",", "\"type\" : \"pacs.report\", \"receive\" : \"soap\", \"service\" : \"PacsService\", "
				+ "\"operation\" : \"notify\", \"params\" : \"xml\", \"msgId\" : \"input.xml.msg.id\", \"auth\" : \"token\", \"token\" : \"pacs-123\",");
		saveFlow("msg", "pacs-report", definition, true);

		String soap = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body><notify>"
				+ "<xml>&lt;msg&gt;&lt;id&gt;PX1&lt;/id&gt;&lt;/msg&gt;</xml></notify></soapenv:Body></soapenv:Envelope>";
		int denied = mvc.perform(post("/ws/PacsService").contentType("text/xml;charset=UTF-8").content(soap.getBytes(StandardCharsets.UTF_8))).andReturn().getResponse().getStatus();
		assertThat(denied).isEqualTo(500);
		String reply = body(mvc.perform(post("/ws/PacsService").header("X-Token", "pacs-123").contentType("text/xml;charset=UTF-8").content(soap.getBytes(StandardCharsets.UTF_8))));
		assertThat(reply).contains("notifyResponse").contains("PX1");

		await("处理并回调", () -> "SUCCESS".equals(column("cb_status", "pacs.report", "PX1")));
		assertThat(jdbc.queryForObject("select content from pacs_report where report_no = 'PX1'", String.class)).isEqualTo("未见异常");
		assertThat(pacs.requests().get(0).path).isEqualTo("/detail");
		assertThat(pacs.requests().get(0).body).contains("\"msgId\":\"PX1\"");
		assertThat(pacs.requests().get(1).path).isEqualTo("/ack");
		assertThat(pacs.requests().get(1).body).contains("\"msgId\": \"PX1\"").contains("\"status\": \"SUCCESS\"");

		// 导出配置包：令牌、取详情地址、回调地址都是差异项
		String exported = data(mvc.perform(post("/magic/web/flow/package/export").contentType(MediaType.APPLICATION_JSON).content("{}"))).asText();
		Map<String, String> files = unzip(Base64.getDecoder().decode(exported));
		String manifest = files.get("manifest.json");
		assertThat(manifest).contains("pacs-report.token").contains("pacs-detail.url").contains("pacs-ack.url");
		String flowFile = files.entrySet().stream().filter(e -> e.getKey().startsWith("flows/") && e.getValue().contains("pacs.report")).map(Map.Entry::getValue).findFirst().orElse("");
		assertThat(flowFile).contains("${var.pacs-report.token}").doesNotContain("pacs-123");
	}

	private static Map<String, String> unzip(byte[] bytes) throws Exception {
		Map<String, String> files = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
			ZipEntry entry;
			byte[] buffer = new byte[8192];
			while ((entry = zip.getNextEntry()) != null) {
				java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
				int len;
				while ((len = zip.read(buffer)) != -1) {
					out.write(buffer, 0, len);
				}
				files.put(entry.getName(), new String(out.toByteArray(), StandardCharsets.UTF_8));
			}
		}
		return files;
	}
}
