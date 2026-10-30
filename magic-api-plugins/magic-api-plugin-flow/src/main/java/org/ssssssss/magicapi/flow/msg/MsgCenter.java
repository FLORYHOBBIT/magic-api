package org.ssssssss.magicapi.flow.msg;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletRequest;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletResponse;
import org.ssssssss.magicapi.flow.FlowJsonCodes;
import org.ssssssss.magicapi.flow.engine.FlowEngine;
import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.flow.model.FlowDefinition;
import org.ssssssss.magicapi.flow.model.FlowInfo;
import org.ssssssss.magicapi.flow.service.FlowRunner;
import org.ssssssss.magicapi.flow.service.FlowTriggerRegistrar;
import org.ssssssss.magicapi.flow.support.FlowJson;
import org.ssssssss.magicapi.flow.support.FlowScripts;
import org.ssssssss.magicapi.flow.support.FlowTemplate;
import org.ssssssss.magicapi.flow.support.XmlCodec;
import org.ssssssss.magicapi.flow.trigger.HttpFlowTriggerRegistrar;
import org.ssssssss.magicapi.flow.trigger.SoapFlowTriggerRegistrar;
import org.ssssssss.magicapi.utils.Mapping;
import org.ssssssss.magicapi.utils.PathUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 消息中心的类型注册与接收入口。
 * <ul>
 *     <li>起始节点为「消息」的已启用流程登记为该消息类型的消费者，一种类型只能有一个流程</li>
 *     <li>HTTP：POST 前缀/类型，或 POST 前缀（类型在参数、报文的 type 字段或请求头 X-Msg-Type）</li>
 *     <li>WebService：通用服务 sendMessage(type, msgId, payload)；或按触发节点配置的自定义服务、方法</li>
 * </ul>
 * 收到后只做鉴权、取消息 id、去重入库，立即回执；处理由 {@link MsgDispatcher} 异步完成。
 */
public class MsgCenter implements FlowTriggerRegistrar {

	private static final Logger logger = LoggerFactory.getLogger(MsgCenter.class);

	private static final int MAX_BODY = 20 * 1024 * 1024;

	private static final Set<String> SECRET_HEADERS = new HashSet<>(Arrays.asList("authorization", "cookie", "proxy-authorization"));

	private final Supplier<RequestMappingHandlerMapping> mappingSupplier;

	private final Supplier<FlowRunner> runnerSupplier;

	private final SoapFlowTriggerRegistrar soap;

	private final JdbcMsgStore store;

	private final String httpPath;

	private final String soapService;

	/**
	 * 类型 → 流程 id
	 */
	private final Map<String, String> bindings = new ConcurrentHashMap<>();

	/**
	 * 自定义 WebService 方法（服务#方法）→ 使用它的类型
	 */
	private final Map<String, SoapEntry> soapEntries = new LinkedHashMap<>();

	private final Method httpMethod;

	private volatile boolean routesRegistered;

	private volatile Consumer<String> receivedListener = type -> {
	};

	/**
	 * @param httpPath magic-api.prefix + magic-api.flow.msg.http-path
	 */
	public MsgCenter(Supplier<RequestMappingHandlerMapping> mappingSupplier, Supplier<FlowRunner> runnerSupplier,
					 SoapFlowTriggerRegistrar soap, JdbcMsgStore store, String httpPath, String soapService) {
		this.mappingSupplier = mappingSupplier;
		this.runnerSupplier = runnerSupplier;
		this.soap = soap;
		this.store = store;
		this.httpPath = PathUtils.replaceSlash("/" + StringUtils.defaultString(httpPath));
		this.soapService = StringUtils.defaultIfBlank(soapService, "msg");
		try {
			this.httpMethod = MsgCenter.class.getDeclaredMethod("receiveHttp", MagicHttpServletRequest.class, MagicHttpServletResponse.class, Map.class, Map.class, Map.class);
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException(e);
		}
	}

	public JdbcMsgStore getStore() {
		return store;
	}

	/**
	 * 入库后通知（消费调度据此立即处理）
	 */
	public void onReceived(Consumer<String> listener) {
		this.receivedListener = listener == null ? type -> {
		} : listener;
	}

	public String httpUrl(String type) {
		return PathUtils.replaceSlash(httpPath + "/" + type);
	}

	// ------------------------------------------------------------------ 类型注册

	@Override
	public synchronized Object register(FlowInfo info, String mappingKey) {
		FlowRunner runner = runnerSupplier.get();
		FlowDefinition definition = runner.definition(info);
		FlowDefinition.Node start = FlowEngine.startNode(definition);
		if (start == null || !MsgTriggerNodeHandler.TYPE.equals(start.getType())) {
			return null;
		}
		runner.getEngine().prepare(definition);
		MsgTypeConfig config = resolve(start);
		String existing = bindings.get(config.type);
		if (existing != null && !existing.equals(info.getId())) {
			FlowInfo other = runner.flow(existing);
			throw new InvalidArgumentException(FlowJsonCodes.FLOW_MSG_CONFLICT.format(config.type, other == null ? existing : other.getName()));
		}
		ensureRoutes();
		String soapKey = null;
		if (MsgTypeConfig.RECEIVE_SOAP.equals(config.receive)) {
			soapKey = addSoapEntry(config);
		}
		bindings.put(config.type, info.getId());
		logger.info("消息中心：类型 {} -> 流程 {}", config.type, info.getName());
		return new Handle(config.type, soapKey, describe(config));
	}

	@Override
	public synchronized void unregister(FlowInfo info, Object handle) {
		Handle h = (Handle) handle;
		bindings.remove(h.type);
		if (h.soapKey != null) {
			SoapEntry entry = soapEntries.get(h.soapKey);
			if (entry != null) {
				entry.types.remove(h.type);
				if (entry.types.isEmpty()) {
					soap.removeOperation(entry.handle);
					soapEntries.remove(h.soapKey);
				}
			}
		}
		logger.info("消息中心：类型 {} 停止接收（已入库的消息保留，重新启用后继续处理）", h.type);
	}

	@Override
	public String describe(Object handle) {
		return ((Handle) handle).description;
	}

	private String describe(MsgTypeConfig config) {
		String ws = soap.path(soapService);
		if (MsgTypeConfig.RECEIVE_SOAP.equals(config.receive)) {
			return "消息 " + config.type + "：WebService " + soap.path(config.service) + " 方法 " + config.operation + "（WSDL：" + soap.path(config.service) + "?wsdl）";
		}
		return "消息 " + config.type + "：HTTP POST " + httpUrl(config.type) + "；WebService " + ws + " 方法 sendMessage（WSDL：" + ws + "?wsdl）";
	}

	/**
	 * 已登记的类型 → 流程 id
	 */
	public Map<String, String> bindings() {
		return new TreeMap<>(bindings);
	}

	/**
	 * 某类型当前的配置（按最新保存的流程与差异项）；类型未登记或流程已删除时返回 null
	 */
	public MsgTypeConfig config(String type) {
		String flowId = type == null ? null : bindings.get(type);
		FlowInfo info = flowId == null ? null : runnerSupplier.get().flow(flowId);
		if (info == null) {
			return null;
		}
		FlowDefinition.Node start = FlowEngine.startNode(runnerSupplier.get().definition(info));
		if (start == null || !MsgTriggerNodeHandler.TYPE.equals(start.getType())) {
			return null;
		}
		return resolve(start);
	}

	public FlowInfo flow(String type) {
		String flowId = type == null ? null : bindings.get(type);
		return flowId == null ? null : runnerSupplier.get().flow(flowId);
	}

	private MsgTypeConfig resolve(FlowDefinition.Node start) {
		return MsgTypeConfig.from(new FlowNodeContext(start, null, runnerSupplier.get().getEngine().getVariables()));
	}

	private void ensureRoutes() {
		if (routesRegistered) {
			return;
		}
		Mapping mapping = Mapping.create(mappingSupplier.get());
		for (String path : Arrays.asList(httpPath, httpPath + "/{type}")) {
			RequestMappingInfo info = mapping.paths(path).methods(RequestMethod.POST).build();
			if (mapping.getHandlerMethods().containsKey(info)) {
				throw new InvalidArgumentException(FlowJsonCodes.FLOW_HTTP_CONFLICT.format("POST " + path));
			}
			mapping.register(info, this, httpMethod);
		}
		soap.addOperation(soapService, "sendMessage", null, Arrays.asList("type", "msgId", "payload"), "return", "消息中心",
				input -> receiveSoap(stringValue(input.get("type")), null, input, stringValue(input.get("payload"))));
		routesRegistered = true;
		logger.info("消息中心接收地址：HTTP POST {}/类型；WebService {}?wsdl", httpPath, soap.path(soapService));
	}

	private String addSoapEntry(MsgTypeConfig config) {
		String key = config.service + "#" + config.operation;
		SoapEntry entry = soapEntries.get(key);
		if (entry != null) {
			// 同一方法供多个类型使用时，每个类型都必须配置 typeFrom
			if (entry.typeFrom == null || config.typeFrom == null) {
				throw new InvalidArgumentException(FlowJsonCodes.FLOW_SOAP_CONFLICT.format(config.service, config.operation,
						"消息类型 " + String.join("、", entry.types) + "（多个类型共用一个方法时都要填写「区分类型」）"));
			}
			entry.types.add(config.type);
			return key;
		}
		SoapEntry created = new SoapEntry();
		created.typeFrom = config.typeFrom;
		created.types.add(config.type);
		String payloadParam = config.params.get(config.params.size() - 1);
		created.handle = soap.addOperation(config.service, config.operation, config.namespace, config.params, config.resultName,
				"消息类型 " + config.type, input -> {
					String type;
					if (created.typeFrom == null) {
						type = created.types.iterator().next();
					} else {
						Object value = evaluate(created.typeFrom, input, "消息/" + config.operation + "/区分类型");
						type = value == null ? null : value.toString().trim();
						if (type == null || !created.types.contains(type)) {
							throw new SoapFlowTriggerRegistrar.Rejected("未知的消息类型：" + type + "（可用：" + String.join("、", created.types) + "）", true);
						}
					}
					return receiveSoap(type, created, input, stringValue(input.get(payloadParam)));
				});
		soapEntries.put(key, created);
		return key;
	}

	// ------------------------------------------------------------------ 接收

	/**
	 * HTTP 入口（由 Spring MVC 调用）
	 */
	public ResponseEntity<byte[]> receiveHttp(MagicHttpServletRequest request, MagicHttpServletResponse response,
											  @PathVariable(required = false) Map<String, Object> pathVariables,
											  @RequestHeader(required = false) Map<String, Object> headers,
											  @RequestParam(required = false) Map<String, Object> parameters) throws IOException {
		Map<String, Object> lowerHeaders = lower(headers);
		String payload;
		try {
			payload = readBody(request);
		} catch (IllegalArgumentException e) {
			return HttpFlowTriggerRegistrar.error(400, e.getMessage(), null);
		}
		Map<String, Object> params = parameters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters);
		String type = pathVariables == null ? null : stringValue(pathVariables.get("type"));
		if (type == null) {
			type = stringValue(params.get("type"));
		}
		if (type == null && payload != null && payload.trim().startsWith("{")) {
			Object body = FlowJson.tryParse(payload);
			type = body instanceof Map ? stringValue(((Map<?, ?>) body).get("type")) : null;
		}
		if (type == null) {
			type = stringValue(lowerHeaders.get("x-msg-type"));
		}
		MsgTypeConfig config = config(type);
		if (config == null) {
			return HttpFlowTriggerRegistrar.error(404, type == null ? "没有指定消息类型" : "未知的消息类型：" + type, null);
		}
		try {
			Accepted accepted = accept(config, payload, params, lowerHeaders, request.getRemoteAddr());
			String format = "auto".equals(config.replyFormat) ? FlowTemplate.JSON : config.replyFormat;
			// auto：按内容识别 JSON / XML（传 json 会把文本再序列化一次）
			return HttpFlowTriggerRegistrar.ok(reply(config, accepted, format), FlowTemplate.TEXT.equals(format) ? "text" : "auto");
		} catch (Rejected e) {
			return HttpFlowTriggerRegistrar.error(e.status, e.getMessage(), null);
		}
	}

	@SuppressWarnings("unchecked")
	private String receiveSoap(String type, SoapEntry entry, Map<String, Object> soapInput, String payload) {
		MsgTypeConfig config = config(type);
		if (config == null) {
			throw new SoapFlowTriggerRegistrar.Rejected(type == null ? "没有指定消息类型（参数 type）" : "未知的消息类型：" + type, true);
		}
		Map<String, Object> params = new LinkedHashMap<>(soapInput);
		Object headers = params.remove("headers");
		String clientIp = stringValue(params.remove("clientIp"));
		params.remove("operation");
		try {
			Accepted accepted = accept(config, payload, params, lower(headers instanceof Map ? (Map<String, Object>) headers : null), clientIp);
			String format = "auto".equals(config.replyFormat) ? FlowTemplate.XML : config.replyFormat;
			return reply(config, accepted, format);
		} catch (Rejected e) {
			throw new SoapFlowTriggerRegistrar.Rejected(e.getMessage(), e.status < 500);
		}
	}

	/**
	 * 鉴权、取消息 id 与分组键、入库
	 */
	Accepted accept(MsgTypeConfig config, String payload, Map<String, Object> params, Map<String, Object> headers, String clientIp) {
		String denied = checkAccess(config, headers, clientIp);
		if (denied != null) {
			logger.warn("消息中心拒绝类型 {} 的消息：{}，来源 {}", config.type, denied, clientIp);
			throw new Rejected(denied.startsWith("IP") ? 403 : 401, denied);
		}
		Map<String, Object> stored = new LinkedHashMap<>(headers);
		stored.remove(config.tokenHeader.toLowerCase(Locale.ROOT));
		SECRET_HEADERS.forEach(stored::remove);
		Map<String, Object> input = MsgInput.build(config.type, payload, params, stored, clientIp);
		String msgId;
		try {
			msgId = stringValue(evaluate(config.msgIdExpression, input, "消息/" + config.type + "/消息id"));
		} catch (RuntimeException e) {
			throw new Rejected(400, "取消息 id 失败（" + config.msgIdExpression + "）：" + FlowEngine.message(e));
		}
		if (msgId == null) {
			throw new Rejected(400, "缺少消息 id（取法：" + config.msgIdExpression + "）");
		}
		if (msgId.length() > 128) {
			throw new Rejected(400, "消息 id 超过 128 位");
		}
		String orderKey = null;
		if (MsgTypeConfig.ORDER_TYPE.equals(config.order)) {
			orderKey = JdbcMsgStore.WHOLE_TYPE;
		} else if (MsgTypeConfig.ORDER_KEY.equals(config.order)) {
			try {
				orderKey = StringUtils.defaultIfBlank(stringValue(evaluate(config.orderKeyExpression, input, "消息/" + config.type + "/分组键")), JdbcMsgStore.WHOLE_TYPE);
			} catch (RuntimeException e) {
				throw new Rejected(400, "取分组键失败（" + config.orderKeyExpression + "）：" + FlowEngine.message(e));
			}
			orderKey = JdbcMsgStore.truncate(orderKey, 128);
		}
		FlowMessage message = new FlowMessage();
		message.setType(config.type);
		message.setMsgId(msgId);
		message.setOrderKey(orderKey);
		message.setPayload(payload);
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("params", params);
		meta.put("headers", stored);
		message.setMeta(FlowJson.stringify(meta));
		message.setClientIp(clientIp);
		JdbcMsgStore.Received received;
		try {
			received = store.receive(message);
		} catch (RuntimeException e) {
			logger.error("消息中心入库失败：类型 {}，消息 id {}", config.type, msgId, e);
			throw new Rejected(500, "消息入库失败：" + FlowEngine.message(e));
		}
		if (!received.isDuplicate()) {
			try {
				receivedListener.accept(config.type);
			} catch (RuntimeException e) {
				logger.warn("消息中心唤醒消费失败（不影响接收）", e);
			}
		}
		return new Accepted(received.getMessage(), received.isDuplicate());
	}

	private static String reply(MsgTypeConfig config, Accepted accepted, String format) {
		Map<String, Object> vars = new LinkedHashMap<>();
		vars.put("msgId", accepted.message.getMsgId());
		vars.put("type", accepted.message.getType());
		vars.put("id", accepted.message.getId());
		vars.put("duplicate", accepted.duplicate);
		if (config.reply != null) {
			return FlowTemplate.render(config.reply, format, (expression, scope) -> {
				Map<String, Object> merged = new LinkedHashMap<>(vars);
				if (scope != null) {
					merged.putAll(scope);
				}
				return FlowScripts.evaluate(expression, merged, "消息/" + config.type + "/回执");
			});
		}
		String message = accepted.duplicate ? "重复消息，已接收" : "已接收";
		if (FlowTemplate.XML.equals(format)) {
			return "<response><code>0</code><message>" + message + "</message><msgId>" + XmlCodec.escape(accepted.message.getMsgId())
					+ "</msgId><duplicate>" + accepted.duplicate + "</duplicate></response>";
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", 0);
		body.put("message", message);
		body.put("msgId", accepted.message.getMsgId());
		body.put("duplicate", accepted.duplicate);
		return FlowJson.stringify(body);
	}

	private static String checkAccess(MsgTypeConfig config, Map<String, Object> headers, String ip) {
		if (config.allowIps != null) {
			boolean allowed = false;
			for (String rule : config.allowIps.split("[,，\\s]+")) {
				String r = rule.trim();
				if (!r.isEmpty() && (r.endsWith("*") ? ip != null && ip.startsWith(r.substring(0, r.length() - 1)) : r.equals(ip))) {
					allowed = true;
					break;
				}
			}
			if (!allowed) {
				return "IP " + ip + " 不在允许范围内";
			}
		}
		if (config.token != null) {
			Object actual = headers.get(config.tokenHeader.toLowerCase(Locale.ROOT));
			if (actual == null || !MessageDigest.isEqual(config.token.getBytes(StandardCharsets.UTF_8), actual.toString().getBytes(StandardCharsets.UTF_8))) {
				return "令牌不正确";
			}
		}
		return null;
	}

	static Object evaluate(String expression, Map<String, Object> input, String name) {
		Map<String, Object> vars = new HashMap<>();
		vars.put("input", input);
		return FlowScripts.evaluate(expression, vars, name);
	}

	private static Map<String, Object> lower(Map<String, Object> headers) {
		Map<String, Object> result = new LinkedHashMap<>();
		if (headers != null) {
			headers.forEach((k, v) -> result.put(k.toLowerCase(Locale.ROOT), v));
		}
		return result;
	}

	private static String stringValue(Object value) {
		return value == null ? null : StringUtils.trimToNull(value.toString());
	}

	private static String readBody(MagicHttpServletRequest request) throws IOException {
		String contentType = StringUtils.defaultString(request.getContentType()).toLowerCase(Locale.ROOT);
		if (contentType.startsWith("application/x-www-form-urlencoded") || contentType.startsWith("multipart/")) {
			return null;
		}
		try (InputStream in = request.getInputStream()) {
			if (in == null) {
				return null;
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int len;
			while ((len = in.read(buffer)) != -1) {
				out.write(buffer, 0, len);
				if (out.size() > MAX_BODY) {
					throw new IllegalArgumentException("请求体超过 20MB");
				}
			}
			if (out.size() == 0) {
				return null;
			}
			Charset charset = StandardCharsets.UTF_8;
			int idx = contentType.indexOf("charset=");
			if (idx > -1) {
				try {
					charset = Charset.forName(contentType.substring(idx + 8).split(";")[0].trim().replace("\"", ""));
				} catch (Exception ignored) {
					// 不认识的编码按 UTF-8
				}
			}
			return new String(out.toByteArray(), charset);
		}
	}

	static final class Accepted {

		final FlowMessage message;

		final boolean duplicate;

		Accepted(FlowMessage message, boolean duplicate) {
			this.message = message;
			this.duplicate = duplicate;
		}
	}

	static final class Rejected extends RuntimeException {

		final int status;

		Rejected(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	private static final class SoapEntry {

		Object handle;

		String typeFrom;

		final Set<String> types = new LinkedHashSet<>();
	}

	static final class Handle {

		final String type;

		final String soapKey;

		final String description;

		Handle(String type, String soapKey, String description) {
			this.type = type;
			this.soapKey = soapKey;
			this.description = description;
		}

		@Override
		public String toString() {
			return description;
		}
	}
}
