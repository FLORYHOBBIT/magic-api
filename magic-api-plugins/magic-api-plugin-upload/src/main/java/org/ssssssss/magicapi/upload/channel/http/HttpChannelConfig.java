package org.ssssssss.magicapi.upload.channel.http;

import org.ssssssss.magicapi.upload.channel.BodyBuilder;
import org.ssssssss.magicapi.upload.channel.ResponseRule;
import org.ssssssss.magicapi.upload.channel.UploadSigner;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP 上传通道配置
 */
public class HttpChannelConfig {

	public static final String CONTENT_JSON = "json";

	public static final String CONTENT_FORM = "form";

	private static final List<String> METHODS = Arrays.asList("POST", "PUT", "PATCH");

	private final String url;

	private String method = "POST";

	private final Map<String, String> headers = new LinkedHashMap<>();

	private final Map<String, String> queryParams = new LinkedHashMap<>();

	private String contentType = CONTENT_JSON;

	private Duration connectTimeout = Duration.ofSeconds(10);

	private Duration readTimeout = Duration.ofSeconds(60);

	private ResponseRule responseRule = new ResponseRule();

	private BodyBuilder bodyBuilder = BodyBuilder.ROWS;

	private UploadSigner signer;

	public HttpChannelConfig(String url) {
		if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
			throw new IllegalArgumentException("上传地址必须以 http:// 或 https:// 开头: " + url);
		}
		this.url = url.trim();
	}

	public String getUrl() {
		return url;
	}

	public String getMethod() {
		return method;
	}

	public HttpChannelConfig method(String method) {
		String value = method == null ? "POST" : method.trim().toUpperCase(Locale.ROOT);
		if (!METHODS.contains(value)) {
			throw new IllegalArgumentException("上传请求方法只支持 POST、PUT、PATCH: " + method);
		}
		this.method = value;
		return this;
	}

	public Map<String, String> getHeaders() {
		return headers;
	}

	public HttpChannelConfig header(String name, Object value) {
		if (name != null && value != null) {
			headers.put(name, value.toString());
		}
		return this;
	}

	public HttpChannelConfig headers(Map<String, ?> values) {
		if (values != null) {
			values.forEach(this::header);
		}
		return this;
	}

	public Map<String, String> getQueryParams() {
		return queryParams;
	}

	public HttpChannelConfig param(String name, Object value) {
		if (name != null && value != null) {
			queryParams.put(name, value.toString());
		}
		return this;
	}

	public HttpChannelConfig params(Map<String, ?> values) {
		if (values != null) {
			values.forEach(this::param);
		}
		return this;
	}

	public String getContentType() {
		return contentType;
	}

	/**
	 * json（默认）或 form
	 */
	public HttpChannelConfig contentType(String contentType) {
		String value = contentType == null ? CONTENT_JSON : contentType.trim().toLowerCase(Locale.ROOT);
		if (!CONTENT_JSON.equals(value) && !CONTENT_FORM.equals(value)) {
			throw new IllegalArgumentException("内容类型只支持 json、form: " + contentType);
		}
		this.contentType = value;
		return this;
	}

	public Duration getConnectTimeout() {
		return connectTimeout;
	}

	public HttpChannelConfig connectTimeout(Duration connectTimeout) {
		this.connectTimeout = requirePositive(connectTimeout, "连接超时");
		return this;
	}

	public Duration getReadTimeout() {
		return readTimeout;
	}

	public HttpChannelConfig readTimeout(Duration readTimeout) {
		this.readTimeout = requirePositive(readTimeout, "读取超时");
		return this;
	}

	public ResponseRule getResponseRule() {
		return responseRule;
	}

	public HttpChannelConfig responseRule(ResponseRule responseRule) {
		this.responseRule = responseRule == null ? new ResponseRule() : responseRule;
		return this;
	}

	public BodyBuilder getBodyBuilder() {
		return bodyBuilder;
	}

	public HttpChannelConfig bodyBuilder(BodyBuilder bodyBuilder) {
		this.bodyBuilder = bodyBuilder == null ? BodyBuilder.ROWS : bodyBuilder;
		return this;
	}

	public UploadSigner getSigner() {
		return signer;
	}

	public HttpChannelConfig signer(UploadSigner signer) {
		this.signer = signer;
		return this;
	}

	private static Duration requirePositive(Duration duration, String what) {
		if (duration == null || duration.isNegative() || duration.isZero()) {
			throw new IllegalArgumentException(what + "必须大于 0");
		}
		if (duration.toMillis() > Integer.MAX_VALUE) {
			throw new IllegalArgumentException(what + "过长");
		}
		return duration;
	}
}
