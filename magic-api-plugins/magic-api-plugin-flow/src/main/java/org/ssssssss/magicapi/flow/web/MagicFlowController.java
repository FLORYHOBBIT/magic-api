package org.ssssssss.magicapi.flow.web;

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.model.JsonBean;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.web.MagicController;
import org.ssssssss.magicapi.core.web.MagicExceptionHandler;
import org.ssssssss.magicapi.flow.model.FlowDefinition;
import org.ssssssss.magicapi.flow.model.FlowDefinitions;
import org.ssssssss.magicapi.flow.model.FlowInfo;

import static org.ssssssss.magicapi.flow.FlowJsonCodes.FLOW_NOT_FOUND;

/**
 * 集成流程的编辑器接口（挂在 magic-api 编辑器的 web 前缀下，复用编辑器登录鉴权）
 */
public class MagicFlowController extends MagicController implements MagicExceptionHandler {

	public MagicFlowController(MagicConfiguration configuration) {
		super(configuration);
	}

	/**
	 * 校验流程定义，返回格式化后的 JSON。
	 *
	 * @param id         已保存的流程 id
	 * @param definition 编辑器中尚未保存的定义；为空时校验已保存版本
	 */
	@PostMapping("/flow/validate")
	@ResponseBody
	public JsonBean<String> validate(String id, String definition) {
		String json = definition;
		if (StringUtils.isBlank(json)) {
			MagicEntity entity = MagicConfiguration.getMagicResourceService().file(id);
			isTrue(entity instanceof FlowInfo, FLOW_NOT_FOUND.format(id));
			json = entity.getScript();
		}
		FlowDefinition parsed = FlowDefinitions.parse(json);
		return new JsonBean<>(FlowDefinitions.toJson(parsed));
	}
}
