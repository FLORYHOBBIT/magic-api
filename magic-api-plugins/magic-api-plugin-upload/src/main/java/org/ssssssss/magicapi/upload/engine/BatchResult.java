package org.ssssssss.magicapi.upload.engine;

/**
 * 单个批次的最终结果（包含重试后）
 */
public class BatchResult {

	private final int index;

	private final int size;

	private final boolean success;

	/**
	 * 实际发送次数（含首次）
	 */
	private final int attempts;

	private final String message;

	private final String response;

	private final long costMillis;

	public BatchResult(int index, int size, boolean success, int attempts, String message, String response, long costMillis) {
		this.index = index;
		this.size = size;
		this.success = success;
		this.attempts = attempts;
		this.message = message;
		this.response = response;
		this.costMillis = costMillis;
	}

	public int getIndex() {
		return index;
	}

	public int getSize() {
		return size;
	}

	public boolean isSuccess() {
		return success;
	}

	public int getAttempts() {
		return attempts;
	}

	public String getMessage() {
		return message;
	}

	public String getResponse() {
		return response;
	}

	public long getCostMillis() {
		return costMillis;
	}
}
