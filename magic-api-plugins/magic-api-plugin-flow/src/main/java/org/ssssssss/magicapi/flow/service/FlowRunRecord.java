package org.ssssssss.magicapi.flow.service;

/**
 * 一次流程运行的记录（内存中按流程保留最近若干条）
 */
public class FlowRunRecord {

	private String flowId;

	private String flowName;

	/**
	 * http / soap / timer / manual
	 */
	private String trigger;

	private long startTime;

	private long duration;

	private boolean success;

	private String errorNode;

	private String message;

	private String input;

	private String output;

	public String getFlowId() {
		return flowId;
	}

	public void setFlowId(String flowId) {
		this.flowId = flowId;
	}

	public String getFlowName() {
		return flowName;
	}

	public void setFlowName(String flowName) {
		this.flowName = flowName;
	}

	public String getTrigger() {
		return trigger;
	}

	public void setTrigger(String trigger) {
		this.trigger = trigger;
	}

	public long getStartTime() {
		return startTime;
	}

	public void setStartTime(long startTime) {
		this.startTime = startTime;
	}

	public long getDuration() {
		return duration;
	}

	public void setDuration(long duration) {
		this.duration = duration;
	}

	public boolean isSuccess() {
		return success;
	}

	public void setSuccess(boolean success) {
		this.success = success;
	}

	public String getErrorNode() {
		return errorNode;
	}

	public void setErrorNode(String errorNode) {
		this.errorNode = errorNode;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public String getInput() {
		return input;
	}

	public void setInput(String input) {
		this.input = input;
	}

	public String getOutput() {
		return output;
	}

	public void setOutput(String output) {
		this.output = output;
	}
}
