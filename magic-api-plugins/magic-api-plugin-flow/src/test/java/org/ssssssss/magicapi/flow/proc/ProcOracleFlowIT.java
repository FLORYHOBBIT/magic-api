package org.ssssssss.magicapi.flow.proc;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MvcResult;
import org.ssssssss.magicapi.core.model.ApiInfo;
import org.ssssssss.magicapi.datasource.model.MagicDynamicDataSource;
import org.ssssssss.magicapi.flow.support.AbstractFlowTest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 真实 Oracle 端到端：流程「调用存储过程」节点与脚本 proc 模块调用 zhis4_java_inteface.op_invoke。
 * 主数据源仍为 H2（插件自己的表建在 H2），Oracle 以数据源 his 注册，不在 Oracle 中建表。
 * 环境变量同 {@link ProcOracleIT}。
 */
@EnabledIfEnvironmentVariable(named = "FLOW_ORACLE_URL", matches = ".+")
class ProcOracleFlowIT extends AbstractFlowTest {

	private static final String REQ = "<req><hospitalId></hospitalId></req>";

	@BeforeEach
	void oracle() {
		MagicDynamicDataSource dynamic = context.getBean(MagicDynamicDataSource.class);
		if (!dynamic.datasources().contains("his")) {
			DriverManagerDataSource ds = new DriverManagerDataSource(System.getenv("FLOW_ORACLE_URL"), System.getenv("FLOW_ORACLE_USER"), System.getenv("FLOW_ORACLE_PASSWORD"));
			ds.setDriverClassName("oracle.jdbc.OracleDriver");
			dynamic.put("his", ds);
		}
	}

	@Test
	void flowNodeReturnsRawXmlOrWrappedJson() throws Exception {
		String node = "{`id`:`p1`,`type`:`proc`,`name`:`取医院信息`,`config`:{`datasource`:`his`,`name`:`zhis4_java_inteface.op_invoke`,`params`:["
				+ "{`name`:`as_method`,`value`:`input.method`},"
				+ "{`name`:`as_type`},"
				+ "{`name`:`ab_in_parm`,`type`:`clob`,`value`:`input.body`},"
				+ "{`name`:`rb_out_parm`,`mode`:`out`,`type`:`clob`,`parse`:`%s`}]}}";
		saveFlow("his", "hospital-raw", q("{`nodes`:[{`id`:`start`,`type`:`http`,`config`:{`method`:`POST`,`contentType`:`xml`}},"
				+ String.format(node, "string") + ",{`id`:`end`,`type`:`return`,`config`:{`value`:`p1.rb_out_parm`}}],"
				+ "`edges`:[{`source`:`start`,`target`:`p1`},{`source`:`p1`,`target`:`end`}]}"), true);
		saveFlow("his", "hospital-json", q("{`nodes`:[{`id`:`start`,`type`:`http`,`config`:{`method`:`POST`}},"
				+ String.format(node, "xml") + ",{`id`:`end`,`type`:`return`,`config`:{`value`:`{code: 0, data: p1.rb_out_parm.res}`}}],"
				+ "`edges`:[{`source`:`start`,`target`:`p1`},{`source`:`p1`,`target`:`end`}]}"), true);

		MvcResult raw = mvc().perform(post("/his/hospital-raw").param("method", "getHospitalInfo").contentType(MediaType.APPLICATION_XML).content(REQ)).andReturn();
		assertThat(raw.getResponse().getStatus()).isEqualTo(200);
		assertThat(raw.getResponse().getContentType()).startsWith("application/xml");
		assertThat(raw.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("<res service=\"getHospitalInfo\"><resultCode>0</resultCode>");

		JsonNode json = json(mvc().perform(post("/his/hospital-json").param("method", "getHospitalInfo").contentType(MediaType.APPLICATION_XML).content(REQ)));
		assertThat(json.at("/code").asInt()).isZero();
		assertThat(json.at("/data/resultCode").asText()).isEqualTo("0");
		assertThat(json.at("/data/hospitalName").asText()).isNotEmpty();
	}

	@Test
	void scriptModuleInfersParameters() throws Exception {
		ApiInfo api = new ApiInfo();
		api.setGroupId(group("api", "proc-oracle"));
		api.setName("hospital");
		api.setPath("hospital");
		api.setMethod("GET");
		api.setScript("import proc\nimport response\n"
				+ "var r = proc.use('his').call('zhis4_java_inteface.op_invoke', {as_method: 'getHospitalInfo', ab_in_parm: xml})\n"
				+ "return response.text(r.rb_out_parm)");
		resources().saveFile(api);
		ApiInfo args = new ApiInfo();
		args.setGroupId(api.getGroupId());
		args.setName("args");
		args.setPath("args");
		args.setMethod("GET");
		args.setScript("import proc\nreturn proc.use('his').arguments('zhis4_java_inteface.op_invoke')");
		resources().saveFile(args);

		String text = body(mvc().perform(get("/proc-oracle/hospital").param("xml", REQ)));
		assertThat(text).contains("<res service=\"getHospitalInfo\"><resultCode>0</resultCode>");
		JsonNode list = json(mvc().perform(get("/proc-oracle/args")));
		assertThat(list.at("/data/0").size()).isEqualTo(4);
		assertThat(list.at("/data/0/3/mode").asText()).isEqualTo("out");
	}

	@Test
	void editorReadsArgumentsFromOracle() throws Exception {
		JsonNode args = data(mvc().perform(get("/magic/web/flow/proc/arguments").param("datasource", "his").param("name", "zhis4_java_inteface.op_invoke")));
		assertThat(args.at("/0/1/name").asText()).isEqualTo("as_type");
		assertThat(args.at("/0/1/defaulted").asBoolean()).isTrue();
		assertThat(args.at("/0/2/type").asText()).isEqualTo("clob");
	}
}
