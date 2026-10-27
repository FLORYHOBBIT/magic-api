package org.ssssssss.magicapi.upload.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 测试辅助
 */
public final class TestDatabases {

	private TestDatabases() {
	}

	/**
	 * mode 为 null 表示普通 H2；可选 Oracle、MSSQLServer
	 */
	public static JdbcTemplate h2(String mode) {
		String url = "jdbc:h2:mem:" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1"
				+ (mode == null ? "" : ";MODE=" + mode);
		DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
		dataSource.setDriverClassName("org.h2.Driver");
		return new JdbcTemplate(dataSource);
	}

	/**
	 * 创建一个已建好表的存储
	 *
	 * @param ddlType 用哪种数据库的建表语句；H2 时由存储自动建表
	 */
	public static JdbcUploadStore store(String mode, DatabaseType ddlType, Clock clock) {
		JdbcTemplate jdbc = h2(mode);
		String prefix = "magic_upload_";
		if (ddlType != DatabaseType.H2) {
			UploadSchema schema = new UploadSchema(prefix);
			for (String table : schema.tables()) {
				for (String sql : schema.ddl(table, ddlType)) {
					jdbc.execute(sql);
				}
			}
		}
		return new JdbcUploadStore(() -> jdbc, prefix, ddlType == DatabaseType.H2, clock);
	}

	public static class MutableClock extends Clock {

		private Instant now = Instant.parse("2026-10-01T00:00:00Z");

		public void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
