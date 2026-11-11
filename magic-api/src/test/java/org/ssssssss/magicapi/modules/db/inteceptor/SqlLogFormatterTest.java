package org.ssssssss.magicapi.modules.db.inteceptor;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.SqlParameterValue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;

class SqlLogFormatterTest {
	@Test void rendersValuesWithoutChangingQuestionMarksInsideValues() {
		assertEquals("select 'O''Brien?中文', 12.3400, NULL, 1",
				SqlLogFormatter.format("select ?, ?, ?, ?", new Object[]{"O'Brien?中文", new BigDecimal("12.3400"), null, true}));
	}

	@Test void skipsQuotedTextIdentifiersAndComments() {
		String source = "select '?' as \"?\", [?], `?`, 'it''s ?' -- ?\nfrom test where id=? /* ? */ and name=?";
		assertEquals("select '?' as \"?\", [?], `?`, 'it''s ?' -- ?\nfrom test where id=42 /* ? */ and name='张三'",
				SqlLogFormatter.format(source, new Object[]{42, "张三"}));
	}

	@Test void skipsOracleAlternativeQuotesAndDollarQuotes() {
		assertEquals("select q'[isn't ?]', Q'{?}', q'!?!', $$?$$, $tag$?$tag$, 'value' from dual",
				SqlLogFormatter.format("select q'[isn't ?]', Q'{?}', q'!?!', $$?$$, $tag$?$tag$, ? from dual", new Object[]{"value"}));
	}

	@Test void rendersJdbcTypesAndDates() {
		assertEquals("select 'typed', DATE '2026-11-10', TIMESTAMP '2026-11-10 17:00:00.123456789'",
				SqlLogFormatter.format("select ?, ?, ?", new Object[]{new SqlParameterValue(Types.VARCHAR, "typed"), java.sql.Date.valueOf("2026-11-10"), Timestamp.valueOf("2026-11-10 17:00:00.123456789")}));
	}

	@Test void rendersEveryBatchRowWithItsOwnParameters() {
		assertEquals("-- 批次 1/2\nupdate t set name='one' where id=1;\n-- 批次 2/2\nupdate t set name='two' where id=2;",
				SqlLogFormatter.format("update t set name=? where id=?", new Object[]{new Object[]{"one", 1}, new Object[]{"two", 2}}));
	}

	@Test void doesNotTruncateLongParameters() {
		String text = String.join("", java.util.Collections.nCopies(5000, "长"));
		String rendered = SqlLogFormatter.format("insert into t values(?)", new Object[]{text});
		assertEquals("insert into t values('" + text + "')", rendered);
	}

	@Test void rejectsBindingMismatchesInsteadOfInventingValues() {
		assertThrows(IllegalArgumentException.class, () -> SqlLogFormatter.format("select ?, ?", new Object[]{1}));
		assertThrows(IllegalArgumentException.class, () -> SqlLogFormatter.format("select ?", new Object[]{1, 2}));
	}
}
