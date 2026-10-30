package org.ssssssss.magicapi.flow.engine.node;

import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.modules.db.BoundSql;
import org.ssssssss.magicapi.modules.db.SQLModule;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 查库、写库节点共用：选数据源、列名格式，按 magic-api 的 SQL 语法（#{} 参数、${} 拼接、mybatis 标签）绑定变量。
 */
abstract class AbstractSqlNodeHandler {

	static final String[] COLUMN_CASES = {"lower", "camel", "upper", "raw"};

	private final SQLModule sqlModule;

	AbstractSqlNodeHandler(SQLModule sqlModule) {
		this.sqlModule = sqlModule;
	}

	void checkSql(FlowNodeContext node) {
		node.requireString("sql");
		node.oneOf("columnCase", "lower", COLUMN_CASES);
	}

	/**
	 * datasource 为空时用主数据源；列名默认统一转小写，避免 Oracle 大写列名和 MySQL 小写列名在后续节点中写法不一致
	 */
	SQLModule module(FlowNodeContext node) {
		if (sqlModule == null) {
			throw new IllegalStateException("没有可用的 db 模块，请确认已配置数据源");
		}
		SQLModule module = sqlModule.getDynamicAttribute(node.string("datasource"));
		String columnCase = node.oneOf("columnCase", "lower", COLUMN_CASES);
		return module.columnCase("raw".equals(columnCase) ? "default" : columnCase);
	}

	BoundSql bind(SQLModule module, FlowNodeContext node, String sql, Map<String, Object> extra) {
		Map<String, Object> variables = new LinkedHashMap<>(node.variables());
		if (extra != null) {
			variables.putAll(extra);
		}
		return new BoundSql(null, sql, variables, module);
	}
}
