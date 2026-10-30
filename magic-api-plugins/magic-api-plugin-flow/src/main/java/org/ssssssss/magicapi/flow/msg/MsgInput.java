package org.ssssssss.magicapi.flow.msg;

import org.ssssssss.magicapi.flow.support.FlowJson;
import org.ssssssss.magicapi.flow.support.XmlCodec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 接收时（取消息 id、分组键）和消费时（流程 input）使用同一份结构：
 * 参数与 JSON 报文的字段放在顶层，另有 payload、body、xml、params、headers、clientIp。
 */
final class MsgInput {

	private MsgInput() {
	}

	static Map<String, Object> build(String type, String payload, Map<String, Object> params, Map<String, Object> headers, String clientIp) {
		Map<String, Object> input = new LinkedHashMap<>();
		if (params != null) {
			input.putAll(params);
		}
		Object body = null;
		Object xml = null;
		if (payload != null) {
			String text = payload.trim();
			if (text.startsWith("{") || text.startsWith("[")) {
				body = FlowJson.tryParse(text);
			} else if (text.startsWith("<")) {
				try {
					xml = XmlCodec.parse(text);
				} catch (IllegalArgumentException ignored) {
					// 不是合法 XML 时只保留原文
				}
			}
		}
		if (body instanceof Map) {
			((Map<?, ?>) body).forEach((k, v) -> input.putIfAbsent(String.valueOf(k), v));
		}
		input.put("type", type);
		input.put("payload", payload);
		input.put("body", body);
		input.put("xml", xml);
		input.put("params", params == null ? new LinkedHashMap<>() : params);
		input.put("headers", headers == null ? new LinkedHashMap<>() : headers);
		input.put("clientIp", clientIp);
		return input;
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> fromMessage(FlowMessage message) {
		Map<String, Object> meta = new LinkedHashMap<>();
		Object parsed = message.getMeta() == null ? null : FlowJson.tryParse(message.getMeta());
		if (parsed instanceof Map) {
			meta = (Map<String, Object>) parsed;
		}
		Map<String, Object> input = build(message.getType(), message.getPayload(), (Map<String, Object>) meta.get("params"),
				(Map<String, Object>) meta.get("headers"), message.getClientIp());
		input.put("msgId", message.getMsgId());
		input.put("id", message.getId());
		input.put("attempt", message.getAttempts());
		input.put("receiveTime", message.getReceiveTime());
		return input;
	}
}
