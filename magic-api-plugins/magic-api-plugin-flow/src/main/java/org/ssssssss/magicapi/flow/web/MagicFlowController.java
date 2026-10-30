package org.ssssssss.magicapi.flow.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.model.JsonBean;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.web.MagicController;
import org.ssssssss.magicapi.core.web.MagicExceptionHandler;
import org.ssssssss.magicapi.flow.engine.FlowEngine;
import org.ssssssss.magicapi.flow.engine.FlowResult;
import org.ssssssss.magicapi.flow.model.FlowDefinition;
import org.ssssssss.magicapi.flow.model.FlowDefinitions;
import org.ssssssss.magicapi.flow.model.FlowInfo;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.ssssssss.magicapi.flow.FlowJsonCodes.FLOW_DEFINITION_INVALID;
import static org.ssssssss.magicapi.flow.FlowJsonCodes.FLOW_NOT_FOUND;

/**
 * 集成流程的编辑器接口（挂在 magic-api 编辑器的 web 前缀下，复用编辑器登录鉴权）
 */
public class MagicFlowController extends MagicController implements MagicExceptionHandler {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final FlowEngine engine;

	public MagicFlowController(MagicConfiguration configuration, FlowEngine engine) {
		super(configuration);
		this.engine = engine;
	}

	/**
	 * 校验流程定义（结构 + 节点类型与必填配置），返回格式化后的 JSON。
	 *
	 * @param id         已保存的流程 id
	 * @param definition 编辑器中尚未保存的定义；为空时校验已保存版本
	 */
	@PostMapping("/flow/validate")
	@ResponseBody
	public JsonBean<String> validate(String id, String definition) {
		FlowDefinition parsed = FlowDefinitions.parse(definition(id, definition));
		engine.prepare(parsed);
		return new JsonBean<>(FlowDefinitions.toJson(parsed));
	}

	/**
	 * 调试运行：真实执行流程（会访问数据库），返回每个节点的输出与耗时。
	 *
	 * @param input 调试输入，JSON 对象，流程中用 input 引用
	 */
	@PostMapping("/flow/debug")
	@ResponseBody
	public JsonBean<FlowResult> debug(String id, String definition, String input) {
		FlowDefinition parsed = FlowDefinitions.parse(definition(id, definition));
		MagicEntity saved = StringUtils.isBlank(id) ? null : MagicConfiguration.getMagicResourceService().file(id);
		String name = saved == null ? "debug" : MagicConfiguration.getMagicResourceService().getScriptName(saved);
		return new JsonBean<>(engine.run(parsed, name, parseInput(input), true));
	}

	private String definition(String id, String definition) {
		if (StringUtils.isNotBlank(definition)) {
			return definition;
		}
		MagicEntity entity = StringUtils.isBlank(id) ? null : MagicConfiguration.getMagicResourceService().file(id);
		isTrue(entity instanceof FlowInfo, FLOW_NOT_FOUND.format(id));
		return entity.getScript();
	}

	private static Map<String, Object> parseInput(String input) {
		if (StringUtils.isBlank(input)) {
			return new LinkedHashMap<>();
		}
		try {
			return MAPPER.readValue(input, new TypeReference<LinkedHashMap<String, Object>>() {
			});
		} catch (JsonProcessingException e) {
			throw new InvalidArgumentException(FLOW_DEFINITION_INVALID.format("调试输入必须是 JSON 对象：" + e.getOriginalMessage()));
		}
	}
}
