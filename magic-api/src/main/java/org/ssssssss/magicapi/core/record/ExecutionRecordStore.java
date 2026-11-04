package org.ssssssss.magicapi.core.record;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.utils.JsonUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayDeque;
import java.io.BufferedReader;
import java.io.Reader;
import java.util.stream.Stream;

/**
 * 执行记录的文件存储：每个资源一个 jsonl 文件。
 * <p>
 * 写入为追加写，只有行数超过 2 × maxRecords 时才压缩为最近 maxRecords 条，
 * 因此平时每次写入只是一次追加，压缩频率约为每 maxRecords 次写入一次。
 * 加锁按资源粒度进行，不同资源之间互不阻塞。
 */
public class ExecutionRecordStore {

	private static final Logger logger = LoggerFactory.getLogger(ExecutionRecordStore.class);

	private static final int MAX_MESSAGE_LENGTH = 500;

	private final Path dir;

	private final int maxRecords;

	private final int compactThreshold;
	private final int maxLogChars;

	private final Map<String, Object> locks = new ConcurrentHashMap<>();

	/**
	 * 每个文件当前的行数，读写都在该资源的锁内完成
	 */
	private final Map<String, Integer> lineCounts = new ConcurrentHashMap<>();

	public ExecutionRecordStore(Path dir, int maxRecords) {
		this(dir, maxRecords, 262144);
	}

	public ExecutionRecordStore(Path dir, int maxRecords, int maxLogChars) {
		this.dir = dir;
		this.maxRecords = Math.max(1, maxRecords);
		this.compactThreshold = (int) Math.min(Integer.MAX_VALUE, (long) this.maxRecords * 2);
		this.maxLogChars = Math.min(1048576, Math.max(0, maxLogChars));
	}

	public int getMaxLogChars() { return maxLogChars; }

	/**
	 * 记录一次执行；未启用或写入失败时只打日志，不影响执行本身
	 */
	public static void record(ExecutionRecord record) {
		record(record, null, false);
	}

	public static void record(ExecutionRecord record, String log, boolean truncated) {
		ExecutionRecordStore store = MagicConfiguration.getExecutionRecordStore();
		if (store == null || record == null || StringUtils.isBlank(record.getId())) {
			return;
		}
		try {
			store.append(record, log, truncated);
		} catch (Exception e) {
			logger.warn("写入执行记录失败：{}", record.getId(), e);
		}
	}

	/**
	 * 异常转为记录中的错误摘要
	 */
	public static String messageOf(Throwable throwable) {
		String text = StringUtils.defaultIfBlank(throwable.getMessage(), throwable.getClass().getSimpleName());
		return StringUtils.abbreviate(text, MAX_MESSAGE_LENGTH);
	}

	public void append(ExecutionRecord record) throws IOException {
		append(record, null, false);
	}

	public void append(ExecutionRecord record, String log, boolean truncated) throws IOException {
		String key = key(record.getId());
		synchronized (lock(key)) {
			Files.createDirectories(dir);
			record.setRunId(UUID.randomUUID().toString().replace("-", ""));
			record.setLogAvailable(false);
			record.setLogTruncated(truncated);
			if (log != null && maxLogChars > 0) {
				try {
					Path folder = dir.resolve(key + "-logs");
					Files.createDirectories(folder);
					if (log.length() > maxLogChars) {
						int end = maxLogChars;
						if (end > 0 && Character.isHighSurrogate(log.charAt(end - 1))) { end--; }
						log = log.substring(0, end);
						record.setLogTruncated(true);
					}
					Files.write(folder.resolve(record.getRunId() + ".log"), log.getBytes(StandardCharsets.UTF_8));
					record.setLogAvailable(true);
				} catch (IOException e) {
					logger.warn("写入执行日志失败：{}", record.getId(), e);
				}
			}
			Path file = dir.resolve(key + ".jsonl");
			String line = JsonUtils.toJsonStringWithoutPretty(record) + System.lineSeparator();
			Files.write(file, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			int count = lineCounts.containsKey(key) ? lineCounts.get(key) + 1 : countLines(file);
			if (count > compactThreshold) {
				count = compact(file);
				pruneLogs(key, recentLines(file, maxRecords));
			}
			lineCounts.put(key, count);
		}
	}

	/**
	 * 按时间倒序返回某个资源的最近执行记录（最多 maxRecords 条）
	 */
	public List<ExecutionRecord> list(String id) throws IOException {
		if (StringUtils.isBlank(id)) {
			return Collections.emptyList();
		}
		String key = key(id);
		synchronized (lock(key)) {
			Path file = dir.resolve(key + ".jsonl");
			if (!Files.exists(file)) {
				return Collections.emptyList();
			}
			List<String> lines = recentLines(file, maxRecords);
			List<ExecutionRecord> result = new ArrayList<>(Math.min(lines.size(), maxRecords));
			for (int i = lines.size() - 1; i >= 0 && result.size() < maxRecords; i--) {
				String line = lines.get(i);
				if (StringUtils.isBlank(line)) {
					continue;
				}
				try {
					ExecutionRecord record = JsonUtils.readValue(line, ExecutionRecord.class);
					if (record != null && id.equals(record.getId())) { result.add(record); }
				} catch (RuntimeException e) {
					logger.warn("跳过无法解析的执行记录：{}", file);
				}
			}
			return result;
		}
	}

	public ExecutionLog logs(String id, String runId) throws IOException {
		if (StringUtils.isBlank(id) || runId == null || !runId.matches("[a-f0-9]{32}")) {
			return new ExecutionLog(false, false, "");
		}
		String key = key(id);
		synchronized (lock(key)) {
			ExecutionRecord record = list(id).stream().filter(it -> runId.equals(it.getRunId())).findFirst().orElse(null);
			Path file = dir.resolve(key + "-logs").resolve(runId + ".log");
			if (record == null || !record.isLogAvailable() || !Files.isRegularFile(file)) {
				return new ExecutionLog(false, false, "");
			}
			StringBuilder text = new StringBuilder();
			int readLimit = maxLogChars == 0 ? 262144 : maxLogChars;
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				char[] chunk = new char[4096];
				int size;
				while (text.length() <= readLimit && (size = reader.read(chunk, 0, Math.min(chunk.length, readLimit + 1 - text.length()))) > 0) {
					text.append(chunk, 0, size);
				}
			}
			boolean truncated = record.isLogTruncated() || text.length() > readLimit;
			if (text.length() > readLimit) { text.setLength(readLimit); }
			if (text.length() > 0 && Character.isHighSurrogate(text.charAt(text.length() - 1))) { text.setLength(text.length() - 1); }
			return new ExecutionLog(true, truncated, text.toString());
		}
	}

	/**
	 * 只保留最近 maxRecords 行，返回压缩后的行数
	 */
	private int compact(Path file) throws IOException {
		List<String> kept = recentLines(file, maxRecords);
		Path tmp = Files.createTempFile(dir, "record-", ".tmp");
		try {
			Files.write(tmp, kept, StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(tmp);
		}
		return kept.size();
	}

	private int countLines(Path file) throws IOException {
		if (!Files.exists(file)) { return 0; }
		try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
			return (int) Math.min(Integer.MAX_VALUE, lines.count());
		}
	}

	private List<String> recentLines(Path file, int limit) throws IOException {
		ArrayDeque<String> tail = new ArrayDeque<>();
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (tail.size() == limit) { tail.removeFirst(); }
				tail.addLast(line);
			}
		}
		return new ArrayList<>(tail);
	}

	private void pruneLogs(String key, List<String> retained) throws IOException {
		Path folder = dir.resolve(key + "-logs");
		if (!Files.isDirectory(folder)) { return; }
		Set<String> names = new HashSet<>();
		for (String line : retained) {
			ExecutionRecord record = JsonUtils.readValue(line, ExecutionRecord.class);
			if (record != null && record.getRunId() != null) { names.add(record.getRunId() + ".log"); }
		}
		try (Stream<Path> files = Files.list(folder)) {
			for (Path file : (Iterable<Path>) files::iterator) {
				if (file.getFileName().toString().matches("[a-f0-9]{32}\\.log") && !names.contains(file.getFileName().toString())) {
					Files.deleteIfExists(file);
				}
			}
		}
	}

	private Object lock(String key) {
		return locks.computeIfAbsent(key, k -> new Object());
	}

	private static String key(String id) {
		return id.replaceAll("[^A-Za-z0-9_\\-]", "_");
	}
}
