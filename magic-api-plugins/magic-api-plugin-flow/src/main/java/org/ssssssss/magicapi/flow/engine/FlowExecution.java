package org.ssssssss.magicapi.flow.engine;

import org.ssssssss.magicapi.flow.model.FlowDefinition;
import org.ssssssss.magicapi.flow.support.FlowScripts;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 一次流程执行的状态。
 * <p>
 * 表达式、SQL 与脚本中可用的变量：
 * <ul>
 *     <li>input：触发输入（HTTP 参数/报文、定时任务参数、调试输入）</li>
 *     <li>vars：流程变量，脚本节点可以写入，如 {@code vars.total = 10}</li>
 *     <li>nodes：已执行节点的输出，按节点 id 取，如 {@code nodes['q1']}</li>
 *     <li>节点 id：节点 id 是合法变量名时可直接引用，如 {@code q1[0].name}</li>
 *     <li>prev：上一个节点的输出</li>
 *     <li>error：走失败连线时的错误信息 {node, message}</li>
 * </ul>
 */
public class FlowExecution {

	public static final Set<String> RESERVED = new HashSet<>(Arrays.asList(
			"input", "vars", "nodes", "prev", "error", "row", "index", "flow",
			"db", "http", "log", "env", "request", "response", "header", "cookie", "session", "body", "path", "magic"));

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

	private final FlowDefinition definition;

	private final String flowName;

	private final boolean debug;

	private final Map<String, Object> variables = new LinkedHashMap<>();

	private final Map<String, Object> nodeOutputs = new LinkedHashMap<>();

	private final List<FlowTrace> trace = new ArrayList<>();

	private boolean finished;

	private Object result;

	/**
	 * 结束流程，返回 value（返回节点调用）
	 */
	public void finish(Object value) {
		this.finished = true;
		this.result = value;
	}

	public boolean isFinished() {
		return finished;
	}

	public Object getResult() {
		return result;
	}

	public FlowExecution(FlowDefinition definition, String flowName, Map<String, Object> input, boolean debug) {
		this.definition = definition;
		this.flowName = flowName == null ? "flow" : flowName;
		this.debug = debug;
		variables.put("input", input == null ? new LinkedHashMap<>() : input);
		variables.put("vars", new LinkedHashMap<String, Object>());
		variables.put("nodes", nodeOutputs);
		variables.put("prev", null);
		Map<String, Object> flow = new LinkedHashMap<>();
		flow.put("name", this.flowName);
		flow.put("debug", debug);
		variables.put("flow", flow);
	}

	public FlowDefinition getDefinition() {
		return definition;
	}

	public String getFlowName() {
		return flowName;
	}

	public boolean isDebug() {
		return debug;
	}

	public Map<String, Object> getVariables() {
		return variables;
	}

	public List<FlowTrace> getTrace() {
		return trace;
	}

	void output(FlowDefinition.Node node, Object value) {
		nodeOutputs.put(node.getId(), value);
		variables.put("prev", value);
		if (canBind(node.getId())) {
			variables.put(node.getId(), value);
		}
	}

	static boolean canBind(String id) {
		return IDENTIFIER.matcher(id).matches() && !RESERVED.contains(id);
	}

	Object evaluate(String expression, Map<String, Object> extra, String scriptName) {
		Map<String, Object> vars = variables;
		if (extra != null && !extra.isEmpty()) {
			vars = new LinkedHashMap<>(variables);
			vars.putAll(extra);
		}
		return FlowScripts.evaluate(expression, vars, scriptName);
	}
}
