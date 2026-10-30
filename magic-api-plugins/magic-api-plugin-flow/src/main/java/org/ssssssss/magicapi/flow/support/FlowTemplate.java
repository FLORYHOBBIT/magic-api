package org.ssssssss.magicapi.flow.support;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * 报文模板：文本中用双花括号写 magic-script 表达式。
 * <pre>
 * {{expr}}                     输出值（按格式转义：xml 转义 &lt;&gt;&amp;，json 转义为字符串内容）；对象/列表输出为 JSON 或 XML
 * {{{expr}}}                   原样输出，不转义
 * {{#each expr as item}}…{{/each}}   遍历列表，循环内可用 item（或指定的名称）、index
 * {{#if expr}}…{{else}}…{{/if}}      条件
 * </pre>
 * 日期按 yyyy-MM-dd HH:mm:ss 输出，数值不使用科学计数法。
 */
public final class FlowTemplate {

	public static final String XML = "xml";

	public static final String JSON = "json";

	public static final String TEXT = "text";

	private static final Map<String, List<Part>> CACHE = new ConcurrentHashMap<>();

	private FlowTemplate() {
	}

	/**
	 * @param evaluator (表达式, 额外变量) → 值
	 */
	public static String render(String template, String format, BiFunction<String, Map<String, Object>, Object> evaluator) {
		if (template == null || template.isEmpty()) {
			return "";
		}
		List<Part> parts = CACHE.computeIfAbsent(template, FlowTemplate::parse);
		if (CACHE.size() > 2000) {
			CACHE.clear();
		}
		StringBuilder sb = new StringBuilder(template.length() * 2);
		renderParts(parts, sb, format, evaluator, new LinkedHashMap<>());
		return sb.toString();
	}

	/**
	 * 只做插值（不含 each/if），用于请求头、文件名等短文本；不含 {{ 时原样返回
	 */
	public static String interpolate(String text, BiFunction<String, Map<String, Object>, Object> evaluator) {
		if (text == null || !text.contains("{{")) {
			return text;
		}
		return render(text, TEXT, evaluator);
	}

	private static void renderParts(List<Part> parts, StringBuilder sb, String format,
									BiFunction<String, Map<String, Object>, Object> evaluator, Map<String, Object> scope) {
		for (Part part : parts) {
			part.render(sb, format, evaluator, scope);
		}
	}

	static List<Part> parse(String template) {
		Parser parser = new Parser(template);
		List<Part> parts = parser.parseUntil(Collections.<String>emptySet());
		if (parser.pos < template.length()) {
			throw new IllegalArgumentException("模板第 " + parser.line() + " 行有多余的 {{" + parser.lastTag + "}}");
		}
		return parts;
	}

	static String text(Object value, String format) {
		if (value == null) {
			return "";
		}
		if (value instanceof BigDecimal) {
			return ((BigDecimal) value).toPlainString();
		}
		if (value instanceof Double || value instanceof Float) {
			return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
		}
		Object normalized = FlowJson.normalize(value);
		if (normalized instanceof Map || normalized instanceof Collection) {
			return XML.equals(format) ? XmlCodec.build(normalized, null, null, false) : FlowJson.stringify(normalized);
		}
		return String.valueOf(normalized);
	}

	static String escape(String text, String format) {
		if (XML.equals(format)) {
			return XmlCodec.escape(text);
		}
		if (JSON.equals(format)) {
			String quoted = FlowJson.stringify(text);
			return quoted.substring(1, quoted.length() - 1);
		}
		return text;
	}

	interface Part {
		void render(StringBuilder sb, String format, BiFunction<String, Map<String, Object>, Object> evaluator, Map<String, Object> scope);
	}

	static final class Text implements Part {
		final String text;

		Text(String text) {
			this.text = text;
		}

		@Override
		public void render(StringBuilder sb, String format, BiFunction<String, Map<String, Object>, Object> evaluator, Map<String, Object> scope) {
			sb.append(text);
		}
	}

	static final class Value implements Part {
		final String expression;

		final boolean raw;

		final int line;

		Value(String expression, boolean raw, int line) {
			this.expression = expression;
			this.raw = raw;
			this.line = line;
		}

		@Override
		public void render(StringBuilder sb, String format, BiFunction<String, Map<String, Object>, Object> evaluator, Map<String, Object> scope) {
			Object value = evaluate(evaluator, expression, scope, line);
			boolean structured = value instanceof Map || value instanceof Collection;
			String text = text(value, format);
			sb.append(raw || structured ? text : escape(text, format));
		}
	}

	static final class Each implements Part {
		final String expression;

		final String name;

		final List<Part> body;

		final int line;

		Each(String expression, String name, List<Part> body, int line) {
			this.expression = expression;
			this.name = name;
			this.body = body;
			this.line = line;
		}

		@Override
		public void render(StringBuilder sb, String format, BiFunction<String, Map<String, Object>, Object> evaluator, Map<String, Object> scope) {
			Object value = evaluate(evaluator, expression, scope, line);
			Collection<?> items;
			if (value == null) {
				items = Collections.emptyList();
			} else if (value instanceof Collection) {
				items = (Collection<?>) value;
			} else if (value instanceof Object[]) {
				items = Arrays.asList((Object[]) value);
			} else {
				items = Collections.singletonList(value);
			}
			int index = 0;
			for (Object item : items) {
				Map<String, Object> inner = new LinkedHashMap<>(scope);
				inner.put(name, item);
				inner.put("index", index++);
				renderParts(body, sb, format, evaluator, inner);
			}
		}
	}

	static final class If implements Part {
		final String expression;

		final List<Part> then;

		final List<Part> otherwise;

		final int line;

		If(String expression, List<Part> then, List<Part> otherwise, int line) {
			this.expression = expression;
			this.then = then;
			this.otherwise = otherwise;
			this.line = line;
		}

		@Override
		public void render(StringBuilder sb, String format, BiFunction<String, Map<String, Object>, Object> evaluator, Map<String, Object> scope) {
			Object value = evaluate(evaluator, expression, scope, line);
			boolean truthy = org.ssssssss.script.parsing.ast.literal.BooleanLiteral.isTrue(value);
			renderParts(truthy ? then : otherwise, sb, format, evaluator, scope);
		}
	}

	private static Object evaluate(BiFunction<String, Map<String, Object>, Object> evaluator, String expression, Map<String, Object> scope, int line) {
		try {
			return evaluator.apply(expression, scope);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("模板第 " + line + " 行表达式 {{" + expression + "}} 出错：" + e.getMessage(), e);
		}
	}

	private static final class Parser {
		final String text;

		int pos;

		String lastTag;

		Parser(String text) {
			this.text = text;
		}

		int line() {
			int line = 1;
			for (int i = 0; i < pos && i < text.length(); i++) {
				if (text.charAt(i) == '\n') {
					line++;
				}
			}
			return line;
		}

		/**
		 * 解析到 stops 中的某个结束标签为止（标签本身留给调用方读取）
		 */
		List<Part> parseUntil(Set<String> stops) {
			List<Part> parts = new ArrayList<>();
			while (pos < text.length()) {
				int open = text.indexOf("{{", pos);
				if (open < 0) {
					parts.add(new Text(text.substring(pos)));
					pos = text.length();
					break;
				}
				if (open > pos) {
					parts.add(new Text(text.substring(pos, open)));
				}
				pos = open;
				int line = line();
				boolean raw = text.startsWith("{{{", open);
				String close = raw ? "}}}" : "}}";
				int end = text.indexOf(close, open + close.length());
				if (end < 0) {
					throw new IllegalArgumentException("模板第 " + line + " 行的 {{ 没有闭合");
				}
				String tag = text.substring(open + close.length(), end).trim();
				lastTag = tag;
				if (!raw && (tag.startsWith("/") || "else".equals(tag))) {
					if (stops.contains(tag.startsWith("/") ? tag : "else")) {
						return parts;
					}
					throw new IllegalArgumentException("模板第 " + line + " 行的 {{" + tag + "}} 没有对应的开始标签");
				}
				pos = end + close.length();
				if (!raw && tag.startsWith("#each ")) {
					String expression = tag.substring(6).trim();
					String name = "item";
					int as = expression.lastIndexOf(" as ");
					if (as > 0) {
						name = expression.substring(as + 4).trim();
						expression = expression.substring(0, as).trim();
					}
					List<Part> body = parseUntil(Collections.singleton("/each"));
					expectEnd("/each", line);
					parts.add(new Each(expression, name, body, line));
				} else if (!raw && tag.startsWith("#if ")) {
					String expression = tag.substring(4).trim();
					Set<String> ifStops = new HashSet<>(Arrays.asList("/if", "else"));
					List<Part> then = parseUntil(ifStops);
					List<Part> otherwise = Collections.emptyList();
					if (pos < text.length() && "else".equals(currentTag())) {
						skipTag();
						otherwise = parseUntil(Collections.singleton("/if"));
					}
					expectEnd("/if", line);
					parts.add(new If(expression, then, otherwise, line));
				} else {
					if (tag.isEmpty()) {
						throw new IllegalArgumentException("模板第 " + line + " 行有空的 {{}}");
					}
					parts.add(new Value(tag, raw, line));
				}
			}
			if (!stops.isEmpty()) {
				String expected = stops.contains("/if") ? "/if" : stops.iterator().next();
				throw new IllegalArgumentException("模板缺少结束标签 {{" + expected + "}}");
			}
			return parts;
		}

		private String currentTag() {
			int end = text.indexOf("}}", pos);
			return end < 0 ? null : text.substring(pos + 2, end).trim();
		}

		private void skipTag() {
			pos = text.indexOf("}}", pos) + 2;
		}

		private void expectEnd(String tag, int line) {
			if (pos >= text.length() || !tag.equals(currentTag())) {
				throw new IllegalArgumentException("模板第 " + line + " 行的标签缺少 {{" + tag + "}}");
			}
			skipTag();
		}
	}
}
