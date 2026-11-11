package org.ssssssss.magicapi.core.record;

/** 单次执行的详细日志，缺失时明确返回 available=false。 */
public class ExecutionLog {
	private final boolean available;
	private final boolean truncated;
	private final String text;
	private final long nextOffset;
	private final boolean hasMore;

	public ExecutionLog(boolean available, boolean truncated, String text) {
		this(available, truncated, text, text.length(), false);
	}

	public ExecutionLog(boolean available, boolean truncated, String text, long nextOffset, boolean hasMore) {
		this.available = available;
		this.truncated = truncated;
		this.text = text;
		this.nextOffset = nextOffset;
		this.hasMore = hasMore;
	}

	public boolean isAvailable() { return available; }
	public boolean isTruncated() { return truncated; }
	public String getText() { return text; }
	public long getNextOffset() { return nextOffset; }
	public boolean isHasMore() { return hasMore; }
}
