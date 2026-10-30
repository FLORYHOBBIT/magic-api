package org.ssssssss.magicapi.flow.engine.node;

import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.flow.engine.FlowNodeHandler;
import org.ssssssss.magicapi.utils.ScriptManager;
import org.ssssssss.script.MagicScriptContext;

/**
 * 脚本节点：执行一段 magic-script，返回值为节点输出。
 * <pre>
 * config:
 *   script  脚本内容，可用 input、vars、prev、节点 id 等变量，以及 db、http、log 等 magic-api 模块
 * </pre>
 * 用于表单节点覆盖不到的特殊处理；常规查库、映射、调用请用对应节点。
 */
public class ScriptNodeHandler implements FlowNodeHandler {

	public static final String TYPE = "script";

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void check(FlowNodeContext node) {
		node.requireString("script");
	}

	@Override
	public Object execute(FlowNodeContext node) {
		MagicScriptContext context = new MagicScriptContext(node.variables());
		context.setScriptName(node.scriptName());
		return ScriptManager.executeScript(node.requireString("script"), context);
	}
}
