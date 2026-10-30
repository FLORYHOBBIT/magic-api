package org.ssssssss.magicapi.flow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.magicapi.backup.service.MagicBackupService;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.model.Group;
import org.ssssssss.magicapi.core.model.TreeNode;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.flow.engine.node.CallNodeHandler;
import org.ssssssss.magicapi.flow.model.FlowDefinition;
import org.ssssssss.magicapi.flow.model.FlowDefinitions;
import org.ssssssss.magicapi.flow.model.FlowInfo;
import org.ssssssss.magicapi.flow.support.FlowJson;
import org.ssssssss.magicapi.flow.support.FlowVariables;
import org.ssssssss.magicapi.upload.resource.UploadChannelEncryptor;
import org.ssssssss.magicapi.upload.resource.UploadChannelInfo;
import org.ssssssss.magicapi.upload.resource.UploadChannelMagicResourceStorage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.ssssssss.magicapi.flow.FlowJsonCodes.FLOW_PACKAGE_INVALID;

/**
 * 配置包：把选中的流程及其引用的外部连接打成 zip，在另一家医院导入。
 * <p>
 * <ul>
 *     <li>外部连接中的密码、密钥不导出，替换为差异项 ${var.连接名.参数名}，导入时填写</li>
 *     <li>流程和连接中引用的差异项列在清单中，导入时逐项填写（已配置的可保留）</li>
 *     <li>导入前自动全量备份工作目录（magic-api.flow.backup-dir，开启了 magic-api 备份时同时写一份备份记录）</li>
 *     <li>同 id 或同路径的流程、同名的连接直接覆盖，其余新建；分组按路径查找，没有则创建</li>
 * </ul>
 */
public class FlowPackageService {

	private static final Logger logger = LoggerFactory.getLogger(FlowPackageService.class);

	public static final String FORMAT = "magic-flow-package";

	private static final String MANIFEST = "manifest.json";

	private static final int MAX_ENTRY = 50 * 1024 * 1024;

	/**
	 * 外部连接中各医院不同的参数，导出时替换为差异项
	 */
	static final List<String> CONNECTION_KEYS = Collections.unmodifiableList(Arrays.asList("url", "host", "port", "username", "dir"));

	private final Supplier<MagicResourceService> resourceService;

	private final FlowInfoMagicResourceStorage flowStorage;

	private final Supplier<UploadChannelMagicResourceStorage> channelStorage;

	private final FlowVariables variables;

	private final Supplier<MagicBackupService> backupService;

	private final Path backupDir;

	public FlowPackageService(Supplier<MagicResourceService> resourceService, FlowInfoMagicResourceStorage flowStorage,
							  Supplier<UploadChannelMagicResourceStorage> channelStorage, FlowVariables variables,
							  Supplier<MagicBackupService> backupService, String backupDir) {
		this.resourceService = resourceService;
		this.flowStorage = flowStorage;
		this.channelStorage = channelStorage;
		this.variables = variables;
		this.backupService = backupService;
		this.backupDir = Paths.get(StringUtils.defaultIfBlank(backupDir, "./magic-flow/backups")).toAbsolutePath().normalize();
	}

	// ------------------------------------------------------------------ 导出

	/**
	 * @param ids 流程 id；为空时导出全部流程
	 */
	public byte[] export(Collection<String> ids) throws IOException {
		MagicResourceService service = resourceService.get();
		List<FlowInfo> flows = new ArrayList<>();
		for (FlowInfo info : service.<FlowInfo>files(FlowInfoMagicResourceStorage.FOLDER)) {
			if (ids == null || ids.isEmpty() || ids.contains(info.getId())) {
				flows.add(service.file(info.getId()));
			}
		}
		if (flows.isEmpty()) {
			throw new InvalidArgumentException(FLOW_PACKAGE_INVALID.format("没有可导出的流程"));
		}
		Set<String> channelNames = new LinkedHashSet<>();
		for (FlowInfo flow : flows) {
			channelNames.addAll(channelsOf(flow));
		}
		List<UploadChannelInfo> channels = new ArrayList<>();
		for (UploadChannelInfo channel : service.<UploadChannelInfo>files(UploadChannelMagicResourceStorage.FOLDER)) {
			if (channelNames.contains(channel.getName())) {
				channels.add(service.file(channel.getId()));
			}
		}
		Map<String, Map<String, Object>> declared = new LinkedHashMap<>();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("format", FORMAT);
		manifest.put("version", 1);
		manifest.put("exportedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
		List<Map<String, Object>> flowItems = new ArrayList<>();
		List<Map<String, Object>> channelItems = new ArrayList<>();
		try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
			for (FlowInfo flow : flows) {
				String file = "flows/" + flow.getId() + ".ms";
				put(zip, file, flowStorage.write(flow));
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("id", flow.getId());
				item.put("name", flow.getName());
				item.put("path", flow.getPath());
				item.put("groups", groups(service, flow.getGroupId()));
				item.put("file", file);
				flowItems.add(item);
				for (String name : FlowVariables.references(flow.getScript())) {
					declare(declared, name, false, "流程「" + flow.getName() + "」");
				}
			}
			for (UploadChannelInfo channel : channels) {
				Map<String, Object> options = new LinkedHashMap<>(channel.getOptions());
				for (String key : CONNECTION_KEYS) {
					Object value = options.get(key);
					if (value != null && !String.valueOf(value).isEmpty() && !FlowVariables.REFERENCE.matcher(String.valueOf(value)).matches()) {
						String name = channel.getName() + "." + key;
						options.put(key, "${var." + name + "}");
						Map<String, Object> variable = declare(declared, name, false, "外部连接「" + channel.getName() + "」的 " + key);
						variable.put("example", String.valueOf(value));
					}
				}
				for (String key : UploadChannelEncryptor.SENSITIVE_KEYS) {
					Object value = options.get(key);
					if (value instanceof String && !((String) value).isEmpty() && !FlowVariables.REFERENCE.matcher((String) value).matches()) {
						String name = channel.getName() + "." + key;
						options.put(key, "${var." + name + "}");
						declare(declared, name, true, "外部连接「" + channel.getName() + "」的 " + key);
					}
				}
				for (String name : FlowVariables.references(FlowJson.stringify(options))) {
					declare(declared, name, false, "外部连接「" + channel.getName() + "」");
				}
				Map<String, Object> content = new LinkedHashMap<>();
				content.put("id", channel.getId());
				content.put("name", channel.getName());
				content.put("protocol", channel.getProtocol());
				content.put("description", channel.getDescription());
				content.put("options", options);
				content.put("script", channel.getScript());
				String file = "channels/" + channel.getId() + ".json";
				put(zip, file, FlowJson.pretty(content).getBytes(StandardCharsets.UTF_8));
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("id", channel.getId());
				item.put("name", channel.getName());
				item.put("groups", groups(service, channel.getGroupId()));
				item.put("file", file);
				channelItems.add(item);
			}
			for (Map<String, Object> variable : declared.values()) {
				FlowVariables.Variable local = variables.find((String) variable.get("name"));
				if (local != null) {
					variable.put("secret", local.isSecret() || Boolean.TRUE.equals(variable.get("secret")));
					if (StringUtils.isNotBlank(local.getDescription())) {
						variable.put("description", local.getDescription());
					}
					if (!Boolean.TRUE.equals(variable.get("secret"))) {
						variable.put("example", local.getValue());
					}
				}
			}
			manifest.put("flows", flowItems);
			manifest.put("channels", channelItems);
			manifest.put("variables", new ArrayList<>(declared.values()));
			put(zip, MANIFEST, FlowJson.pretty(manifest).getBytes(StandardCharsets.UTF_8));
		}
		return bytes.toByteArray();
	}

	private static Map<String, Object> declare(Map<String, Map<String, Object>> declared, String name, boolean secret, String usedBy) {
		Map<String, Object> variable = declared.computeIfAbsent(name, k -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("name", k);
			item.put("description", null);
			item.put("secret", false);
			item.put("usedBy", new ArrayList<String>());
			return item;
		});
		if (secret) {
			variable.put("secret", true);
		}
		@SuppressWarnings("unchecked")
		List<String> used = (List<String>) variable.get("usedBy");
		if (!used.contains(usedBy)) {
			used.add(usedBy);
		}
		return variable;
	}

	private static Set<String> channelsOf(FlowInfo flow) {
		Set<String> names = new LinkedHashSet<>();
		try {
			FlowDefinition definition = FlowDefinitions.parse(flow.getScript());
			for (FlowDefinition.Node node : definition.getNodes()) {
				Object channel = node.getConfig().get("channel");
				if (CallNodeHandler.TYPE.equals(node.getType()) && channel != null && StringUtils.isNotBlank(channel.toString())) {
					names.add(channel.toString().trim());
				}
			}
		} catch (RuntimeException e) {
			logger.warn("流程[{}]定义解析失败，跳过外部连接收集", flow.getName());
		}
		return names;
	}

	private static List<Map<String, Object>> groups(MagicResourceService service, String groupId) {
		LinkedList<Map<String, Object>> chain = new LinkedList<>();
		String id = groupId;
		int guard = 0;
		while (id != null && !"0".equals(id) && guard++ < 50) {
			Group group = service.getGroup(id);
			if (group == null) {
				break;
			}
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("name", group.getName());
			item.put("path", group.getPath());
			chain.addFirst(item);
			id = group.getParentId();
		}
		return chain;
	}

	private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content);
		zip.closeEntry();
	}

	// ------------------------------------------------------------------ 导入

	/**
	 * 预览：列出包内的流程、连接（新建还是覆盖）和需要填写的差异项
	 */
	public Map<String, Object> preview(byte[] content) throws IOException {
		Package pkg = read(content);
		MagicResourceService service = resourceService.get();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("exportedAt", pkg.manifest.get("exportedAt"));
		List<Map<String, Object>> flows = new ArrayList<>();
		for (Map<String, Object> item : pkg.list("flows")) {
			FlowInfo info = flowStorage.read(pkg.file(item));
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", info.getName());
			row.put("path", groupPath(item) + "/" + info.getPath());
			row.put("action", findFlow(service, info, item) == null ? "create" : "overwrite");
			flows.add(row);
		}
		result.put("flows", flows);
		List<Map<String, Object>> channels = new ArrayList<>();
		for (Map<String, Object> item : pkg.list("channels")) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", item.get("name"));
			row.put("action", findChannel(service, (String) item.get("id"), (String) item.get("name")) == null ? "create" : "overwrite");
			channels.add(row);
		}
		result.put("channels", channels);
		List<Map<String, Object>> vars = new ArrayList<>();
		for (Map<String, Object> variable : pkg.list("variables")) {
			Map<String, Object> row = new LinkedHashMap<>(variable);
			String name = (String) variable.get("name");
			FlowVariables.Variable local = variables.find(name);
			boolean secret = Boolean.TRUE.equals(variable.get("secret"));
			row.put("configured", variables.value(name) != null);
			row.put("current", local == null || secret ? null : local.getValue());
			vars.add(row);
		}
		result.put("variables", vars);
		return result;
	}

	/**
	 * 导入。values 为差异项的值（名称 → 值），未提供且本地已配置的保留原值；仍未配置的差异项会导致导入失败。
	 */
	public Map<String, Object> importPackage(byte[] content, Map<String, String> values, String operator) throws IOException {
		Package pkg = read(content);
		Map<String, String> provided = values == null ? Collections.<String, String>emptyMap() : values;
		List<String> missing = new ArrayList<>();
		for (Map<String, Object> variable : pkg.list("variables")) {
			String name = (String) variable.get("name");
			String value = provided.get(name);
			if ((value == null || value.isEmpty()) && variables.value(name) == null) {
				missing.add(name);
			}
		}
		if (!missing.isEmpty()) {
			throw new InvalidArgumentException(FLOW_PACKAGE_INVALID.format("以下差异项还没有填写：" + String.join("、", missing)));
		}
		String backup = backup(operator);
		for (Map<String, Object> variable : pkg.list("variables")) {
			String name = (String) variable.get("name");
			String value = provided.get(name);
			if (value != null && !value.isEmpty()) {
				FlowVariables.Variable local = variables.find(name);
				String description = local != null && StringUtils.isNotBlank(local.getDescription()) ? local.getDescription() : (String) variable.get("description");
				variables.save(name, value, Boolean.TRUE.equals(variable.get("secret")), description);
			}
		}
		MagicResourceService service = resourceService.get();
		List<Map<String, Object>> channelResults = new ArrayList<>();
		for (Map<String, Object> item : pkg.list("channels")) {
			Map<String, Object> data = FlowJson.MAPPER.readValue(pkg.file(item), new TypeReference<LinkedHashMap<String, Object>>() {
			});
			UploadChannelInfo info = new UploadChannelInfo();
			info.setName((String) data.get("name"));
			info.setProtocol((String) data.get("protocol"));
			info.setDescription((String) data.get("description"));
			info.setScript(StringUtils.defaultIfBlank((String) data.get("script"), "return rows"));
			@SuppressWarnings("unchecked")
			Map<String, Object> options = data.get("options") instanceof Map ? new LinkedHashMap<>((Map<String, Object>) data.get("options")) : new LinkedHashMap<>();
			// 连接参数中的差异项在导入时替换为本院的值（密码、密钥按本院密钥加密保存）
			info.setOptions(variables.resolveDeep(options));
			UploadChannelInfo existing = findChannel(service, (String) data.get("id"), info.getName());
			info.setId(existing == null ? (String) data.get("id") : existing.getId());
			info.setGroupId(ensureGroups(service, pkg.groups(item), UploadChannelMagicResourceStorage.FOLDER));
			service.saveFile(info);
			channelResults.add(result(info.getName(), existing == null ? "create" : "overwrite"));
		}
		List<Map<String, Object>> flowResults = new ArrayList<>();
		for (Map<String, Object> item : pkg.list("flows")) {
			FlowInfo info = flowStorage.read(pkg.file(item));
			FlowInfo existing = findFlow(service, info, item);
			info.setGroupId(ensureGroups(service, pkg.groups(item), FlowInfoMagicResourceStorage.FOLDER));
			if (existing != null) {
				info.setId(existing.getId());
			}
			service.saveFile(info);
			flowResults.add(result(info.getName(), existing == null ? "create" : "overwrite"));
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("backup", backup);
		result.put("channels", channelResults);
		result.put("flows", flowResults);
		logger.info("导入配置包：{} 个流程、{} 个外部连接，导入前备份 {}", flowResults.size(), channelResults.size(), backup);
		return result;
	}

	private static Map<String, Object> result(String name, String action) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("name", name);
		item.put("action", action);
		return item;
	}

	private String backup(String operator) throws IOException {
		MagicBackupService backup = backupService.get();
		if (backup != null) {
			backup.doBackupAll("导入集成配置包前，系统自动全量备份", operator);
		}
		Files.createDirectories(backupDir);
		Path file = backupDir.resolve("before-import-" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + ".zip");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		resourceService.get().export(null, null, out);
		Files.write(file, out.toByteArray());
		return file.toString();
	}

	private FlowInfo findFlow(MagicResourceService service, FlowInfo info, Map<String, Object> item) {
		if (info.getId() != null) {
			Object existing = service.file(info.getId());
			if (existing instanceof FlowInfo) {
				return (FlowInfo) existing;
			}
		}
		String targetPath = normalize(groupPath(item) + "/" + info.getPath());
		for (FlowInfo flow : service.<FlowInfo>files(FlowInfoMagicResourceStorage.FOLDER)) {
			String path = normalize(StringUtils.defaultString(service.getGroupPath(flow.getGroupId())) + "/" + flow.getPath());
			if (path.equals(targetPath)) {
				return flow;
			}
		}
		return null;
	}

	private static UploadChannelInfo findChannel(MagicResourceService service, String id, String name) {
		for (UploadChannelInfo channel : service.<UploadChannelInfo>files(UploadChannelMagicResourceStorage.FOLDER)) {
			if (Objects.equals(channel.getId(), id) || Objects.equals(channel.getName(), name)) {
				return channel;
			}
		}
		return null;
	}

	private static String groupPath(Map<String, Object> item) {
		StringBuilder sb = new StringBuilder();
		Object groups = item.get("groups");
		if (groups instanceof List) {
			for (Object group : (List<?>) groups) {
				if (group instanceof Map && ((Map<?, ?>) group).get("path") != null) {
					sb.append('/').append(((Map<?, ?>) group).get("path"));
				}
			}
		}
		return sb.toString();
	}

	private static String normalize(String path) {
		return ("/" + path).replaceAll("/+", "/").replaceAll("/$", "");
	}

	/**
	 * 按名称与路径逐级查找分组，没有则创建，返回最后一级分组 id
	 */
	private static String ensureGroups(MagicResourceService service, List<Map<String, Object>> chain, String type) {
		String parentId = "0";
		for (Map<String, Object> item : chain) {
			String name = Objects.toString(item.get("name"), "");
			String path = Objects.toString(item.get("path"), "");
			Group found = null;
			for (Group group : allGroups(service, type)) {
				if (Objects.equals(group.getParentId(), parentId) && Objects.equals(StringUtils.defaultString(group.getPath()), path)
						&& (StringUtils.isNotBlank(path) || Objects.equals(group.getName(), name))) {
					found = group;
					break;
				}
			}
			if (found == null) {
				found = new Group();
				found.setName(name);
				found.setPath(path);
				found.setType(type);
				found.setParentId(parentId);
				service.saveGroup(found);
			}
			parentId = found.getId();
		}
		return parentId;
	}

	private static List<Group> allGroups(MagicResourceService service, String type) {
		List<Group> result = new ArrayList<>();
		TreeNode<Group> root = service.tree(type);
		if (root != null) {
			collect(root, result);
		}
		return result;
	}

	private static void collect(TreeNode<Group> node, List<Group> result) {
		if (node.getNode() != null && node.getNode().getId() != null) {
			result.add(node.getNode());
		}
		if (node.getChildren() != null) {
			for (TreeNode<Group> child : node.getChildren()) {
				collect(child, result);
			}
		}
	}

	private Package read(byte[] content) throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.isDirectory()) {
					continue;
				}
				String name = entry.getName();
				if (name.contains("..") || name.startsWith("/") || name.contains("\\")) {
					throw new InvalidArgumentException(FLOW_PACKAGE_INVALID.format("包内文件名不合法：" + name));
				}
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buffer = new byte[8192];
				int len;
				while ((len = zip.read(buffer)) != -1) {
					out.write(buffer, 0, len);
					if (out.size() > MAX_ENTRY) {
						throw new InvalidArgumentException(FLOW_PACKAGE_INVALID.format("包内文件过大：" + name));
					}
				}
				entries.put(name, out.toByteArray());
			}
		} catch (IllegalArgumentException e) {
			throw new InvalidArgumentException(FLOW_PACKAGE_INVALID.format("不是 zip 文件"));
		}
		byte[] manifest = entries.get(MANIFEST);
		if (manifest == null) {
			throw new InvalidArgumentException(FLOW_PACKAGE_INVALID.format("缺少 manifest.json，不是集成配置包（magic-api 的全量导出请用编辑器的「上传」）"));
		}
		Map<String, Object> data;
		try {
			data = FlowJson.MAPPER.readValue(manifest, new TypeReference<LinkedHashMap<String, Object>>() {
			});
		} catch (IOException e) {
			throw new InvalidArgumentException(FLOW_PACKAGE_INVALID.format("manifest.json 格式错误"));
		}
		if (!FORMAT.equals(data.get("format"))) {
			throw new InvalidArgumentException(FLOW_PACKAGE_INVALID.format("manifest.json 的 format 不是 " + FORMAT));
		}
		return new Package(data, entries);
	}

	private static final class Package {

		final Map<String, Object> manifest;

		final Map<String, byte[]> entries;

		Package(Map<String, Object> manifest, Map<String, byte[]> entries) {
			this.manifest = manifest;
			this.entries = entries;
		}

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> list(String key) {
			Object value = manifest.get(key);
			return value instanceof List ? (List<Map<String, Object>>) value : Collections.<Map<String, Object>>emptyList();
		}

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> groups(Map<String, Object> item) {
			Object value = item.get("groups");
			return value instanceof List ? (List<Map<String, Object>>) value : Collections.<Map<String, Object>>emptyList();
		}

		byte[] file(Map<String, Object> item) {
			byte[] bytes = entries.get(String.valueOf(item.get("file")));
			if (bytes == null) {
				throw new InvalidArgumentException(FLOW_PACKAGE_INVALID.format("缺少文件 " + item.get("file")));
			}
			return bytes;
		}
	}
}
