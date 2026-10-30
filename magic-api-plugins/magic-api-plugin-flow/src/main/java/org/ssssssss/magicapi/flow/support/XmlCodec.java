package org.ssssssss.magicapi.flow.support;

import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;

/**
 * XML 与 Map 互转。
 * <p>
 * 解析规则：元素名为键；只有文本的元素为字符串；同名元素出现多次为列表；属性以 "@名称" 保存；
 * 既有子元素又有文本时文本保存在 "#text"。命名空间前缀去掉，只保留本地名。
 * <p>
 * 生成规则与解析相反：列表生成多个同名元素，"@" 开头的键生成属性。
 */
public final class XmlCodec {

	private XmlCodec() {
	}

	public static Document document(String xml) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			// 防 XXE：外部系统传入的报文不允许引用外部实体
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			DocumentBuilder builder = factory.newDocumentBuilder();
			return builder.parse(new InputSource(new StringReader(xml.trim())));
		} catch (Exception e) {
			throw new IllegalArgumentException("XML 格式错误：" + e.getMessage(), e);
		}
	}

	/**
	 * 解析为 Map：{根元素名: 内容}
	 */
	public static Map<String, Object> parse(String xml) {
		if (xml == null || xml.trim().isEmpty()) {
			return new LinkedHashMap<>();
		}
		Element root = document(xml).getDocumentElement();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put(localName(root), toValue(root));
		return result;
	}

	/**
	 * 只解析根元素的内容（不含根元素名）
	 */
	public static Object parseContent(String xml) {
		return toValue(document(xml).getDocumentElement());
	}

	public static Object toValue(Element element) {
		Map<String, Object> map = new LinkedHashMap<>();
		NamedNodeMap attributes = element.getAttributes();
		for (int i = 0; i < attributes.getLength(); i++) {
			Node attr = attributes.item(i);
			String name = attr.getNodeName();
			if (name.startsWith("xmlns")) {
				continue;
			}
			map.put("@" + localName(attr), attr.getNodeValue());
		}
		StringBuilder text = new StringBuilder();
		boolean hasChild = false;
		NodeList children = element.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (child.getNodeType() == Node.ELEMENT_NODE) {
				hasChild = true;
				String name = localName(child);
				Object value = toValue((Element) child);
				Object existing = map.get(name);
				if (existing == null && !map.containsKey(name)) {
					map.put(name, value);
				} else if (existing instanceof RepeatedList) {
					((RepeatedList) existing).add(value);
				} else {
					RepeatedList list = new RepeatedList();
					list.add(existing);
					list.add(value);
					map.put(name, list);
				}
			} else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
				text.append(child.getNodeValue());
			}
		}
		String trimmed = text.toString().trim();
		if (!hasChild && map.isEmpty()) {
			return trimmed;
		}
		if (!trimmed.isEmpty()) {
			map.put("#text", trimmed);
		}
		return map;
	}

	/**
	 * 元素内部的 XML（不含元素自身的标签）。参数是未转义的 XML 片段时用它取原文。
	 */
	public static String innerXml(Element element) {
		StringBuilder sb = new StringBuilder();
		NodeList children = element.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (child.getNodeType() == Node.ELEMENT_NODE) {
				sb.append(serialize(child));
			} else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
				sb.append(escape(child.getNodeValue()));
			}
		}
		return sb.toString().trim();
	}

	public static String serialize(Node node) {
		try {
			Transformer transformer = TransformerFactory.newInstance().newTransformer();
			transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
			transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			StringWriter writer = new StringWriter();
			transformer.transform(new DOMSource(node), new StreamResult(writer));
			return writer.toString();
		} catch (Exception e) {
			throw new IllegalStateException("XML 输出失败：" + e.getMessage(), e);
		}
	}

	public static String localName(Node node) {
		String name = node.getLocalName();
		if (name == null) {
			name = node.getNodeName();
			int idx = name.indexOf(':');
			if (idx > -1) {
				name = name.substring(idx + 1);
			}
		}
		return name;
	}

	public static Element firstChildElement(Element element) {
		NodeList children = element.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
				return (Element) children.item(i);
			}
		}
		return null;
	}

	public static Element findChild(Element element, String localName) {
		NodeList children = element.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (child.getNodeType() == Node.ELEMENT_NODE && localName.equals(localName(child))) {
				return (Element) child;
			}
		}
		return null;
	}

	/**
	 * 生成 XML。value 为 Map 时其键为子元素；只有一个键且 root 为空时以该键为根元素。
	 *
	 * @param root        根元素名，为空时取 value 唯一的键
	 * @param itemName    顶层值是列表时每项的元素名
	 * @param declaration 是否输出 XML 声明
	 */
	public static String build(Object value, String root, String itemName, boolean declaration) {
		StringBuilder sb = new StringBuilder();
		if (declaration) {
			sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
		}
		Object content = value;
		String rootName = root;
		if ((rootName == null || rootName.isEmpty()) && value instanceof Map && ((Map<?, ?>) value).size() == 1) {
			Map.Entry<?, ?> entry = ((Map<?, ?>) value).entrySet().iterator().next();
			rootName = String.valueOf(entry.getKey());
			content = entry.getValue();
		}
		if (rootName == null || rootName.isEmpty()) {
			rootName = "root";
		}
		String item = itemName == null || itemName.isEmpty() ? "item" : itemName;
		if (content instanceof Collection) {
			sb.append('<').append(rootName).append('>');
			for (Object it : (Collection<?>) content) {
				appendElement(sb, item, it);
			}
			sb.append("</").append(rootName).append('>');
		} else {
			appendElement(sb, rootName, content);
		}
		return sb.toString();
	}

	private static void appendElement(StringBuilder sb, String name, Object value) {
		Object v = FlowJson.normalize(value);
		if (v instanceof Collection) {
			for (Object it : (Collection<?>) v) {
				appendElement(sb, name, it);
			}
			return;
		}
		sb.append('<').append(name);
		if (v instanceof Map) {
			Map<?, ?> map = (Map<?, ?>) v;
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				String key = String.valueOf(entry.getKey());
				if (key.startsWith("@")) {
					sb.append(' ').append(key.substring(1)).append("=\"").append(escape(entry.getValue() == null ? "" : String.valueOf(entry.getValue()))).append('"');
				}
			}
			sb.append('>');
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				String key = String.valueOf(entry.getKey());
				if (key.startsWith("@")) {
					continue;
				}
				if ("#text".equals(key)) {
					sb.append(escape(entry.getValue() == null ? "" : String.valueOf(entry.getValue())));
				} else {
					appendElement(sb, key, entry.getValue());
				}
			}
		} else {
			sb.append('>');
			if (v != null) {
				sb.append(escape(plain(v)));
			}
		}
		sb.append("</").append(name).append('>');
	}

	private static String plain(Object v) {
		if (v instanceof java.math.BigDecimal) {
			return ((java.math.BigDecimal) v).toPlainString();
		}
		return String.valueOf(v);
	}

	public static String escape(String text) {
		if (text == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(text.length() + 16);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '<':
					sb.append("&lt;");
					break;
				case '>':
					sb.append("&gt;");
					break;
				case '&':
					sb.append("&amp;");
					break;
				case '"':
					sb.append("&quot;");
					break;
				case '\'':
					sb.append("&apos;");
					break;
				default:
					sb.append(c);
			}
		}
		return sb.toString();
	}

	/**
	 * 解析得到的重复元素列表（与普通 List 区分，便于以后扩展）
	 */
	static class RepeatedList extends ArrayList<Object> {
	}
}
