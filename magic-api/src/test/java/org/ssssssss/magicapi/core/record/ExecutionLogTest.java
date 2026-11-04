package org.ssssssss.magicapi.core.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ssssssss.magicapi.core.logging.MagicLoggerContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionLogTest {
	@TempDir Path dir;
	private ExecutionRecord record(String id) { return new ExecutionRecord(id, "task", id, "cron", 1, 2, true, null); }

	@Test void logsSurviveRestartAndCannotBeReadAsAnotherTask() throws Exception {
		ExecutionRecordStore store = new ExecutionRecordStore(dir, 3, 100);
		ExecutionRecord record = record("one");
		store.append(record, "中文日志\n<script>alert(1)</script>", false);
		ExecutionRecordStore reopened = new ExecutionRecordStore(dir, 3, 100);
		ExecutionLog log = reopened.logs("one", record.getRunId());
		assertTrue(log.isAvailable());
		assertTrue(log.getText().startsWith("中文日志"));
		assertFalse(reopened.logs("two", record.getRunId()).isAvailable());
		assertFalse(reopened.logs("one", "../../outside").isAvailable());
	}

	@Test void logSizeAndRetentionAreBounded() throws Exception {
		ExecutionRecordStore store = new ExecutionRecordStore(dir, 2, 8);
		ExecutionRecord first = record("one");
		store.append(first, "0123456789", false);
		assertEquals("01234567", store.logs("one", first.getRunId()).getText());
		assertTrue(store.logs("one", first.getRunId()).isTruncated());
		for (int i = 0; i < 4; i++) store.append(record("one"), "new", false);
		assertFalse(store.logs("one", first.getRunId()).isAvailable());
		try (Stream<Path> files = Files.list(dir.resolve("one-logs"))) { assertEquals(2, files.count()); }
	}

	@Test void legacyRecordsAndDisabledLoggingRemainReadable() throws Exception {
		Files.write(dir.resolve("one.jsonl"), ("{\"id\":\"one\",\"success\":true}\n").getBytes(StandardCharsets.UTF_8));
		ExecutionRecordStore store = new ExecutionRecordStore(dir, 2, 0);
		assertFalse(store.list("one").get(0).isLogAvailable());
		ExecutionRecord next = record("one");
		store.append(next, "disabled", false);
		assertFalse(next.isLogAvailable());
		assertFalse(Files.exists(dir.resolve("one-logs")));
	}

	@Test void captureIsIsolatedAndClearedBeforeThreadReuse() throws Exception {
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			Future<String> first = worker.submit(() -> {
				try (ExecutionLogCapture capture = new ExecutionLogCapture(30)) {
					MagicLoggerContext.println("task-one");
					return capture.text();
				}
			});
			assertEquals("task-one", first.get());
			assertEquals("task-two", worker.submit(() -> {
				MagicLoggerContext.println("outside");
				try (ExecutionLogCapture capture = new ExecutionLogCapture(30)) {
					MagicLoggerContext.println("task-two");
					return capture.text();
				}
			}).get());
		} finally { worker.shutdownNow(); }
	}

	@Test void nestedAndChildThreadLogsDoNotPolluteClosedRuns() throws Exception {
		ExecutionLogCapture parent = new ExecutionLogCapture(100);
		try {
			MagicLoggerContext.println("parent");
			try (ExecutionLogCapture child = new ExecutionLogCapture(100)) {
				Thread thread = new Thread(() -> MagicLoggerContext.println("child"));
				thread.start(); thread.join();
				assertEquals("child", child.text());
			}
			MagicLoggerContext.println("again");
			assertEquals("parentagain", parent.text());
		} finally { parent.close(); }
		MagicLoggerContext.println("after-close");
		assertEquals("parentagain", parent.text());
	}

	@Test void captureTruncatesAndPreservesUnicode() {
		try (ExecutionLogCapture capture = new ExecutionLogCapture(4)) {
			MagicLoggerContext.println("中文😀extra");
			assertEquals("中文😀", capture.text());
			assertTrue(capture.isTruncated());
		}
		try (ExecutionLogCapture capture = new ExecutionLogCapture(3)) {
			MagicLoggerContext.println("中文😀");
			assertEquals("中文", capture.text());
		}
	}
}
