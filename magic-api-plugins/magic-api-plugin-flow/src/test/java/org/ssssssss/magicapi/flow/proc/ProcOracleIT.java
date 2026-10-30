package org.ssssssss.magicapi.flow.proc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 连真实 Oracle（11g 及以上）验证存储过程调用。只在设置了环境变量时运行：
 * <pre>
 *   FLOW_ORACLE_URL=jdbc:oracle:thin:@127.0.0.1:1521/ORCL
 *   FLOW_ORACLE_USER=...  FLOW_ORACLE_PASSWORD=...
 *   mvn -pl magic-api-plugins/magic-api-plugin-flow test -Dtest=ProcOracleIT
 * </pre>
 * 不在库中建任何对象；op_invoke 用例要求库中有 zhis4_java_inteface 包（每次调用会写一行 comm.interface_trade_logs）。
 */
@EnabledIfEnvironmentVariable(named = "FLOW_ORACLE_URL", matches = ".+")
public class ProcOracleIT {

	static JdbcTemplate jdbc;

	private final ProcExecutor executor = new ProcExecutor();

	@BeforeAll
	static void connect() {
		DriverManagerDataSource ds = new DriverManagerDataSource(System.getenv("FLOW_ORACLE_URL"), System.getenv("FLOW_ORACLE_USER"), System.getenv("FLOW_ORACLE_PASSWORD"));
		ds.setDriverClassName("oracle.jdbc.OracleDriver");
		jdbc = new JdbcTemplate(ds);
	}

	static boolean hasOpInvoke() {
		return jdbc.queryForObject("select count(*) from all_objects where object_name = 'ZHIS4_JAVA_INTEFACE' and object_type = 'PACKAGE'", Integer.class) > 0;
	}

	@Test
	void opInvokeReturnsXml() {
		org.junit.jupiter.api.Assumptions.assumeTrue(hasOpInvoke(), "库中没有 zhis4_java_inteface");
		ProcSpec spec = new ProcSpec().setName("zhis4_java_inteface.op_invoke")
				.param(ProcParam.in("as_method", "varchar", "getHospitalInfo"))
				.param(ProcParam.in("ab_in_parm", "clob", "<req><hospitalId></hospitalId></req>"))
				.param(ProcParam.out("rb_out_parm", "clob", "string"));
		Map<String, Object> raw = executor.execute(jdbc, spec);
		String xml = (String) raw.get("rb_out_parm");
		assertNotNull(xml);
		assertTrue(xml.contains("<res"), xml);

		ProcSpec parsed = new ProcSpec().setName("zhis4_java_inteface.op_invoke")
				.param(ProcParam.in("as_method", "varchar", "getHospitalInfo"))
				.param(ProcParam.in("ab_in_parm", "clob", "<req><hospitalId></hospitalId></req>"))
				.param(ProcParam.out("rb_out_parm", "clob", "xml"));
		Map<?, ?> res = (Map<?, ?>) ((Map<?, ?>) executor.execute(jdbc, parsed).get("rb_out_parm")).get("res");
		assertNotNull(res.get("resultCode"), String.valueOf(res));
	}

	@Test
	void metadataOfOpInvoke() {
		org.junit.jupiter.api.Assumptions.assumeTrue(hasOpInvoke(), "库中没有 zhis4_java_inteface");
		ProcMetadata metadata = new ProcMetadata();
		List<List<ProcMetadata.Argument>> overloads = metadata.overloads(jdbc, "it", "zhis4_java_inteface.op_invoke");
		List<ProcMetadata.Argument> args = overloads.get(0);
		assertEquals(4, args.size());
		assertEquals("as_method", args.get(0).getName());
		assertTrue(args.get(1).isDefaulted());
		assertEquals("clob", args.get(2).getType());
		assertEquals("out", args.get(3).getMode());

		Map<String, Object> inputs = new HashMap<>();
		inputs.put("as_method", "getHospitalInfo");
		inputs.put("ab_in_parm", "<req><hospitalId></hospitalId></req>");
		ProcSpec spec = metadata.infer(jdbc, "it", "ZHIS4_JAVA_INTEFACE.OP_INVOKE", inputs, null);
		assertTrue(((String) executor.execute(jdbc, spec).get("rb_out_parm")).contains("<res"));
	}

	@Test
	void multipleOutputsAndCursor() {
		ProcSpec spec = new ProcSpec().setMode("sql").setColumnCase("camel")
				.setSql("begin :code := 0; :msg := '成功'; open :rows for select level as row_no, '名' || level as row_name, sysdate as made_at from dual connect by level <= 3; end;")
				.param(ProcParam.out("code", "integer", null))
				.param(ProcParam.out("msg", "varchar", null))
				.param(ProcParam.out("rows", "cursor", null));
		Map<String, Object> result = executor.execute(jdbc, spec);
		assertEquals(0L, result.get("code"));
		assertEquals("成功", result.get("msg"));
		List<?> rows = (List<?>) result.get("rows");
		assertEquals(3, rows.size());
		Map<?, ?> last = (Map<?, ?>) rows.get(2);
		assertEquals("名3", last.get("rowName"));
		assertEquals(0, new BigDecimal("3").compareTo((BigDecimal) last.get("rowNo")));
		assertTrue(last.get("madeAt") instanceof java.util.Date, String.valueOf(last.get("madeAt").getClass()));
	}

	@Test
	void largeClobInputAndJsonOutput() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 4000; i++) {
			sb.append("检验报告0123");
		}
		String text = sb.toString();
		ProcSpec spec = new ProcSpec().setMode("sql")
				.setSql("declare n integer; begin n := dbms_lob.getlength(:txt); :len := n; :back := :txt; :js := '{\"n\":' || n || ',\"ok\":true}'; end;")
				.param(ProcParam.in("txt", "clob", text))
				.param(ProcParam.out("len", "integer", null))
				.param(ProcParam.out("back", "clob", null))
				.param(ProcParam.out("js", "clob", "json"));
		Map<String, Object> result = executor.execute(jdbc, spec);
		assertEquals((long) text.length(), result.get("len"));
		assertEquals(text, result.get("back"));
		assertEquals(Boolean.TRUE, ((Map<?, ?>) result.get("js")).get("ok"));
	}

	@Test
	void nullDateAndInout() {
		ProcSpec spec = new ProcSpec().setMode("sql")
				.setSql("begin :r := nvl(:a, '是空'); :d2 := :d1 + 1; :io := :io || '-改'; end;")
				.param(ProcParam.in("a", "varchar", null))
				.param(ProcParam.out("r", "varchar", null))
				.param(ProcParam.in("d1", "date", "2026-10-30 08:00:00"))
				.param(ProcParam.out("d2", "date", null))
				.param(new ProcParam("io", "inout", "varchar", "原", true, null));
		Map<String, Object> result = executor.execute(jdbc, spec);
		assertEquals("是空", result.get("r"));
		assertEquals(Timestamp.valueOf("2026-10-31 08:00:00"), result.get("d2"));
		assertEquals("原-改", result.get("io"));
	}

	@Test
	void functionByForm() {
		ProcSpec spec = new ProcSpec().setName("dbms_utility.get_time").setReturnType("integer");
		assertTrue(executor.execute(jdbc, spec).get(ProcSpec.RETURN) instanceof Long);
		ProcSpec upper = new ProcSpec().setName("sys.dbms_assert.simple_sql_name").setReturnType("varchar").param(ProcParam.in("str", "varchar", "abc"));
		assertEquals("abc", executor.execute(jdbc, upper).get(ProcSpec.RETURN));
	}

	@Test
	void oracleErrorsAreReadable() {
		ProcException e = assertThrows(ProcException.class, () -> executor.execute(jdbc, new ProcSpec().setName("zhis4_java_inteface.no_such_proc")));
		assertTrue(e.getMessage().contains("ORA-"), e.getMessage());
		ProcException raised = assertThrows(ProcException.class, () -> executor.execute(jdbc,
				new ProcSpec().setMode("sql").setSql("begin raise_application_error(-20001, '患者不存在'); end;")));
		assertTrue(raised.getMessage().contains("ORA-20001: 患者不存在"), raised.getMessage());
		ProcException missing = assertThrows(ProcException.class, () -> new ProcMetadata().overloads(jdbc, "it", "no_such_pkg.no_such_proc"));
		assertTrue(missing.getMessage().contains("找不到过程"), missing.getMessage());
	}
}
