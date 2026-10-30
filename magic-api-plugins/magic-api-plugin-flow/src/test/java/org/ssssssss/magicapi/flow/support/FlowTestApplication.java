package org.ssssssss.magicapi.flow.support;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.ssssssss.magicapi.modules.db.BoundSql;
import org.ssssssss.magicapi.modules.db.dialect.Dialect;

/**
 * 仅用于集成测试：启动完整的 magic-api（编辑器、资源服务、flow 插件）
 */
@SpringBootApplication
public class FlowTestApplication {

	/**
	 * magic-api 没有内置 H2 方言，测试中按 limit/offset 分页
	 */
	@Bean
	public Dialect h2Dialect() {
		return new Dialect() {
			@Override
			public boolean match(String jdbcUrl) {
				return jdbcUrl.contains(":h2:");
			}

			@Override
			public String getPageSql(String sql, BoundSql boundSql, long offset, long limit) {
				boundSql.addParameter(limit);
				boundSql.addParameter(offset);
				return sql + "\n limit ? offset ?";
			}
		};
	}
}
