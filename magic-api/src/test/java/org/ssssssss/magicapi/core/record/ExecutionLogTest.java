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
	@Test void completeCaptureStreamsBeyondPreviewAndAllSegmentsAndDownloadMatch() throws Exception {
		ExecutionRecordStore store = new ExecutionRecordStore(dir, 2, 2097152);
		ExecutionRecord record = record("complete");
		String block = new String(new char[8191]).replace('\0', '中') + "😀";
		ExecutionLogCapture capture = new ExecutionLogCapture(store);
		for (int i = 0; i < 600; i++) MagicLoggerContext.println(block);
		assertEquals("", capture.text(), "streaming capture must not accumulate the complete log in heap");
		capture.finish(record);
		assertFalse(record.isLogTruncated());
		long characters = 0;
		java.security.MessageDigest downloaded = java.security.MessageDigest.getInstance("SHA-256");
		try (java.io.Reader input = new java.io.InputStreamReader(new java.security.DigestInputStream(store.openLog("complete", record.getRunId()), downloaded), StandardCharsets.UTF_8)) {
			char[] buffer = new char[8192]; int count;
			while ((count = input.read(buffer)) != -1) characters += count;
		}
		assertTrue(characters > 2097152 * 2L);
		long offset = 0;
		java.security.MessageDigest segments = java.security.MessageDigest.getInstance("SHA-256");
		ExecutionLog segment;
		do {
			segment = store.logs("complete", record.getRunId(), offset);
			assertFalse(segment.isTruncated());
			assertTrue(segment.getText().length() <= store.getMaxLogChars());
			assertFalse(Character.isHighSurrogate(segment.getText().charAt(segment.getText().length() - 1)));
			segments.update(segment.getText().getBytes(StandardCharsets.UTF_8));
			offset = segment.getNextOffset();
		} while (segment.isHasMore());
		assertEquals(characters, offset);
		assertArrayEquals(downloaded.digest(), segments.digest());
		assertTrue(segment.getText().contains("执行结束：成功"));
		assertNull(store.openLog("another", record.getRunId()));
		assertNull(store.openLog("complete", "../outside"));
		assertThrows(IllegalArgumentException.class, () -> store.logs("complete", record.getRunId(), -1));
		try (Stream<Path> files = Files.list(dir.resolve("in-progress"))) { assertEquals(0, files.count()); }
	}

	@Test void completeCaptureFailureDoesNotBreakTheTaskAndIsExplicit() throws Exception {
		Files.write(dir.resolve("blocked"), new byte[]{1});
		ExecutionRecordStore store = new ExecutionRecordStore(dir.resolve("blocked"), 2, 20);
		ExecutionLogCapture capture = new ExecutionLogCapture(store);
		MagicLoggerContext.println("some task output");
		assertTrue(capture.isTruncated());
		assertDoesNotThrow(() -> capture.finish(record("failure")));
		try (ExecutionLogCapture next = new ExecutionLogCapture(100)) {
			MagicLoggerContext.println("next task"); assertEquals("next task", next.text());
		}
	}
	@Test void eightTimesDefaultSurvivesCaptureStorageAndRead() throws Exception {
		int limit = 262144 * 8;
		assertEquals(limit, new org.ssssssss.magicapi.core.config.ExecutionRecordConfig().getMaxLogChars());
		ExecutionRecordStore store = new ExecutionRecordStore(dir, 2);
		assertEquals(limit, store.getMaxLogChars());
		assertEquals(limit, new ExecutionRecordStore(dir, 2, Integer.MAX_VALUE).getMaxLogChars());
		assertEquals(100, new ExecutionRecordStore(dir, 2, 100).getMaxLogChars());
		StringBuilder text = new StringBuilder(limit);
		while (text.length() < limit - 2) text.append('中');
		text.append("😀");
		ExecutionRecord record = record("large");
		try (ExecutionLogCapture capture = new ExecutionLogCapture(limit)) {
			MagicLoggerContext.println(text.toString());
			assertFalse(capture.isTruncated());
			store.append(record, capture.text(), capture.isTruncated());
		}
		ExecutionLog log = store.logs("large", record.getRunId());
		assertEquals(text.toString(), log.getText());
		assertFalse(log.isTruncated());
		store.append(record, text.append('尾').toString(), false);
		assertTrue(store.logs("large", record.getRunId()).isTruncated());
		assertEquals(limit, store.logs("large", record.getRunId()).getText().length());
	}
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
