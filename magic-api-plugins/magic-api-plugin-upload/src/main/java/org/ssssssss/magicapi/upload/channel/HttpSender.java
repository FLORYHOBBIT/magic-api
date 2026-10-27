package org.ssssssss.magicapi.upload.channel;

import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**
 * HTTP 与 SOAP 通道共用的请求发送
 * <p>
 * 不把非 2xx 当异常，由调用方按响应判定；网络错误、超时抛出异常，由引擎重试。
 */
public class HttpSender {

	private static final ResponseErrorHandler NO_ERROR = new ResponseErrorHandler() {
		@Override
		public boolean hasError(ClientHttpResponse response) {
			return false;
		}

		@Override
		public void handleError(ClientHttpResponse response) {
		}
	};

	private final RestTemplate restTemplate;

	public HttpSender(Duration connectTimeout, Duration readTimeout) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(toMillis(connectTimeout, "连接超时"));
		factory.setReadTimeout(toMillis(readTimeout, "读取超时"));
		// 流式发送时，HttpURLConnection 遇到 401 会抛 HttpRetryException，导致"未授权"被误判为网络错误而重试
		factory.setOutputStreaming(false);
		this.restTemplate = new RestTemplate(factory);
		this.restTemplate.setMessageConverters(Collections.singletonList(new ByteArrayHttpMessageConverter()));
		this.restTemplate.setErrorHandler(NO_ERROR);
	}

	public Response send(String method, URI uri, HttpHeaders headers, String body) {
		RequestEntity<byte[]> request = new RequestEntity<>(body.getBytes(StandardCharsets.UTF_8), headers, HttpMethod.valueOf(method), uri);
		ResponseEntity<byte[]> response = restTemplate.exchange(request, byte[].class);
		byte[] bytes = response.getBody();
		String text = "";
		if (bytes != null) {
			MediaType type = response.getHeaders().getContentType();
			Charset charset = type == null || type.getCharset() == null ? StandardCharsets.UTF_8 : type.getCharset();
			text = new String(bytes, charset);
		}
		return new Response(response.getStatusCodeValue(), text);
	}

	public static URI buildUri(String url, Map<String, String> queryParams) {
		if (queryParams == null || queryParams.isEmpty()) {
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

	public static String encode(String value) {
		try {
			return URLEncoder.encode(value == null ? "" : value, "UTF-8");
		} catch (java.io.UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	private static int toMillis(Duration duration, String what) {
		if (duration == null || duration.isNegative() || duration.isZero()) {
			throw new IllegalArgumentException(what + "必须大于 0");
		}
		if (duration.toMillis() > Integer.MAX_VALUE) {
			throw new IllegalArgumentException(what + "过长");
		}
		return (int) duration.toMillis();
	}

	public static class Response {

		private final int status;

		private final String body;

		Response(int status, String body) {
			this.status = status;
			this.body = body;
		}

		public int getStatus() {
			return status;
		}

		public String getBody() {
			return body;
		}
	}
}
