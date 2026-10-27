package org.ssssssss.magicapi.upload.channel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 报文转换的完整结果：请求体，以及本批需要额外加上的请求头、查询参数（例如按请求体计算的签名）
 * <p>
 * 报文转换只返回请求体时不需要用它。
 */
public class ChannelRequest {

	private final Object body;

	private final Map<String, String> headers = new LinkedHashMap<>();

	private final Map<String, String> params = new LinkedHashMap<>();

	public ChannelRequest(Object body, Map<?, ?> headers, Map<?, ?> params) {
		this.body = body;
		copy(headers, this.headers);
		copy(params, this.params);
	}

	private static void copy(Map<?, ?> from, Map<String, String> to) {
		if (from != null) {
			from.forEach((k, v) -> {
				if (k != null && v != null) {
					to.put(String.valueOf(k), String.valueOf(v));
				}
			});
		}
	}

	public Object getBody() {
		return body;
	}

	public Map<String, String> getHeaders() {
		return headers;
	}

	public Map<String, String> getParams() {
		return params;
	}

	/**
	 * 把报文转换结果拆成请求体，并把额外的请求头、查询参数合并进去
	 */
	public static Object unwrap(Object built, Map<String, String> headers, Map<String, String> params) {
		if (built instanceof ChannelRequest) {
			ChannelRequest request = (ChannelRequest) built;
			headers.putAll(request.getHeaders());
			params.putAll(request.getParams());
			return request.getBody();
		}
		return built;
	}
}
