package org.ssssssss.magicapi.flow.service;

import org.ssssssss.magicapi.core.service.AbstractPathMagicResourceStorage;
import org.ssssssss.magicapi.flow.model.FlowDefinitions;
import org.ssssssss.magicapi.flow.model.FlowInfo;

/**
 * 集成流程的存储：放在资源目录的 flow 文件夹下，格式与接口、任务相同
 * （元数据 JSON + 分隔线 + 流程定义 JSON），因此能直接参与 magic-api 的导入导出、备份与 Git 同步。
 */
public class FlowInfoMagicResourceStorage extends AbstractPathMagicResourceStorage<FlowInfo> {

	public static final String FOLDER = "flow";

	@Override
	public String folder() {
		return FOLDER;
	}

	@Override
	public Class<FlowInfo> magicClass() {
		return FlowInfo.class;
	}

	@Override
	public void validate(FlowInfo entity) {
		notBlank(entity.getPath(), REQUEST_PATH_REQUIRED);
		FlowDefinitions.parse(entity.getScript());
	}

	@Override
	public String buildMappingKey(FlowInfo info) {
		return buildMappingKey(info, magicResourceService.getGroupPath(info.getGroupId()));
	}
}
