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

	private final Map<String, Object> locks = new ConcurrentHashMap<>();

	/**
	 * 每个文件当前的行数，读写都在该资源的锁内完成
	 */
	private final Map<String, Integer> lineCounts = new ConcurrentHashMap<>();

	public ExecutionRecordStore(Path dir, int maxRecords) {
		this.dir = dir;
		this.maxRecords = Math.max(1, maxRecords);
		this.compactThreshold = this.maxRecords * 2;
	}

	/**
	 * 记录一次执行；未启用或写入失败时只打日志，不影响执行本身
	 */
	public static void record(ExecutionRecord record) {
		ExecutionRecordStore store = MagicConfiguration.getExecutionRecordStore();
		if (store == null || record == null || StringUtils.isBlank(record.getId())) {
			return;
		}
		try {
			store.append(record);
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
		String key = key(record.getId());
		synchronized (lock(key)) {
			Files.createDirectories(dir);
			Path file = dir.resolve(key + ".jsonl");
			String line = JsonUtils.toJsonStringWithoutPretty(record) + System.lineSeparator();
			Files.write(file, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			int count = lineCounts.containsKey(key) ? lineCounts.get(key) + 1 : countLines(file);
			if (count > compactThreshold) {
				count = compact(file);
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
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			List<ExecutionRecord> result = new ArrayList<>(Math.min(lines.size(), maxRecords));
			for (int i = lines.size() - 1; i >= 0 && result.size() < maxRecords; i--) {
				String line = lines.get(i);
				if (StringUtils.isBlank(line)) {
					continue;
				}
				try {
					result.add(JsonUtils.readValue(line, ExecutionRecord.class));
				} catch (RuntimeException e) {
					logger.warn("跳过无法解析的执行记录：{}", file);
				}
			}
			return result;
		}
	}

	/**
	 * 只保留最近 maxRecords 行，返回压缩后的行数
	 */
	private int compact(Path file) throws IOException {
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		List<String> kept = new ArrayList<>(lines.subList(Math.max(0, lines.size() - maxRecords), lines.size()));
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
		return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8).size() : 0;
	}

	private Object lock(String key) {
		return locks.computeIfAbsent(key, k -> new Object());
	}

	private static String key(String id) {
		return id.replaceAll("[^A-Za-z0-9_\\-]", "_");
	}
}
