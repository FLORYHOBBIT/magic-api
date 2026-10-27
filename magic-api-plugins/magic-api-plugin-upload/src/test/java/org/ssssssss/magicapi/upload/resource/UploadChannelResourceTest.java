package org.ssssssss.magicapi.upload.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.upload.channel.ChannelFactory;
import org.ssssssss.magicapi.upload.engine.SendResult;
import org.ssssssss.magicapi.upload.engine.UploadBatch;
import org.ssssssss.magicapi.upload.support.TestHttpServer;
import org.ssssssss.magicapi.upload.support.TestHttpServer.Response;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UploadChannelResourceTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private TestHttpServer server;

	private final ChannelFactory factory = new ChannelFactory(null);

	private final UploadChannelMagicResourceStorage storage = new UploadChannelMagicResourceStorage(factory, new AesUploadChannelEncryptor("hospital-secret"));

	@BeforeEach
	void start() throws Exception {
		server = new TestHttpServer();
	}

	@AfterEach
	void stop() {
		server.close();
	}

	@Test
	void aesRoundTrip() {
		AesUploadChannelEncryptor encryptor = new AesUploadChannelEncryptor("k1");
		String a = encryptor.encrypt("P@ssw0rd");
		String b = encryptor.encrypt("P@ssw0rd");

		assertThat(a).startsWith("ENC(").endsWith(")").isNotEqualTo(b);
		assertThat(encryptor.decrypt(a)).isEqualTo("P@ssw0rd");
		assertThat(encryptor.encrypt(a)).isEqualTo(a);
		assertThat(encryptor.decrypt("plain")).isEqualTo("plain");

		assertThatThrownBy(() -> new AesUploadChannelEncryptor("k2").decrypt(a)).hasMessageContaining("secret-key");
		AesUploadChannelEncryptor none = new AesUploadChannelEncryptor(null);
		assertThat(none.isEnabled()).isFalse();
		assertThat(none.encrypt("x")).isEqualTo("x");
		assertThatThrownBy(() -> none.decrypt(a)).hasMessageContaining("没有配置");
	}

	@Test
	void writeEncryptsSensitiveOptionsAndReadKeepsCiphertext() {
		UploadChannelInfo info = channel("province", "http");
		info.getOptions().put("password", "P@ss");
		info.getOptions().put("token", "T0ken");
		info.setScript("return rows");

		String file = new String(storage.write(info), StandardCharsets.UTF_8);

		assertThat(file).doesNotContain("P@ss").doesNotContain("T0ken").contains("ENC(").contains("return rows");
		// 写入不修改内存中的对象
		assertThat(info.getOptions().get("password")).isEqualTo("P@ss");

		UploadChannelInfo read = storage.read(file.getBytes(StandardCharsets.UTF_8));
		assertThat(read.getName()).isEqualTo("province");
		assertThat(read.getScript()).isEqualTo("return rows");
		assertThat((String) read.getOptions().get("password")).startsWith("ENC(");
		assertThat(storage.decrypt(read.getOptions()).get("password")).isEqualTo("P@ss");

		// 界面原样保存时不会二次加密
		String again = new String(storage.write(read), StandardCharsets.UTF_8);
		UploadChannelInfo reread = storage.read(again.getBytes(StandardCharsets.UTF_8));
		assertThat(storage.decrypt(reread.getOptions()).get("token")).isEqualTo("T0ken");
	}

	@Test
	void validatesChannel() {
		MagicResourceService service = mock(MagicResourceService.class);
		UploadChannelInfo existing = channel("province", "http");
		existing.setId("other");
		when(service.<UploadChannelInfo>files(UploadChannelMagicResourceStorage.FOLDER)).thenReturn(Collections.singletonList(existing));
		storage.setMagicResourceService(service);

		UploadChannelInfo ok = channel("city", "HTTP");
		storage.validate(ok);
		assertThat(ok.getProtocol()).isEqualTo("http");

		assertThatThrownBy(() -> storage.validate(channel("province", "http"))).isInstanceOf(InvalidArgumentException.class);
		assertThatThrownBy(() -> storage.validate(channel("a b", "http"))).isInstanceOf(InvalidArgumentException.class);
		assertThatThrownBy(() -> storage.validate(channel("x", "mq"))).hasMessageContaining("不支持的上传协议");
		UploadChannelInfo noUrl = channel("y", "http");
		noUrl.getOptions().remove("url");
		assertThatThrownBy(() -> storage.validate(noUrl)).hasMessageContaining("url");
		assertThatThrownBy(() -> storage.validate(channel("z", "soap"))).hasMessageContaining("报文转换");
		UploadChannelInfo sftp = channel("s", "sftp");
		sftp.getOptions().put("host", "10.0.0.1");
		sftp.getOptions().put("username", "u");
		sftp.getOptions().put("password", "p");
		assertThatThrownBy(() -> storage.validate(sftp)).hasMessageContaining("knownHosts");
	}

	@Test
	void registryFindsByNameAndUnregisters() {
		UploadChannelMagicDynamicRegistry registry = new UploadChannelMagicDynamicRegistry(storage);
		UploadChannelInfo info = channel("province", "http");

		registry.register(info);
		assertThat(registry.find("province")).isSameAs(info);

		UploadChannelInfo renamed = info.copy();
		renamed.setName("province-v2");
		registry.register(renamed);
		assertThat(registry.find("province")).isNull();
		assertThat(registry.find("province-v2")).isNotNull();

		registry.unregister(renamed);
		assertThat(registry.find("province-v2")).isNull();
	}

	@Test
	void providerRunsTransformScriptWithDecryptedOptions() throws Exception {
		UploadChannelMagicDynamicRegistry registry = new UploadChannelMagicDynamicRegistry(storage);
		UploadChannelProvider provider = new UploadChannelProvider(registry, storage, factory);
		UploadChannelInfo info = channel("province", "http");
		info.getOptions().put("appSecret", "s3cret");
		info.getOptions().put("successField", "code");
		info.getOptions().put("successValues", "0");
		info.setScript("var body = {org: 'H001', total: batch.size, list: rows};\n"
				+ "headers['X-Sign'] = options.appSecret + ':' + batch.index;\n"
				+ "params['ts'] = '1700000000';\n"
				+ "return body;");
		// 模拟从文件读回：内存中是密文
		UploadChannelInfo saved = storage.read(storage.write(info));
		registry.register(saved);
		server.respond(r -> Response.json(200, "{\"code\":0}"));

		SendResult result = provider.create("province").send(new UploadBatch("report", 3, rows(2), null, 2));

		assertThat(result.isSuccess()).isTrue();
		TestHttpServer.Request request = server.requests().get(0);
		assertThat(request.header("X-Sign")).isEqualTo("s3cret:3");
		assertThat(request.rawQuery).isEqualTo("ts=1700000000");
		assertThat(MAPPER.readTree(request.body).get("total").asInt()).isEqualTo(2);
		assertThat(MAPPER.readTree(request.body).get("list").get(1).get("id").asInt()).isEqualTo(2);

		assertThatThrownBy(() -> provider.create("missing")).hasMessageContaining("找不到上传通道");
	}

	@Test
	void testSendReportsSuccessAndFailure() {
		UploadChannelProvider provider = new UploadChannelProvider(new UploadChannelMagicDynamicRegistry(storage), storage, factory);
		UploadChannelInfo info = channel("province", "http");

		Map<String, Object> ok = provider.test(info, null);
		assertThat(ok.get("success")).isEqualTo(true);
		assertThat(server.requests().get(0).body).isEqualTo("[{\"test\":true}]");

		server.respond(r -> Response.text(401, "unauthorized"));
		Map<String, Object> rejected = provider.test(info, rows(1));
		assertThat(rejected.get("success")).isEqualTo(false);
		assertThat(rejected.get("retryable")).isEqualTo(false);
		assertThat((String) rejected.get("message")).contains("401");

		info.getOptions().put("url", "http://127.0.0.1:1/none");
		info.getOptions().put("connectTimeout", 300);
		Map<String, Object> unreachable = provider.test(info, rows(1));
		assertThat(unreachable.get("success")).isEqualTo(false);
		assertThat(unreachable.get("retryable")).isEqualTo(true);
	}

	private UploadChannelInfo channel(String name, String protocol) {
		UploadChannelInfo info = new UploadChannelInfo();
		info.setId(UUID.randomUUID().toString());
		info.setName(name);
		info.setProtocol(protocol);
		info.getOptions().put("url", server.url("/upload"));
		return info;
	}

	private static List<Map<String, Object>> rows(int count) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", i);
			rows.add(row);
		}
		return rows;
	}
}
