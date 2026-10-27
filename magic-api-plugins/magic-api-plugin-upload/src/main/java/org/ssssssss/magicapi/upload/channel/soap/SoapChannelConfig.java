package org.ssssssss.magicapi.upload.channel.soap;

import org.ssssssss.magicapi.upload.channel.BodyBuilder;
import org.ssssssss.magicapi.upload.channel.ResponseRule;
import org.ssssssss.magicapi.upload.channel.UploadSigner;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebService（SOAP）上传通道配置
 */
public class SoapChannelConfig {

	public static final String VERSION_11 = "1.1";

	public static final String VERSION_12 = "1.2";

	private final String url;

	private String version = VERSION_11;

	private String soapAction = "";

	private final Map<String, String> headers = new LinkedHashMap<>();

	private Duration connectTimeout = Duration.ofSeconds(10);

	private Duration readTimeout = Duration.ofSeconds(60);

	private ResponseRule responseRule = new ResponseRule();

	private BodyBuilder bodyBuilder;

	private UploadSigner signer;

	public SoapChannelConfig(String url) {
		if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
			throw new IllegalArgumentException("WebService 地址必须以 http:// 或 https:// 开头: " + url);
		}
		this.url = url.trim();
	}

	public String getUrl() {
		return url;
	}

	public String getVersion() {
		return version;
	}

	public SoapChannelConfig version(String version) {
		String value = version == null ? VERSION_11 : version.trim();
		if (!VERSION_11.equals(value) && !VERSION_12.equals(value)) {
			throw new IllegalArgumentException("SOAP 版本只支持 1.1、1.2: " + version);
		}
		this.version = value;
		return this;
	}

	public String getSoapAction() {
		return soapAction;
	}

	public SoapChannelConfig soapAction(String soapAction) {
		this.soapAction = soapAction == null ? "" : soapAction.trim();
		return this;
	}

	public Map<String, String> getHeaders() {
		return headers;
	}

	public SoapChannelConfig header(String name, Object value) {
		if (name != null && value != null) {
			headers.put(name, value.toString());
		}
		return this;
	}

	public SoapChannelConfig headers(Map<String, ?> values) {
		if (values != null) {
			values.forEach(this::header);
		}
		return this;
	}

	public Duration getConnectTimeout() {
		return connectTimeout;
	}

	public SoapChannelConfig connectTimeout(Duration connectTimeout) {
		this.connectTimeout = connectTimeout;
		return this;
	}

	public Duration getReadTimeout() {
		return readTimeout;
	}

	public SoapChannelConfig readTimeout(Duration readTimeout) {
		this.readTimeout = readTimeout;
		return this;
	}

	public ResponseRule getResponseRule() {
		return responseRule;
	}

	public SoapChannelConfig responseRule(ResponseRule responseRule) {
		this.responseRule = responseRule == null ? new ResponseRule() : responseRule;
		return this;
	}

	public BodyBuilder getBodyBuilder() {
		return bodyBuilder;
	}

	/**
	 * 必须返回 XML 字符串：完整的 SOAP Envelope，或只是 Body 里的内容（自动包装）
	 */
	public SoapChannelConfig bodyBuilder(BodyBuilder bodyBuilder) {
		this.bodyBuilder = bodyBuilder;
		return this;
	}

	public UploadSigner getSigner() {
		return signer;
	}

	public SoapChannelConfig signer(UploadSigner signer) {
		this.signer = signer;
		return this;
	}
}
