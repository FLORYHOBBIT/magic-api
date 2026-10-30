package org.ssssssss.magicapi.flow.support;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 测试用的外部系统：记录收到的请求，按设定返回
 */
public class MockHttpServer implements AutoCloseable {

	private final HttpServer server;

	private final List<Request> requests = new CopyOnWriteArrayList<>();

	private volatile Function<Request, Response> responder = r -> new Response(200, "application/json", "{\"code\":0,\"data\":{\"ok\":true}}");

	public MockHttpServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			Request request = new Request();
			request.method = exchange.getRequestMethod();
			request.path = exchange.getRequestURI().getPath();
			request.query = exchange.getRequestURI().getRawQuery();
			exchange.getRequestHeaders().forEach((k, v) -> request.headers.put(k.toLowerCase(Locale.ROOT), v.get(0)));
			try (InputStream in = exchange.getRequestBody()) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buffer = new byte[4096];
				int len;
				while ((len = in.read(buffer)) != -1) {
					out.write(buffer, 0, len);
				}
				request.body = new String(out.toByteArray(), StandardCharsets.UTF_8);
			}
			requests.add(request);
			Response response = responder.apply(request);
			byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", response.contentType + ";charset=UTF-8");
			exchange.sendResponseHeaders(response.status, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		server.start();
	}

	public String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	public void respond(Function<Request, Response> responder) {
		this.responder = responder;
	}

	public List<Request> requests() {
		return requests;
	}

	public void clear() {
		requests.clear();
	}

	@Override
	public void close() {
		server.stop(0);
	}

	public static class Request {
		public String method;
		public String path;
		public String query;
		public final Map<String, String> headers = new LinkedHashMap<>();
		public String body;
	}

	public static class Response {
		final int status;
		final String contentType;
		final String body;

		public Response(int status, String contentType, String body) {
			this.status = status;
			this.contentType = contentType;
			this.body = body;
		}

		public static Response json(int status, String body) {
			return new Response(status, "application/json", body);
		}

		public static Response xml(int status, String body) {
			return new Response(status, "text/xml", body);
		}
	}
}
