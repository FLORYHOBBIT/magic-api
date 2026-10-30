package org.ssssssss.magicapi.flow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.ssssssss.magicapi.flow.model.FlowInfo;
import org.ssssssss.magicapi.flow.support.AbstractFlowTest;
import org.ssssssss.magicapi.flow.support.XmlCodec;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 3 / Task 9：流程发布为 HTTP 接口与 WebService
 */
class FlowPublishTest extends AbstractFlowTest {

	@BeforeEach
	void schema() {
		jdbc.execute("drop table if exists PATIENT");
		jdbc.execute("create table PATIENT (PATIENT_ID varchar(20) primary key, NAME varchar(50), SEX varchar(2))");
		jdbc.update("insert into PATIENT values ('P1', '张三', '男'), ('P2', 'O''Brien <&>', '女')");
	}

	@Test
	void httpTriggerPublishesQueryApi() throws Exception {
		FlowInfo info = saveFlow("his", "patient", q("{`nodes`:["
				+ "{`id`:`start`,`type`:`http`,`config`:{`method`:`GET`}},"
				+ "{`id`:`q`,`type`:`query`,`config`:{`sql`:`select PATIENT_ID, NAME from PATIENT where PATIENT_ID = #{input.pid}`,`result`:`one`}},"
				+ "{`id`:`end`,`type`:`return`,`config`:{`value`:`{code: 0, data: q, ip: input.clientIp}`}}],"
				+ "`edges`:[{`source`:`start`,`target`:`q`},{`source`:`q`,`target`:`end`}]}"), true);
		MockMvc mvc = mvc();

		MvcResult result = mvc.perform(get("/his/patient").param("pid", "P1")).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getContentType()).startsWith("application/json");
		JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
		assertThat(body.at("/data/name").asText()).isEqualTo("张三");
		assertThat(body.at("/code").asInt()).isZero();

		// 方法不对：没有这个路由
		assertThat(mvc.perform(post("/his/patient")).andReturn().getResponse().getStatus()).isIn(404, 405);

		// 状态接口显示已注册的地址
		JsonNode status = data(mvc.perform(get("/magic/web/flow/status").param("id", info.getId())));
		assertThat(status.get("triggers").get(0).asText()).isEqualTo("HTTP GET /his/patient");
		assertThat(status.get("records").get(0).get("trigger").asText()).isEqualTo("http");

		// 停用后路由注销
		saveFlow("his", "patient", info.getScript(), false);
		assertThat(mvc.perform(get("/his/patient").param("pid", "P1")).andReturn().getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void httpTriggerReadsJsonBodyChecksTokenAndReturnsXml() throws Exception {
		saveFlow("his", "echo", q("{`nodes`:["
				+ "{`id`:`start`,`type`:`http`,`config`:{`method`:`POST`,`auth`:`token`,`token`:`${var.echo-token:s3cret}`,`contentType`:`xml`}},"
				+ "{`id`:`end`,`type`:`return`,`config`:{`value`:`{result: {name: input.name, count: input.items.size()}}`}}],"
				+ "`edges`:[{`source`:`start`,`target`:`end`}]}"), true);
		MockMvc mvc = mvc();

		MvcResult denied = mvc.perform(post("/his/echo").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
		assertThat(denied.getResponse().getStatus()).isEqualTo(401);

		MvcResult ok = mvc.perform(post("/his/echo").header("X-Token", "s3cret").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"李四\",\"items\":[1,2,3]}")).andReturn();
		assertThat(ok.getResponse().getStatus()).isEqualTo(200);
		assertThat(ok.getResponse().getContentType()).startsWith("application/xml");
		String xml = ok.getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(xml).contains("<result><name>李四</name><count>3</count></result>");
	}

	@Test
	void httpTriggerFailureReturnsErrorJson() throws Exception {
		saveFlow("his", "broken", q("{`nodes`:["
				+ "{`id`:`start`,`type`:`http`,`config`:{`method`:`GET`,`errorStatus`:`502`}},"
				+ "{`id`:`q`,`type`:`query`,`name`:`查不存在的表`,`config`:{`sql`:`select * from NOT_EXISTS`}}],"
				+ "`edges`:[{`source`:`start`,`target`:`q`}]}"), true);

		MvcResult result = mvc().perform(get("/his/broken")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(502);
		JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
		assertThat(body.get("code").asInt()).isEqualTo(-1);
		assertThat(body.get("node").asText()).isEqualTo("q");
		assertThat(body.get("message").asText()).containsIgnoringCase("NOT_EXISTS");
	}

	@Test
	void httpPathConflictIsReported() throws Exception {
		org.ssssssss.magicapi.core.model.ApiInfo api = new org.ssssssss.magicapi.core.model.ApiInfo();
		api.setGroupId(group("api", "his"));
		api.setName("占用");
		api.setPath("taken");
		api.setMethod("GET");
		api.setScript("return 'api'");
		resources().saveFile(api);
		String definition = q("{`nodes`:[{`id`:`start`,`type`:`http`,`config`:{`method`:`GET`}},{`id`:`end`,`type`:`return`,`config`:{`value`:`1`}}],"
				+ "`edges`:[{`source`:`start`,`target`:`end`}]}");

		// 与 magic-api 接口同路径：流程照常保存，触发器注册失败，状态里能看到原因
		FlowInfo info = saveFlow("his", "taken", definition, true);

		JsonNode status = data(mvc().perform(get("/magic/web/flow/status").param("id", info.getId())));
		assertThat(status.get("triggers")).isEmpty();
		assertThat(status.get("errors").get(0).asText()).contains("GET /his/taken").contains("已被其他接口或流程占用");
	}

	@Test
	void soapTriggerPublishesWebServiceWithWsdl() throws Exception {
		saveFlow("ws", "query-patient", q("{`nodes`:["
				+ "{`id`:`start`,`type`:`soap`,`config`:{`service`:`HisService`,`operation`:`queryPatient`,`params`:`message`}},"
				+ "{`id`:`req`,`type`:`xml`,`config`:{`action`:`parse`,`source`:`input.message`,`path`:`request`}},"
				+ "{`id`:`q`,`type`:`query`,`config`:{`sql`:`select * from PATIENT where PATIENT_ID = #{req.patientId}`}},"
				+ "{`id`:`resp`,`type`:`template`,`config`:{`format`:`xml`,`template`:`<response><code>0</code>{{#each q as r}}<patient><name>{{r.name}}</name></patient>{{/each}}</response>`}},"
				+ "{`id`:`end`,`type`:`return`,`config`:{`value`:`resp`}}],"
				+ "`edges`:[{`source`:`start`,`target`:`req`},{`source`:`req`,`target`:`q`},{`source`:`q`,`target`:`resp`},{`source`:`resp`,`target`:`end`}]}"), true);
		saveFlow("ws", "ping", q("{`nodes`:["
				+ "{`id`:`start`,`type`:`soap`,`config`:{`service`:`HisService`,`operation`:`ping`,`params`:`a,b`}},"
				+ "{`id`:`end`,`type`:`return`,`config`:{`value`:`{pong: input.a + input.b}`}}],"
				+ "`edges`:[{`source`:`start`,`target`:`end`}]}"), true);
		MockMvc mvc = mvc();

		String wsdl = body(mvc.perform(get("/ws/HisService").param("wsdl", "")));
		assertThat(wsdl).contains("<wsdl:operation name=\"queryPatient\">").contains("<wsdl:operation name=\"ping\">")
				.contains("<xsd:element name=\"a\" type=\"xsd:string\"").contains("soap12:address");
		// WSDL 本身是合法 XML
		XmlCodec.document(wsdl);

		// 参数是转义后的 XML 字符串（常见的 String 入参写法）
		String request = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:his=\"http://flow.magicapi.ssssssss.org/HisService\">"
				+ "<soapenv:Body><his:queryPatient><message>&lt;request&gt;&lt;patientId&gt;P2&lt;/patientId&gt;&lt;/request&gt;</message></his:queryPatient></soapenv:Body></soapenv:Envelope>";
		MvcResult result = mvc.perform(post("/ws/HisService").contentType("text/xml;charset=UTF-8").content(request.getBytes(StandardCharsets.UTF_8))).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		String response = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		Map<String, Object> parsed = XmlCodec.parse(response);
		@SuppressWarnings("unchecked")
		Map<String, Object> body = (Map<String, Object>) ((Map<String, Object>) parsed.get("Envelope")).get("Body");
		@SuppressWarnings("unchecked")
		String inner = (String) ((Map<String, Object>) body.get("queryPatientResponse")).get("return");
		assertThat(inner).isEqualTo("<response><code>0</code><patient><name>O&apos;Brien &lt;&amp;&gt;</name></patient></response>");

		// 参数是未转义的 XML 片段、SOAP 1.2
		String request12 = "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\"><soap:Body>"
				+ "<ping xmlns=\"http://flow.magicapi.ssssssss.org/HisService\"><a>1</a><b>2</b></ping></soap:Body></soap:Envelope>";
		MvcResult ping = mvc.perform(post("/ws/HisService").contentType("application/soap+xml;charset=UTF-8").content(request12.getBytes(StandardCharsets.UTF_8))).andReturn();
		assertThat(ping.getResponse().getContentType()).startsWith("application/soap+xml");
		assertThat(ping.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("<return>&lt;pong&gt;12&lt;/pong&gt;</return>");

		// 不存在的方法：SOAP Fault（Client）
		String unknown = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body><nope/></soapenv:Body></soapenv:Envelope>";
		MvcResult fault = mvc.perform(post("/ws/HisService").contentType("text/xml").content(unknown)).andReturn();
		assertThat(fault.getResponse().getStatus()).isEqualTo(500);
		assertThat(fault.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("<faultcode>soap:Client</faultcode>").contains("没有方法 nope");

		// 同一服务重复的方法名：第二个流程注册失败
		FlowInfo duplicate = saveFlow("ws", "ping-copy", q("{`nodes`:["
				+ "{`id`:`start`,`type`:`soap`,`config`:{`service`:`HisService`,`operation`:`ping`}},"
				+ "{`id`:`end`,`type`:`return`,`config`:{`value`:`1`}}],`edges`:[{`source`:`start`,`target`:`end`}]}"), true);
		JsonNode status = data(mvc.perform(get("/magic/web/flow/status").param("id", duplicate.getId())));
		assertThat(status.get("errors").get(0).asText()).contains("ping").contains("已被流程");
	}

	@Test
	void soapFlowFailureReturnsServerFault() throws Exception {
		saveFlow("ws", "fail", q("{`nodes`:["
				+ "{`id`:`start`,`type`:`soap`,`config`:{`service`:`FailService`,`operation`:`boom`}},"
				+ "{`id`:`q`,`type`:`query`,`config`:{`sql`:`select * from NOT_EXISTS`}}],"
				+ "`edges`:[{`source`:`start`,`target`:`q`}]}"), true);
		String request = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body><boom><message>x</message></boom></soapenv:Body></soapenv:Envelope>";

		MvcResult result = mvc().perform(post("/ws/FailService").contentType("text/xml").content(request)).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(500);
		assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("<faultcode>soap:Server</faultcode>");
	}
}
