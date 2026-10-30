package org.ssssssss.magicapi.flow.engine.node;

import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.flow.engine.FlowNodeHandler;
import org.ssssssss.magicapi.modules.db.BoundSql;
import org.ssssssss.magicapi.modules.db.SQLModule;

/**
 * 查库节点。
 * <pre>
 * config:
 *   datasource  数据源 key，空为主数据源
 *   sql         查询语句，支持 #{input.id}、#{q1[0].pid} 等参数
 *   result      list（默认，多行） / one（第一行，无数据为 null） / value（第一行第一列） / count（整数）
 *   columnCase  lower（默认） / camel / upper / raw
 * </pre>
 */
public class QueryNodeHandler extends AbstractSqlNodeHandler implements FlowNodeHandler {

	public static final String TYPE = "query";

	private static final String[] RESULTS = {"list", "one", "value", "count"};

	public QueryNodeHandler(SQLModule sqlModule) {
		super(sqlModule);
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void check(FlowNodeContext node) {
		checkSql(node);
		node.oneOf("result", "list", RESULTS);
	}

	@Override
	public Object execute(FlowNodeContext node) {
		SQLModule module = module(node);
		BoundSql sql = bind(module, node, node.requireString("sql"), null);
		switch (node.oneOf("result", "list", RESULTS)) {
			case "one":
				return module.selectOne(sql);
			case "value":
				java.util.Map<String, Object> row = module.selectOne(sql);
				return row == null || row.isEmpty() ? null : row.values().iterator().next();
			case "count":
				return module.selectInt(sql);
			default:
				return module.select(sql);
		}
	}
}
