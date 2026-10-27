package org.ssssssss.magicapi.upload.channel;

import org.ssssssss.magicapi.upload.engine.UploadBatch;

import java.util.Map;

/**
 * 签名时可读取和修改的请求信息
 */
public class SignContext {

	private final String method;

	private final String url;

	private final Map<String, String> headers;

	private final Map<String, String> queryParams;

	private final String body;

	private final UploadBatch batch;

	public SignContext(String method, String url, Map<String, String> headers, Map<String, String> queryParams, String body, UploadBatch batch) {
		this.method = method;
		this.url = url;
		this.headers = headers;
		this.queryParams = queryParams;
		this.body = body;
		this.batch = batch;
	}

	public String getMethod() {
		return method;
	}

	/**
	 * 不含查询参数的地址
	 */
	public String getUrl() {
		return url;
	}

	/**
	 * 可修改
	 */
	public Map<String, String> getHeaders() {
		return headers;
	}

	/**
	 * 可修改，签名后才拼接到地址上
	 */
	public Map<String, String> getQueryParams() {
		return queryParams;
	}

	/**
	 * 即将发送的请求体（已序列化）
	 */
	public String getBody() {
		return body;
	}

	public UploadBatch getBatch() {
		return batch;
	}
}
