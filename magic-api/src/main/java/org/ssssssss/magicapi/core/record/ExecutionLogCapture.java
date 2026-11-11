package org.ssssssss.magicapi.core.record;

import org.ssssssss.magicapi.core.config.MagicConfiguration;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.slf4j.LoggerFactory;

/** 正式执行流式落盘；兼容有上限的内存采集，线程复用前恢复上下文。 */
public final class ExecutionLogCapture implements AutoCloseable {
	private static final InheritableThreadLocal<ExecutionLogCapture> CURRENT = new InheritableThreadLocal<>();
	private final ExecutionLogCapture previous;
	private final int limit;
	private final StringBuilder buffer = new StringBuilder();
	private boolean truncated;
	private boolean closed;
	private ExecutionRecordStore store;
	private Path file;
	private BufferedWriter writer;
	private boolean writeFailed;

	public ExecutionLogCapture(int limit) {
		this.limit = Math.max(0, limit);
		this.previous = CURRENT.get();
		CURRENT.set(this);
	}

	ExecutionLogCapture(ExecutionRecordStore store) {
		this(store == null ? 0 : store.getMaxLogChars());
		this.store = store;
		if (limit > 0) {
			try {
				file = store.createCaptureFile();
				writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
			} catch (Exception e) {
				writeFailed = true;
				LoggerFactory.getLogger(ExecutionLogCapture.class).warn("完整执行日志创建失败，退回有限日志缓冲", e);
				if (file != null) { try { Files.deleteIfExists(file); } catch (Exception ignored) {} file = null; }
			}
		}
	}

	public static ExecutionLogCapture start(String name) {
		ExecutionRecordStore store = MagicConfiguration.getExecutionRecordStore();
		ExecutionLogCapture capture = new ExecutionLogCapture(store);
		capture.add(LocalDateTime.now() + " 开始执行：" + name + "\n");
		return capture;
	}

	public static void append(String text) {
		ExecutionLogCapture capture = CURRENT.get();
		if (capture != null) { capture.add(text); }
	}

	private synchronized void add(String text) {
		if (closed || limit == 0 || text == null) { return; }
		if (writer != null) {
			if (!writeFailed) {
				try { writer.write(text); }
				catch (Exception e) {
					writeFailed = true;
					LoggerFactory.getLogger(ExecutionLogCapture.class).warn("完整执行日志写入失败，执行继续", e);
				}
			}
			return;
		}
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
		try {
			if (store != null && file != null) { store.appendComplete(record, file, writeFailed); }
			else if (store != null) { store.append(record, limit == 0 ? null : text(), isTruncated()); }
			else { ExecutionRecordStore.record(record, limit == 0 ? null : text(), isTruncated()); }
		} catch (Exception e) {
			LoggerFactory.getLogger(ExecutionLogCapture.class).warn("保存执行记录失败，业务执行结果不变", e);
		} finally {
			// 成功时临时文件已移动；保存失败时保留 partial，便于现场恢复。
			if (file != null && record.isLogAvailable()) { try { Files.deleteIfExists(file); } catch (Exception ignored) {} }
		}
	}

	public synchronized String text() { return buffer.toString(); }
	public synchronized boolean isTruncated() { return truncated || writeFailed; }

	@Override
	public synchronized void close() {
		if (!closed) {
			closed = true;
			if (writer != null) {
				try { writer.close(); } catch (Exception e) {
					writeFailed = true;
					LoggerFactory.getLogger(ExecutionLogCapture.class).warn("关闭完整执行日志失败", e);
				}
			}
			if (CURRENT.get() == this) {
				if (previous == null) { CURRENT.remove(); } else { CURRENT.set(previous); }
			}
		}
	}
}
