package org.ssssssss.magicapi.flow.engine;

/**
 * 节点执行失败，但仍有输出（例如外部系统返回了业务失败的回执）。
 * <p>
 * 引擎会把 output 记为节点输出，失败连线中可以通过节点 id 或 error.output 读取。
 */
public class FlowNodeException extends RuntimeException {

	private final transient Object output;

	/**
	 * 定时上传中是否值得重试：网络错误、超时、5xx 为 true，业务拒绝为 false
	 */
	private final boolean retryable;

	public FlowNodeException(String message, Object output, boolean retryable) {
		super(message);
		this.output = output;
		this.retryable = retryable;
	}

	public FlowNodeException(String message, Object output, boolean retryable, Throwable cause) {
		super(message, cause);
		this.output = output;
		this.retryable = retryable;
	}

	public Object getOutput() {
		return output;
	}

	public boolean isRetryable() {
		return retryable;
	}
}
