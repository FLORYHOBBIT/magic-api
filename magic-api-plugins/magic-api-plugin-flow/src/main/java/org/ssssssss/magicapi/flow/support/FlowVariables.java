package org.ssssssss.magicapi.flow.support;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.upload.resource.UploadChannelEncryptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.ssssssss.magicapi.flow.FlowJsonCodes.FLOW_VARIABLE_INVALID;
import static org.ssssssss.magicapi.flow.FlowJsonCodes.FLOW_VARIABLE_MISSING;

/**
 * 差异项：各医院不同的值（对方地址、账号、机构编码等）。
 * <p>
 * 流程节点配置、触发配置、外部连接参数中写 {@code ${var.名称}} 或 {@code ${var.名称:默认值}}，执行时替换。
 * 值保存在本地文件（magic-api.flow.variables-file），不在 magic-api 工作目录中，因此不随配置包导出；
 * 标记为密文的值用上传插件的密钥（magic-api.upload.secret-key）加密保存。
 * <p>
 * 取值顺序：本地文件 → application.yml 中的 magic-api.flow.vars.名称 → 默认值；都没有时报错。
 */
public class FlowVariables {

	private static final Logger logger = LoggerFactory.getLogger(FlowVariables.class);

	public static final Pattern REFERENCE = Pattern.compile("\\$\\{var\\.([\\w\\-.\\u4e00-\\u9fa5]+)(?::([^}]*))?}");

	private static final Pattern NAME = Pattern.compile("[\\w\\-.\\u4e00-\\u9fa5]{1,100}");

	private final Path file;

	private final UploadChannelEncryptor encryptor;

	private final Function<String, String> fallback;

	private final Map<String, Variable> variables = new LinkedHashMap<>();

	/**
	 * @param fallback 本地文件中没有时的取值（application.yml），可为 null
	 */
	public FlowVariables(String file, UploadChannelEncryptor encryptor, Function<String, String> fallback) {
		this.file = file == null || file.trim().isEmpty() ? null : Paths.get(file.trim()).toAbsolutePath().normalize();
		this.encryptor = encryptor;
		this.fallback = fallback;
		load();
	}

	public static void checkName(String name) {
		if (name == null || !NAME.matcher(name).matches()) {
			throw new InvalidArgumentException(FLOW_VARIABLE_INVALID.format(name));
		}
	}

	private synchronized void load() {
		variables.clear();
		if (file == null || !Files.exists(file)) {
			return;
		}
		try {
			List<Variable> list = FlowJson.MAPPER.readValue(Files.readAllBytes(file), new TypeReference<List<Variable>>() {
			});
			for (Variable variable : list) {
				if (variable.getName() != null) {
					variables.put(variable.getName(), variable);
				}
			}
		} catch (IOException e) {
			logger.error("读取差异项文件 {} 失败，差异项按未配置处理", file, e);
		}
	}

	private void store() {
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.write(tmp, FlowJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(new ArrayList<>(variables.values())));
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new IllegalStateException("保存差异项文件 " + file + " 失败：" + e.getMessage(), e);
		}
	}

	public Path getFile() {
		return file;
	}

	/**
	 * 列出全部差异项，密文只返回是否已配置
	 */
	public synchronized List<Map<String, Object>> list() {
		List<Map<String, Object>> result = new ArrayList<>();
		for (Variable variable : variables.values()) {
			result.add(describe(variable));
		}
		return result;
	}

	private Map<String, Object> describe(Variable variable) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("name", variable.getName());
		item.put("description", variable.getDescription());
		item.put("secret", variable.isSecret());
		item.put("value", variable.isSecret() ? null : variable.getValue());
		item.put("configured", variable.getValue() != null && !variable.getValue().isEmpty());
		return item;
	}

	public synchronized Variable find(String name) {
		Variable variable = variables.get(name);
		return variable == null ? null : variable.copy();
	}

	/**
	 * 保存差异项。value 为 null 且已存在时保留原值（界面不回显密文，只改说明时不清空）
	 */
	public synchronized void save(String name, String value, boolean secret, String description) {
		checkName(name);
		Variable variable = variables.get(name);
		if (variable == null) {
			variable = new Variable();
			variable.setName(name);
		}
		if (value != null) {
			variable.setValue(secret && encryptor != null ? encryptor.encrypt(value) : value);
		} else if (variable.isSecret() != secret && variable.getValue() != null && encryptor != null) {
			String plain = encryptor.decrypt(variable.getValue());
			variable.setValue(secret ? encryptor.encrypt(plain) : plain);
		}
		variable.setSecret(secret);
		variable.setDescription(description);
		variables.put(name, variable);
		store();
	}

	public synchronized void remove(String name) {
		if (variables.remove(name) != null) {
			store();
		}
	}

	/**
	 * 取值，没有时返回 null
	 */
	public String value(String name) {
		Variable variable;
		synchronized (this) {
			variable = variables.get(name);
		}
		if (variable != null && variable.getValue() != null) {
			return variable.isSecret() && encryptor != null ? encryptor.decrypt(variable.getValue()) : variable.getValue();
		}
		return fallback == null ? null : fallback.apply(name);
	}

	/**
	 * 替换文本中的 ${var.名称}
	 */
	public String resolve(String text) {
		if (text == null || text.indexOf("${var.") < 0) {
			return text;
		}
		Matcher matcher = REFERENCE.matcher(text);
		StringBuffer sb = new StringBuffer();
		while (matcher.find()) {
			String name = matcher.group(1);
			String value = value(name);
			if (value == null) {
				value = matcher.group(2);
			}
			if (value == null) {
				throw new InvalidArgumentException(FLOW_VARIABLE_MISSING.format(name));
			}
			matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(sb);
		return sb.toString();
	}

	/**
	 * 递归替换 Map、List 中所有字符串值，返回副本
	 */
	@SuppressWarnings("unchecked")
	public <T> T resolveDeep(T value) {
		if (value instanceof String) {
			return (T) resolve((String) value);
		}
		if (value instanceof Map) {
			Map<Object, Object> copy = new LinkedHashMap<>();
			((Map<?, ?>) value).forEach((k, v) -> copy.put(k, resolveDeep(v)));
			return (T) copy;
		}
		if (value instanceof List) {
			List<Object> copy = new ArrayList<>();
			for (Object it : (List<?>) value) {
				copy.add(resolveDeep(it));
			}
			return (T) copy;
		}
		return value;
	}

	/**
	 * 找出文本中引用的差异项名称
	 */
	public static Set<String> references(String text) {
		Set<String> names = new LinkedHashSet<>();
		if (text != null) {
			Matcher matcher = REFERENCE.matcher(text);
			while (matcher.find()) {
				names.add(matcher.group(1));
			}
		}
		return names;
	}

	public static class Variable {

		private String name;

		private String value;

		private boolean secret;

		private String description;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getValue() {
			return value;
		}

		public void setValue(String value) {
			this.value = value;
		}

		public boolean isSecret() {
			return secret;
		}

		public void setSecret(boolean secret) {
			this.secret = secret;
		}

		public String getDescription() {
			return description;
		}

		public void setDescription(String description) {
			this.description = description;
		}

		Variable copy() {
			Variable copy = new Variable();
			copy.name = name;
			copy.value = value;
			copy.secret = secret;
			copy.description = description;
			return copy;
		}
	}
}
