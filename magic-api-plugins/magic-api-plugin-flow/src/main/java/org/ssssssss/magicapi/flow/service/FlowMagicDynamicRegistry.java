package org.ssssssss.magicapi.flow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.ssssssss.magicapi.core.event.FileEvent;
import org.ssssssss.magicapi.core.event.GroupEvent;
import org.ssssssss.magicapi.core.service.AbstractMagicDynamicRegistry;
import org.ssssssss.magicapi.core.service.MagicResourceStorage;
import org.ssssssss.magicapi.flow.engine.FlowEngine;
import org.ssssssss.magicapi.flow.model.FlowInfo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集成流程注册中心：维护"路径 → 流程"的映射，并在流程保存、删除、启停时
 * 通知各类触发器（HTTP、WebService、定时）重新注册。
 * <p>
 * 父类在映射 key 不变、仅内容变化时只替换实体而不重新注册；触发器需要感知启停和配置变化，
 * 所以这里在内容变化时先注销再注册。
 */
public class FlowMagicDynamicRegistry extends AbstractMagicDynamicRegistry<FlowInfo> {

	private static final Logger logger = LoggerFactory.getLogger(FlowMagicDynamicRegistry.class);

	private final List<FlowTriggerRegistrar> registrars;

	/**
	 * 流程 id → 触发器注册情况（给界面看）
	 */
	private final Map<String, TriggerStatus> status = new ConcurrentHashMap<>();

	public FlowMagicDynamicRegistry(MagicResourceStorage<FlowInfo> magicResourceStorage, FlowTriggerRegistrar triggerRegistrar) {
		this(magicResourceStorage, triggerRegistrar == null ? Collections.<FlowTriggerRegistrar>emptyList() : Collections.singletonList(triggerRegistrar));
	}

	public FlowMagicDynamicRegistry(MagicResourceStorage<FlowInfo> magicResourceStorage, List<FlowTriggerRegistrar> registrars) {
		super(magicResourceStorage);
		this.registrars = registrars == null ? Collections.<FlowTriggerRegistrar>emptyList() : new ArrayList<>(registrars);
	}

	@EventListener(condition = "#event.type == 'flow'")
	public void onFileEvent(FileEvent event) {
		processEvent(event);
	}

	@EventListener(condition = "#event.type == 'flow'")
	public void onGroupEvent(GroupEvent event) {
		processEvent(event);
	}

	public String mappingKey(FlowInfo info) {
		return magicResourceStorage.buildKey(info);
	}

	/**
	 * 所有已注册的流程
	 */
	public List<FlowInfo> flows() {
		Map<String, FlowInfo> unique = new LinkedHashMap<>();
		for (FlowInfo info : mappings()) {
			if (info != null) {
				unique.putIfAbsent(info.getId(), info);
			}
		}
		return new ArrayList<>(unique.values());
	}

	public TriggerStatus status(String id) {
		TriggerStatus value = status.get(id);
		return value == null ? new TriggerStatus() : value;
	}

	@Override
	public boolean register(FlowInfo entity) {
		FlowInfo current = getMapping(entity.getId());
		if (current != null && Objects.equals(magicResourceStorage.buildKey(current), magicResourceStorage.buildKey(entity))
				&& !current.equals(entity)) {
			// key 不变但内容变了：父类只会替换实体，这里强制重新注册触发器
			unregister(current);
		}
		return super.register(entity);
	}

	@Override
	protected boolean register(MappingNode<FlowInfo> mappingNode) {
		FlowInfo info = mappingNode.getEntity();
		logger.debug("注册集成流程：{}，启用：{}", mappingNode.getMappingKey(), info.isEnabled());
		TriggerStatus triggerStatus = new TriggerStatus();
		triggerStatus.enabled = info.isEnabled();
		Map<FlowTriggerRegistrar, Object> handles = new LinkedHashMap<>();
		if (info.isEnabled()) {
			for (FlowTriggerRegistrar registrar : registrars) {
				try {
					Object handle = registrar.register(info, mappingNode.getMappingKey());
					if (handle != null) {
						handles.put(registrar, handle);
						triggerStatus.triggers.add(registrar.describe(handle));
					}
				} catch (Exception e) {
					// 触发器注册失败不影响流程本身保存，记录下来在界面提示，调试运行仍可用
					logger.error("集成流程[{}]触发器注册失败：{}", mappingNode.getMappingKey(), FlowEngine.message(e), e);
					triggerStatus.errors.add(FlowEngine.message(e));
				}
			}
		}
		mappingNode.setMappingData(handles);
		status.put(info.getId(), triggerStatus);
		return true;
	}

	@Override
	@SuppressWarnings("unchecked")
	protected void unregister(MappingNode<FlowInfo> mappingNode) {
		logger.debug("取消注册集成流程：{}", mappingNode.getMappingKey());
		Object data = mappingNode.getMappingData();
		if (data instanceof Map) {
			((Map<FlowTriggerRegistrar, Object>) data).forEach((registrar, handle) -> {
				try {
					registrar.unregister(mappingNode.getEntity(), handle);
				} catch (Exception e) {
					logger.warn("集成流程[{}]触发器注销失败", mappingNode.getMappingKey(), e);
				}
			});
		}
		status.remove(mappingNode.getEntity().getId());
	}

	/**
	 * 流程的触发器注册情况
	 */
	public static class TriggerStatus {

		private boolean enabled;

		private final List<String> triggers = new ArrayList<>();

		private final List<String> errors = new ArrayList<>();

		public boolean isEnabled() {
			return enabled;
		}

		public List<String> getTriggers() {
			return triggers;
		}

		public List<String> getErrors() {
			return errors;
		}
	}
}
