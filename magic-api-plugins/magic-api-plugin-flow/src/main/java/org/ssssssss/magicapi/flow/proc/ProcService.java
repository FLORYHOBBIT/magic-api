package org.ssssssss.magicapi.flow.proc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.ssssssss.magicapi.modules.db.SQLModule;

import java.util.*;
import java.util.function.Supplier;

/**
 * 存储过程调用入口，流程节点、脚本模块 proc 与编辑器「读取参数」共用。数据源为 magic-api 已配置的数据源（空为主数据源）。
 */
public class ProcService {

	private final Supplier<SQLModule> sqlModule;

	private final ProcExecutor executor;

	private final ProcMetadata metadata;

	public ProcService(Supplier<SQLModule> sqlModule) {
		this(sqlModule, new ProcExecutor(), new ProcMetadata());
	}

	public ProcService(Supplier<SQLModule> sqlModule, ProcExecutor executor, ProcMetadata metadata) {
		this.sqlModule = sqlModule;
		this.executor = executor;
		this.metadata = metadata;
	}

	public JdbcTemplate jdbcTemplate(String datasource) {
		SQLModule module = sqlModule == null ? null : sqlModule.get();
		if (module == null) {
			throw new IllegalStateException("没有可用的 db 模块，请确认已配置数据源");
		}
		return module.getDynamicAttribute(datasource == null || datasource.trim().isEmpty() ? null : datasource.trim()).getJdbcTemplate();
	}

	/**
	 * 按完整描述调用
	 */
	public Map<String, Object> call(ProcSpec spec) {
		return executor.execute(jdbcTemplate(spec.getDatasource()), spec);
	}

	/**
	 * 按参数定义推断调用：只给入参，出参自动注册
	 */
	public Map<String, Object> call(String datasource, String name, Map<String, ?> inputs, Map<String, String> parses) {
		JdbcTemplate jdbc = jdbcTemplate(datasource);
		ProcSpec spec = metadata.infer(jdbc, cacheKey(datasource), name, inputs, parses).setDatasource(datasource);
		return executor.execute(jdbc, spec);
	}

	/**
	 * 参数定义（每个重载一组）
	 */
	public List<List<Map<String, Object>>> arguments(String datasource, String name, boolean refresh) {
		if (refresh) {
			metadata.evict();
		}
		List<List<Map<String, Object>>> result = new ArrayList<>();
		for (List<ProcMetadata.Argument> overload : metadata.overloads(jdbcTemplate(datasource), cacheKey(datasource), name)) {
			List<Map<String, Object>> args = new ArrayList<>();
			overload.forEach(a -> args.add(a.toMap()));
			result.add(args);
		}
		return result;
	}

	private static String cacheKey(String datasource) {
		return datasource == null ? "" : datasource.trim();
	}
}
