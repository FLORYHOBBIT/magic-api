package org.ssssssss.magicapi.flow.engine.node;

import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.flow.engine.FlowNodeException;
import org.ssssssss.magicapi.flow.engine.FlowNodeHandler;
import org.ssssssss.magicapi.flow.support.FlowJson;
import org.ssssssss.magicapi.flow.support.FlowTemplate;
import org.ssssssss.magicapi.flow.support.FlowVariables;
import org.ssssssss.magicapi.flow.support.XmlCodec;
import org.ssssssss.magicapi.upload.channel.ChannelFactory;
import org.ssssssss.magicapi.upload.channel.ChannelRequest;
import org.ssssssss.magicapi.upload.engine.SendResult;
import org.ssssssss.magicapi.upload.engine.UploadBatch;
import org.ssssssss.magicapi.upload.engine.UploadChannel;
import org.ssssssss.magicapi.upload.resource.UploadChannelInfo;
import org.ssssssss.magicapi.upload.resource.UploadChannelMagicDynamicRegistry;
import org.ssssssss.magicapi.upload.resource.UploadChannelMagicResourceStorage;
import org.w3c.dom.Element;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 调用外部系统：通过「外部连接」（上传插件的上传通道，支持 HTTP / WebService / 文件 / FTP / SFTP）发送报文。
 * <p>
 * 地址、超时、固定请求头、签名器、成功判定（successField / successValues / messageField）在外部连接中配置，
 * 连接参数中的密码、密钥加密保存，也可以写差异项 ${var.xxx}。
 * <pre>
 * config:
 *   channel        外部连接名称
 *   body           请求体表达式，默认 prev；字符串原样发送（XML、已生成的 JSON），对象按 JSON / 表单发送
 *   headers        追加的请求头 {名称: 值}，值中可用 {{表达式}}，如 "Bearer {{login.data.token}}"
 *   params         追加的查询参数，写法同 headers
 *   forEach        可选，表达式，列表逐项发送，body 中可用 item、index（body 默认 item）
 *   retry          失败后重试次数（只重试网络错误、超时、5xx），默认 0
 *   retryInterval  首次重试等待毫秒数，之后翻倍，默认 1000
 *   cacheSeconds   结果缓存秒数，用于登录取令牌（同一流程同一节点、同一 cacheKey 在有效期内不再调用），默认 0
 *   cacheKey       缓存键，可用 {{表达式}}，默认按流程和节点
 * </pre>
 * 输出：{success, message, response（原始回执）, data（回执解析为对象：JSON 或 XML；WebService 为 Body 内的元素）, retryable}。
 * 单次调用失败时节点失败（可接失败连线，error.output 为输出）；forEach 时不失败，输出 {success, total, failed, results}。
 */
public class CallNodeHandler implements FlowNodeHandler {

	public static final String TYPE = "call";

	private final Supplier<UploadChannelMagicDynamicRegistry> registry;

	private final Supplier<UploadChannelMagicResourceStorage> storage;

	private final Supplier<ChannelFactory> factory;

	private final Supplier<FlowVariables> variables;

	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	public CallNodeHandler(Supplier<UploadChannelMagicDynamicRegistry> registry, Supplier<UploadChannelMagicResourceStorage> storage,
						   Supplier<ChannelFactory> factory, Supplier<FlowVariables> variables) {
		this.registry = registry;
		this.storage = storage;
		this.factory = factory;
		this.variables = variables;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void check(FlowNodeContext node) {
		node.requireString("channel");
		node.stringMap("headers");
		node.stringMap("params");
		if (node.integer("retry", 0) < 0) {
			throw node.invalid("retry", "不能小于 0");
		}
		if (node.integer("cacheSeconds", 0) < 0) {
			throw node.invalid("cacheSeconds", "不能小于 0");
		}
	}

	@Override
	public Object execute(FlowNodeContext node) throws Exception {
		UploadChannelMagicDynamicRegistry channels = registry.get();
		if (channels == null) {
			throw new IllegalStateException("没有启用上传插件，无法使用外部连接（magic-api.upload.enable）");
		}
		String name = node.requireString("channel");
		UploadChannelInfo info = channels.find(name);
		if (info == null) {
			throw node.invalid("channel", "找不到外部连接「" + name + "」，请先在「数据上传 → 上传通道」中创建并保存");
		}
		Map<String, Object> options = storage.get().decrypt(info.getOptions());
		FlowVariables vars = variables.get();
		if (vars != null) {
			options = vars.resolveDeep(options);
		}
		int cacheSeconds = node.integer("cacheSeconds", 0);
		String cacheKey = null;
		if (cacheSeconds > 0) {
			cacheKey = node.getExecution().getFlowName() + "#" + node.getNode().getId() + "#"
					+ FlowTemplate.interpolate(node.string("cacheKey", ""), node::evaluate);
			Cached cached = cache.get(cacheKey);
			if (cached != null && cached.expireAt > System.currentTimeMillis()) {
				Map<String, Object> output = new LinkedHashMap<>(cached.output);
				output.put("cached", true);
				return output;
			}
		}
		String forEach = node.string("forEach");
		if (forEach != null) {
			List<Object> items = node.asList("forEach", node.evaluate(forEach));
			List<Map<String, Object>> results = new ArrayList<>();
			int failed = 0;
			int index = 0;
			for (Object item : items) {
				Map<String, Object> extra = new HashMap<>();
				extra.put("item", item);
				extra.put("index", index);
				Map<String, Object> result = send(node, info, options, node.evaluate(node.string("body", "item"), extra), extra, index + 1);
				result.put("item", item);
				result.put("index", index);
				if (!Boolean.TRUE.equals(result.get("success"))) {
					failed++;
				}
				results.add(result);
				index++;
			}
			Map<String, Object> output = new LinkedHashMap<>();
			output.put("success", failed == 0);
			output.put("total", items.size());
			output.put("failed", failed);
			output.put("results", results);
			return output;
		}
		Map<String, Object> output = send(node, info, options, node.evaluate(node.string("body", "prev")), Collections.<String, Object>emptyMap(), 1);
		if (!Boolean.TRUE.equals(output.get("success"))) {
			throw new FlowNodeException("调用「" + name + "」失败：" + output.get("message"), output, Boolean.TRUE.equals(output.get("retryable")));
		}
		if (cacheKey != null) {
			cache.put(cacheKey, new Cached(output, System.currentTimeMillis() + cacheSeconds * 1000L));
		}
		return output;
	}

	private Map<String, Object> send(FlowNodeContext node, UploadChannelInfo info, Map<String, Object> options, Object body,
									 Map<String, Object> extra, int index) throws InterruptedException {
		Map<String, String> headers = new LinkedHashMap<>();
		node.stringMap("headers").forEach((k, v) -> headers.put(k, FlowTemplate.interpolate(v, (expression, scope) -> node.evaluate(expression, merge(extra, scope)))));
		Map<String, String> params = new LinkedHashMap<>();
		node.stringMap("params").forEach((k, v) -> params.put(k, FlowTemplate.interpolate(v, (expression, scope) -> node.evaluate(expression, merge(extra, scope)))));
		UploadChannel channel = factory.get().create(info.getProtocol(), options, batch -> new ChannelRequest(body, headers, params));
		UploadBatch batch = new UploadBatch(node.getExecution().getFlowName(), index, rows(body), null, null);
		int retry = node.integer("retry", 0);
		long interval = Math.max(0, node.integer("retryInterval", 1000));
		long start = System.currentTimeMillis();
		SendResult result = null;
		int attempt = 0;
		while (attempt <= retry) {
			attempt++;
			try {
				result = channel.send(batch);
			} catch (InterruptedException e) {
				throw e;
			} catch (Exception e) {
				String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
				result = e instanceof IllegalArgumentException ? SendResult.rejected(message, null) : SendResult.failure(message);
			}
			if (result.isSuccess() || !result.isRetryable() || attempt > retry) {
				break;
			}
			Thread.sleep(Math.min(interval * (1L << Math.min(attempt - 1, 10)), 60000L));
		}
		Map<String, Object> output = new LinkedHashMap<>();
		output.put("success", result.isSuccess());
		output.put("message", result.getMessage());
		output.put("request", body instanceof CharSequence ? body.toString() : FlowJson.stringify(body));
		output.put("response", result.getResponse());
		output.put("data", parse(info.getProtocol(), result.getResponse()));
		output.put("retryable", !result.isSuccess() && result.isRetryable());
		output.put("attempts", attempt);
		output.put("duration", System.currentTimeMillis() - start);
		return output;
	}

	/**
	 * 通过外部连接发送一次（不重试），供消息回调等非流程场景使用。输出同节点：{success, message, request, response, data, retryable, duration}
	 */
	public Map<String, Object> callChannel(String name, Object body, Map<String, String> headers, String label) {
		UploadChannelMagicDynamicRegistry channels = registry.get();
		if (channels == null) {
			throw new IllegalStateException("没有启用上传插件，无法使用外部连接（magic-api.upload.enable）");
		}
		UploadChannelInfo info = channels.find(name);
		if (info == null) {
			throw new IllegalArgumentException("找不到外部连接「" + name + "」，请先在「数据上传 → 上传通道」中创建并保存");
		}
		Map<String, Object> options = storage.get().decrypt(info.getOptions());
		FlowVariables vars = variables.get();
		if (vars != null) {
			options = vars.resolveDeep(options);
		}
		Map<String, String> requestHeaders = headers == null ? new LinkedHashMap<>() : headers;
		UploadChannel channel = factory.get().create(info.getProtocol(), options, batch -> new ChannelRequest(body, requestHeaders, new LinkedHashMap<>()));
		long start = System.currentTimeMillis();
		SendResult result;
		try {
			result = channel.send(new UploadBatch(label, 1, rows(body), null, null));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			result = SendResult.failure("已中断");
		} catch (Exception e) {
			String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			result = e instanceof IllegalArgumentException ? SendResult.rejected(message, null) : SendResult.failure(message);
		}
		Map<String, Object> output = new LinkedHashMap<>();
		output.put("success", result.isSuccess());
		output.put("message", result.getMessage());
		output.put("request", body instanceof CharSequence ? body.toString() : FlowJson.stringify(body));
		output.put("response", result.getResponse());
		output.put("data", parse(info.getProtocol(), result.getResponse()));
		output.put("retryable", !result.isSuccess() && result.isRetryable());
		output.put("duration", System.currentTimeMillis() - start);
		return output;
	}

	private static Map<String, Object> merge(Map<String, Object> extra, Map<String, Object> scope) {
		if (scope == null || scope.isEmpty()) {
			return extra;
		}
		Map<String, Object> merged = new HashMap<>(extra);
		merged.putAll(scope);
		return merged;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> rows(Object body) {
		if (body instanceof List) {
			List<Map<String, Object>> rows = new ArrayList<>();
			for (Object item : (List<?>) body) {
				if (item instanceof Map) {
					rows.add((Map<String, Object>) item);
				}
			}
			return rows;
		}
		if (body instanceof Map) {
			return Collections.singletonList((Map<String, Object>) body);
		}
		return Collections.emptyList();
	}

	static Object parse(String protocol, String response) {
		if (response == null || response.trim().isEmpty()) {
			return null;
		}
		Object json = FlowJson.tryParse(response);
		if (json != null) {
			return json;
		}
		String text = response.trim();
		if (text.startsWith("<")) {
			try {
				if (ChannelFactory.SOAP.equals(protocol)) {
					Element envelope = XmlCodec.document(text).getDocumentElement();
					Element body = XmlCodec.findChild(envelope, "Body");
					Element first = body == null ? null : XmlCodec.firstChildElement(body);
					if (first != null) {
						Map<String, Object> data = new LinkedHashMap<>();
						data.put(XmlCodec.localName(first), XmlCodec.toValue(first));
						return data;
					}
				}
				return XmlCodec.parse(text);
			} catch (IllegalArgumentException ignored) {
				return null;
			}
		}
		return null;
	}

	private static final class Cached {

		final Map<String, Object> output;

		final long expireAt;

		Cached(Map<String, Object> output, long expireAt) {
			this.output = output;
			this.expireAt = expireAt;
		}
	}
}
