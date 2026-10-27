package org.ssssssss.magicapi.upload.channel.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ssssssss.magicapi.upload.channel.ChannelFactory;
import org.ssssssss.magicapi.upload.engine.*;
import org.ssssssss.magicapi.upload.source.ListSource;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileChannelTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final ChannelFactory factory = new ChannelFactory(null);

	@Test
	void writesJsonFilePerBatch(@TempDir Path dir) throws Exception {
		Map<String, Object> options = new HashMap<>();
		options.put("dir", dir.toString());
		options.put("fileName", "{job}_{batch}.{ext}");

		JobResult result = new BatchEngine().run(new JobOptions("report").batchSize(2), new ListSource(rows(5)),
				factory.create("file", options, null), null, null);

		assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(list(dir)).containsExactly("report_0001.json", "report_0002.json", "report_0003.json");
		JsonNode last = MAPPER.readTree(dir.resolve("report_0003.json").toFile());
		assertThat(last.size()).isEqualTo(1);
		assertThat(last.get(0).get("name").asText()).isEqualTo("患者5");
		assertThat(last.get(0).get("visit_time").asText()).isEqualTo("2026-10-27 08:00:00.000");
		assertThat(result.getBatches().get(0).getResponse()).contains("report_0001.json");
	}

	@Test
	void csvWithEscapingAndGbk(@TempDir Path dir) throws Exception {
		Map<String, Object> options = new HashMap<>();
		options.put("dir", dir.toString());
		options.put("format", "csv");
		options.put("encoding", "GBK");
		options.put("fileName", "out.{ext}");
		List<Map<String, Object>> data = rows(2);
		data.get(1).put("name", "张\"三\",备注");

		factory.create("file", options, null).send(new UploadBatch("r", 1, data, null, 2));

		List<String> lines = Files.readAllLines(dir.resolve("out.csv"), Charset.forName("GBK"));
		assertThat(lines).containsExactly("id,name,visit_time",
				"1,患者1,2026-10-27 08:00:00.000",
				"2,\"张\"\"三\"\",备注\",2026-10-27 08:00:00.000");
	}

	@Test
	void customContentAndStableNameOnRetry(@TempDir Path dir) throws Exception {
		FileChannelConfig config = new FileChannelConfig().fileName("{job}_{datetime}_{batch}.txt")
				.bodyBuilder(b -> "TOTAL=" + b.getSize());
		FileUploadChannel channel = new FileUploadChannel(config, new LocalFileTarget(dir.toString()));
		UploadBatch batch = new UploadBatch("r", 1, rows(3), null, 3);

		channel.send(batch);
		Thread.sleep(1100);
		channel.send(batch);

		assertThat(list(dir)).hasSize(1);
		assertThat(new String(Files.readAllBytes(dir.resolve(list(dir).get(0))), StandardCharsets.UTF_8)).isEqualTo("TOTAL=3");
	}

	@Test
	void rejectsUnsafeFileNames(@TempDir Path dir) {
		FileChannelConfig config = new FileChannelConfig().fileName("../{job}.{ext}");
		FileUploadChannel channel = new FileUploadChannel(config, new LocalFileTarget(dir.toString()));
		assertThatThrownBy(() -> channel.send(new UploadBatch("r", 1, rows(1), null, 1))).hasMessageContaining("文件名不合法");
		assertThatThrownBy(() -> new FileChannelConfig().format("xml")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> factory.create("file", new HashMap<>(), null)).hasMessageContaining("dir");
	}

	@Test
	void remoteTargetUploadsTempThenRenames() throws Exception {
		List<String> ops = new ArrayList<>();
		RemoteSession session = new RemoteSession() {
			@Override
			public void changeDir(String dir) {
				ops.add("cd " + dir);
			}

			@Override
			public void upload(String name, byte[] content) {
				ops.add("put " + name + " " + content.length);
			}

			@Override
			public void delete(String name) {
				ops.add("rm " + name);
			}

			@Override
			public void rename(String from, String to) {
				ops.add("mv " + from + " " + to);
			}

			@Override
			public void close() {
				ops.add("close");
			}
		};

		new RemoteFileTarget(() -> session, "/upload/2026", "sftp://x").write("a.json", new byte[]{1, 2, 3});

		assertThat(ops).containsExactly("cd /upload/2026", "put a.json.tmp 3", "rm a.json", "mv a.json.tmp a.json", "close");
	}

	@Test
	void remoteFailureClosesSessionAndEngineRetries() {
		List<String> closed = new ArrayList<>();
		int[] calls = {0};
		RemoteFileTarget target = new RemoteFileTarget(() -> new RemoteSession() {
			@Override
			public void changeDir(String dir) {
			}

			@Override
			public void upload(String name, byte[] content) throws Exception {
				if (++calls[0] == 1) {
					throw new java.io.IOException("连接被重置");
				}
			}

			@Override
			public void delete(String name) {
			}

			@Override
			public void rename(String from, String to) {
			}

			@Override
			public void close() {
				closed.add("close");
			}
		}, "", "ftp://x");

		JobResult result = new BatchEngine(m -> {
		}).run(new JobOptions("r").retry(1).retryBackoff(Duration.ZERO), new ListSource(rows(1)),
				new FileUploadChannel(new FileChannelConfig(), target), null, null);

		assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(result.getBatches().get(0).getAttempts()).isEqualTo(2);
		assertThat(closed).hasSize(2);
	}

	@Test
	void ftpAndSftpConfiguration() throws Exception {
		Map<String, Object> ftp = new HashMap<>();
		ftp.put("host", "127.0.0.1");
		ftp.put("port", 1);
		ftp.put("username", "u");
		ftp.put("password", "p");
		ftp.put("connectTimeout", 500);
		UploadChannel channel = factory.create("ftp", ftp, null);
		// 端口 1 无服务：连接失败抛出异常，由引擎重试
		assertThatThrownBy(() -> channel.send(new UploadBatch("r", 1, rows(1), null, 1))).isInstanceOf(Exception.class);

		Map<String, Object> sftp = new HashMap<>();
		sftp.put("host", "127.0.0.1");
		sftp.put("username", "u");
		assertThatThrownBy(() -> factory.create("sftp", sftp, null)).hasMessageContaining("knownHosts");
		sftp.put("strictHostKey", false);
		assertThatThrownBy(() -> factory.create("sftp", sftp, null)).hasMessageContaining("密码");
		sftp.put("password", "p");
		assertThat(factory.create("sftp", sftp, null)).isInstanceOf(FileUploadChannel.class);
	}

	private static List<String> list(Path dir) throws Exception {
		try (Stream<Path> files = Files.list(dir)) {
			return files.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
		}
	}

	private static List<Map<String, Object>> rows(int count) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", i);
			row.put("name", "患者" + i);
			row.put("visit_time", Timestamp.valueOf("2026-10-27 08:00:00"));
			rows.add(row);
		}
		return rows;
	}
}
