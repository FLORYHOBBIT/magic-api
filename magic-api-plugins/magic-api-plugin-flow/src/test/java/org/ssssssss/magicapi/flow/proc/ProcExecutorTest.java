package org.ssssssss.magicapi.flow.proc;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ProcExecutorTest {

	private final ProcExecutor executor = new ProcExecutor();

	@Test
	void bindsInputsAndReadsOutputs() throws Exception {
		Connection con = mock(Connection.class);
		CallableStatement cs = mock(CallableStatement.class);
		Clob tempClob = mock(Clob.class);
		when(con.prepareCall(anyString())).thenReturn(cs);
		when(con.createClob()).thenReturn(tempClob);
		Clob xml = clob("<res><resultCode>0</resultCode></res>");
		when(cs.getClob(6)).thenReturn(xml);
		when(cs.getString(7)).thenReturn("{\"a\":1}");
		when(cs.getLong(8)).thenReturn(0L);
		when(cs.wasNull()).thenReturn(false);
		ResultSet rs = rows(new String[]{"PAT_ID", "PAT_NAME"}, new Object[][]{{1, "张三"}, {2, "李四"}});
		when(cs.getObject(9)).thenReturn(rs);

		StringBuilder big = new StringBuilder();
		for (int i = 0; i < 5000; i++) {
			big.append("0123456789");
		}
		ProcSpec spec = new ProcSpec().setName("pkg.p").setTimeout(30).setColumnCase("camel")
				.param(ProcParam.in("a_method", "varchar", "getX"))
				.param(ProcParam.in("a_num", "number", "12.50"))
				.param(ProcParam.in("a_date", "date", "2026-10-30"))
				.param(ProcParam.in("a_null", "clob", null))
				.param(ProcParam.in("a_big", "clob", big.toString()))
				.param(ProcParam.out("r_xml", "clob", "xml"))
				.param(ProcParam.out("r_json", "varchar", "json"))
				.param(ProcParam.out("r_code", "integer", null))
				.param(ProcParam.out("r_rows", "cursor", null));
		Map<String, Object> result = executor.call(con, spec, spec.build());

		verify(cs).setQueryTimeout(30);
		verify(cs).setString(1, "getX");
		verify(cs).setBigDecimal(2, new BigDecimal("12.50"));
		verify(cs).setTimestamp(3, Timestamp.valueOf("2026-10-30 00:00:00"));
		verify(cs).setNull(4, Types.CLOB);
		verify(tempClob).setString(1, big.toString());
		verify(cs).setClob(5, tempClob);
		verify(cs).registerOutParameter(6, Types.CLOB);
		verify(cs).registerOutParameter(7, Types.VARCHAR);
		verify(cs).registerOutParameter(8, Types.NUMERIC);
		verify(cs).registerOutParameter(9, ProcParam.ORACLE_CURSOR);
		verify(tempClob).free();

		assertEquals(Arrays.asList("r_xml", "r_json", "r_code", "r_rows"), new ArrayList<>(result.keySet()));
		assertEquals("0", ((Map<?, ?>) ((Map<?, ?>) result.get("r_xml")).get("res")).get("resultCode"));
		assertEquals(1, ((Map<?, ?>) result.get("r_json")).get("a"));
		assertEquals(0L, result.get("r_code"));
		List<?> list = (List<?>) result.get("r_rows");
		assertEquals(2, list.size());
		assertEquals("李四", ((Map<?, ?>) list.get(1)).get("patName"));
		verify(rs).close();
	}

	@Test
	void inoutBindsAndRegisters() throws Exception {
		Connection con = mock(Connection.class);
		CallableStatement cs = mock(CallableStatement.class);
		when(con.prepareCall(anyString())).thenReturn(cs);
		when(cs.getString(1)).thenReturn("after");
		ProcSpec spec = new ProcSpec().setName("p").param(new ProcParam("x", "inout", "varchar", "before", true, null));
		Map<String, Object> result = executor.call(con, spec, spec.build());
		verify(cs).setString(1, "before");
		verify(cs).registerOutParameter(1, Types.VARCHAR);
		assertEquals("after", result.get("x"));
	}

	@Test
	void invalidJsonOutputReportsParamAndContent() throws Exception {
		Connection con = mock(Connection.class);
		CallableStatement cs = mock(CallableStatement.class);
		when(con.prepareCall(anyString())).thenReturn(cs);
		Clob bad = clob("not json");
		when(cs.getClob(1)).thenReturn(bad);
		ProcSpec spec = new ProcSpec().setName("p").param(ProcParam.out("r", "clob", "json"));
		ProcException e = assertThrows(ProcException.class, () -> executor.call(con, spec, spec.build()));
		assertTrue(e.getMessage().contains("出参 r 不是合法的 JSON"), e.getMessage());
		assertTrue(e.getMessage().contains("not json"), e.getMessage());
	}

	@Test
	void badInputs() throws Exception {
		Connection con = mock(Connection.class);
		CallableStatement cs = mock(CallableStatement.class);
		when(con.prepareCall(anyString())).thenReturn(cs);
		ProcSpec number = new ProcSpec().setName("p").param(ProcParam.in("n", "number", "abc"));
		assertTrue(assertThrows(ProcException.class, () -> executor.call(con, number, number.build())).getMessage().contains("入参 n 不是数字"));
		ProcSpec date = new ProcSpec().setName("p").param(ProcParam.in("d", "date", "30/10/2026"));
		assertTrue(assertThrows(ProcException.class, () -> executor.call(con, date, date.build())).getMessage().contains("入参 d 不是日期"));
	}

	@Test
	void oracleErrorIsReadable() throws Exception {
		Connection con = mock(Connection.class);
		CallableStatement cs = mock(CallableStatement.class);
		when(con.prepareCall(anyString())).thenReturn(cs);
		when(cs.execute()).thenThrow(new SQLException("ORA-06550: line 1, column 7:\nPLS-00306: wrong number or types of arguments in call to 'P'\n", "65000", 6550));
		JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(con, true));
		ProcException e = assertThrows(ProcException.class, () -> executor.execute(jdbc, new ProcSpec().setName("p")));
		assertTrue(e.getMessage().startsWith("调用存储过程失败：ORA-06550"), e.getMessage());
		assertTrue(e.getMessage().contains("PLS-00306"), e.getMessage());
		assertTrue(e.getMessage().contains("begin p(); end;"), e.getMessage());
	}

	private static Clob clob(String text) throws SQLException {
		Clob clob = mock(Clob.class);
		when(clob.getCharacterStream()).thenReturn(new StringReader(text));
		when(clob.length()).thenReturn((long) text.length());
		return clob;
	}

	private static ResultSet rows(String[] columns, Object[][] data) throws SQLException {
		ResultSet rs = mock(ResultSet.class);
		ResultSetMetaData meta = mock(ResultSetMetaData.class);
		when(rs.getMetaData()).thenReturn(meta);
		when(meta.getColumnCount()).thenReturn(columns.length);
		for (int i = 0; i < columns.length; i++) {
			when(meta.getColumnLabel(i + 1)).thenReturn(columns[i]);
			when(meta.getColumnName(i + 1)).thenReturn(columns[i]);
		}
		int[] cursor = {-1};
		when(rs.next()).thenAnswer(inv -> ++cursor[0] < data.length);
		when(rs.getObject(anyInt())).thenAnswer(inv -> data[cursor[0]][(Integer) inv.getArgument(0) - 1]);
		return rs;
	}
}
