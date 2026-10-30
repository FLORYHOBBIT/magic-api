package org.ssssssss.magicapi.flow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.ssssssss.magicapi.flow.model.FlowInfo;
import org.ssssssss.magicapi.flow.support.AbstractFlowTest;
import org.ssssssss.magicapi.flow.support.FlowVariables;
import org.ssssssss.magicapi.flow.support.MockHttpServer;
import org.ssssssss.magicapi.flow.support.MockHttpServer.Response;
import org.ssssssss.magicapi.flow.trigger.FlowJobService;
import org.ssssssss.magicapi.upload.engine.JobResult;
import org.ssssssss.magicapi.upload.engine.JobStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 12 验收：用流程配置复刻 magic-scripts/insur_trace/upload_3501.ms（商品盘存上传）。
 * <p>
 * 对照脚本版的行为：
 * <ul>
 *     <li>只取上传记录中状态不是 1 的数据，逐行上传</li>
 *     <li>报文：统一报文头 + input.invinfo（SQL 列去掉 send_flag、pk_value，日期转字符串，追加 drugtracinfo）</li>
 *     <li>请求头 x-tif-paasid / signature / timestamp / nonce，signature = sha256(ts + secret + nonce + ts)</li>
 *     <li>infcode = 0 成功，回写状态 1；失败回写状态 0、失败次数 +1，下次重传</li>
 * </ul>
 * 配置（case_interface_config 中的地址、密钥、机构编码）改为差异项。
 */
class FlowAcceptanceTest extends AbstractFlowTest {

	private static final String SECRET = "sk-test-0001";

	private MockHttpServer server;

	@Autowired
	FlowJobService jobService;

	@Autowired
	FlowVariables variables;

	@BeforeEach
	void setUp() throws Exception {
		server = new MockHttpServer();
		jdbc.execute("drop table if exists v_gjyhyb_goods_invinfo");
		jdbc.execute("drop table if exists insur_goods_upload_record");
		jdbc.execute("create table v_gjyhyb_goods_invinfo (pk_value varchar(32) primary key, med_list_codg varchar(50), fixmedins_hilist_id varchar(50),"
				+ " fixmedins_hilist_name varchar(100), rx_flag varchar(2), invdate timestamp, inv_cnt decimal(12,2), manu_lotnum varchar(50),"
				+ " fixmedins_bchno varchar(50), manu_date varchar(20), expy_end varchar(20), memo varchar(100), drug_trac_codg varchar(100))");
		jdbc.execute("create table insur_goods_upload_record (trade_type varchar(10), pk_key varchar(32), goods_code varchar(50), goods_name varchar(100),"
				+ " status varchar(2), error_times int default 0, in_parm clob, result_parm clob)");
		jdbc.update("insert into v_gjyhyb_goods_invinfo values "
				+ "('K1', 'XA01', 'H001', '阿莫西林胶囊', '1', '2024-05-01 10:00:00', 20, 'L1', 'B1', '2023-01-01', '2026-01-01', null, '81234567890123456789'),"
				+ "('K2', 'XA02', 'H002', '布洛芬片', '0', '2024-05-01 11:00:00', 5.5, 'L2', 'B2', '2023-02-01', '2026-02-01', '备注', ''),"
				+ "('K3', 'XA03', 'H003', '维生素C', '0', '2024-05-01 12:00:00', 1, 'L3', 'B3', '2023-03-01', '2026-03-01', null, null)");
		// 已上传成功的不再上传
		jdbc.update("insert into v_gjyhyb_goods_invinfo values ('K0', 'XA00', 'H000', '已传', '0', '2024-04-01 10:00:00', 1, 'L0', 'B0', '', '', null, null)");
		jdbc.update("insert into insur_goods_upload_record (trade_type, pk_key, status) values ('3501', 'K0', '1')");

		saveChannel("gdyb", "http", options("url", server.url("/isp-api/powercsb/3501"),
				"successField", "infcode", "successValues", "0", "messageField", "err_msg"));
		variables.save("gdyb.secretkey", SECRET, true, "医保密钥");
		variables.save("gdyb.paasid", "paas-001", false, "x-tif-paasid");
		variables.save("gdyb.fixmedins-code", "H44010100001", false, "定点机构编码");
		variables.save("gdyb.fixmedins-name", "测试医院", false, "定点机构名称");
		variables.save("gdyb.infver", "V1.0", false, "接口版本");
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	@Test
	void replicatesTraceCodeInventoryUpload() throws Exception {
		server.respond(r -> r.body.contains("\"fixmedins_hilist_id\":\"H002\"")
				? Response.json(200, "{\"infcode\":-1,\"err_msg\":\"追溯码格式错误\"}")
				: Response.json(200, "{\"infcode\":0,\"output\":{}}"));
		String definition = new String(Files.readAllBytes(Paths.get("examples", "insur-3501.json")), StandardCharsets.UTF_8);
		FlowInfo info = saveFlow("insur", "upload-3501", definition, true);

		JobResult first = jobService.run(resources().file(info.getId()), "manual");

		assertThat(first.getStatus()).as(first.getMessage()).isEqualTo(JobStatus.SUCCESS);
		assertThat(server.requests()).hasSize(3);
		MockHttpServer.Request request = server.requests().get(0);
		JsonNode body = MAPPER.readTree(request.body);
		// 报文头与脚本版字段一致
		assertThat(fieldNames(body)).containsExactly("infno", "msgid", "insuplc_admdvs", "mdtrtarea_admvs", "recer_admdvs", "dev_no", "dev_safe_info",
				"signtype", "cainfo", "infver", "opter_type", "opter", "opter_name", "inf_time", "fixmedins_code", "fixmedins_name", "recer_sys_code", "sign_no", "input");
		assertThat(body.get("infno").asText()).isEqualTo("3501");
		assertThat(body.get("msgid").asText()).startsWith("H44010100001").hasSize(12 + 14 + 4);
		assertThat(body.get("insuplc_admdvs").asText()).isEqualTo("440100");
		assertThat(body.get("opter").asText()).isEqualTo("magic-api");
		JsonNode invinfo = body.at("/input/invinfo");
		assertThat(fieldNames(invinfo)).containsExactlyInAnyOrder("med_list_codg", "fixmedins_hilist_id", "fixmedins_hilist_name", "rx_flag", "invdate",
				"inv_cnt", "manu_lotnum", "fixmedins_bchno", "manu_date", "expy_end", "memo", "drug_trac_codg", "drugtracinfo");
		assertThat(invinfo.get("invdate").asText()).isEqualTo("2024-05-01 10:00:00");
		assertThat(invinfo.at("/drugtracinfo/0/drug_trac_codg").asText()).isEqualTo("81234567890123456789");
		// 没有追溯码时不加 drugtracinfo
		assertThat(MAPPER.readTree(server.requests().get(2).body).at("/input/invinfo").has("drugtracinfo")).isFalse();
		// 签名
		assertThat(request.headers.get("x-tif-paasid")).isEqualTo("paas-001");
		String ts = request.headers.get("x-tif-timestamp");
		String nonce = request.headers.get("x-tif-nonce");
		assertThat(request.headers.get("x-tif-signature")).isEqualTo(sha256(ts + SECRET + nonce + ts));

		// 回写：成功 1，失败 0 且失败次数 1
		assertThat(status("K1")).isEqualTo("1");
		assertThat(status("K2")).isEqualTo("0");
		assertThat(status("K3")).isEqualTo("1");
		assertThat(jdbc.queryForObject("select error_times from insur_goods_upload_record where pk_key = 'K2'", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select result_parm from insur_goods_upload_record where pk_key = 'K2'", String.class)).contains("追溯码格式错误");
		assertThat(jdbc.queryForObject("select in_parm from insur_goods_upload_record where pk_key = 'K1'", String.class)).contains("\"infno\":\"3501\"");
		assertThat(jdbc.queryForObject("select count(*) from insur_goods_upload_record where pk_key = 'K0'", Integer.class)).isEqualTo(1);

		// 第二次：只重传失败的那一行；再失败则失败次数累加
		server.clear();
		jobService.run(resources().file(info.getId()), "manual");
		assertThat(server.requests()).hasSize(1);
		assertThat(server.requests().get(0).body).contains("\"fixmedins_hilist_id\":\"H002\"");
		assertThat(jdbc.queryForObject("select error_times from insur_goods_upload_record where pk_key = 'K2'", Integer.class)).isEqualTo(2);

		// 对方修复后重传成功
		server.clear();
		server.respond(r -> Response.json(200, "{\"infcode\":0}"));
		jobService.run(resources().file(info.getId()), "manual");
		assertThat(server.requests()).hasSize(1);
		assertThat(status("K2")).isEqualTo("1");
		server.clear();
		jobService.run(resources().file(info.getId()), "manual");
		assertThat(server.requests()).isEmpty();
	}

	private String status(String key) {
		return jdbc.queryForObject("select status from insur_goods_upload_record where trade_type = '3501' and pk_key = ?", String.class, key);
	}

	private static List<String> fieldNames(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private static String sha256(String text) throws Exception {
		byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b & 0xFF));
		}
		return sb.toString();
	}
}
