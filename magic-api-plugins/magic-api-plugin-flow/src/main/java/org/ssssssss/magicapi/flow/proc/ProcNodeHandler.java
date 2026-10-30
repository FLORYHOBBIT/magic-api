package org.ssssssss.magicapi.flow.proc;

import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.flow.engine.FlowNodeHandler;

import java.util.Map;

/**
 * 调用存储过程节点，输出为出参 Map（键为参数名；函数返回值键为 return），后续节点用 节点id.参数名 引用。
 * <pre>
 * config:
 *   datasource   数据源 key，空为主数据源
 *   mode         form（默认，过程名 + 参数表，按名传参） / sql（手写语句，用 :参数名 引用参数表）
 *   name         过程名：过程、包.过程、用户.包.过程（form）
 *   sql          调用语句，如 begin pkg.p(:a, :b); end; 或 {call pkg.p(:a, :b)}（sql）
 *   params       [{name, mode: in/out/inout, type, value: 表达式, parse: string/json/xml}]
 *                type: varchar（默认） / number / integer / date / timestamp / clob / blob / cursor
 *                form 模式下 value 为空的入参不传，由过程使用默认值；sql 模式下 value 为空传 null
 *   returnType   函数返回值类型，空表示过程（form）
 *   returnParse  函数返回值处理：string / json / xml
 *   columnCase   游标列名：lower（默认） / camel / upper / raw
 *   timeout      超时秒数，0 不限制
 * </pre>
 */
public class ProcNodeHandler implements FlowNodeHandler {

	public static final String TYPE = "proc";

	private final ProcService service;

	public ProcNodeHandler(ProcService service) {
		this.service = service;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void check(FlowNodeContext node) {
		try {
			ProcSpec.from(node.config(), new ProcSpec.Values() {
				@Override
				public boolean has(Map<String, Object> row) {
					return hasExpression(row);
				}

				@Override
				public Object get(Map<String, Object> row) {
					return null;
				}
			}).build();
		} catch (IllegalArgumentException e) {
			throw node.invalid("sql".equalsIgnoreCase(node.string("mode")) ? "sql" : "name", e.getMessage());
		}
	}

	@Override
	public Object execute(FlowNodeContext node) {
		ProcSpec spec;
		try {
			spec = ProcSpec.from(node.config(), new ProcSpec.Values() {
				@Override
				public boolean has(Map<String, Object> row) {
					return hasExpression(row);
				}

				@Override
				public Object get(Map<String, Object> row) {
					return node.evaluate(row.get("value").toString());
				}
			});
			spec.build();
		} catch (IllegalArgumentException e) {
			throw node.invalid("params", e.getMessage());
		}
		return service.call(spec);
	}

	private static boolean hasExpression(Map<String, Object> row) {
		Object value = row.get("value");
		return value != null && !value.toString().trim().isEmpty();
	}
}
