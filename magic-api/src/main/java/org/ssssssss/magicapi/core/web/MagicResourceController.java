package org.ssssssss.magicapi.core.web;

import org.springframework.web.bind.annotation.*;
import org.ssssssss.magicapi.core.config.Constants;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.context.MagicUser;
import org.ssssssss.magicapi.core.exception.MagicLoginException;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.interceptor.Authorization;
import org.ssssssss.magicapi.core.model.*;
import org.ssssssss.magicapi.core.resource.FileResource;
import org.ssssssss.magicapi.core.resource.Resource;
import org.ssssssss.magicapi.core.service.MagicDynamicRegistry;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletRequest;
import org.ssssssss.magicapi.datasource.model.DataSourceInfo;
import org.ssssssss.magicapi.utils.IoUtils;
import org.ssssssss.magicapi.utils.ROT13Utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

public class MagicResourceController extends MagicController implements MagicExceptionHandler {

	private final MagicResourceService service;

	public MagicResourceController(MagicConfiguration configuration) {
		super(configuration);
		this.service = MagicConfiguration.getMagicResourceService();
	}

	@PostMapping("/resource/folder/save")
	@ResponseBody
	public JsonBean<String> saveFolder(@RequestBody Group group, MagicHttpServletRequest request) {
		isTrue(allowVisit(request, Authorization.SAVE, group), PERMISSION_INVALID);
		Resource resource = service.getResource();
		if(resource instanceof FileResource){
			isTrue(resource.exists(), FILE_PATH_NOT_EXISTS);
		}
		if (service.saveGroup(group)) {
			return new JsonBean<>(group.getId());
		}
		return new JsonBean<>((String) null);
	}

	@PostMapping("/resource/folder/copy")
	@ResponseBody
	public JsonBean<String> saveFolder(String src, String target, MagicHttpServletRequest request) {
		Group srcGroup = service.getGroup(src);
		notNull(srcGroup, GROUP_NOT_FOUND);
		isTrue(allowVisit(request, Authorization.VIEW, srcGroup), PERMISSION_INVALID);
		Group targetGroup = srcGroup.copy();
		targetGroup.setId(null);
		targetGroup.setParentId(target);
		targetGroup.setType(srcGroup.getType());
		isTrue(allowVisit(request, Authorization.SAVE, targetGroup), PERMISSION_INVALID);
		return new JsonBean<>(service.copyGroup(src, target));
	}

	@PostMapping("/resource/delete")
	@ResponseBody
	public JsonBean<Boolean> delete(String id, MagicHttpServletRequest request) {
		Group group = service.getGroup(id);
		if(group == null){
			MagicEntity entity = service.file(id);
			notNull(entity, FILE_NOT_FOUND);
			isTrue(allowVisit(request, Authorization.DELETE, entity), PERMISSION_INVALID);
		} else {
			isTrue(allowVisit(request, Authorization.DELETE, group), PERMISSION_INVALID);
		}
		return new JsonBean<>(service.delete(id));
	}

	@PostMapping("/resource/file/{folder}/save")
	@ResponseBody
	public JsonBean<String> saveFile(@PathVariable("folder") String folder, String auto, MagicHttpServletRequest request) throws IOException {
		byte[] bytes = IoUtils.bytes(request.getInputStream());
		String encrypt = new String(bytes, StandardCharsets.UTF_8);
		String decrypt = ROT13Utils.decrypt(encrypt);
		MagicEntity entity = configuration.getMagicDynamicRegistries().stream()
				.map(MagicDynamicRegistry::getMagicResourceStorage)
				.filter(it -> Objects.equals(it.folder(), folder))
				.findFirst()
				.orElseThrow(() -> new InvalidArgumentException(GROUP_NOT_FOUND))
				.read(decrypt.getBytes(StandardCharsets.UTF_8));
		isTrue(allowVisit(request, Authorization.SAVE, entity), PERMISSION_INVALID);
		// 自动保存的代码，和旧版代码对比，如果一致，则不保存，直接返回。
		if (entity ==null){
			return new JsonBean<>(null);
		}
		if (entity instanceof DataSourceInfo && entity.getId() != null) {
			MagicEntity existing = service.file(entity.getId());
			notNull(existing, FILE_NOT_FOUND);
			isTrue(existing instanceof DataSourceInfo && allowVisit(request, Authorization.SAVE, existing), PERMISSION_INVALID);
			DataSourceInfo dataSource = (DataSourceInfo) entity;
			if ("".equals(dataSource.getPassword())) {
				dataSource.setPassword(null);
			}
		}
		if (!(entity instanceof DataSourceInfo) && entity.getId() != null && "1".equals(auto)) {
			MagicEntity oldInfo = service.file(entity.getId());
			if(oldInfo != null && Objects.equals(oldInfo, entity)){
				return new JsonBean<>(entity.getId());
			}
		}
		if (MagicConfiguration.getMagicResourceService().saveFile(entity)) {
			return new JsonBean<>(entity.getId());
		}
		return new JsonBean<>(null);
	}

	@GetMapping("/resource/file/{id}")
	@ResponseBody
	public JsonBean<MagicEntity> detail(@PathVariable("id") String id, MagicHttpServletRequest request) {
		MagicEntity entity = MagicConfiguration.getMagicResourceService().file(id);
		isTrue(allowVisit(request, Authorization.VIEW, entity), PERMISSION_INVALID);
		if (entity instanceof DataSourceInfo) {
			DataSourceInfo detail = ((DataSourceInfo) entity).withoutPassword();
			detail.setDriverClassName(detail.jdbcDriverClassName());
			entity = detail;
		}
		return new JsonBean<>(entity);
	}

	@PostMapping("/resource/move")
	@ResponseBody
	public JsonBean<Boolean> move(String src, String groupId, MagicHttpServletRequest request) {
		Group group = service.getGroup(src);
		if(group == null){
			MagicEntity entity = service.file(src);
			notNull(entity, FILE_NOT_FOUND);
			entity = entity.copy();
			entity.setGroupId(groupId);
			isTrue(allowVisit(request, Authorization.SAVE, entity), PERMISSION_INVALID);
		} else {
			group = group.copy();
			group.setParentId(groupId);
			isTrue(allowVisit(request, Authorization.DELETE, group), PERMISSION_INVALID);
		}
		return new JsonBean<>(service.move(src, groupId));
	}

	@PostMapping("/resource/lock")
	@ResponseBody
	public JsonBean<Boolean> lock(String id, @RequestParam(value = "password", required = false) String password, MagicHttpServletRequest request) {
		MagicEntity entity = service.file(id);
		notNull(entity, FILE_NOT_FOUND);
		isTrue(allowVisit(request, Authorization.LOCK, entity), PERMISSION_INVALID);
		confirmLoginPassword(request, password);
		return new JsonBean<>(service.lock(id));
	}

	@PostMapping("/resource/unlock")
	@ResponseBody
	public JsonBean<Boolean> unlock(String id, @RequestParam(value = "password", required = false) String password, MagicHttpServletRequest request) {
		MagicEntity entity = service.file(id);
		notNull(entity, FILE_NOT_FOUND);
		isTrue(allowVisit(request, Authorization.UNLOCK, entity), PERMISSION_INVALID);
		confirmLoginPassword(request, password);
		return new JsonBean<>(service.unlock(id));
	}

	@PostMapping("/resource/folder/lock")
	@ResponseBody
	public JsonBean<Map<String, Object>> lockFolder(String id, @RequestParam(value = "password", required = false) String password, MagicHttpServletRequest request) {
		return changeFolderLock(id, password, true, request);
	}

	@PostMapping("/resource/folder/unlock")
	@ResponseBody
	public JsonBean<Map<String, Object>> unlockFolder(String id, @RequestParam(value = "password", required = false) String password, MagicHttpServletRequest request) {
		return changeFolderLock(id, password, false, request);
	}

	private JsonBean<Map<String, Object>> changeFolderLock(String id, String password, boolean locked, MagicHttpServletRequest request) {
		Group group = service.getGroup(id);
		notNull(group, GROUP_NOT_FOUND);
		Authorization action = locked ? Authorization.LOCK : Authorization.UNLOCK;
		isTrue(allowVisit(request, action, group), PERMISSION_INVALID);
		confirmLoginPassword(request, password);
		return new JsonBean<>(service.lockGroup(id, locked, entity -> allowVisit(request, action, entity)));
	}

	private void confirmLoginPassword(MagicHttpServletRequest request, String password) {
		MagicUser current = (MagicUser) request.getAttribute(Constants.ATTRIBUTE_MAGIC_USER);
		isTrue(configuration.getAuthorizationInterceptor().requireLogin() && current != null
				&& current.getUsername() != null, LOCK_LOGIN_REQUIRED);
		isTrue(password != null && !password.isEmpty(), LOCK_PASSWORD_REQUIRED);
		MagicUser verified;
		try {
			verified = configuration.getAuthorizationInterceptor().login(current.getUsername(), password);
		} catch (MagicLoginException ignored) {
			throw new InvalidArgumentException(LOCK_PASSWORD_INVALID);
		}
		isTrue(verified != null && Objects.equals(current.getUsername(), verified.getUsername())
				&& Objects.equals(current.getId(), verified.getId()), LOCK_PASSWORD_INVALID);
	}

	@PostMapping("/resource")
	@ResponseBody
	public JsonBean<Map<String, TreeNode<Attributes<Object>>>> resources(MagicHttpServletRequest request) {
		Map<String, TreeNode<Group>> tree = service.tree();
		Map<String, TreeNode<Attributes<Object>>> result = new HashMap<>();
		tree.forEach((key, value) -> {
			TreeNode<Attributes<Object>> node = process(value, request);
			List<TreeNode<Attributes<Object>>> groups = node.getChildren();
			if(groups.size() > 0){
				List<TreeNode<Attributes<Object>>> nodes = groups.get(0).getChildren();
				configuration.getMagicDynamicRegistries().stream()
						.filter(it -> it.getMagicResourceStorage().folder().equals(key))
						.findFirst()
						.map(MagicDynamicRegistry::defaultMappings)
						.ifPresent(mappings -> {
							for (MagicEntity mapping : mappings) {
								nodes.add(new TreeNode<>(mapping));
							}
						});
			}
			result.put(key, node);
		});
		return new JsonBean<>(result);
	}

	private TreeNode<Attributes<Object>> process(TreeNode<Group> groupNode, MagicHttpServletRequest request) {
		TreeNode<Attributes<Object>> value = new TreeNode<>();
		value.setNode(groupNode.getNode());
		groupNode.getChildren().stream()
				.filter(it -> allowVisit(request, Authorization.VIEW, it.getNode()))
				.map(it -> process(it, request))
				.forEach(value::addChild);
		if (!Constants.ROOT_ID.equals(groupNode.getNode().getId())) {
			service
					.listFiles(groupNode.getNode().getId())
					.stream()
					.filter(it -> allowVisit(request, Authorization.VIEW, it))
					.map(MagicEntity::simple)
					.sorted(Comparator.comparing(MagicEntity::getName))
					.map((Function<MagicEntity, TreeNode>) TreeNode::new)
					.forEach(value::addChild);
		}
		return value;
	}
}
