package org.ssssssss.magicapi.upload.store;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class CodecTest {

	@Test
	void checkpointKeepsType() {
		assertRoundTrip(1000L, 1000L);
		assertRoundTrip(1000, 1000L);
		assertRoundTrip(new BigDecimal("12345678901234567890.5"), new BigDecimal("12345678901234567890.5"));
		// Oracle NUMBER 主键通过 JDBC 读出来是 BigDecimal
		assertRoundTrip(new BigDecimal("3000"), new BigDecimal("3000"));
		assertRoundTrip(new BigInteger("99"), 99L);
		assertRoundTrip("ZY20261001-0001", "ZY20261001-0001");
		Timestamp ts = Timestamp.valueOf("2026-10-27 18:30:00.123456789");
		assertRoundTrip(ts, ts);
		assertRoundTrip(LocalDateTime.of(2026, 10, 27, 8, 0), Timestamp.valueOf("2026-10-27 08:00:00"));
		assertRoundTrip(null, null);
	}

	private static void assertRoundTrip(Object input, Object expected) {
		CheckpointCodec.Encoded encoded = CheckpointCodec.encode(input);
		assertThat(CheckpointCodec.decode(encoded.getType(), encoded.getValue())).isEqualTo(expected);
	}

	@Test
	void payloadNormalizesValues() {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("ID", new BigDecimal("1"));
		row.put("NAME", "张三");
		row.put("VISIT_TIME", Timestamp.valueOf("2026-10-27 08:30:00"));
		row.put("BIRTH", java.time.LocalDate.of(1990, 1, 2));
		row.put("EMPTY", null);

		String json = PayloadCodec.encode(Collections.singletonList(row));
		List<Map<String, Object>> decoded = PayloadCodec.decode(json);

		assertThat(decoded).hasSize(1);
		Map<String, Object> back = decoded.get(0);
		assertThat(back.keySet()).containsExactly("ID", "NAME", "VISIT_TIME", "BIRTH", "EMPTY");
		assertThat(back.get("NAME")).isEqualTo("张三");
		assertThat(back.get("VISIT_TIME")).isEqualTo("2026-10-27 08:30:00.000");
		assertThat(back.get("BIRTH")).isEqualTo("1990-01-02");
		assertThat(back.get("EMPTY")).isNull();
		assertThat(PayloadCodec.decode(null)).isEmpty();
	}
}
