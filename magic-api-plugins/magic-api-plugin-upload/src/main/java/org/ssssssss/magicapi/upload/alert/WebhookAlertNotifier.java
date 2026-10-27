package org.ssssssss.magicapi.upload.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.ssssssss.magicapi.upload.channel.HttpSender;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/**
 * Webhook 告警
 * <ul>
 *     <li>dingtalk：钉钉自定义机器人，配置 secret 时按钉钉规则加签</li>
 *     <li>wecom：企业微信群机器人</li>
 *     <li>feishu：飞书自定义机器人，配置 secret 时按飞书规则加签</li>
 *     <li>generic：通用 JSON，字段 title、content、type、job、hospital、status、message、totalRows、failedRows、failedBatches、time</li>
 * </ul>
 */
public class WebhookAlertNotifier implements UploadAlertNotifier {

	public static final List<String> TYPES = Arrays.asList("dingtalk", "wecom", "feishu", "generic");

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final String url;

	private final String type;

	private final String secret;

	private final HttpSender sender;

	private final Clock clock;

	public WebhookAlertNotifier(String url, String type, String secret, Duration timeout) {
		this(url, type, secret, timeout, Clock.systemDefaultZone());
	}

	public WebhookAlertNotifier(String url, String type, String secret, Duration timeout, Clock clock) {
		if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
			throw new IllegalArgumentException("告警 Webhook 地址必须以 http:// 或 https:// 开头: " + url);
		}
		String value = type == null ? "generic" : type.trim().toLowerCase(Locale.ROOT);
		if (!TYPES.contains(value)) {
			throw new IllegalArgumentException("不支持的告警类型: " + type + "，可选: " + String.join("、", TYPES));
		}
		this.url = url.trim();
		this.type = value;
		this.secret = secret == null || secret.trim().isEmpty() ? null : secret.trim();
		this.sender = new HttpSender(timeout, timeout);
		this.clock = clock;
	}

	@Override
	public void notify(UploadAlert alert) throws Exception {
		long timestamp = clock.millis();
		Map<String, String> query = new LinkedHashMap<>();
		Map<String, Object> body = new LinkedHashMap<>();
		switch (type) {
			case "dingtalk":
				body.put("msgtype", "text");
				body.put("text", Collections.singletonMap("content", alert.getText()));
				if (secret != null) {
					query.put("timestamp", String.valueOf(timestamp));
					query.put("sign", dingtalkSign(timestamp, secret));
				}
				break;
			case "wecom":
				body.put("msgtype", "text");
				body.put("text", Collections.singletonMap("content", alert.getText()));
				break;
			case "feishu":
				if (secret != null) {
					long seconds = timestamp / 1000;
					body.put("timestamp", String.valueOf(seconds));
					body.put("sign", feishuSign(seconds, secret));
				}
				body.put("msg_type", "text");
				body.put("content", Collections.singletonMap("text", alert.getText()));
				break;
			default:
				body.put("title", alert.getTitle());
				body.put("content", alert.getText());
				body.put("type", alert.getType().name());
				body.put("job", alert.getJobName());
				body.put("hospital", alert.getHospital());
				body.put("status", alert.getStatus());
				body.put("message", alert.getMessage());
				body.put("totalRows", alert.getTotalRows());
				body.put("failedRows", alert.getFailedRows());
				body.put("failedBatches", alert.getFailedBatches());
				body.put("time", alert.getTime().getTime());
		}
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8");
		HttpSender.Response response = sender.send("POST", HttpSender.buildUri(url, query), headers, MAPPER.writeValueAsString(body));
		if (response.getStatus() < 200 || response.getStatus() >= 300) {
			throw new IOException("告警 Webhook 返回 HTTP " + response.getStatus() + ": " + response.getBody());
		}
		// 钉钉、企业微信失败时返回 errcode != 0，飞书返回 code != 0
		String errcode = readField(response.getBody(), "errcode");
		String code = readField(response.getBody(), "code");
		if ((errcode != null && !"0".equals(errcode)) || ("feishu".equals(type) && code != null && !"0".equals(code))) {
			throw new IOException("告警 Webhook 返回错误: " + response.getBody());
		}
	}

	/**
	 * 钉钉加签：Base64(HmacSHA256(key=secret, data=timestamp + "\n" + secret))，再 URL 编码（编码由查询参数拼接完成）
	 */
	static String dingtalkSign(long timestampMillis, String secret) throws Exception {
		return Base64.getEncoder().encodeToString(hmac(secret, timestampMillis + "\n" + secret));
	}

	/**
	 * 飞书加签：Base64(HmacSHA256(key=timestamp + "\n" + secret, data=空))
	 */
	static String feishuSign(long timestampSeconds, String secret) throws Exception {
		return Base64.getEncoder().encodeToString(hmac(timestampSeconds + "\n" + secret, ""));
	}

	private static byte[] hmac(String key, String data) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
	}

	private static String readField(String body, String field) {
		try {
			com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(body);
			com.fasterxml.jackson.databind.JsonNode value = node == null ? null : node.get(field);
			return value == null || value.isNull() ? null : value.asText();
		} catch (Exception e) {
			return null;
		}
	}
}
