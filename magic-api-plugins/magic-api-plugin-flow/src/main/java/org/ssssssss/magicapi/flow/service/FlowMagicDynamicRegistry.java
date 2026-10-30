package org.ssssssss.magicapi.flow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.ssssssss.magicapi.core.event.FileEvent;
import org.ssssssss.magicapi.core.event.GroupEvent;
import org.ssssssss.magicapi.core.service.AbstractMagicDynamicRegistry;
import org.ssssssss.magicapi.core.service.MagicResourceStorage;
import org.ssssssss.magicapi.flow.model.FlowInfo;

import java.util.Objects;

/**
 * 集成流程注册中心：维护"路径 → 流程"的映射，并在流程保存、删除、启停时
 * 通知各类触发器（HTTP、SOAP、定时）重新注册。
 * <p>
 * 父类在映射 key 不变、仅内容变化时只替换实体而不重新注册；触发器需要感知启停和配置变化，
 * 所以这里在内容变化时先注销再注册。
 */
public class FlowMagicDynamicRegistry extends AbstractMagicDynamicRegistry<FlowInfo> {

	private static final Logger logger = LoggerFactory.getLogger(FlowMagicDynamicRegistry.class);

	private final FlowTriggerRegistrar triggerRegistrar;

	public FlowMagicDynamicRegistry(MagicResourceStorage<FlowInfo> magicResourceStorage, FlowTriggerRegistrar triggerRegistrar) {
		super(magicResourceStorage);
		this.triggerRegistrar = triggerRegistrar == null ? FlowTriggerRegistrar.NONE : triggerRegistrar;
	}

	@EventListener(condition = "#event.type == 'flow'")
	public void onFileEvent(FileEvent event) {
		processEvent(event);
	}

	@EventListener(condition = "#event.type == 'flow'")
	public void onGroupEvent(GroupEvent event) {
		processEvent(event);
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
		if (info.isEnabled()) {
			try {
				mappingNode.setMappingData(triggerRegistrar.register(info, mappingNode.getMappingKey()));
			} catch (Exception e) {
				// 触发器注册失败不影响流程本身保存，记录日志，调试运行仍可用
				logger.error("集成流程[{}]触发器注册失败", mappingNode.getMappingKey(), e);
			}
		}
		return true;
	}

	@Override
	protected void unregister(MappingNode<FlowInfo> mappingNode) {
		logger.debug("取消注册集成流程：{}", mappingNode.getMappingKey());
		Object handle = mappingNode.getMappingData();
		if (handle != null) {
			try {
				triggerRegistrar.unregister(mappingNode.getEntity(), handle);
			} catch (Exception e) {
				logger.warn("集成流程[{}]触发器注销失败", mappingNode.getMappingKey(), e);
			}
		}
	}
}
