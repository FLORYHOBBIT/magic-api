package org.ssssssss.magicapi.upload.engine;

/**
 * 一次发送的结果
 */
public class SendResult {

	private final boolean success;

	private final String message;

	/**
	 * 对方返回的原始内容（回执），用于记录
	 */
	private final String response;

	/**
	 * 失败时是否值得重试。网络错误、超时、5xx 应重试；数据校验不通过之类的业务拒绝不应重试。
	 */
	private final boolean retryable;

	private SendResult(boolean success, String message, String response, boolean retryable) {
		this.success = success;
		this.message = message;
		this.response = response;
		this.retryable = retryable;
	}

	public static SendResult success() {
		return success(null);
	}

	public static SendResult success(String response) {
		return new SendResult(true, "success", response, false);
	}

	/**
	 * 可重试的失败
	 */
	public static SendResult failure(String message) {
		return failure(message, null);
	}

	/**
	 * 可重试的失败
	 */
	public static SendResult failure(String message, String response) {
		return new SendResult(false, message, response, true);
	}

	/**
	 * 不可重试的失败（业务拒绝）
	 */
	public static SendResult rejected(String message, String response) {
		return new SendResult(false, message, response, false);
	}

	public boolean isSuccess() {
		return success;
	}

	public String getMessage() {
		return message;
	}

	public String getResponse() {
		return response;
	}

	public boolean isRetryable() {
		return retryable;
	}
}
