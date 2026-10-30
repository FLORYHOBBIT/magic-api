package org.ssssssss.magicapi.flow.proc;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProcSpecTest {

	private static Map<String, Object> row(Object... kv) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			map.put((String) kv[i], kv[i + 1]);
		}
		return map;
	}

	@Test
	void formSkipsInputsWithoutValue() {
		Map<String, Object> config = row("name", "zhis4_java_inteface.op_invoke", "params", Arrays.asList(
				row("name", "as_method", "value", "getHospitalInfo"),
				row("name", "as_type"),
				row("name", "ab_in_parm", "type", "clob", "value", "<req/>"),
				row("name", "rb_out_parm", "mode", "out", "type", "clob", "parse", "xml")));
		ProcSpec.ProcStatement st = ProcSpec.from(config, ProcSpec.LITERAL).build();
		assertEquals("begin zhis4_java_inteface.op_invoke(as_method => ?, ab_in_parm => ?, rb_out_parm => ?); end;", st.getSql());
		assertEquals(Arrays.asList("as_method", "ab_in_parm", "rb_out_parm"), names(st));
		assertEquals("xml", st.getBindings().get(2).getParse());
		assertFalse(st.getBindings().get(2).isIn());
	}

	@Test
	void explicitNullIsPassed() {
		Map<String, Object> config = row("name", "p", "params", Collections.singletonList(row("name", "a", "value", null)));
		assertEquals("begin p(a => ?); end;", ProcSpec.from(config, ProcSpec.LITERAL).build().getSql());
	}

	@Test
	void function() {
		ProcSpec spec = new ProcSpec().setName("owner.pkg.f").setReturnType("number").param(ProcParam.in("a", "integer", 1));
		ProcSpec.ProcStatement st = spec.build();
		assertEquals("begin ? := owner.pkg.f(a => ?); end;", st.getSql());
		assertEquals(ProcSpec.RETURN, st.getBindings().get(0).getName());
		assertTrue(st.getBindings().get(0).isOut());
	}

	@Test
	void handWrittenStatement() {
		ProcSpec spec = new ProcSpec().setMode("sql")
				.setSql("begin\n  -- :ignored in comment\n  :code := 0; /* :also */ x := ':str'; open :rows for select :id id from dual where 'a''b:c' = 'a''b:c';\n  p(:id, :msg)\nend")
				.param(ProcParam.in("id", "number", 5))
				.param(ProcParam.out("code", "integer", null))
				.param(ProcParam.out("msg", "varchar", null))
				.param(ProcParam.out("rows", "cursor", null));
		ProcSpec.ProcStatement st = spec.build();
		assertEquals("begin\n  -- :ignored in comment\n  ? := 0; /* :also */ x := ':str'; open ? for select ? id from dual where 'a''b:c' = 'a''b:c';\n  p(?, ?)\nend;", st.getSql());
		assertEquals(Arrays.asList("code", "rows", "id", "id", "msg"), names(st));
	}

	@Test
	void callSyntaxKeepsBraces() {
		ProcSpec spec = new ProcSpec().setMode("sql").setSql("{call p(:A)}").param(ProcParam.in("a", "varchar", "x"));
		assertEquals("{call p(?)}", spec.build().getSql());
	}

	@Test
	void rejectsUnsafeNamesAndBadConfig() {
		assertMessage("过程名", new ProcSpec().setName("p; drop table x"));
		assertMessage("过程名", new ProcSpec().setName("a.b.c.d"));
		assertMessage("请填写过程名", new ProcSpec());
		assertMessage("参数名", new ProcSpec().setName("p").param(ProcParam.in("a b", "varchar", 1)));
		assertMessage("重复", new ProcSpec().setName("p").param(ProcParam.in("a", "varchar", 1)).param(ProcParam.in("A", "varchar", 1)));
		assertMessage("类型", new ProcSpec().setName("p").param(ProcParam.in("a", "bool", 1)));
		assertMessage("游标", new ProcSpec().setName("p").param(ProcParam.in("a", "cursor", 1)));
		assertMessage("处理方式", new ProcSpec().setName("p").param(ProcParam.out("a", "clob", "yaml")));
		assertMessage(":b 没有在参数表中定义", new ProcSpec().setMode("sql").setSql("begin p(:a, :b); end;").param(ProcParam.in("a", "varchar", 1)));
		assertMessage("调用方式", new ProcSpec().setMode("call").setName("p"));
	}

	private static void assertMessage(String expected, ProcSpec spec) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, spec::build);
		assertTrue(e.getMessage().contains(expected), e.getMessage());
	}

	private static List<String> names(ProcSpec.ProcStatement st) {
		List<String> names = new ArrayList<>();
		st.getBindings().forEach(p -> names.add(p.getName()));
		return names;
	}
}
