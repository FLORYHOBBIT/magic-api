package org.ssssssss.magicapi.upload.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ssssssss.magicapi.upload.engine.*;
import org.ssssssss.magicapi.upload.service.UploadJobRunner;
import org.ssssssss.magicapi.upload.source.ListSource;
import org.ssssssss.magicapi.upload.store.DatabaseType;
import org.ssssssss.magicapi.upload.store.TestDatabases;
import org.ssssssss.magicapi.upload.support.TestHttpServer;
import org.ssssssss.magicapi.upload.support.TestHttpServer.Response;

import java.net.URLDecoder;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadAlertTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private TestHttpServer server;

	private final TestDatabases.MutableClock clock = new TestDatabases.MutableClock();

	@BeforeEach
	void start() throws Exception {
		server = new TestHttpServer();
	}

	@AfterEach
	void stop() {
		server.close();
	}

	private UploadAlert alert() {
		return new UploadAlert(UploadAlert.Type.JOB_FAILED, "report", "省人民医院", "FAILED", "第3批失败: HTTP 503",
				3000, 1000, 1, new Date(clock.millis()));
	}

	@Test
	void dingtalkPayloadAndSign() throws Exception {
		server.respond(r -> Response.json(200, "{\"errcode\":0}"));
		new WebhookAlertNotifier(server.url("/robot/send?access_token=T"), "dingtalk", "SEC1", Duration.ofSeconds(2), clock).notify(alert());

		TestHttpServer.Request request = server.requests().get(0);
		JsonNode body = MAPPER.readTree(request.body);
		assertThat(body.get("msgtype").asText()).isEqualTo("text");
		assertThat(body.get("text").get("content").asText()).contains("[省人民医院] 数据上传任务 report 上传失败").contains("HTTP 503");
		String query = URLDecoder.decode(request.rawQuery, "UTF-8");
		long ts = clock.millis();
		assertThat(query).isEqualTo("access_token=T&timestamp=" + ts + "&sign=" + WebhookAlertNotifier.dingtalkSign(ts, "SEC1"));
	}

	@Test
	void feishuAndWecomAndGeneric() throws Exception {
		server.respond(r -> Response.json(200, "{\"code\":0}"));
		new WebhookAlertNotifier(server.url("/f"), "feishu", "FS", Duration.ofSeconds(2), clock).notify(alert());
		JsonNode feishu = MAPPER.readTree(server.requests().get(0).body);
		long seconds = clock.millis() / 1000;
		assertThat(feishu.get("msg_type").asText()).isEqualTo("text");
		assertThat(feishu.get("timestamp").asText()).isEqualTo(String.valueOf(seconds));
		assertThat(feishu.get("sign").asText()).isEqualTo(WebhookAlertNotifier.feishuSign(seconds, "FS"));

		new WebhookAlertNotifier(server.url("/w"), "wecom", null, Duration.ofSeconds(2), clock).notify(alert());
		assertThat(MAPPER.readTree(server.requests().get(1).body).get("text").get("content").asText()).contains("report");

		new WebhookAlertNotifier(server.url("/g"), "generic", null, Duration.ofSeconds(2), clock).notify(alert());
		JsonNode generic = MAPPER.readTree(server.requests().get(2).body);
		assertThat(generic.get("type").asText()).isEqualTo("JOB_FAILED");
		assertThat(generic.get("hospital").asText()).isEqualTo("省人民医院");
		assertThat(generic.get("failedRows").asInt()).isEqualTo(1000);
	}

	@Test
	void webhookErrorsAreReported() {
		server.respond(r -> Response.json(200, "{\"errcode\":310000,\"errmsg\":\"sign not match\"}"));
		WebhookAlertNotifier notifier = new WebhookAlertNotifier(server.url("/d"), "dingtalk", "x", Duration.ofSeconds(2), clock);
		assertThatThrownBy(() -> notifier.notify(alert())).hasMessageContaining("sign not match");
		assertThatThrownBy(() -> new WebhookAlertNotifier(server.url("/d"), "sms", null, Duration.ofSeconds(1))).hasMessageContaining("不支持");
	}

	@Test
	void serviceAlertsOnFailureWithSilenceAndIsolatesErrors() {
		List<UploadAlert> received = new ArrayList<>();
		UploadAlertNotifier broken = a -> {
			throw new IllegalStateException("sms down");
		};
		UploadAlertService service = new UploadAlertService(Arrays.asList(broken, received::add), "H", Duration.ofMinutes(10), true, clock);
		JobOptions options = new JobOptions("report").retry(0).retryBackoff(Duration.ZERO);
		UploadChannel failing = b -> SendResult.failure("HTTP 503");

		JobResult failed = new BatchEngine().run(options, new ListSource(rows(3)), failing, null, service.listener());
		new BatchEngine().run(options, new ListSource(rows(3)), failing, null, service.listener());

		assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
		assertThat(received).hasSize(1);
		assertThat(received.get(0).getType()).isEqualTo(UploadAlert.Type.JOB_FAILED);

		clock.advance(Duration.ofMinutes(11));
		new BatchEngine().run(options, new ListSource(rows(3)), failing, null, service.listener());
		assertThat(received).hasSize(2);

		// 成功不告警；部分失败告警
		new BatchEngine().run(options, new ListSource(rows(3)), b -> SendResult.success(), null, service.listener());
		assertThat(received).hasSize(2);
		new BatchEngine().run(options.batchSize(1).failurePolicy(FailurePolicy.SKIP), new ListSource(rows(3)),
				b -> b.getIndex() == 2 ? SendResult.failure("x") : SendResult.success(), null, service.listener());
		assertThat(received).hasSize(3);
		assertThat(received.get(2).getType()).isEqualTo(UploadAlert.Type.JOB_PARTIAL);
	}

	@Test
	void runnerAlertsOnJobAndRetryFailure() {
		List<UploadAlert> received = new ArrayList<>();
		UploadJobRunner runner = new UploadJobRunner(TestDatabases.store(null, DatabaseType.H2, clock),
				new BatchEngine(m -> {
				}), Duration.ofHours(1), false);
		runner.setAlertService(new UploadAlertService(Collections.singletonList(received::add), "H", Duration.ZERO, true, clock));
		JobOptions options = new JobOptions("report").batchSize(1).retry(0).retryBackoff(Duration.ZERO).failurePolicy(FailurePolicy.SKIP);

		runner.run(options, new ListSource(rows(2)), b -> b.getIndex() == 1 ? SendResult.failure("HTTP 502") : SendResult.success());
		runner.retryFailed(options, b -> SendResult.failure("HTTP 502"), 0);

		assertThat(received).extracting(UploadAlert::getType).containsExactly(UploadAlert.Type.JOB_PARTIAL, UploadAlert.Type.RETRY_FAILED);
		assertThat(received.get(1).getMessage()).contains("HTTP 502");
	}

	private static List<Map<String, Object>> rows(int count) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			rows.add(Collections.singletonMap("id", i));
		}
		return rows;
	}
}
