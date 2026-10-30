package org.ssssssss.magicapi.flow.trigger;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletRequest;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletResponse;
import org.ssssssss.magicapi.flow.engine.FlowEngine;
import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.flow.engine.FlowResult;
import org.ssssssss.magicapi.flow.model.FlowDefinition;
import org.ssssssss.magicapi.flow.model.FlowInfo;
import org.ssssssss.magicapi.flow.service.FlowRunner;
import org.ssssssss.magicapi.flow.service.FlowTriggerRegistrar;
import org.ssssssss.magicapi.flow.support.FlowJson;
import org.ssssssss.magicapi.flow.support.FlowVariables;
import org.ssssssss.magicapi.flow.support.XmlCodec;
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
import java.util.function.Supplier;

/**
 * HTTP 触发：把起始节点为 http 的已启用流程注册为 Spring MVC 路由。
 */
public class HttpFlowTriggerRegistrar implements FlowTriggerRegistrar {

	private static final Logger logger = LoggerFactory.getLogger(HttpFlowTriggerRegistrar.class);

	private static final int MAX_BODY = 20 * 1024 * 1024;

	private final Supplier<RequestMappingHandlerMapping> mappingSupplier;

	private final Supplier<FlowRunner> runnerSupplier;

	private final String basePath;

	private final Map<String, String> routes = new ConcurrentHashMap<>();

	private final Method method;

	/**
	 * @param basePath magic-api.prefix + magic-api.flow.http-prefix
	 */
	public HttpFlowTriggerRegistrar(Supplier<RequestMappingHandlerMapping> mappingSupplier, Supplier<FlowRunner> runnerSupplier, String basePath) {
		this.mappingSupplier = mappingSupplier;
		this.runnerSupplier = runnerSupplier;
		this.basePath = StringUtils.defaultString(basePath);
		try {
			this.method = HttpFlowTriggerRegistrar.class.getDeclaredMethod("invoke", MagicHttpServletRequest.class, MagicHttpServletResponse.class, Map.class, Map.class, Map.class);
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException(e);
		}
	}

	public String url(String mappingKey) {
		return PathUtils.replaceSlash("/" + basePath + "/" + mappingKey);
	}

	@Override
	public Object register(FlowInfo info, String mappingKey) {
		FlowRunner runner = runnerSupplier.get();
		FlowDefinition definition = runner.definition(info);
		FlowDefinition.Node start = FlowEngine.startNode(definition);
		if (start == null || !TriggerNodeHandler.Http.TYPE.equals(start.getType())) {
			return null;
		}
		runner.getEngine().prepare(definition);
		String httpMethod = StringUtils.defaultIfBlank(Objects.toString(start.getConfig().get("method"), null), "POST").toUpperCase(Locale.ROOT);
		String path = url(mappingKey);
		Mapping mapping = Mapping.create(mappingSupplier.get());
		RequestMappingInfo requestMappingInfo = mapping.paths(path).methods(RequestMethod.valueOf(httpMethod)).build();
		if (mapping.getHandlerMethods().containsKey(requestMappingInfo)) {
			throw new InvalidArgumentException(org.ssssssss.magicapi.flow.FlowJsonCodes.FLOW_HTTP_CONFLICT.format(httpMethod + " " + path));
		}
		mapping.register(requestMappingInfo, this, method);
		String key = httpMethod + ":" + path;
		routes.put(key, info.getId());
		logger.info("注册集成流程 HTTP 接口：{} {} -> {}", httpMethod, path, info.getName());
		return new Handle(requestMappingInfo, key, httpMethod + " " + path);
	}

	@Override
	public void unregister(FlowInfo info, Object handle) {
		Handle h = (Handle) handle;
		Mapping.create(mappingSupplier.get()).unregister(h.info);
		routes.remove(h.key);
		logger.info("取消集成流程 HTTP 接口：{}", h.description);
	}

	@Override
	public String describe(Object handle) {
		return "HTTP " + ((Handle) handle).description;
	}

	/**
	 * HTTP 请求入口（由 Spring MVC 调用）
	 */
	public ResponseEntity<byte[]> invoke(MagicHttpServletRequest request, MagicHttpServletResponse response,
										 @PathVariable(required = false) Map<String, Object> pathVariables,
										 @RequestHeader(required = false) Map<String, Object> headers,
										 @RequestParam(required = false) Map<String, Object> parameters) throws IOException {
		String key = Objects.toString(request.getMethod(), "GET").toUpperCase(Locale.ROOT) + ":" + request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		FlowRunner runner = runnerSupplier.get();
		String id = routes.get(key);
		FlowInfo info = id == null ? null : runner.flow(id);
		if (info == null) {
			return error(404, "接口不存在", null);
		}
		FlowDefinition definition = runner.definition(info);
		FlowDefinition.Node start = FlowEngine.startNode(definition);
		FlowVariables variables = runner.getEngine().getVariables();
		FlowNodeContext config = new FlowNodeContext(start, null, variables);
		Map<String, Object> lowerHeaders = new LinkedHashMap<>();
		if (headers != null) {
			headers.forEach((k, v) -> lowerHeaders.put(k.toLowerCase(Locale.ROOT), v));
		}
		String denied = checkAccess(config, request, lowerHeaders);
		if (denied != null) {
			logger.warn("集成流程[{}]拒绝访问：{}，来源 {}", info.getName(), denied, request.getRemoteAddr());
			return error(denied.startsWith("IP") ? 403 : 401, denied, null);
		}
		Map<String, Object> input = new LinkedHashMap<>();
		if (parameters != null) {
			input.putAll(parameters);
		}
		if (pathVariables != null) {
			input.putAll(pathVariables);
		}
		Object body;
		try {
			body = readBody(request);
		} catch (IllegalArgumentException e) {
			return error(400, e.getMessage(), null);
		}
		if (body instanceof Map) {
			((Map<?, ?>) body).forEach((k, v) -> input.put(String.valueOf(k), v));
		}
		if (body != null) {
			input.put("body", body);
			if (body instanceof String && ((String) body).trim().startsWith("<")) {
				try {
					input.put("xml", XmlCodec.parse((String) body));
				} catch (IllegalArgumentException ignored) {
					// 不是合法 XML 时只保留原文
				}
			}
		}
		input.put("headers", lowerHeaders);
		input.put("clientIp", request.getRemoteAddr());
		FlowResult result;
		try {
			result = runner.run(info, "http", input);
		} catch (InvalidArgumentException e) {
			return error(500, e.getMessage(), null);
		}
		if (!result.isSuccess()) {
			return error(config.integer("errorStatus", 500), result.getMessage(), result.getErrorNode());
		}
		return ok(result.getValue(), config.string("contentType", "auto"));
	}

	private static String checkAccess(FlowNodeContext config, MagicHttpServletRequest request, Map<String, Object> headers) {
		String allowIps = config.string("allowIps");
		if (allowIps != null) {
			String ip = request.getRemoteAddr();
			boolean allowed = false;
			for (String rule : allowIps.split("[,，\\s]+")) {
				String r = rule.trim();
				if (r.isEmpty()) {
					continue;
				}
				if (r.endsWith("*") ? ip != null && ip.startsWith(r.substring(0, r.length() - 1)) : r.equals(ip)) {
					allowed = true;
					break;
				}
			}
			if (!allowed) {
				return "IP " + ip + " 不在允许范围内";
			}
		}
		if ("token".equals(config.oneOf("auth", "none", "none", "token"))) {
			String header = config.string("tokenHeader", "X-Token").toLowerCase(Locale.ROOT);
			Object actual = headers.get(header);
			String expected = config.requireString("token");
			if (actual == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.toString().getBytes(StandardCharsets.UTF_8))) {
				return "令牌不正确";
			}
		}
		return null;
	}

	static Object readBody(MagicHttpServletRequest request) throws IOException {
		String contentType = StringUtils.defaultString(request.getContentType()).toLowerCase(Locale.ROOT);
		if (contentType.startsWith("application/x-www-form-urlencoded") || contentType.startsWith("multipart/")) {
			return null;
		}
		byte[] bytes;
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
			bytes = out.toByteArray();
		}
		if (bytes.length == 0) {
			return null;
		}
		String text = new String(bytes, charset(contentType));
		if (contentType.contains("json") || (contentType.isEmpty() && (text.trim().startsWith("{") || text.trim().startsWith("[")))) {
			try {
				return FlowJson.parse(text);
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("请求体不是合法的 JSON：" + e.getMessage());
			}
		}
		return text;
	}

	private static Charset charset(String contentType) {
		int idx = contentType.indexOf("charset=");
		if (idx > -1) {
			String name = contentType.substring(idx + 8).split(";")[0].trim().replace("\"", "");
			try {
				return Charset.forName(name);
			} catch (Exception ignored) {
				// 不认识的编码按 UTF-8
			}
		}
		return StandardCharsets.UTF_8;
	}

	public static ResponseEntity<byte[]> ok(Object value, String contentType) {
		if (value == null) {
			return ResponseEntity.ok().build();
		}
		String type = contentType == null ? "auto" : contentType;
		String text;
		String mediaType;
		if (value instanceof CharSequence && !"json".equals(type)) {
			text = value.toString();
			if ("xml".equals(type) || ("auto".equals(type) && text.trim().startsWith("<"))) {
				mediaType = "application/xml;charset=UTF-8";
			} else if ("auto".equals(type) && FlowJson.tryParse(text) != null) {
				mediaType = "application/json;charset=UTF-8";
			} else {
				mediaType = "text/plain;charset=UTF-8";
			}
		} else if ("xml".equals(type)) {
			text = XmlCodec.build(value, null, null, true);
			mediaType = "application/xml;charset=UTF-8";
		} else {
			text = FlowJson.stringify(value);
			mediaType = "application/json;charset=UTF-8";
		}
		return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, mediaType).body(text.getBytes(StandardCharsets.UTF_8));
	}

	public static ResponseEntity<byte[]> error(int status, String message, String node) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", -1);
		body.put("message", message);
		if (node != null) {
			body.put("node", node);
		}
		return ResponseEntity.status(status).header(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8")
				.body(FlowJson.stringify(body).getBytes(StandardCharsets.UTF_8));
	}

	static final class Handle {

		final RequestMappingInfo info;

		final String key;

		final String description;

		Handle(RequestMappingInfo info, String key, String description) {
			this.info = info;
			this.key = key;
			this.description = description;
		}

		@Override
		public String toString() {
			return description;
		}
	}
}
