package org.ssssssss.magicapi.upload.channel.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.ssssssss.magicapi.upload.channel.ChannelRequest;
import org.ssssssss.magicapi.upload.channel.HttpSender;
import org.ssssssss.magicapi.upload.channel.SignContext;
import org.ssssssss.magicapi.upload.engine.SendResult;
import org.ssssssss.magicapi.upload.engine.UploadBatch;
import org.ssssssss.magicapi.upload.engine.UploadChannel;
import org.ssssssss.magicapi.upload.store.PayloadCodec;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * HTTP 上传通道，请求体为 JSON 或表单
 * <p>
 * 网络错误、超时会抛出异常，由引擎按重试次数重试；响应按 {@link org.ssssssss.magicapi.upload.channel.ResponseRule} 判定。
 */
public class HttpUploadChannel implements UploadChannel {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final HttpChannelConfig config;

	private final HttpSender sender;

	public HttpUploadChannel(HttpChannelConfig config) {
		this.config = Objects.requireNonNull(config, "config");
		this.sender = new HttpSender(config.getConnectTimeout(), config.getReadTimeout());
	}

	public HttpChannelConfig getConfig() {
		return config;
	}

	@Override
	public SendResult send(UploadBatch batch) throws Exception {
		Map<String, String> headers = new LinkedHashMap<>(config.getHeaders());
		Map<String, String> queryParams = new LinkedHashMap<>(config.getQueryParams());
		Object body = ChannelRequest.unwrap(config.getBodyBuilder().build(batch), headers, queryParams);
		boolean form = HttpChannelConfig.CONTENT_FORM.equals(config.getContentType());
		String payload = body instanceof CharSequence ? body.toString() : (form ? toForm(body) : toJson(body));

		if (config.getSigner() != null) {
			config.getSigner().sign(new SignContext(config.getMethod(), config.getUrl(), headers, queryParams, payload, batch));
		}

		HttpHeaders httpHeaders = new HttpHeaders();
		headers.forEach(httpHeaders::set);
		if (!httpHeaders.containsKey(HttpHeaders.CONTENT_TYPE)) {
			httpHeaders.set(HttpHeaders.CONTENT_TYPE, form
					? "application/x-www-form-urlencoded;charset=UTF-8"
					: "application/json;charset=UTF-8");
		}
		HttpSender.Response response = sender.send(config.getMethod(), buildUri(config.getUrl(), queryParams), httpHeaders, payload);
		return config.getResponseRule().evaluate(response.getStatus(), response.getBody(), new JsonFieldReader(response.getBody()));
	}

	static URI buildUri(String url, Map<String, String> queryParams) {
		return HttpSender.buildUri(url, queryParams);
	}

	public static String toJson(Object body) throws Exception {
		return MAPPER.writeValueAsString(PayloadCodec.normalize(body));
	}

	static String toForm(Object body) throws Exception {
		if (!(body instanceof Map)) {
			throw new IllegalArgumentException("表单格式的请求体必须是对象（Map），请在报文转换中返回 {字段: 值}");
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<?, ?> entry : ((Map<?, ?>) body).entrySet()) {
			if (sb.length() > 0) {
				sb.append('&');
			}
			Object value = PayloadCodec.normalize(entry.getValue());
			String text;
			if (value == null) {
				text = "";
			} else if (value instanceof Map || value instanceof Collection) {
				text = MAPPER.writeValueAsString(value);
			} else {
				text = value.toString();
			}
			sb.append(HttpSender.encode(String.valueOf(entry.getKey()))).append('=').append(HttpSender.encode(text));
		}
		return sb.toString();
	}
}
