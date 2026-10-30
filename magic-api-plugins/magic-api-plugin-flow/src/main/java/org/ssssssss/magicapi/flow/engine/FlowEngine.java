package org.ssssssss.magicapi.flow.engine;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.flow.model.FlowDefinition;
import org.ssssssss.magicapi.flow.model.FlowDefinitions;
import org.ssssssss.magicapi.flow.model.FlowInfo;
import org.ssssssss.script.parsing.ast.literal.BooleanLiteral;

import java.util.*;
import java.util.stream.Collectors;

import static org.ssssssss.magicapi.flow.FlowJsonCodes.*;

/**
 * 流程执行引擎：从起始节点开始逐个执行，按连线条件选择下一个节点。
 * <p>
 * 选路规则（每个节点执行后）：
 * <ol>
 *     <li>节点出错：有 failure 连线走 failure，否则流程失败</li>
 *     <li>节点成功：按定义顺序计算条件连线，第一个为真的生效；都不命中走默认连线；没有默认连线则流程结束</li>
 *     <li>返回节点执行后流程立即结束</li>
 * </ol>
 */
public class FlowEngine {

	private static final Logger logger = LoggerFactory.getLogger(FlowEngine.class);

	private static final int MAX_MESSAGE = 2000;

	private final Map<String, FlowNodeHandler> handlers = new LinkedHashMap<>();

	private int maxSteps = 10000;

	public FlowEngine(Collection<? extends FlowNodeHandler> handlers) {
		for (FlowNodeHandler handler : handlers) {
			// 排在前面的优先，便于项目用同名类型覆盖内置节点
			this.handlers.putIfAbsent(handler.type(), handler);
		}
	}

	public void setMaxSteps(int maxSteps) {
		this.maxSteps = maxSteps;
	}

	public Set<String> types() {
		return Collections.unmodifiableSet(handlers.keySet());
	}

	public FlowResult run(FlowInfo info, Map<String, Object> input, boolean debug) {
		return run(FlowDefinitions.parse(info.getScript()), info.getName(), input, debug);
	}

	/**
	 * 执行流程。配置错误（类型不支持、缺必填项、起始节点不唯一）直接抛出 InvalidArgumentException，
	 * 运行中节点出错体现在返回结果中。
	 */
	public FlowResult run(FlowDefinition definition, String flowName, Map<String, Object> input, boolean debug) {
		FlowDefinition.Node start = prepare(definition);
		FlowExecution execution = new FlowExecution(definition, flowName, input, debug);
		long begin = System.currentTimeMillis();
		FlowDefinition.Node current = start;
		Object last = null;
		int steps = 0;
		while (current != null) {
			if (++steps > maxSteps) {
				InvalidArgumentException error = new InvalidArgumentException(FLOW_CYCLE_DETECTED.format(maxSteps, label(current)));
				return FlowResult.failure(current.getId(), error, execution.getTrace(), System.currentTimeMillis() - begin);
			}
			FlowNodeContext context = new FlowNodeContext(current, execution);
			FlowTrace trace = new FlowTrace();
			trace.setNodeId(current.getId());
			trace.setName(current.getName());
			trace.setType(current.getType());
			execution.getTrace().add(trace);
			long nodeBegin = System.currentTimeMillis();
			FlowDefinition.Node next;
			try {
				Object output = handlers.get(current.getType()).execute(context);
				execution.output(current, output);
				last = output;
				trace.setOutput(FlowTrace.preview(output));
				next = execution.isFinished() ? null : chooseNext(definition, current, context);
			} catch (Throwable e) {
				trace.setSuccess(false);
				trace.setError(message(e));
				String failedId = current.getId();
				FlowDefinition.Edge failure = definition.getEdges().stream()
						.filter(it -> failedId.equals(it.getSource()) && it.isFailure())
						.findFirst().orElse(null);
				if (failure == null) {
					trace.setDuration(System.currentTimeMillis() - nodeBegin);
					logger.warn("流程「{}」节点「{}」执行失败：{}", execution.getFlowName(), label(current), trace.getError(), e);
					return FlowResult.failure(current.getId(), e, execution.getTrace(), System.currentTimeMillis() - begin);
				}
				Map<String, Object> error = new LinkedHashMap<>();
				error.put("node", current.getId());
				error.put("name", label(current));
				error.put("message", trace.getError());
				execution.getVariables().put("error", error);
				next = definition.findNode(failure.getTarget());
			}
			trace.setDuration(System.currentTimeMillis() - nodeBegin);
			trace.setNext(next == null ? null : next.getId());
			current = next;
		}
		Object value = execution.isFinished() ? execution.getResult() : last;
		return FlowResult.success(value, execution.getTrace(), System.currentTimeMillis() - begin);
	}

	/**
	 * 执行前检查：节点类型都受支持、配置完整、起始节点唯一
	 */
	public FlowDefinition.Node prepare(FlowDefinition definition) {
		FlowDefinitions.validate(definition);
		if (definition.getNodes().isEmpty()) {
			throw new InvalidArgumentException(FLOW_START_NODE_INVALID.format("没有节点"));
		}
		FlowExecution probe = new FlowExecution(definition, null, null, false);
		for (FlowDefinition.Node node : definition.getNodes()) {
			FlowNodeHandler handler = handlers.get(node.getType());
			if (handler == null) {
				throw new InvalidArgumentException(FLOW_NODE_TYPE_UNKNOWN.format(label(node), node.getType()));
			}
			handler.check(new FlowNodeContext(node, probe));
		}
		Set<String> targets = definition.getEdges().stream().map(FlowDefinition.Edge::getTarget).collect(Collectors.toSet());
		List<FlowDefinition.Node> starts = definition.getNodes().stream()
				.filter(it -> !targets.contains(it.getId()))
				.collect(Collectors.toList());
		if (starts.size() != 1) {
			String detail = starts.isEmpty() ? "所有节点都有入线" : starts.stream().map(FlowEngine::label).collect(Collectors.joining("、"));
			throw new InvalidArgumentException(FLOW_START_NODE_INVALID.format(detail));
		}
		return starts.get(0);
	}

	private FlowDefinition.Node chooseNext(FlowDefinition definition, FlowDefinition.Node current, FlowNodeContext context) {
		FlowDefinition.Edge fallback = null;
		for (FlowDefinition.Edge edge : definition.getEdges()) {
			if (!current.getId().equals(edge.getSource()) || edge.isFailure()) {
				continue;
			}
			if (edge.isDefault()) {
				fallback = edge;
				continue;
			}
			Object value;
			try {
				value = context.evaluate(edge.getCondition());
			} catch (Exception e) {
				throw new IllegalStateException("连线条件「" + edge.getCondition() + "」计算出错：" + message(e), e);
			}
			if (BooleanLiteral.isTrue(value)) {
				return definition.findNode(edge.getTarget());
			}
		}
		return fallback == null ? null : definition.findNode(fallback.getTarget());
	}

	static String label(FlowDefinition.Node node) {
		return StringUtils.isNotBlank(node.getName()) ? node.getName() : node.getId();
	}

	/**
	 * 给实施人员看的错误信息：外层说明 + 根因（如数据库报错）
	 */
	static String message(Throwable e) {
		if (e == null) {
			return null;
		}
		String top = e.getMessage();
		Throwable root = ExceptionUtils.getRootCause(e);
		String rootMessage = root == null || root == e ? null : root.getMessage();
		String message;
		if (StringUtils.isBlank(top)) {
			message = StringUtils.isNotBlank(rootMessage) ? rootMessage : (root == null ? e : root).getClass().getSimpleName();
		} else if (StringUtils.isNotBlank(rootMessage) && !top.contains(rootMessage)) {
			message = top + "：" + rootMessage;
		} else {
			message = top;
		}
		return message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) + "…" : message;
	}
}
