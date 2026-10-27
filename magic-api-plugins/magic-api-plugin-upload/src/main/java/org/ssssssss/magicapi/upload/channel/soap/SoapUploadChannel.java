package org.ssssssss.magicapi.upload.channel.soap;

import org.springframework.http.HttpHeaders;
import org.ssssssss.magicapi.upload.channel.ChannelRequest;
import org.ssssssss.magicapi.upload.channel.HttpSender;
import org.ssssssss.magicapi.upload.channel.SignContext;
import org.ssssssss.magicapi.upload.engine.SendResult;
import org.ssssssss.magicapi.upload.engine.UploadBatch;
import org.ssssssss.magicapi.upload.engine.UploadChannel;
import org.w3c.dom.Element;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * WebService（SOAP）上传通道
 * <p>
 * 报文由通道的报文转换生成（XML 字符串）。只给出 Body 里的内容时自动包装为 SOAP Envelope。
 * 响应中有 SOAP Fault 时：faultcode 为 Client/Sender 视为拒绝（不重试），其他视为可重试；
 * 没有 Fault 时按 HTTP 状态码与业务字段（XML 路径，见 {@link XmlFieldReader}）判定。
 */
public class SoapUploadChannel implements UploadChannel {

	static final String NS_11 = "http://schemas.xmlsoap.org/soap/envelope/";

	static final String NS_12 = "http://www.w3.org/2003/05/soap-envelope";

	private final SoapChannelConfig config;

	private final HttpSender sender;

	public SoapUploadChannel(SoapChannelConfig config) {
		this.config = Objects.requireNonNull(config, "config");
		if (config.getBodyBuilder() == null) {
			throw new IllegalArgumentException("WebService 通道必须配置报文转换，生成请求 XML");
		}
		this.sender = new HttpSender(config.getConnectTimeout(), config.getReadTimeout());
	}

	@Override
	public SendResult send(UploadBatch batch) throws Exception {
		Map<String, String> headers = new LinkedHashMap<>(config.getHeaders());
		Map<String, String> queryParams = new LinkedHashMap<>();
		Object built = ChannelRequest.unwrap(config.getBodyBuilder().build(batch), headers, queryParams);
		if (!(built instanceof CharSequence)) {
			throw new IllegalArgumentException("WebService 报文转换必须返回 XML 字符串");
		}
		String payload = wrap(built.toString().trim(), config.getVersion());

		if (config.getSigner() != null) {
			config.getSigner().sign(new SignContext("POST", config.getUrl(), headers, queryParams, payload, batch));
		}
		HttpHeaders httpHeaders = new HttpHeaders();
		headers.forEach(httpHeaders::set);
		if (SoapChannelConfig.VERSION_12.equals(config.getVersion())) {
			String contentType = "application/soap+xml;charset=UTF-8";
			if (!config.getSoapAction().isEmpty()) {
				contentType += ";action=\"" + config.getSoapAction() + "\"";
			}
			httpHeaders.set(HttpHeaders.CONTENT_TYPE, contentType);
		} else {
			httpHeaders.set(HttpHeaders.CONTENT_TYPE, "text/xml;charset=UTF-8");
			httpHeaders.set("SOAPAction", "\"" + config.getSoapAction() + "\"");
		}

		HttpSender.Response response = sender.send("POST", HttpSender.buildUri(config.getUrl(), queryParams), httpHeaders, payload);
		XmlFieldReader reader = new XmlFieldReader(response.getBody());
		SendResult fault = checkFault(reader, response);
		if (fault != null) {
			return fault;
		}
		return config.getResponseRule().evaluate(response.getStatus(), response.getBody(), reader);
	}

	static String wrap(String xml, String version) {
		String head = xml.length() > 300 ? xml.substring(0, 300) : xml;
		if (head.contains(":Envelope") || head.contains("<Envelope")) {
			return xml;
		}
		String ns = SoapChannelConfig.VERSION_12.equals(version) ? NS_12 : NS_11;
		String body = xml.startsWith("<?xml") ? xml.substring(xml.indexOf("?>") + 2).trim() : xml;
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<soap:Envelope xmlns:soap=\"" + ns + "\"><soap:Body>" + body + "</soap:Body></soap:Envelope>";
	}

	private static SendResult checkFault(XmlFieldReader reader, HttpSender.Response response) {
		if (!reader.isXml()) {
			return null;
		}
		Element body = XmlFieldReader.findChild(reader.getRoot(), "Body");
		Element fault = body == null ? null : XmlFieldReader.findChild(body, "Fault");
		if (fault == null) {
			return null;
		}
		String code = firstNonNull(XmlFieldReader.read(fault, "faultcode"), XmlFieldReader.read(fault, "Code/Value"));
		String reason = firstNonNull(XmlFieldReader.read(fault, "faultstring"), XmlFieldReader.read(fault, "Reason/Text"));
		String message = "SOAP Fault " + (code == null ? "" : code.trim()) + ": " + (reason == null ? "" : reason.trim());
		String lowerCode = code == null ? "" : code.toLowerCase(Locale.ROOT);
		if (lowerCode.endsWith("client") || lowerCode.endsWith("sender")) {
			return SendResult.rejected(message, response.getBody());
		}
		return SendResult.failure(message, response.getBody());
	}

	private static String firstNonNull(String a, String b) {
		return a != null ? a : b;
	}

	public SoapChannelConfig getConfig() {
		return config;
	}
}
