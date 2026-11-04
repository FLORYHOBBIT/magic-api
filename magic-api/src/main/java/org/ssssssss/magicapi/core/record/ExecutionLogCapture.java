package org.ssssssss.magicapi.core.record;

import org.ssssssss.magicapi.core.config.MagicConfiguration;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;

/** 每次执行独立、有上限的日志缓冲；线程池复用前恢复上下文。 */
public final class ExecutionLogCapture implements AutoCloseable {
	private static final InheritableThreadLocal<ExecutionLogCapture> CURRENT = new InheritableThreadLocal<>();
	private final ExecutionLogCapture previous;
	private final int limit;
	private final StringBuilder buffer = new StringBuilder();
	private boolean truncated;
	private boolean closed;

	public ExecutionLogCapture(int limit) {
		this.limit = Math.max(0, limit);
		this.previous = CURRENT.get();
		CURRENT.set(this);
	}

	public static ExecutionLogCapture start(String name) {
		ExecutionRecordStore store = MagicConfiguration.getExecutionRecordStore();
		ExecutionLogCapture capture = new ExecutionLogCapture(store == null ? 0 : store.getMaxLogChars());
		capture.add(LocalDateTime.now() + " 开始执行：" + name + "\n");
		return capture;
	}

	public static void append(String text) {
		ExecutionLogCapture capture = CURRENT.get();
		if (capture != null) { capture.add(text); }
	}

	private synchronized void add(String text) {
		if (closed || limit == 0 || text == null) { return; }
		int remaining = limit - buffer.length();
		if (text.length() > remaining) { truncated = true; }
		if (remaining > 0) {
			int count = Math.min(remaining, text.length());
			// 不截断 UTF-16 代理对。
			if (count < text.length() && count > 0 && Character.isHighSurrogate(text.charAt(count - 1))) { count--; }
			buffer.append(text, 0, count);
		}
	}

	public void error(Throwable error) {
		StringWriter text = new StringWriter();
		error.printStackTrace(new PrintWriter(text));
		add(text.toString());
	}

	public void finish(ExecutionRecord record) {
		add(LocalDateTime.now() + " 执行结束：" + (record.isSuccess() ? "成功" : "失败") + "，耗时 " + record.getCostMillis() + " ms\n");
		close();
		ExecutionRecordStore.record(record, limit == 0 ? null : text(), isTruncated());
	}

	public synchronized String text() { return buffer.toString(); }
	public synchronized boolean isTruncated() { return truncated; }

	@Override
	public synchronized void close() {
		if (!closed) {
			closed = true;
			if (CURRENT.get() == this) {
				if (previous == null) { CURRENT.remove(); } else { CURRENT.set(previous); }
			}
		}
	}
}
