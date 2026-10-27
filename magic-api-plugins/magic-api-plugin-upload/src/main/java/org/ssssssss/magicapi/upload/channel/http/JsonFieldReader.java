package org.ssssssss.magicapi.upload.channel.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.function.Function;

/**
 * 按 "a.b.0.c" 路径读取 JSON 字段值；响应不是 JSON 或字段不存在时返回 null
 */
class JsonFieldReader implements Function<String, String> {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final JsonNode root;

	JsonFieldReader(String body) {
		JsonNode node = null;
		if (body != null && !body.trim().isEmpty()) {
			try {
				node = MAPPER.readTree(body);
			} catch (Exception ignored) {
				node = null;
			}
		}
		this.root = node;
	}

	@Override
	public String apply(String path) {
		if (root == null || path == null) {
			return null;
		}
		JsonNode node = root;
		for (String part : path.split("\\.")) {
			if (node == null) {
				return null;
			}
			if (node.isArray()) {
				try {
					node = node.get(Integer.parseInt(part));
				} catch (NumberFormatException e) {
					return null;
				}
			} else {
				node = node.get(part);
			}
		}
		if (node == null || node.isNull() || node.isMissingNode()) {
			return null;
		}
		return node.isValueNode() ? node.asText() : node.toString();
	}
}
