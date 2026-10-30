package org.ssssssss.magicapi.flow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.ssssssss.magicapi.flow.engine.FlowEngine;
import org.ssssssss.magicapi.flow.engine.FlowResult;
import org.ssssssss.magicapi.flow.model.FlowDefinitions;
import org.ssssssss.magicapi.flow.support.AbstractFlowTest;
import org.ssssssss.magicapi.flow.support.FlowTemplate;
import org.ssssssss.magicapi.flow.support.XmlCodec;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 6：字段映射、主从组装、报文模板、XML/JSON 转换、数据校验
 */
class FlowMessageTest extends AbstractFlowTest {

	@Autowired
	FlowEngine engine;

	@BeforeEach
	void schema() {
		jdbc.execute("drop table if exists SETL");
		jdbc.execute("drop table if exists SETL_FEE");
		jdbc.execute("drop table if exists SETL_DIAG");
		jdbc.execute("create table SETL (SETL_ID varchar(20), PSN_NAME varchar(20), SETL_TIME timestamp, AMT decimal(10,2))");
		jdbc.execute("create table SETL_FEE (SETL_ID varchar(20), ITEM_CODE varchar(20), FEE decimal(10,2))");
		jdbc.execute("create table SETL_DIAG (SETL_ID varchar(20), DIAG_CODE varchar(20))");
		jdbc.update("insert into SETL values ('S1', '张三', '2024-03-01 08:30:00', 100.5), ('S2', '', '2024-03-02 09:00:00', 3)");
		jdbc.update("insert into SETL_FEE values ('S1', 'A01', 60), ('S1', 'A02', 40.5), ('S2', 'B01', 3)");
		jdbc.update("insert into SETL_DIAG values ('S1', 'J18.9')");
	}

	@Test
	void masterChildMappingAndXmlTemplate() {
		FlowResult result = run(q("{`nodes`:["
				+ "{`id`:`m`,`type`:`query`,`config`:{`sql`:`select * from SETL order by SETL_ID`}},"
				+ "{`id`:`f`,`type`:`query`,`config`:{`sql`:`select * from SETL_FEE order by ITEM_CODE`}},"
				+ "{`id`:`d`,`type`:`query`,`config`:{`sql`:`select * from SETL_DIAG`}},"
				+ "{`id`:`nest`,`type`:`nest`,`config`:{`master`:`m`,`children`:["
				+ "  {`name`:`fees`,`source`:`f`,`masterKey`:`setl_id`,`removeKey`:true},"
				+ "  {`name`:`diag`,`source`:`d`,`masterKey`:`setl_id`,`single`:true}]}},"
				+ "{`id`:`map`,`type`:`map`,`config`:{`fields`:["
				+ "  {`name`:`setlId`,`value`:`row.setl_id`},"
				+ "  {`name`:`name`,`value`:`row.psn_name`,`default`:`未知`},"
				+ "  {`name`:`setlTime`,`value`:`row.setl_time`,`type`:`date`,`format`:`yyyyMMddHHmmss`},"
				+ "  {`name`:`amt`,`value`:`row.amt`,`type`:`string`},"
				+ "  {`name`:`feeCount`,`value`:`row.fees.size()`,`type`:`int`},"
				+ "  {`name`:`fees`},{`name`:`diagCode`,`value`:`row.diag == null ? '' : row.diag.diag_code`}]}},"
				+ "{`id`:`xml`,`type`:`template`,`config`:{`format`:`xml`,`template`:"
				+ "`<input>{{#each map as s}}<setl id=\\`{{s.setlId}}\\`><name>{{s.name}}</name><time>{{s.setlTime}}</time><amt>{{s.amt}}</amt>"
				+ "{{#if s.diagCode != ''}}<diag>{{s.diagCode}}</diag>{{else}}<diag/>{{/if}}"
				+ "{{#each s.fees as fee}}<fee no=\\`{{index + 1}}\\`>{{fee.item_code}}:{{fee.fee}}</fee>{{/each}}</setl>{{/each}}</input>`}}],"
				+ "`edges`:[{`source`:`m`,`target`:`f`},{`source`:`f`,`target`:`d`},{`source`:`d`,`target`:`nest`},{`source`:`nest`,`target`:`map`},{`source`:`map`,`target`:`xml`}]}"));

		assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
		assertThat(result.getValue()).isEqualTo("<input>"
				+ "<setl id=\"S1\"><name>张三</name><time>20240301083000</time><amt>100.50</amt><diag>J18.9</diag>"
				+ "<fee no=\"1\">A01:60.00</fee><fee no=\"2\">A02:40.50</fee></setl>"
				+ "<setl id=\"S2\"><name>未知</name><time>20240302090000</time><amt>3.00</amt><diag/>"
				+ "<fee no=\"1\">B01:3.00</fee></setl></input>");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> mapped = (List<Map<String, Object>>) result.getTrace().get(4).getOutput();
		assertThat(mapped.get(0)).containsEntry("feeCount", 2L).containsKeys("fees");
		@SuppressWarnings("unchecked")
		Map<String, Object> fee = ((List<Map<String, Object>>) mapped.get(0).get("fees")).get(0);
		assertThat(fee).doesNotContainKey("setl_id").containsKey("item_code");
	}

	@Test
	void jsonTemplateEscapesStringsAndEmbedsObjects() {
		String text = FlowTemplate.render("{\"name\": \"{{name}}\", \"list\": {{list}}, \"raw\": {{{raw}}}}", FlowTemplate.JSON, (expression, scope) -> {
			switch (expression) {
				case "name":
					return "a\"b\n";
				case "list":
					return Arrays.asList(1, "x");
				default:
					return "[true]";
			}
		});
		assertThat(text).isEqualTo("{\"name\": \"a\\\"b\\n\", \"list\": [1,\"x\"], \"raw\": [true]}");
		assertThatThrownBy(() -> FlowTemplate.render("{{#each a}}x", FlowTemplate.TEXT, (e, s) -> null)).hasMessageContaining("{{/each}}");
		assertThatThrownBy(() -> FlowTemplate.render("x{{/if}}", FlowTemplate.TEXT, (e, s) -> null)).hasMessageContaining("没有对应的开始标签");
	}

	@Test
	void xmlParseBuildAndJsonNodes() {
		FlowResult result = run(q("{`nodes`:["
				+ "{`id`:`p`,`type`:`xml`,`config`:{`source`:`input.xml`,`path`:`root.body`}},"
				+ "{`id`:`b`,`type`:`xml`,`config`:{`action`:`build`,`source`:`{resp: {code: 0, items: p.item}}`,`declaration`:false}},"
				+ "{`id`:`j`,`type`:`json`,`config`:{`action`:`stringify`,`source`:`p`}},"
				+ "{`id`:`back`,`type`:`json`,`config`:{`source`:`j`}},"
				+ "{`id`:`end`,`type`:`return`,`config`:{`value`:`{xml: b, count: back.item.size(), attr: p['@ver']}`}}],"
				+ "`edges`:[{`source`:`p`,`target`:`b`},{`source`:`b`,`target`:`j`},{`source`:`j`,`target`:`back`},{`source`:`back`,`target`:`end`}]}"),
				Collections.singletonMap("xml", "<ns:root xmlns:ns=\"urn:x\"><ns:body ver=\"2\"><item>a</item><item>b&amp;c</item></ns:body></ns:root>"));

		assertThat(result.isSuccess()).as(result.getMessage()).isTrue();
		Map<?, ?> value = (Map<?, ?>) result.getValue();
		assertThat(value.get("xml")).isEqualTo("<resp><code>0</code><items>a</items><items>b&amp;c</items></resp>");
		assertThat(value.get("count")).isEqualTo(2);
		assertThat(value.get("attr")).isEqualTo("2");
	}

	@Test
	void xmlParserRejectsExternalEntities() {
		assertThatThrownBy(() -> XmlCodec.parse("<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>"))
				.hasMessageContaining("XML 格式错误");
	}

	@Test
	void validateSplitsInvalidRows() {
		FlowResult result = run(q("{`nodes`:["
				+ "{`id`:`m`,`type`:`query`,`config`:{`sql`:`select * from SETL order by SETL_ID`}},"
				+ "{`id`:`v`,`type`:`validate`,`config`:{`rules`:["
				+ "  {`field`:`psn_name`,`rule`:`required`,`message`:`姓名不能为空`},"
				+ "  {`field`:`setl_id`,`rule`:`regex`,`value`:`S\\\\d+`},"
				+ "  {`rule`:`expr`,`value`:`row.amt > 5`,`message`:`金额过小`}]}},"
				+ "{`id`:`end`,`type`:`return`,`config`:{`value`:`{ok: v.valid.size(), bad: v.invalid[0].message}`}}],"
				+ "`edges`:[{`source`:`m`,`target`:`v`},{`source`:`v`,`target`:`end`}]}"));

		Map<?, ?> value = (Map<?, ?>) result.getValue();
		assertThat(value.get("ok")).isEqualTo(1);
		assertThat(value.get("bad")).isEqualTo("姓名不能为空；金额过小");

		FlowResult fail = run(q("{`nodes`:["
				+ "{`id`:`m`,`type`:`query`,`config`:{`sql`:`select * from SETL`}},"
				+ "{`id`:`v`,`type`:`validate`,`config`:{`mode`:`fail`,`rules`:[{`field`:`psn_name`,`rule`:`required`}]}}],"
				+ "`edges`:[{`source`:`m`,`target`:`v`}]}"));
		assertThat(fail.isSuccess()).isFalse();
		assertThat(fail.getMessage()).contains("1 行数据校验不通过");
	}

	@Test
	void mapNodeChecksConfiguration() {
		assertThatThrownBy(() -> engine.prepare(FlowDefinitions.parse(q("{`nodes`:[{`id`:`m`,`type`:`map`,`config`:{`fields`:[{`name`:`a`,`type`:`money`}]}}]}"))))
				.hasMessageContaining("string / number / int / date / boolean");
		assertThatThrownBy(() -> engine.prepare(FlowDefinitions.parse(q("{`nodes`:[{`id`:`n`,`type`:`nest`,`config`:{`children`:[{`name`:`a`}]}}]}"))))
				.hasMessageContaining("子表缺少 source");
		assertThatThrownBy(() -> engine.prepare(FlowDefinitions.parse(q("{`nodes`:[{`id`:`t`,`type`:`template`,`config`:{`template`:`{{#if a}}x`}}]}"))))
				.hasMessageContaining("{{/if}}");
	}

	@Test
	void triggerMustBeStartNode() {
		assertThatThrownBy(() -> engine.prepare(FlowDefinitions.parse(q("{`nodes`:[{`id`:`a`,`type`:`return`},{`id`:`h`,`type`:`http`,`name`:`入口`}],"
				+ "`edges`:[{`source`:`a`,`target`:`h`}]}"))))
				.hasMessageContaining("触发节点「入口」必须是起始节点");
	}

	private FlowResult run(String definition) {
		return run(definition, null);
	}

	private FlowResult run(String definition, Map<String, Object> input) {
		return engine.run(FlowDefinitions.parse(definition), "test", input == null ? null : new LinkedHashMap<>(input), true);
	}
}
