package org.ssssssss.magicapi.flow.engine.node;

import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.flow.engine.FlowNodeHandler;
import org.ssssssss.magicapi.flow.support.FlowJson;
import org.ssssssss.magicapi.flow.support.FlowTemplate;
import org.ssssssss.magicapi.flow.support.XmlCodec;

/**
 * 报文转换节点：模板、XML、JSON。
 */
public final class MessageNodeHandlers {

	private MessageNodeHandlers() {
	}

	/**
	 * 报文模板：按模板生成 XML / JSON / 文本报文。
	 * <pre>
	 * config:
	 *   format    xml / json / text
	 *   template  模板，语法见 {@link FlowTemplate}，如
	 *             &lt;data&gt;{{#each prev as r}}&lt;row&gt;&lt;id&gt;{{r.id}}&lt;/id&gt;&lt;/row&gt;{{/each}}&lt;/data&gt;
	 * </pre>
	 * 输出：字符串。
	 */
	public static class Template implements FlowNodeHandler {

		public static final String TYPE = "template";

		@Override
		public String type() {
			return TYPE;
		}

		@Override
		public void check(FlowNodeContext node) {
			node.oneOf("format", FlowTemplate.JSON, FlowTemplate.XML, FlowTemplate.JSON, FlowTemplate.TEXT);
			String template = node.requireString("template");
			try {
				FlowTemplate.render(template, FlowTemplate.TEXT, (expression, scope) -> "");
			} catch (IllegalArgumentException e) {
				throw node.invalid("template", e.getMessage());
			}
		}

		@Override
		public Object execute(FlowNodeContext node) {
			String format = node.oneOf("format", FlowTemplate.JSON, FlowTemplate.XML, FlowTemplate.JSON, FlowTemplate.TEXT);
			Object template = node.raw("template");
			return FlowTemplate.render(template == null ? "" : template.toString(), format, node::evaluate);
		}
	}

	/**
	 * XML 转换。
	 * <pre>
	 * config:
	 *   action       parse（XML 文本 → 对象，默认）/ build（对象 → XML 文本）
	 *   source       表达式，默认 prev
	 *   path         parse 时只取某个元素，如 "Envelope.Body.response"（按元素名逐级取）
	 *   root         build 时的根元素名；为空时对象只有一个键则用该键
	 *   itemName     build 时列表每项的元素名，默认 item
	 *   declaration  build 时是否输出 XML 声明，默认 true
	 * </pre>
	 */
	public static class Xml implements FlowNodeHandler {

		public static final String TYPE = "xml";

		@Override
		public String type() {
			return TYPE;
		}

		@Override
		public void check(FlowNodeContext node) {
			node.oneOf("action", "parse", "parse", "build");
		}

		@Override
		public Object execute(FlowNodeContext node) {
			Object source = node.evaluateConfig("source", "prev");
			if ("build".equals(node.oneOf("action", "parse", "parse", "build"))) {
				return XmlCodec.build(source, node.string("root"), node.string("itemName"), node.bool("declaration", true));
			}
			if (source == null) {
				return null;
			}
			Object value = XmlCodec.parse(source.toString());
			String path = node.string("path");
			if (path != null) {
				for (String segment : path.split("\\.")) {
					if (!(value instanceof java.util.Map)) {
						return null;
					}
					value = ((java.util.Map<?, ?>) value).get(segment.trim());
				}
			}
			return value;
		}
	}

	/**
	 * JSON 转换。
	 * <pre>
	 * config:
	 *   action  parse（JSON 文本 → 对象，默认）/ stringify（对象 → JSON 文本）
	 *   source  表达式，默认 prev
	 *   pretty  stringify 时是否格式化，默认 false
	 * </pre>
	 */
	public static class Json implements FlowNodeHandler {

		public static final String TYPE = "json";

		@Override
		public String type() {
			return TYPE;
		}

		@Override
		public void check(FlowNodeContext node) {
			node.oneOf("action", "parse", "parse", "stringify");
		}

		@Override
		public Object execute(FlowNodeContext node) {
			Object source = node.evaluateConfig("source", "prev");
			if ("stringify".equals(node.oneOf("action", "parse", "parse", "stringify"))) {
				return node.bool("pretty", false) ? FlowJson.pretty(source) : FlowJson.stringify(source);
			}
			return source == null || source instanceof java.util.Map || source instanceof java.util.Collection
					? source : FlowJson.parse(source.toString());
		}
	}
}
