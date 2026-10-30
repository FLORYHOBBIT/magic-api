package org.ssssssss.magicapi.flow.proc;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.ssssssss.magicapi.core.model.ApiInfo;
import org.ssssssss.magicapi.flow.support.AbstractFlowTest;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 「调用存储过程」节点、脚本模块 proc 与编辑器接口（数据库调用由 ProcService 替身完成，真实 Oracle 见 ProcOracleIT）
 */
class ProcFlowTest extends AbstractFlowTest {

	@MockBean
	ProcService procService;

	@BeforeEach
	void reset() {
		org.mockito.Mockito.reset(procService);
	}

	@Test
	void nodeEvaluatesParamsAndReturnsOutputRaw() throws Exception {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("rb_out_parm", "<res><resultCode>0</resultCode></res>");
		when(procService.call(any(ProcSpec.class))).thenReturn(out);
		saveFlow("his", "hospital", q("{`nodes`:["
				+ "{`id`:`start`,`type`:`http`,`config`:{`method`:`POST`}},"
				+ "{`id`:`p1`,`type`:`proc`,`config`:{`datasource`:`his`,`name`:`zhis4_java_inteface.op_invoke`,`timeout`:`30`,`params`:["
				+ "{`name`:`as_method`,`value`:`'getHospitalInfo'`},"
				+ "{`name`:`as_type`,`value`:``},"
				+ "{`name`:`ab_in_parm`,`type`:`clob`,`value`:`input.body`},"
				+ "{`name`:`rb_out_parm`,`mode`:`out`,`type`:`clob`,`parse`:`string`}]}},"
				+ "{`id`:`end`,`type`:`return`,`config`:{`value`:`p1.rb_out_parm`}}],"
				+ "`edges`:[{`source`:`start`,`target`:`p1`},{`source`:`p1`,`target`:`end`}]}"), true);

		MvcResult result = mvc().perform(post("/his/hospital").contentType(MediaType.APPLICATION_XML).content("<req><hospitalId/></req>")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("<res><resultCode>0</resultCode></res>");
		ArgumentCaptor<ProcSpec> captor = ArgumentCaptor.forClass(ProcSpec.class);
		verify(procService).call(captor.capture());
		ProcSpec spec = captor.getValue();
		assertThat(spec.getDatasource()).isEqualTo("his");
		assertThat(spec.getTimeout()).isEqualTo(30);
		ProcSpec.ProcStatement st = spec.build();
		assertThat(st.getSql()).isEqualTo("begin zhis4_java_inteface.op_invoke(as_method => ?, ab_in_parm => ?, rb_out_parm => ?); end;");
		assertThat(st.getBindings().get(0).getValue()).isEqualTo("getHospitalInfo");
		assertThat(st.getBindings().get(1).getValue()).isEqualTo("<req><hospitalId/></req>");
	}

	@Test
	void nodeOutputCanBeWrappedAndErrorsFailTheFlow() throws Exception {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("code", 0L);
		out.put("rows", Collections.singletonList(Collections.singletonMap("pat_name", "张三")));
		when(procService.call(any(ProcSpec.class))).thenReturn(out);
		saveFlow("his", "patients", q("{`nodes`:["
				+ "{`id`:`start`,`type`:`http`,`config`:{`method`:`GET`}},"
				+ "{`id`:`p`,`type`:`proc`,`name`:`查患者`,`config`:{`mode`:`sql`,`sql`:`begin pkg.q(:pid, :code, :rows); end;`,`params`:["
				+ "{`name`:`pid`,`value`:`input.pid`},{`name`:`code`,`mode`:`out`,`type`:`integer`},{`name`:`rows`,`mode`:`out`,`type`:`cursor`}]}},"
				+ "{`id`:`end`,`type`:`return`,`config`:{`value`:`{code: p.code, data: p.rows}`}}],"
				+ "`edges`:[{`source`:`start`,`target`:`p`},{`source`:`p`,`target`:`end`}]}"), true);

		JsonNode body = json(mvc().perform(get("/his/patients").param("pid", "P1")));
		assertThat(body.at("/code").asInt()).isZero();
		assertThat(body.at("/data/0/pat_name").asText()).isEqualTo("张三");

		when(procService.call(any(ProcSpec.class))).thenThrow(new ProcException("调用存储过程失败：ORA-20001: 患者不存在（语句：begin pkg.q(?, ?, ?); end;）"));
		MvcResult failed = mvc().perform(get("/his/patients").param("pid", "X")).andReturn();
		assertThat(failed.getResponse().getStatus()).isEqualTo(500);
		JsonNode error = MAPPER.readTree(failed.getResponse().getContentAsString(StandardCharsets.UTF_8));
		assertThat(error.get("node").asText()).isEqualTo("p");
		assertThat(error.get("message").asText()).contains("ORA-20001: 患者不存在");
	}

	@Test
	void checkRejectsBadConfigBeforeRunning() throws Exception {
		String definition = q("{`nodes`:[{`id`:`start`,`type`:`http`,`config`:{`method`:`GET`}},"
				+ "{`id`:`p`,`type`:`proc`,`config`:{`name`:`pkg.p; drop table x`}}],`edges`:[{`source`:`start`,`target`:`p`}]}");
		JsonNode result = json(mvc().perform(post("/magic/web/flow/validate").param("definition", definition)));
		assertThat(result.toString()).contains("过程名").contains("不合法");
		verify(procService, never()).call(any(ProcSpec.class));
	}

	@Test
	void scriptModuleCallsByNameAndBySpec() throws Exception {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("rb_out_parm", "<res/>");
		when(procService.call(any(), eq("zhis4_java_inteface.op_invoke"), any(), any())).thenReturn(out);
		when(procService.call(any(ProcSpec.class))).thenReturn(Collections.singletonMap("r", Collections.singletonList(Collections.singletonMap("id", 1))));
		saveApi("raw", "import proc\nimport response\nreturn response.text(proc.call('zhis4_java_inteface.op_invoke', {as_method: 'getHospitalInfo', ab_in_parm: xml}).rb_out_parm)");
		saveApi("std", "import proc\nreturn proc.use('his').call({mode: 'sql', sql: 'begin pkg.p(:a, :r); end;', params: [{name: 'a', value: a}, {name: 'r', mode: 'out', type: 'cursor'}]})");

		MvcResult raw = mvc().perform(get("/proc-api/raw").param("xml", "<req/>")).andReturn();
		assertThat(raw.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("<res/>");
		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<Map<String, ?>> inputs = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
		verify(procService).call(isNull(), eq("zhis4_java_inteface.op_invoke"), inputs.capture(), isNull());
		assertThat(inputs.getValue().get("as_method")).isEqualTo("getHospitalInfo");
		assertThat(inputs.getValue().get("ab_in_parm")).isEqualTo("<req/>");

		JsonNode std = json(mvc().perform(get("/proc-api/std").param("a", "7")));
		assertThat(std.at("/code").asInt()).isEqualTo(1);
		assertThat(std.at("/data/r/0/id").asInt()).isEqualTo(1);
		ArgumentCaptor<ProcSpec> spec = ArgumentCaptor.forClass(ProcSpec.class);
		verify(procService).call(spec.capture());
		assertThat(spec.getValue().getDatasource()).isEqualTo("his");
		ProcSpec.ProcStatement st = spec.getValue().build();
		assertThat(st.getSql()).isEqualTo("begin pkg.p(?, ?); end;");
		assertThat(st.getBindings().get(0).getValue()).isEqualTo("7");
		assertThat(st.getBindings().get(1).getType()).isEqualTo("cursor");
	}

	@Test
	void editorReadsArgumentsAndPreviewsStatement() throws Exception {
		Map<String, Object> arg = new LinkedHashMap<>();
		arg.put("name", "as_method");
		arg.put("mode", "in");
		arg.put("type", "varchar");
		when(procService.arguments(eq("his"), eq("pkg.op_invoke"), eq(true))).thenReturn(Collections.singletonList(Collections.singletonList(arg)));
		JsonNode args = data(mvc().perform(get("/magic/web/flow/proc/arguments").param("datasource", "his").param("name", "pkg.op_invoke")));
		assertThat(args.at("/0/0/name").asText()).isEqualTo("as_method");

		when(procService.arguments(any(), eq("nope.p"), anyBoolean())).thenThrow(new ProcException("找不到过程 nope.p，或当前用户没有执行权限"));
		JsonNode missing = json(mvc().perform(get("/magic/web/flow/proc/arguments").param("name", "nope.p")));
		assertThat(missing.get("code").asInt()).isEqualTo(5025);
		assertThat(missing.get("message").asText()).contains("找不到过程 nope.p");

		String config = q("{`name`:`pkg.op_invoke`,`params`:[{`name`:`as_method`,`value`:`'x'`},{`name`:`as_type`},{`name`:`r`,`mode`:`out`,`type`:`clob`}]}");
		JsonNode preview = data(mvc().perform(post("/magic/web/flow/proc/preview").contentType(MediaType.APPLICATION_JSON).content(config)));
		assertThat(preview.asText()).isEqualTo("begin pkg.op_invoke(as_method => ?, r => ?); end;");
		JsonNode bad = json(mvc().perform(post("/magic/web/flow/proc/preview").contentType(MediaType.APPLICATION_JSON)
				.content(q("{`mode`:`sql`,`sql`:`begin p(:a); end;`}"))));
		assertThat(bad.get("message").asText()).contains(":a 没有在参数表中定义");
	}

	private static void saveApi(String path, String script) {
		ApiInfo api = new ApiInfo();
		api.setGroupId(group("api", "proc-api"));
		api.setName(path);
		api.setPath(path);
		api.setMethod("GET");
		api.setScript(script);
		resources().saveFile(api);
	}
}
