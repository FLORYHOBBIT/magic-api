package org.ssssssss.magicapi.upload.support;

import com.sun.net.httpserver.HttpExchange;
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
 * 测试用的本地 HTTP 服务，模拟上传平台
 */
public class TestHttpServer implements AutoCloseable {

	private final HttpServer server;

	private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool();

	private final List<Request> requests = new CopyOnWriteArrayList<>();

	private final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();

	private volatile Function<Request, Response> handler = request -> Response.json(200, "{\"code\":0,\"msg\":\"ok\"}");

	public TestHttpServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.setExecutor(executor);
		server.start();
	}

	public String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	public void respond(Function<Request, Response> handler) {
		this.handler = handler;
	}

	public List<Request> requests() {
		return requests;
	}

	private void handle(HttpExchange exchange) throws IOException {
		Request request = new Request();
		request.method = exchange.getRequestMethod();
		request.path = exchange.getRequestURI().getPath();
		request.rawQuery = exchange.getRequestURI().getRawQuery();
		exchange.getRequestHeaders().forEach((k, v) -> request.headers.put(k.toLowerCase(Locale.ROOT), v.get(0)));
		request.body = read(exchange.getRequestBody());
		request.index = counter.incrementAndGet();
		requests.add(request);
		Response response;
		try {
			response = handler.apply(request);
		} catch (RuntimeException e) {
			response = Response.text(500, "handler error: " + e.getMessage());
		}
		if (response.delayMillis > 0) {
			try {
				Thread.sleep(response.delayMillis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", response.contentType);
		exchange.sendResponseHeaders(response.status, bytes.length == 0 ? -1 : bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		} catch (IOException ignored) {
			// 客户端超时断开
		}
	}

	private static String read(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int len;
		while ((len = in.read(buffer)) != -1) {
			out.write(buffer, 0, len);
		}
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}

	@Override
	public void close() {
		server.stop(0);
		executor.shutdownNow();
	}

	public static class Request {

		public int index;

		public String method;

		public String path;

		public String rawQuery;

		public final Map<String, String> headers = new HashMap<>();

		public String body;

		public String header(String name) {
			return headers.get(name.toLowerCase(Locale.ROOT));
		}
	}

	public static class Response {

		int status;

		String body;

		String contentType;

		long delayMillis;

		public static Response json(int status, String body) {
			Response r = new Response();
			r.status = status;
			r.body = body;
			r.contentType = "application/json;charset=UTF-8";
			return r;
		}

		public static Response text(int status, String body) {
			Response r = json(status, body);
			r.contentType = "text/plain;charset=UTF-8";
			return r;
		}

		public static Response xml(int status, String body) {
			Response r = json(status, body);
			r.contentType = "text/xml;charset=UTF-8";
			return r;
		}

		public Response delay(long millis) {
			this.delayMillis = millis;
			return this;
		}
	}
}
