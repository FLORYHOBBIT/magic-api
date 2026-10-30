package org.ssssssss.magicapi.flow.engine;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * 一次流程执行的结果。
 */
public class FlowResult {

	private boolean success;

	/**
	 * 返回节点的值；没有返回节点时为最后一个节点的输出
	 */
	private Object value;

	/**
	 * 失败时出错的节点 id
	 */
	private String errorNode;

	private String message;

	private long duration;

	private List<FlowTrace> trace;

	@JsonIgnore
	private Throwable error;

	static FlowResult success(Object value, List<FlowTrace> trace, long duration) {
		FlowResult result = new FlowResult();
		result.success = true;
		result.value = value;
		result.trace = trace;
		result.duration = duration;
		return result;
	}

	static FlowResult failure(String node, Throwable error, List<FlowTrace> trace, long duration) {
		FlowResult result = new FlowResult();
		result.success = false;
		result.errorNode = node;
		result.error = error;
		result.message = FlowEngine.message(error);
		result.trace = trace;
		result.duration = duration;
		return result;
	}

	public boolean isSuccess() {
		return success;
	}

	public Object getValue() {
		return value;
	}

	public String getErrorNode() {
		return errorNode;
	}

	public String getMessage() {
		return message;
	}

	public long getDuration() {
		return duration;
	}

	public List<FlowTrace> getTrace() {
		return trace;
	}

	public Throwable getError() {
		return error;
	}
}
