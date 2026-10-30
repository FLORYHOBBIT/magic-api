package org.ssssssss.magicapi.flow.engine.node;

import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.flow.engine.FlowNodeHandler;

/**
 * 返回节点：结束流程并给出返回值（对外接口的响应内容）。
 * <pre>
 * config:
 *   value  表达式，如 q1、{code: 0, data: q1}；为空时返回上一个节点的输出
 * </pre>
 */
public class ReturnNodeHandler implements FlowNodeHandler {

	public static final String TYPE = "return";

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public Object execute(FlowNodeContext node) {
		String expression = node.string("value");
		Object value = expression == null ? node.variables().get("prev") : node.evaluate(expression);
		node.getExecution().finish(value);
		return value;
	}
}
