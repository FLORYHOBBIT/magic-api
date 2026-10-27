package org.ssssssss.magicapi.upload.channel;

import org.ssssssss.magicapi.upload.engine.SendResult;

import java.util.*;
import java.util.function.Function;

/**
 * 根据响应判断上传是否成功
 * <ul>
 *     <li>HTTP 2xx 且未配置业务字段：成功</li>
 *     <li>HTTP 2xx 且业务字段的值在成功值列表中：成功；否则为业务失败（默认不重试）</li>
 *     <li>HTTP 408、429、5xx：失败，可重试</li>
 *     <li>其他状态码（如 400、401、404）：拒绝，不重试</li>
 * </ul>
 */
public class ResponseRule {

	static final int MESSAGE_SNIPPET = 200;

	/**
	 * 业务结果字段路径，如 "code"、"result.code"、"data.0.status"；为空表示只看 HTTP 状态码
	 */
	private String successField;

	/**
	 * 业务字段表示成功的值，如 "0"、"200"、"true"
	 */
	private List<String> successValues = new ArrayList<>();

	/**
	 * 失败时取错误信息的字段路径，如 "msg"、"message"
	 */
	private String messageField;

	/**
	 * 业务失败是否重试。默认不重试：数据校验不通过之类的错误重试也不会成功。
	 */
	private boolean retryOnBusinessFailure;

	public String getSuccessField() {
		return successField;
	}

	public ResponseRule successField(String successField) {
		this.successField = successField == null || successField.trim().isEmpty() ? null : successField.trim();
		return this;
	}

	public List<String> getSuccessValues() {
		return successValues;
	}

	public ResponseRule successValues(Collection<?> values) {
		this.successValues = new ArrayList<>();
		if (values != null) {
			for (Object value : values) {
				if (value != null) {
					this.successValues.add(value.toString().trim());
				}
			}
		}
		return this;
	}

	public ResponseRule successValues(String... values) {
		return successValues(Arrays.asList(values));
	}

	public String getMessageField() {
		return messageField;
	}

	public ResponseRule messageField(String messageField) {
		this.messageField = messageField == null || messageField.trim().isEmpty() ? null : messageField.trim();
		return this;
	}

	public boolean isRetryOnBusinessFailure() {
		return retryOnBusinessFailure;
	}

	public ResponseRule retryOnBusinessFailure(boolean retryOnBusinessFailure) {
		this.retryOnBusinessFailure = retryOnBusinessFailure;
		return this;
	}

	/**
	 * @param status 状态码
	 * @param body   响应内容
	 * @param reader 按路径读取响应字段的函数，读不到返回 null
	 */
	public SendResult evaluate(int status, String body, Function<String, String> reader) {
		if (status >= 200 && status < 300) {
			if (successField == null) {
				return SendResult.success(body);
			}
			String value = reader.apply(successField);
			if (value != null && successValues.contains(value.trim())) {
				return SendResult.success(body);
			}
			String detail = messageField == null ? null : reader.apply(messageField);
			String message = "业务返回失败: " + successField + "=" + value + (detail == null ? "" : "，" + detail);
			if (value == null) {
				message = "响应中没有字段 " + successField + ": " + snippet(body);
			}
			return retryOnBusinessFailure ? SendResult.failure(message, body) : SendResult.rejected(message, body);
		}
		String message = "HTTP " + status + ": " + snippet(body);
		if (status == 408 || status == 429 || status >= 500) {
			return SendResult.failure(message, body);
		}
		return SendResult.rejected(message, body);
	}

	static String snippet(String body) {
		if (body == null) {
			return "";
		}
		String text = body.replaceAll("\\s+", " ").trim();
		return text.length() <= MESSAGE_SNIPPET ? text : text.substring(0, MESSAGE_SNIPPET) + "...";
	}
}
