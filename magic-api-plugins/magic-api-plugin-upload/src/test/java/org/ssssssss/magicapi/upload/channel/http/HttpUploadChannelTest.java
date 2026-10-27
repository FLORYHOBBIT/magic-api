package org.ssssssss.magicapi.upload.channel.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ssssssss.magicapi.upload.channel.ResponseRule;
import org.ssssssss.magicapi.upload.channel.SignFunctions;
import org.ssssssss.magicapi.upload.engine.*;
import org.ssssssss.magicapi.upload.source.ListSource;
import org.ssssssss.magicapi.upload.support.TestHttpServer;
import org.ssssssss.magicapi.upload.support.TestHttpServer.Response;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpUploadChannelTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private TestHttpServer server;

	@BeforeEach
	void start() throws Exception {
		server = new TestHttpServer();
	}

	@AfterEach
	void stop() {
		server.close();
	}

	@Test
	void postsRowsAsJson() throws Exception {
		HttpUploadChannel channel = new HttpUploadChannel(new HttpChannelConfig(server.url("/report"))
				.header("X-Hospital", "H001")
				.param("org", "省人民医院"));

		SendResult result = channel.send(batch(rows(3)));

		assertThat(result.isSuccess()).isTrue();
		assertThat(result.getResponse()).contains("\"code\":0");
		TestHttpServer.Request request = server.requests().get(0);
		assertThat(request.method).isEqualTo("POST");
		assertThat(request.header("Content-Type")).startsWith("application/json");
		assertThat(request.header("X-Hospital")).isEqualTo("H001");
		assertThat(URLDecoder.decode(request.rawQuery, "UTF-8")).isEqualTo("org=省人民医院");
		JsonNode body = MAPPER.readTree(request.body);
		assertThat(body.isArray()).isTrue();
		assertThat(body.size()).isEqualTo(3);
		assertThat(body.get(0).get("name").asText()).isEqualTo("患者1");
		// 日期统一格式化
		assertThat(body.get(0).get("visit_time").asText()).isEqualTo("2026-10-27 08:00:00.000");
	}

	@Test
	void customBodyAndBusinessSuccessField() throws Exception {
		server.respond(r -> Response.json(200, "{\"result\":{\"code\":\"200\",\"receipt\":\"R1\"}}"));
		HttpChannelConfig config = new HttpChannelConfig(server.url("/report"))
				.bodyBuilder(b -> {
					Map<String, Object> body = new LinkedHashMap<>();
					body.put("batchNo", b.getJobName() + "-" + b.getIndex());
					body.put("total", b.getSize());
					body.put("items", b.getRows());
					return body;
				})
				.responseRule(new ResponseRule().successField("result.code").successValues("200", "0"));

		SendResult result = new HttpUploadChannel(config).send(batch(rows(2)));

		assertThat(result.isSuccess()).isTrue();
		JsonNode body = MAPPER.readTree(server.requests().get(0).body);
		assertThat(body.get("batchNo").asText()).isEqualTo("report-1");
		assertThat(body.get("items").size()).isEqualTo(2);
	}

	@Test
	void businessFailureIsRejectedByDefault() throws Exception {
		server.respond(r -> Response.json(200, "{\"code\":1001,\"msg\":\"身份证号格式不正确\"}"));
		ResponseRule rule = new ResponseRule().successField("code").successValues("0").messageField("msg");

		SendResult rejected = new HttpUploadChannel(new HttpChannelConfig(server.url("/r")).responseRule(rule)).send(batch(rows(1)));

		assertThat(rejected.isSuccess()).isFalse();
		assertThat(rejected.isRetryable()).isFalse();
		assertThat(rejected.getMessage()).contains("code=1001").contains("身份证号格式不正确");

		SendResult retryable = new HttpUploadChannel(new HttpChannelConfig(server.url("/r"))
				.responseRule(rule.retryOnBusinessFailure(true))).send(batch(rows(1)));
		assertThat(retryable.isRetryable()).isTrue();
	}

	@Test
	void classifiesHttpStatus() throws Exception {
		HttpUploadChannel channel = new HttpUploadChannel(new HttpChannelConfig(server.url("/r")));

		server.respond(r -> Response.text(503, "Service Unavailable"));
		SendResult unavailable = channel.send(batch(rows(1)));
		assertThat(unavailable.isRetryable()).isTrue();
		assertThat(unavailable.getMessage()).isEqualTo("HTTP 503: Service Unavailable");

		server.respond(r -> Response.text(429, "too many"));
		assertThat(channel.send(batch(rows(1))).isRetryable()).isTrue();

		server.respond(r -> Response.text(401, "unauthorized"));
		SendResult unauthorized = channel.send(batch(rows(1)));
		assertThat(unauthorized.isSuccess()).isFalse();
		assertThat(unauthorized.isRetryable()).isFalse();
	}

	@Test
	void timeoutThrowsSoEngineRetries() {
		server.respond(r -> r.index == 1 ? Response.json(200, "{}").delay(1500) : Response.json(200, "{\"code\":0}"));
		HttpUploadChannel channel = new HttpUploadChannel(new HttpChannelConfig(server.url("/r"))
				.readTimeout(Duration.ofMillis(300)));

		JobResult result = new BatchEngine(m -> {
		}).run(new JobOptions("report").retry(2).retryBackoff(Duration.ZERO), new ListSource(rows(5)), channel, null, null);

		assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(result.getBatches().get(0).getAttempts()).isEqualTo(2);
	}

	@Test
	void connectionRefusedThrows() throws Exception {
		TestHttpServer closed = new TestHttpServer();
		String url = closed.url("/r");
		closed.close();
		HttpUploadChannel channel = new HttpUploadChannel(new HttpChannelConfig(url).connectTimeout(Duration.ofMillis(500)));
		assertThatThrownBy(() -> channel.send(batch(rows(1)))).isInstanceOf(Exception.class);
	}

	@Test
	void engineRetries5xxThenSucceeds() {
		server.respond(r -> r.index <= 2 ? Response.text(502, "Bad Gateway") : Response.json(200, "{\"code\":0}"));
		HttpUploadChannel channel = new HttpUploadChannel(new HttpChannelConfig(server.url("/r"))
				.responseRule(new ResponseRule().successField("code").successValues("0")));

		JobResult result = new BatchEngine(m -> {
		}).run(new JobOptions("report").batchSize(1000).retry(3).retryBackoff(Duration.ZERO),
				new ListSource(rows(2500)), channel, null, null);

		assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(result.getTotalBatches()).isEqualTo(3);
		assertThat(result.getBatches().get(0).getAttempts()).isEqualTo(3);
		assertThat(server.requests()).hasSize(5);
	}

	@Test
	void formBodyAndPut() throws Exception {
		HttpChannelConfig config = new HttpChannelConfig(server.url("/form"))
				.method("put")
				.contentType("form")
				.bodyBuilder(b -> {
					Map<String, Object> form = new LinkedHashMap<>();
					form.put("orgCode", "H001");
					form.put("data", b.getRows());
					return form;
				});

		new HttpUploadChannel(config).send(batch(rows(2)));

		TestHttpServer.Request request = server.requests().get(0);
		assertThat(request.method).isEqualTo("PUT");
		assertThat(request.header("Content-Type")).startsWith("application/x-www-form-urlencoded");
		String decoded = URLDecoder.decode(request.body, "UTF-8");
		assertThat(decoded).startsWith("orgCode=H001&data=[{");
		assertThat(decoded).contains("\"name\":\"患者1\"");
	}

	@Test
	void stringBodyIsSentAsIsWithUserContentType() throws Exception {
		HttpChannelConfig config = new HttpChannelConfig(server.url("/raw"))
				.header("content-type", "text/plain;charset=UTF-8")
				.bodyBuilder(b -> "共 " + b.getSize() + " 条");

		new HttpUploadChannel(config).send(batch(rows(4)));

		TestHttpServer.Request request = server.requests().get(0);
		assertThat(request.body).isEqualTo("共 4 条");
		assertThat(request.header("Content-Type")).isEqualTo("text/plain;charset=UTF-8");
	}

	@Test
	void signerCanAddHeadersAndParams() throws Exception {
		HttpChannelConfig config = new HttpChannelConfig(server.url("/signed?appId=A1"))
				.signer(ctx -> {
					String timestamp = "1761555600";
					ctx.getQueryParams().put("timestamp", timestamp);
					ctx.getHeaders().put("X-Sign", SignFunctions.hmacSha256("secret", ctx.getBody() + timestamp));
				});

		new HttpUploadChannel(config).send(batch(rows(1)));

		TestHttpServer.Request request = server.requests().get(0);
		assertThat(request.rawQuery).isEqualTo("appId=A1&timestamp=1761555600");
		assertThat(request.header("X-Sign")).isEqualTo(SignFunctions.hmacSha256("secret", request.body + "1761555600"));
	}

	@Test
	void validatesConfig() {
		assertThatThrownBy(() -> new HttpChannelConfig("ftp://x")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new HttpChannelConfig("http://x").method("GET")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new HttpChannelConfig("http://x").contentType("xml")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new HttpChannelConfig("http://x").readTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> HttpUploadChannel.toForm(Collections.singletonList(1))).hasMessageContaining("表单");
	}

	@Test
	void signFunctions() {
		assertThat(SignFunctions.md5("abc")).isEqualTo("900150983cd24fb0d6963f7d28e17f72");
		assertThat(SignFunctions.sha256("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
		assertThat(SignFunctions.hmacSha256("key", "The quick brown fox jumps over the lazy dog"))
				.isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
		assertThat(SignFunctions.base64Decode(SignFunctions.base64("医院"))).isEqualTo("医院");
		Map<String, Object> params = new HashMap<>();
		params.put("b", 2);
		params.put("a", 1);
		params.put("c", "");
		assertThat(SignFunctions.sortedQuery(params)).isEqualTo("a=1&b=2");
	}

	private static UploadBatch batch(List<Map<String, Object>> rows) {
		return new UploadBatch("report", 1, rows, null, rows.size());
	}

	private static List<Map<String, Object>> rows(int count) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", new BigDecimal(i));
			row.put("name", "患者" + i);
			row.put("visit_time", Timestamp.valueOf("2026-10-27 08:00:00"));
			rows.add(row);
		}
		return rows;
	}
}
