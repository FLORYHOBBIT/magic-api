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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 执行记录的文件存储：每个资源一个 jsonl 文件，只保留最近 maxRecords 条
 */
public class ExecutionRecordStore {

	private static final Logger logger = LoggerFactory.getLogger(ExecutionRecordStore.class);

	private static final int MAX_MESSAGE_LENGTH = 500;

	private final Path dir;

	private final int maxRecords;

	public ExecutionRecordStore(Path dir, int maxRecords) {
		this.dir = dir;
		this.maxRecords = Math.max(1, maxRecords);
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

	public synchronized void append(ExecutionRecord record) throws IOException {
		Files.createDirectories(dir);
		Path file = file(record.getId());
		List<String> lines = Files.exists(file)
				? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
				: new ArrayList<>();
		lines.add(JsonUtils.toJsonStringWithoutPretty(record));
		if (lines.size() > maxRecords) {
			lines = new ArrayList<>(lines.subList(lines.size() - maxRecords, lines.size()));
		}
		Path tmp = Files.createTempFile(dir, "record-", ".tmp");
		try {
			Files.write(tmp, lines, StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/**
	 * 按时间倒序返回某个资源的执行记录
	 */
	public synchronized List<ExecutionRecord> list(String id) throws IOException {
		if (StringUtils.isBlank(id)) {
			return Collections.emptyList();
		}
		Path file = file(id);
		if (!Files.exists(file)) {
			return Collections.emptyList();
		}
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		List<ExecutionRecord> result = new ArrayList<>(lines.size());
		for (int i = lines.size() - 1; i >= 0; i--) {
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

	private Path file(String id) {
		return dir.resolve(id.replaceAll("[^A-Za-z0-9_\\-]", "_") + ".jsonl");
	}
}
