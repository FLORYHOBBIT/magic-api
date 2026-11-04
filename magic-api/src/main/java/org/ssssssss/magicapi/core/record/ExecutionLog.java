package org.ssssssss.magicapi.core.record;

/** 单次执行的详细日志，缺失时明确返回 available=false。 */
public class ExecutionLog {
	private final boolean available;
	private final boolean truncated;
	private final String text;

	public ExecutionLog(boolean available, boolean truncated, String text) {
		this.available = available;
		this.truncated = truncated;
		this.text = text;
	}

	public boolean isAvailable() { return available; }
	public boolean isTruncated() { return truncated; }
	public String getText() { return text; }
}
