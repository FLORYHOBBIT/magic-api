package org.ssssssss.magicapi.upload.channel.soap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ssssssss.magicapi.upload.channel.ChannelFactory;
import org.ssssssss.magicapi.upload.channel.ResponseRule;
import org.ssssssss.magicapi.upload.engine.*;
import org.ssssssss.magicapi.upload.source.ListSource;
import org.ssssssss.magicapi.upload.support.TestHttpServer;
import org.ssssssss.magicapi.upload.support.TestHttpServer.Response;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoapUploadChannelTest {

	private static final String OK_11 = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
			+ "<ns2:uploadResponse xmlns:ns2=\"http://ws.yb.gov/\"><return><code>0</code><msg>成功</msg></return></ns2:uploadResponse>"
			+ "</soap:Body></soap:Envelope>";

	private TestHttpServer server;

	@BeforeEach
	void start() throws Exception {
		server = new TestHttpServer();
	}

	@AfterEach
	void stop() {
		server.close();
	}

	private SoapChannelConfig config() {
		return new SoapChannelConfig(server.url("/ws/UploadService"))
				.soapAction("urn:upload")
				.bodyBuilder(b -> "<ns:upload xmlns:ns=\"http://ws.yb.gov/\"><count>" + b.getSize() + "</count></ns:upload>")
				.responseRule(new ResponseRule().successField("return/code").successValues("0").messageField("return/msg"));
	}

	@Test
	void soap11WrapsEnvelopeAndChecksBusinessCode() throws Exception {
		server.respond(r -> Response.xml(200, OK_11));

		SendResult result = new SoapUploadChannel(config()).send(batch(3));

		assertThat(result.isSuccess()).isTrue();
		TestHttpServer.Request request = server.requests().get(0);
		assertThat(request.header("Content-Type")).startsWith("text/xml");
		assertThat(request.header("SOAPAction")).isEqualTo("\"urn:upload\"");
		assertThat(request.body).contains("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">")
				.contains("<count>3</count>");
	}

	@Test
	void soap12UsesActionInContentType() throws Exception {
		server.respond(r -> Response.xml(200, OK_11));

		new SoapUploadChannel(config().version("1.2")).send(batch(1));

		TestHttpServer.Request request = server.requests().get(0);
		assertThat(request.header("Content-Type")).isEqualTo("application/soap+xml;charset=UTF-8;action=\"urn:upload\"");
		assertThat(request.header("SOAPAction")).isNull();
		assertThat(request.body).contains("http://www.w3.org/2003/05/soap-envelope");
	}

	@Test
	void fullEnvelopeIsSentAsIs() throws Exception {
		server.respond(r -> Response.xml(200, OK_11));
		String envelope = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Header/><soapenv:Body><a/></soapenv:Body></soapenv:Envelope>";

		new SoapUploadChannel(config().bodyBuilder(b -> envelope)).send(batch(1));

		assertThat(server.requests().get(0).body).isEqualTo(envelope);
	}

	@Test
	void businessFailureIsRejected() throws Exception {
		server.respond(r -> Response.xml(200, OK_11.replace("<code>0</code>", "<code>E01</code>").replace("成功", "医疗机构编码不存在")));

		SendResult result = new SoapUploadChannel(config()).send(batch(1));

		assertThat(result.isSuccess()).isFalse();
		assertThat(result.isRetryable()).isFalse();
		assertThat(result.getMessage()).contains("E01").contains("医疗机构编码不存在");
	}

	@Test
	void faultClientIsRejectedServerIsRetried() throws Exception {
		String fault = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><soap:Fault>"
				+ "<faultcode>soap:%s</faultcode><faultstring>%s</faultstring></soap:Fault></soap:Body></soap:Envelope>";
		SoapUploadChannel channel = new SoapUploadChannel(config());

		server.respond(r -> Response.xml(500, String.format(fault, "Client", "参数格式错误")));
		SendResult client = channel.send(batch(1));
		assertThat(client.isRetryable()).isFalse();
		assertThat(client.getMessage()).isEqualTo("SOAP Fault soap:Client: 参数格式错误");

		server.respond(r -> Response.xml(500, String.format(fault, "Server", "数据库连接超时")));
		assertThat(channel.send(batch(1)).isRetryable()).isTrue();

		String fault12 = "<env:Envelope xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\"><env:Body><env:Fault>"
				+ "<env:Code><env:Value>env:Sender</env:Value></env:Code><env:Reason><env:Text>bad</env:Text></env:Reason>"
				+ "</env:Fault></env:Body></env:Envelope>";
		server.respond(r -> Response.xml(500, fault12));
		SendResult sender = channel.send(batch(1));
		assertThat(sender.isRetryable()).isFalse();
		assertThat(sender.getMessage()).contains("bad");
	}

	@Test
	void readsJsonInsideReturnNode() throws Exception {
		server.respond(r -> Response.xml(200, "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
				+ "<ns2:uploadResponse xmlns:ns2=\"http://x/\"><return>{\"code\":\"1\",\"data\":{\"receipt\":\"R9\"}}</return></ns2:uploadResponse>"
				+ "</soap:Body></soap:Envelope>"));

		SoapUploadChannel channel = new SoapUploadChannel(config().responseRule(new ResponseRule().successField("return#code").successValues("1")));

		assertThat(channel.send(batch(1)).isSuccess()).isTrue();
		assertThat(new XmlFieldReader(server.requests().isEmpty() ? null : "<a><return>{\"data\":{\"receipt\":\"R9\"}}</return></a>")
				.apply("return#data.receipt")).isEqualTo("R9");
	}

	@Test
	void engineRetriesServerFaultThenSucceeds() {
		server.respond(r -> r.index == 1 ? Response.text(503, "busy") : Response.xml(200, OK_11));

		JobResult result = new BatchEngine(m -> {
		}).run(new JobOptions("yb").retry(2).retryBackoff(Duration.ZERO), new ListSource(rows(3)),
				new SoapUploadChannel(config()), null, null);

		assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCESS);
		assertThat(result.getBatches().get(0).getAttempts()).isEqualTo(2);
	}

	@Test
	void rejectsExternalEntities() {
		String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r><code>&x;</code></r>";
		XmlFieldReader reader = new XmlFieldReader(xxe);
		assertThat(reader.isXml()).isFalse();
		assertThat(reader.apply("code")).isNull();
	}

	@Test
	void factoryValidation() {
		ChannelFactory factory = new ChannelFactory(null);
		Map<String, Object> options = new HashMap<>();
		options.put("url", server.url("/ws"));
		assertThatThrownBy(() -> factory.create("soap", options, null)).hasMessageContaining("报文转换");
		options.put("version", "2.0");
		assertThatThrownBy(() -> factory.create("soap", options, b -> "<a/>")).hasMessageContaining("1.1");
		assertThat(SoapUploadChannel.wrap("<?xml version=\"1.0\"?><a/>", "1.1")).contains("<soap:Body><a/></soap:Body>");
	}

	private static UploadBatch batch(int size) {
		return new UploadBatch("yb", 1, rows(size), null, size);
	}

	private static List<Map<String, Object>> rows(int count) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			rows.add(Collections.singletonMap("id", i));
		}
		return rows;
	}
}
