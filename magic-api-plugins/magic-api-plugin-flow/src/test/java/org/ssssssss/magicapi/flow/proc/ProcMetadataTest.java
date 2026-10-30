package org.ssssssss.magicapi.flow.proc;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProcMetadataTest {

	/**
	 * op_invoke 的参数，以及一个两套重载的过程
	 */
	private final ProcMetadata metadata = new ProcMetadata() {
		@Override
		public List<List<Argument>> overloads(JdbcTemplate jdbcTemplate, String cacheKey, String name) {
			if (name.endsWith("op_invoke")) {
				return Collections.singletonList(Arrays.asList(
						new Argument(1, "as_method", "in", "VARCHAR2", "VARCHAR2", false),
						new Argument(2, "as_type", "in", "VARCHAR2", "VARCHAR2", true),
						new Argument(3, "ab_in_parm", "in", "CLOB", "CLOB", false),
						new Argument(4, "rb_out_parm", "out", "CLOB", "CLOB", false)));
			}
			if (name.endsWith("get_code")) {
				return Collections.singletonList(Arrays.asList(
						new Argument(0, ProcSpec.RETURN, "out", "NUMBER", "INTEGER", false),
						new Argument(1, "a", "in", "PL/SQL BOOLEAN", null, true)));
			}
			return Arrays.asList(
					Collections.singletonList(new Argument(1, "a", "in", "NUMBER", "NUMBER", false)),
					Arrays.asList(new Argument(1, "b", "in", "DATE", "DATE", false), new Argument(2, "rows", "out", "REF CURSOR", null, false)));
		}
	};

	@Test
	void typeMapping() {
		assertEquals("varchar", ProcMetadata.typeOf("VARCHAR2", "VARCHAR2"));
		assertEquals("integer", ProcMetadata.typeOf("NUMBER", "INTEGER"));
		assertEquals("integer", ProcMetadata.typeOf("BINARY_INTEGER", null));
		assertEquals("integer", ProcMetadata.typeOf("PL/SQL PLS INTEGER", null));
		assertEquals("number", ProcMetadata.typeOf("NUMBER", "NUMBER"));
		assertEquals("timestamp", ProcMetadata.typeOf("TIMESTAMP(6)", null));
		assertEquals("cursor", ProcMetadata.typeOf("REF CURSOR", null));
		assertEquals("clob", ProcMetadata.typeOf("NCLOB", null));
		assertNull(ProcMetadata.typeOf("PL/SQL BOOLEAN", null));
		assertNull(ProcMetadata.typeOf("PL/SQL RECORD", null));
	}

	@Test
	void inferSkipsDefaultedAndRegistersOutputs() {
		Map<String, Object> inputs = new LinkedHashMap<>();
		inputs.put("AS_METHOD", "getHospitalInfo");
		inputs.put("ab_in_parm", "<req/>");
		ProcSpec spec = metadata.infer(null, "ds", "zhis4_java_inteface.op_invoke", inputs, Collections.singletonMap("RB_OUT_PARM", "xml"));
		ProcSpec.ProcStatement st = spec.build();
		assertEquals("begin zhis4_java_inteface.op_invoke(as_method => ?, ab_in_parm => ?, rb_out_parm => ?); end;", st.getSql());
		assertEquals("getHospitalInfo", st.getBindings().get(0).getValue());
		assertEquals("clob", st.getBindings().get(1).getType());
		assertEquals("xml", st.getBindings().get(2).getParse());
	}

	@Test
	void missingRequiredInputIsNull() {
		ProcSpec spec = metadata.infer(null, "ds", "op_invoke", Collections.singletonMap("as_method", "x"), null);
		assertEquals("begin op_invoke(as_method => ?, ab_in_parm => ?, rb_out_parm => ?); end;", spec.build().getSql());
		assertNull(spec.getParams().get(1).getValue());
		assertTrue(spec.getParams().get(1).hasValue());
	}

	@Test
	void choosesOverloadByInputNames() {
		ProcSpec spec = metadata.infer(null, "ds", "pkg.p", Collections.singletonMap("b", "2026-10-30"), null);
		assertEquals("begin pkg.p(b => ?, rows => ?); end;", spec.build().getSql());
		assertEquals("cursor", spec.getParams().get(1).getType());
	}

	@Test
	void unknownInputListsKnownParams() {
		ProcException e = assertThrows(ProcException.class, () -> metadata.infer(null, "ds", "op_invoke", Collections.singletonMap("as_metod", "x"), null));
		assertTrue(e.getMessage().contains("没有参数 as_metod"), e.getMessage());
		assertTrue(e.getMessage().contains("as_method"), e.getMessage());
	}

	@Test
	void functionReturnAndUnsupportedDefaultedArgument() {
		ProcSpec spec = metadata.infer(null, "ds", "get_code", Collections.emptyMap(), null);
		assertEquals("begin ? := get_code(); end;", spec.build().getSql());
		assertEquals("integer", spec.getReturnType());
		ProcException e = assertThrows(ProcException.class, () -> metadata.infer(null, "ds", "get_code", Collections.singletonMap("a", true), null));
		assertTrue(e.getMessage().contains("PL/SQL BOOLEAN 不支持"), e.getMessage());
	}
}
