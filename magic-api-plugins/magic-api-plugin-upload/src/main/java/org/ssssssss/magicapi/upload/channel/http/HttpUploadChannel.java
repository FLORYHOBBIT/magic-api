package org.ssssssss.magicapi.upload.channel.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.ssssssss.magicapi.upload.channel.SignContext;
import org.ssssssss.magicapi.upload.engine.SendResult;
import org.ssssssss.magicapi.upload.engine.UploadBatch;
import org.ssssssss.magicapi.upload.engine.UploadChannel;
import org.ssssssss.magicapi.upload.store.PayloadCodec;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * HTTP 上传通道，请求体为 JSON 或表单
 * <p>
 * 网络错误、超时会抛出异常，由引擎按重试次数重试；响应按 {@link org.ssssssss.magicapi.upload.channel.ResponseRule} 判定。
 */
public class HttpUploadChannel implements UploadChannel {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final ResponseErrorHandler NO_ERROR = new ResponseErrorHandler() {
		@Override
		public boolean hasError(ClientHttpResponse response) {
			return false;
		}

		@Override
		public void handleError(ClientHttpResponse response) {
		}
	};

	private final HttpChannelConfig config;

	private final RestTemplate restTemplate;

	public HttpUploadChannel(HttpChannelConfig config) {
		this.config = Objects.requireNonNull(config, "config");
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout((int) config.getConnectTimeout().toMillis());
		factory.setReadTimeout((int) config.getReadTimeout().toMillis());
		// 流式发送时，HttpURLConnection 遇到 401 会抛 HttpRetryException，导致"未授权"被误判为网络错误而重试
		factory.setOutputStreaming(false);
		this.restTemplate = new RestTemplate(factory);
		this.restTemplate.setMessageConverters(Collections.singletonList(new ByteArrayHttpMessageConverter()));
		this.restTemplate.setErrorHandler(NO_ERROR);
	}

	public HttpChannelConfig getConfig() {
		return config;
	}

	@Override
	public SendResult send(UploadBatch batch) throws Exception {
		Object body = config.getBodyBuilder().build(batch);
		boolean form = HttpChannelConfig.CONTENT_FORM.equals(config.getContentType());
		String payload = body instanceof CharSequence ? body.toString() : (form ? toForm(body) : toJson(body));

		Map<String, String> headers = new LinkedHashMap<>(config.getHeaders());
		Map<String, String> queryParams = new LinkedHashMap<>(config.getQueryParams());
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
		RequestEntity<byte[]> request = new RequestEntity<>(payload.getBytes(StandardCharsets.UTF_8), httpHeaders,
				HttpMethod.valueOf(config.getMethod()), buildUri(config.getUrl(), queryParams));
		ResponseEntity<byte[]> response = restTemplate.exchange(request, byte[].class);
		String text = decode(response);
		return config.getResponseRule().evaluate(response.getStatusCodeValue(), text, new JsonFieldReader(text));
	}

	static URI buildUri(String url, Map<String, String> queryParams) {
		if (queryParams.isEmpty()) {
			return URI.create(url);
		}
		StringBuilder sb = new StringBuilder(url);
		boolean hasQuery = url.indexOf('?') >= 0;
		for (Map.Entry<String, String> entry : queryParams.entrySet()) {
			sb.append(hasQuery ? '&' : '?');
			hasQuery = true;
			sb.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
		}
		return URI.create(sb.toString());
	}

	static String toJson(Object body) throws Exception {
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
			sb.append(encode(String.valueOf(entry.getKey()))).append('=').append(encode(text));
		}
		return sb.toString();
	}

	private static String encode(String value) {
		try {
			return URLEncoder.encode(value == null ? "" : value, "UTF-8");
		} catch (java.io.UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String decode(ResponseEntity<byte[]> response) {
		byte[] bytes = response.getBody();
		if (bytes == null) {
			return "";
		}
		MediaType type = response.getHeaders().getContentType();
		Charset charset = type == null || type.getCharset() == null ? StandardCharsets.UTF_8 : type.getCharset();
		return new String(bytes, charset);
	}
}
