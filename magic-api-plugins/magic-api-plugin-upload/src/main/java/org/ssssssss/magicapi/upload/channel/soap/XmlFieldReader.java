package org.ssssssss.magicapi.upload.channel.soap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.function.Function;

/**
 * 按路径读取 XML 字段
 * <p>
 * 路径用 "/" 或 "." 分隔元素名（不含命名空间前缀），第一段可以出现在任意层级，例如 "return/code" 匹配
 * &lt;ns:uploadResponse&gt;&lt;return&gt;&lt;code&gt;0&lt;/code&gt;&lt;/return&gt;&lt;/ns:uploadResponse&gt;。
 * <p>
 * 很多接口把结果作为字符串放在节点里，可用 "#" 继续读取：
 * "return#code" 表示取 &lt;return&gt; 的文本，再按 JSON 读取 code（文本以 "&lt;" 开头时按 XML 读取）。
 */
public class XmlFieldReader implements Function<String, String> {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final Element root;

	public XmlFieldReader(String xml) {
		this.root = parse(xml);
	}

	public boolean isXml() {
		return root != null;
	}

	public Element getRoot() {
		return root;
	}

	@Override
	public String apply(String path) {
		if (path == null || root == null) {
			return null;
		}
		int hash = path.indexOf('#');
		String xmlPath = hash < 0 ? path : path.substring(0, hash);
		String text = read(root, xmlPath);
		if (hash < 0 || text == null) {
			return text;
		}
		String inner = path.substring(hash + 1);
		String trimmed = text.trim();
		if (trimmed.startsWith("<")) {
			return new XmlFieldReader(trimmed).apply(inner);
		}
		return readJson(trimmed, inner);
	}

	static String read(Element root, String path) {
		String[] parts = path.trim().split("[/.]");
		Element current = null;
		for (int i = 0; i < parts.length; i++) {
			String name = parts[i].trim();
			if (name.isEmpty()) {
				continue;
			}
			current = current == null ? findDescendant(root, name) : findChild(current, name);
			if (current == null) {
				return null;
			}
		}
		return current == null ? null : current.getTextContent();
	}

	private static Element findDescendant(Element element, String name) {
		if (name.equals(localName(element))) {
			return element;
		}
		NodeList children = element.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (child instanceof Element) {
				Element found = findDescendant((Element) child, name);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	static Element findChild(Element element, String name) {
		NodeList children = element.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node child = children.item(i);
			if (child instanceof Element && name.equals(localName((Element) child))) {
				return (Element) child;
			}
		}
		return null;
	}

	static String localName(Element element) {
		String name = element.getLocalName();
		if (name == null) {
			name = element.getTagName();
			int colon = name.indexOf(':');
			if (colon >= 0) {
				name = name.substring(colon + 1);
			}
		}
		return name;
	}

	private static String readJson(String json, String path) {
		try {
			JsonNode node = MAPPER.readTree(json);
			for (String part : path.split("\\.")) {
				if (node == null) {
					return null;
				}
				node = node.isArray() ? node.get(Integer.parseInt(part)) : node.get(part);
			}
			if (node == null || node.isNull()) {
				return null;
			}
			return node.isValueNode() ? node.asText() : node.toString();
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * 解析 XML，禁用 DTD 与外部实体（防止 XXE）
	 */
	static Element parse(String xml) {
		if (xml == null || xml.trim().isEmpty() || !xml.trim().startsWith("<")) {
			return null;
		}
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			DocumentBuilder builder = factory.newDocumentBuilder();
			builder.setErrorHandler(null);
			Document document = builder.parse(new InputSource(new StringReader(xml.trim())));
			return document.getDocumentElement();
		} catch (Exception e) {
			return null;
		}
	}
}
