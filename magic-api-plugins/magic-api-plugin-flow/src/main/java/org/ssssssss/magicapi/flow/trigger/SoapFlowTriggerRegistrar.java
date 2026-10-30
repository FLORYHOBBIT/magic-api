package org.ssssssss.magicapi.flow.trigger;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletRequest;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletResponse;
import org.ssssssss.magicapi.flow.FlowJsonCodes;
import org.ssssssss.magicapi.flow.engine.FlowEngine;
import org.ssssssss.magicapi.flow.engine.FlowResult;
import org.ssssssss.magicapi.flow.model.FlowDefinition;
import org.ssssssss.magicapi.flow.model.FlowInfo;
import org.ssssssss.magicapi.flow.service.FlowRunner;
import org.ssssssss.magicapi.flow.service.FlowTriggerRegistrar;
import org.ssssssss.magicapi.flow.support.XmlCodec;
import org.ssssssss.magicapi.utils.Mapping;
import org.ssssssss.magicapi.utils.PathUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/**
 * WebService 发布：起始节点为 soap 的流程，按 service 合并为一个 SOAP 服务（document/literal），
 * 同时支持 SOAP 1.1 与 1.2，GET ?wsdl 返回自动生成的 WSDL。
 * <p>
 * 请求：Body 中第一个元素为方法名，其子元素为参数（字符串；参数内是未转义的 XML 时取原文）。
 * 响应：&lt;方法名Response&gt;&lt;return&gt;返回值&lt;/return&gt;&lt;/方法名Response&gt;，返回值为对象时先转为 XML 文本。
 * 流程失败返回 SOAP Fault（Server/Receiver），请求不合法返回 Fault（Client/Sender）。
 */
public class SoapFlowTriggerRegistrar implements FlowTriggerRegistrar {

	private static final Logger logger = LoggerFactory.getLogger(SoapFlowTriggerRegistrar.class);

	static final String NS_11 = "http://schemas.xmlsoap.org/soap/envelope/";

	static final String NS_12 = "http://www.w3.org/2003/05/soap-envelope";

	private static final int MAX_BODY = 20 * 1024 * 1024;

	private final Supplier<RequestMappingHandlerMapping> mappingSupplier;

	private final Supplier<FlowRunner> runnerSupplier;

	private final String basePath;

	private final Map<String, Service> services = new LinkedHashMap<>();

	private final Method method;

	/**
	 * @param basePath magic-api.prefix + magic-api.flow.soap-prefix
	 */
	public SoapFlowTriggerRegistrar(Supplier<RequestMappingHandlerMapping> mappingSupplier, Supplier<FlowRunner> runnerSupplier, String basePath) {
		this.mappingSupplier = mappingSupplier;
		this.runnerSupplier = runnerSupplier;
		this.basePath = StringUtils.defaultString(basePath);
		try {
			this.method = SoapFlowTriggerRegistrar.class.getDeclaredMethod("invoke", MagicHttpServletRequest.class, MagicHttpServletResponse.class, Map.class, Map.class);
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException(e);
		}
	}

	public String url(String service) {
		return PathUtils.replaceSlash("/" + basePath + "/" + service);
	}

	@Override
	public synchronized Object register(FlowInfo info, String mappingKey) {
		FlowRunner runner = runnerSupplier.get();
		FlowDefinition definition = runner.definition(info);
		FlowDefinition.Node start = FlowEngine.startNode(definition);
		if (start == null || !TriggerNodeHandler.Soap.TYPE.equals(start.getType())) {
			return null;
		}
		runner.getEngine().prepare(definition);
		Map<String, Object> config = start.getConfig();
		String serviceName = Objects.toString(config.get("service")).trim();
		String operationName = Objects.toString(config.get("operation")).trim();
		Service service = services.get(serviceName);
		if (service != null && service.operations.containsKey(operationName)) {
			Operation existing = service.operations.get(operationName);
			FlowInfo other = runner.flow(existing.flowId);
			throw new InvalidArgumentException(FlowJsonCodes.FLOW_SOAP_CONFLICT.format(serviceName, operationName, other == null ? existing.flowId : other.getName()));
		}
		Operation operation = new Operation();
		operation.flowId = info.getId();
		operation.name = operationName;
		operation.namespace = StringUtils.defaultIfBlank(Objects.toString(config.get("namespace"), null), "http://flow.magicapi.ssssssss.org/" + serviceName);
		for (String param : StringUtils.defaultIfBlank(Objects.toString(config.get("params"), null), "message").split(",")) {
			if (!param.trim().isEmpty()) {
				operation.params.add(param.trim());
			}
		}
		operation.resultName = StringUtils.defaultIfBlank(Objects.toString(config.get("resultName"), null), "return");
		if (service == null) {
			service = new Service();
			service.name = serviceName;
			service.path = url(serviceName);
			Mapping mapping = Mapping.create(mappingSupplier.get());
			service.get = mapping.paths(service.path).methods(RequestMethod.GET).build();
			service.post = mapping.paths(service.path).methods(RequestMethod.POST).build();
			if (mapping.getHandlerMethods().containsKey(service.get) || mapping.getHandlerMethods().containsKey(service.post)) {
				throw new InvalidArgumentException(FlowJsonCodes.FLOW_HTTP_CONFLICT.format(service.path));
			}
			mapping.register(service.get, this, method);
			mapping.register(service.post, this, method);
			services.put(serviceName, service);
			logger.info("发布 WebService：{}", service.path);
		}
		service.operations.put(operationName, operation);
		logger.info("注册 WebService 方法：{}.{} -> {}", serviceName, operationName, info.getName());
		return new Handle(serviceName, operationName, service.path);
	}

	@Override
	public synchronized void unregister(FlowInfo info, Object handle) {
		Handle h = (Handle) handle;
		Service service = services.get(h.service);
		if (service == null) {
			return;
		}
		service.operations.remove(h.operation);
		if (service.operations.isEmpty()) {
			Mapping mapping = Mapping.create(mappingSupplier.get());
			mapping.unregister(service.get);
			mapping.unregister(service.post);
			services.remove(h.service);
			logger.info("停止 WebService：{}", service.path);
		}
	}

	@Override
	public String describe(Object handle) {
		Handle h = (Handle) handle;
		return "WebService " + h.path + " 方法 " + h.operation + "（WSDL：" + h.path + "?wsdl）";
	}

	public ResponseEntity<byte[]> invoke(MagicHttpServletRequest request, MagicHttpServletResponse response,
										 @RequestHeader(required = false) Map<String, Object> headers,
										 @RequestParam(required = false) Map<String, Object> parameters) throws IOException {
		String pattern = String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
		Service service;
		synchronized (this) {
			service = services.values().stream().filter(it -> it.path.equals(pattern)).findFirst().map(Service::snapshot).orElse(null);
		}
		if (service == null) {
			return ResponseEntity.notFound().build();
		}
		Map<String, Object> lowerHeaders = new LinkedHashMap<>();
		if (headers != null) {
			headers.forEach((k, v) -> lowerHeaders.put(k.toLowerCase(Locale.ROOT), v));
		}
		if ("GET".equalsIgnoreCase(request.getMethod())) {
			String location = location(request, lowerHeaders);
			if (parameters != null && parameters.keySet().stream().anyMatch(k -> "wsdl".equalsIgnoreCase(k))) {
				return xml(200, wsdl(service, location), "text/xml;charset=UTF-8");
			}
			String text = "WebService " + service.name + "\n方法：" + String.join("、", service.operations.keySet()) + "\nWSDL：" + location + "?wsdl";
			return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8").body(text.getBytes(StandardCharsets.UTF_8));
		}
		String contentType = StringUtils.defaultString(request.getContentType()).toLowerCase(Locale.ROOT);
		boolean soap12 = contentType.contains("application/soap+xml");
		Element operationElement;
		try {
			String body = readBody(request, contentType);
			Document document = XmlCodec.document(body);
			Element envelope = document.getDocumentElement();
			if (NS_12.equals(envelope.getNamespaceURI())) {
				soap12 = true;
			}
			Element bodyElement = XmlCodec.findChild(envelope, "Body");
			if (!"Envelope".equals(XmlCodec.localName(envelope)) || bodyElement == null) {
				return fault(soap12, true, "请求不是 SOAP 报文（缺少 Envelope/Body）");
			}
			operationElement = XmlCodec.firstChildElement(bodyElement);
		} catch (IllegalArgumentException e) {
			return fault(soap12, true, e.getMessage());
		}
		String operationName = operationElement == null ? soapAction(lowerHeaders, contentType) : XmlCodec.localName(operationElement);
		Operation operation = operationName == null ? null : service.operations.get(operationName);
		if (operation == null) {
			return fault(soap12, true, "WebService " + service.name + " 没有方法 " + operationName + "，可用方法：" + String.join("、", service.operations.keySet()));
		}
		FlowRunner runner = runnerSupplier.get();
		FlowInfo info = runner.flow(operation.flowId);
		if (info == null) {
			return fault(soap12, false, "方法 " + operationName + " 对应的流程不存在");
		}
		Map<String, Object> input = new LinkedHashMap<>();
		if (operationElement != null) {
			NodeList children = operationElement.getChildNodes();
			for (int i = 0; i < children.getLength(); i++) {
				Node child = children.item(i);
				if (child.getNodeType() == Node.ELEMENT_NODE) {
					Element element = (Element) child;
					boolean hasElement = XmlCodec.firstChildElement(element) != null;
					input.put(XmlCodec.localName(element), hasElement ? XmlCodec.innerXml(element) : element.getTextContent().trim());
				}
			}
		}
		for (String param : operation.params) {
			input.putIfAbsent(param, null);
		}
		input.put("operation", operationName);
		input.put("headers", lowerHeaders);
		input.put("clientIp", request.getRemoteAddr());
		FlowResult result;
		try {
			result = runner.run(info, "soap", input);
		} catch (InvalidArgumentException e) {
			return fault(soap12, false, e.getMessage());
		}
		if (!result.isSuccess()) {
			return fault(soap12, false, result.getMessage());
		}
		Object value = result.getValue();
		String text;
		if (value == null) {
			text = "";
		} else if (value instanceof CharSequence) {
			text = value.toString();
		} else {
			text = XmlCodec.build(value, null, null, false);
		}
		String ns = soap12 ? NS_12 : NS_11;
		String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<soap:Envelope xmlns:soap=\"" + ns + "\"><soap:Body>"
				+ "<ns1:" + operationName + "Response xmlns:ns1=\"" + XmlCodec.escape(service.namespace) + "\">"
				+ "<" + operation.resultName + ">" + XmlCodec.escape(text) + "</" + operation.resultName + ">"
				+ "</ns1:" + operationName + "Response></soap:Body></soap:Envelope>";
		return xml(200, xml, soap12 ? "application/soap+xml;charset=UTF-8" : "text/xml;charset=UTF-8");
	}

	private static String soapAction(Map<String, Object> headers, String contentType) {
		String action = headers.get("soapaction") == null ? null : headers.get("soapaction").toString();
		if (action == null) {
			int idx = contentType.indexOf("action=");
			if (idx > -1) {
				action = contentType.substring(idx + 7).split(";")[0];
			}
		}
		if (action == null) {
			return null;
		}
		action = action.replace("\"", "").trim();
		int idx = Math.max(action.lastIndexOf('/'), action.lastIndexOf('#'));
		return idx > -1 ? action.substring(idx + 1) : action;
	}

	private static String location(MagicHttpServletRequest request, Map<String, Object> headers) {
		String scheme = headers.get("x-forwarded-proto") == null ? "http" : headers.get("x-forwarded-proto").toString();
		String host = headers.get("host") == null ? "localhost" : headers.get("host").toString();
		return scheme + "://" + host + request.getRequestURI();
	}

	static String wsdl(Service service, String location) {
		String ns = XmlCodec.escape(service.namespace);
		String name = service.name;
		StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
		sb.append("<wsdl:definitions xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl/\" xmlns:soap=\"http://schemas.xmlsoap.org/wsdl/soap/\"")
				.append(" xmlns:soap12=\"http://schemas.xmlsoap.org/wsdl/soap12/\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"")
				.append(" xmlns:tns=\"").append(ns).append("\" targetNamespace=\"").append(ns).append("\" name=\"").append(name).append("\">");
		sb.append("<wsdl:types><xsd:schema targetNamespace=\"").append(ns).append("\" elementFormDefault=\"unqualified\">");
		for (Operation op : service.operations.values()) {
			sb.append("<xsd:element name=\"").append(op.name).append("\"><xsd:complexType><xsd:sequence>");
			for (String param : op.params) {
				sb.append("<xsd:element name=\"").append(param).append("\" type=\"xsd:string\" minOccurs=\"0\"/>");
			}
			sb.append("</xsd:sequence></xsd:complexType></xsd:element>");
			sb.append("<xsd:element name=\"").append(op.name).append("Response\"><xsd:complexType><xsd:sequence>")
					.append("<xsd:element name=\"").append(op.resultName).append("\" type=\"xsd:string\" minOccurs=\"0\"/>")
					.append("</xsd:sequence></xsd:complexType></xsd:element>");
		}
		sb.append("</xsd:schema></wsdl:types>");
		for (Operation op : service.operations.values()) {
			sb.append("<wsdl:message name=\"").append(op.name).append("Request\"><wsdl:part name=\"parameters\" element=\"tns:").append(op.name).append("\"/></wsdl:message>");
			sb.append("<wsdl:message name=\"").append(op.name).append("Response\"><wsdl:part name=\"parameters\" element=\"tns:").append(op.name).append("Response\"/></wsdl:message>");
		}
		sb.append("<wsdl:portType name=\"").append(name).append("PortType\">");
		for (Operation op : service.operations.values()) {
			sb.append("<wsdl:operation name=\"").append(op.name).append("\"><wsdl:input message=\"tns:").append(op.name).append("Request\"/>")
					.append("<wsdl:output message=\"tns:").append(op.name).append("Response\"/></wsdl:operation>");
		}
		sb.append("</wsdl:portType>");
		binding(sb, service, "soap", "Soap11");
		binding(sb, service, "soap12", "Soap12");
		String address = XmlCodec.escape(location);
		sb.append("<wsdl:service name=\"").append(name).append("\">")
				.append("<wsdl:port name=\"").append(name).append("Soap11\" binding=\"tns:").append(name).append("Soap11\"><soap:address location=\"").append(address).append("\"/></wsdl:port>")
				.append("<wsdl:port name=\"").append(name).append("Soap12\" binding=\"tns:").append(name).append("Soap12\"><soap12:address location=\"").append(address).append("\"/></wsdl:port>")
				.append("</wsdl:service></wsdl:definitions>");
		return sb.toString();
	}

	private static void binding(StringBuilder sb, Service service, String prefix, String suffix) {
		String ns = XmlCodec.escape(service.namespace);
		sb.append("<wsdl:binding name=\"").append(service.name).append(suffix).append("\" type=\"tns:").append(service.name).append("PortType\">")
				.append("<").append(prefix).append(":binding style=\"document\" transport=\"http://schemas.xmlsoap.org/soap/http\"/>");
		for (Operation op : service.operations.values()) {
			sb.append("<wsdl:operation name=\"").append(op.name).append("\">")
					.append("<").append(prefix).append(":operation soapAction=\"").append(ns).append("/").append(op.name).append("\" style=\"document\"/>")
					.append("<wsdl:input><").append(prefix).append(":body use=\"literal\"/></wsdl:input>")
					.append("<wsdl:output><").append(prefix).append(":body use=\"literal\"/></wsdl:output>")
					.append("</wsdl:operation>");
		}
		sb.append("</wsdl:binding>");
	}

	static ResponseEntity<byte[]> fault(boolean soap12, boolean client, String message) {
		String text = XmlCodec.escape(message == null ? "" : message);
		String xml;
		if (soap12) {
			xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soap:Envelope xmlns:soap=\"" + NS_12 + "\"><soap:Body><soap:Fault>"
					+ "<soap:Code><soap:Value>soap:" + (client ? "Sender" : "Receiver") + "</soap:Value></soap:Code>"
					+ "<soap:Reason><soap:Text xml:lang=\"zh\">" + text + "</soap:Text></soap:Reason>"
					+ "</soap:Fault></soap:Body></soap:Envelope>";
		} else {
			xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soap:Envelope xmlns:soap=\"" + NS_11 + "\"><soap:Body><soap:Fault>"
					+ "<faultcode>soap:" + (client ? "Client" : "Server") + "</faultcode><faultstring>" + text + "</faultstring>"
					+ "</soap:Fault></soap:Body></soap:Envelope>";
		}
		return xml(500, xml, soap12 ? "application/soap+xml;charset=UTF-8" : "text/xml;charset=UTF-8");
	}

	private static ResponseEntity<byte[]> xml(int status, String xml, String contentType) {
		return ResponseEntity.status(status).header(HttpHeaders.CONTENT_TYPE, contentType).body(xml.getBytes(StandardCharsets.UTF_8));
	}

	private static String readBody(MagicHttpServletRequest request, String contentType) throws IOException {
		try (InputStream in = request.getInputStream()) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int len;
			while (in != null && (len = in.read(buffer)) != -1) {
				out.write(buffer, 0, len);
				if (out.size() > MAX_BODY) {
					throw new IllegalArgumentException("请求体超过 20MB");
				}
			}
			Charset charset = StandardCharsets.UTF_8;
			int idx = contentType.indexOf("charset=");
			if (idx > -1) {
				try {
					charset = Charset.forName(contentType.substring(idx + 8).split(";")[0].replace("\"", "").trim());
				} catch (Exception ignored) {
					// 不认识的编码按 UTF-8
				}
			}
			String body = new String(out.toByteArray(), charset);
			if (body.trim().isEmpty()) {
				throw new IllegalArgumentException("请求体为空");
			}
			return body;
		}
	}

	static final class Service {

		String name;

		String path;

		String namespace;

		RequestMappingInfo get;

		RequestMappingInfo post;

		final Map<String, Operation> operations = new LinkedHashMap<>();

		Service snapshot() {
			Service copy = new Service();
			copy.name = name;
			copy.path = path;
			copy.operations.putAll(operations);
			copy.namespace = operations.isEmpty() ? "" : operations.values().iterator().next().namespace;
			return copy;
		}
	}

	static final class Operation {

		String flowId;

		String name;

		String namespace;

		final List<String> params = new ArrayList<>();

		String resultName;
	}

	static final class Handle {

		final String service;

		final String operation;

		final String path;

		Handle(String service, String operation, String path) {
			this.service = service;
			this.operation = operation;
			this.path = path;
		}

		@Override
		public String toString() {
			return path + "#" + operation;
		}
	}
}
