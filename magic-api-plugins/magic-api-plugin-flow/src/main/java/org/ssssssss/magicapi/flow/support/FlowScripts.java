package org.ssssssss.magicapi.flow.support;

import org.ssssssss.script.MagicScript;
import org.ssssssss.script.MagicScriptContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * magic-script 表达式与脚本的编译缓存。
 * <p>
 * 映射、模板对每一行计算表达式，按文本缓存编译结果，避免每行重复解析。
 */
public final class FlowScripts {

	private static final int MAX_CACHE = 5000;

	private static final String EXPRESSION_PREFIX = "/* flow expression */ return ";

	private static final Map<String, MagicScript> CACHE = new ConcurrentHashMap<>();

	private FlowScripts() {
	}

	public static Object evaluate(String expression, Map<String, Object> variables, String scriptName) {
		return execute(EXPRESSION_PREFIX + expression, variables, scriptName);
	}

	public static Object execute(String script, Map<String, Object> variables, String scriptName) {
		MagicScriptContext context = new MagicScriptContext(variables);
		context.setScriptName(scriptName);
		return compile(script).execute(context);
	}

	private static MagicScript compile(String script) {
		MagicScript compiled = CACHE.get(script);
		if (compiled == null) {
			if (CACHE.size() > MAX_CACHE) {
				CACHE.clear();
			}
			compiled = MagicScript.create(script, null);
			CACHE.put(script, compiled);
		}
		return compiled;
	}
}
