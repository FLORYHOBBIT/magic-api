package org.ssssssss.magicapi.flow.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.model.JsonBean;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletRequest;
import org.ssssssss.magicapi.core.web.MagicController;
import org.ssssssss.magicapi.core.web.MagicExceptionHandler;
import org.ssssssss.magicapi.flow.engine.FlowEngine;
import org.ssssssss.magicapi.flow.engine.FlowResult;
import org.ssssssss.magicapi.flow.model.FlowDefinition;
import org.ssssssss.magicapi.flow.model.FlowDefinitions;
import org.ssssssss.magicapi.flow.model.FlowInfo;
import org.ssssssss.magicapi.flow.msg.*;
import org.ssssssss.magicapi.flow.service.*;
import org.ssssssss.magicapi.flow.support.FlowJson;
import org.ssssssss.magicapi.flow.support.FlowVariables;
import org.ssssssss.magicapi.flow.trigger.FlowJobService;
import org.ssssssss.magicapi.flow.trigger.TriggerNodeHandler;
import org.ssssssss.magicapi.utils.WebUtils;

import java.io.IOException;
import java.util.*;

import static org.ssssssss.magicapi.flow.FlowJsonCodes.*;

/**
 * 集成流程的编辑器接口（挂在 magic-api 编辑器的 web 前缀下，复用编辑器登录鉴权）
 */
public class MagicFlowController extends MagicController implements MagicExceptionHandler {

	private final Services services;

	public MagicFlowController(MagicConfiguration configuration, Services services) {
		super(configuration);
		this.services = services;
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
		services.engine.prepare(parsed);
		return new JsonBean<>(FlowDefinitions.toJson(parsed));
	}

	/**
	 * 调试运行：真实执行流程（会访问数据库、调用外部系统），返回每个节点的输出与耗时。
	 *
	 * @param input 调试输入，JSON 对象，流程中用 input 引用
	 */
	@PostMapping("/flow/debug")
	@ResponseBody
	public JsonBean<FlowResult> debug(String id, String definition, String input) {
		FlowDefinition parsed = FlowDefinitions.parse(definition(id, definition));
		MagicEntity saved = StringUtils.isBlank(id) ? null : MagicConfiguration.getMagicResourceService().file(id);
		String name = saved == null ? "debug" : MagicConfiguration.getMagicResourceService().getScriptName(saved);
		return new JsonBean<>(services.engine.run(parsed, name, parseInput(input), true));
	}

	/**
	 * 流程状态：触发器注册情况、最近运行记录；定时流程另有断点、执行记录、失败批次
	 */
	@GetMapping("/flow/status")
	@ResponseBody
	public JsonBean<Map<String, Object>> status(String id) {
		FlowInfo info = saved(id);
		Map<String, Object> result = new LinkedHashMap<>();
		FlowMagicDynamicRegistry.TriggerStatus status = services.runner.getRegistry().status(id);
		result.put("enabled", info.isEnabled());
		result.put("triggers", status.getTriggers());
		result.put("errors", status.getErrors());
		result.put("records", services.runner.records(id));
		String startType = startType(info);
		result.put("startType", startType);
		if (TriggerNodeHandler.Timer.TYPE.equals(startType)) {
			try {
				result.put("job", services.jobService.status(info, 20));
			} catch (RuntimeException e) {
				result.put("jobError", FlowEngine.message(e));
			}
		}
		return new JsonBean<>(result);
	}

	/**
	 * 立即执行一次定时流程（按已保存的版本，与定时触发相同：加锁、分批、记录）
	 */
	@PostMapping("/flow/run")
	@ResponseBody
	public JsonBean<Object> run(String id) {
		FlowInfo info = timer(id);
		return new JsonBean<>(services.jobService.run(info, "manual"));
	}

	/**
	 * 补传失败批次（failurePolicy 为 skip 时记录的批次）
	 */
	@PostMapping("/flow/retry-failed")
	@ResponseBody
	public JsonBean<Object> retryFailed(String id, Integer limit) {
		FlowInfo info = timer(id);
		return new JsonBean<>(services.jobService.retryFailed(info, limit == null ? 0 : limit));
	}

	/**
	 * 清除断点，下次从头读取
	 */
	@PostMapping("/flow/reset-checkpoint")
	@ResponseBody
	public JsonBean<Boolean> resetCheckpoint(String id) {
		services.jobService.resetCheckpoint(timer(id));
		return new JsonBean<>(true);
	}

	// ------------------------------------------------------------------ 场景模板

	@GetMapping("/flow/templates")
	@ResponseBody
	public JsonBean<List<Map<String, Object>>> templates() {
		return new JsonBean<>(services.templates.list());
	}

	/**
	 * 按模板生成流程定义
	 *
	 * @param params 模板参数，JSON 对象
	 */
	@PostMapping("/flow/template/apply")
	@ResponseBody
	public JsonBean<String> applyTemplate(String id, String params) {
		Map<String, String> values = new LinkedHashMap<>();
		if (StringUtils.isNotBlank(params)) {
			try {
				FlowJson.MAPPER.readValue(params, new TypeReference<LinkedHashMap<String, Object>>() {
				}).forEach((k, v) -> values.put(k, v == null ? null : String.valueOf(v)));
			} catch (JsonProcessingException e) {
				throw new InvalidArgumentException(FLOW_DEFINITION_INVALID.format("模板参数必须是 JSON 对象"));
			}
		}
		return new JsonBean<>(services.templates.apply(id, values));
	}

	// ------------------------------------------------------------------ 差异项

	@GetMapping("/flow/variables")
	@ResponseBody
	public JsonBean<Map<String, Object>> variables() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("file", String.valueOf(services.variables.getFile()));
		result.put("variables", services.variables.list());
		// 已保存流程中引用、但还没有配置的差异项
		Set<String> referenced = new TreeSet<>();
		for (FlowInfo flow : services.runner.getRegistry().flows()) {
			referenced.addAll(FlowVariables.references(flow.getScript()));
		}
		List<String> missing = new ArrayList<>();
		for (String name : referenced) {
			if (services.variables.value(name) == null) {
				missing.add(name);
			}
		}
		result.put("missing", missing);
		return new JsonBean<>(result);
	}

	/**
	 * @param value 为空且已存在时保留原值（密文不回显）
	 */
	@PostMapping("/flow/variables/save")
	@ResponseBody
	public JsonBean<Boolean> saveVariable(String name, String value, Boolean secret, String description) {
		services.variables.save(StringUtils.trimToEmpty(name), StringUtils.isEmpty(value) ? null : value, Boolean.TRUE.equals(secret), description);
		return new JsonBean<>(true);
	}

	@PostMapping("/flow/variables/delete")
	@ResponseBody
	public JsonBean<Boolean> deleteVariable(String name) {
		services.variables.remove(name);
		return new JsonBean<>(true);
	}

	// ------------------------------------------------------------------ 配置包

	/**
	 * 导出配置包，返回 Base64 的 zip
	 *
	 * @param body {ids: [流程id]}，ids 为空导出全部流程
	 */
	@PostMapping("/flow/package/export")
	@ResponseBody
	public JsonBean<String> exportPackage(@RequestBody(required = false) Map<String, Object> body) throws IOException {
		List<String> ids = new ArrayList<>();
		Object value = body == null ? null : body.get("ids");
		if (value instanceof Collection) {
			for (Object id : (Collection<?>) value) {
				ids.add(String.valueOf(id));
			}
		}
		return new JsonBean<>(Base64.getEncoder().encodeToString(services.packages.export(ids)));
	}

	/**
	 * @param body {content: Base64 的 zip}
	 */
	@PostMapping("/flow/package/preview")
	@ResponseBody
	public JsonBean<Map<String, Object>> previewPackage(@RequestBody Map<String, Object> body) throws IOException {
		return new JsonBean<>(services.packages.preview(decode(body)));
	}

	/**
	 * @param body {content: Base64 的 zip, values: {差异项: 值}}
	 */
	@PostMapping("/flow/package/import")
	@ResponseBody
	public JsonBean<Map<String, Object>> importPackage(@RequestBody Map<String, Object> body, MagicHttpServletRequest request) throws IOException {
		Map<String, String> values = new LinkedHashMap<>();
		Object raw = body.get("values");
		if (raw instanceof Map) {
			((Map<?, ?>) raw).forEach((k, v) -> values.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
		}
		return new JsonBean<>(services.packages.importPackage(decode(body), values, WebUtils.currentUserName()));
	}

	private static byte[] decode(Map<String, Object> body) {
		Object content = body == null ? null : body.get("content");
		if (content == null || StringUtils.isBlank(content.toString())) {
			throw new InvalidArgumentException(FLOW_PACKAGE_INVALID.format("没有上传文件"));
		}
		String text = content.toString();
		int comma = text.indexOf("base64,");
		if (comma > -1) {
			text = text.substring(comma + 7);
		}
		try {
			return Base64.getDecoder().decode(text.trim());
		} catch (IllegalArgumentException e) {
			throw new InvalidArgumentException(FLOW_PACKAGE_INVALID.format("文件内容不是 Base64"));
		}
	}

	// ------------------------------------------------------------------ 消息中心

	/**
	 * 各类型概况：登记的流程、接收地址、顺序/回调设置，以及未完成消息统计
	 */
	@GetMapping("/flow/msg/stats")
	@ResponseBody
	public JsonBean<Map<String, Object>> msgStats() {
		MsgCenter center = msgCenter();
		Map<String, JdbcMsgStore.TypeStats> stats = center.getStore().stats();
		Map<String, String> bindings = center.bindings();
		Set<String> types = new TreeSet<>(bindings.keySet());
		types.addAll(stats.keySet());
		List<Map<String, Object>> rows = new ArrayList<>();
		for (String type : types) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("type", type);
			FlowInfo flow = center.flow(type);
			row.put("flowId", flow == null ? null : flow.getId());
			row.put("flowName", flow == null ? null : flow.getName());
			try {
				MsgTypeConfig config = center.config(type);
				if (config != null) {
					row.put("url", center.httpUrl(type));
					row.put("receive", config.getReceive());
					row.put("order", config.getOrder());
					row.put("callback", config.getCallback());
					row.put("concurrency", config.getConcurrency());
					row.put("maxRetries", config.getMaxRetries());
				}
			} catch (RuntimeException e) {
				row.put("configError", FlowEngine.message(e));
			}
			JdbcMsgStore.TypeStats s = stats.get(type);
			row.put("backlog", s == null ? 0 : s.getBacklog());
			row.put("retrying", s == null ? 0 : s.getRetrying());
			row.put("processing", s == null ? 0 : s.getProcessing());
			row.put("dead", s == null ? 0 : s.getDead());
			row.put("callbackFailed", s == null ? 0 : s.getCallbackFailed());
			row.put("oldest", s == null ? null : s.getOldest());
			rows.add(row);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("types", rows);
		result.put("table", center.getStore().schema().table());
		result.put("node", services.msgDispatcher == null ? null : services.msgDispatcher.getOwner());
		return new JsonBean<>(result);
	}

	/**
	 * 消息列表（不含报文），按接收时间倒序
	 *
	 * @param from / to 接收时间，yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss
	 */
	@GetMapping("/flow/msg/list")
	@ResponseBody
	public JsonBean<JdbcMsgStore.Page> msgList(String type, String status, String cbStatus, String msgId, String from, String to,
											   Integer page, Integer size) {
		JdbcMsgStore.Query query = new JdbcMsgStore.Query();
		query.type = StringUtils.trimToNull(type);
		query.status = StringUtils.trimToNull(status);
		query.cbStatus = StringUtils.trimToNull(cbStatus);
		query.msgId = StringUtils.trimToNull(msgId);
		query.from = parseTime(from, false);
		query.to = parseTime(to, true);
		query.size = size == null ? 20 : size;
		query.offset = Math.max(0, (page == null ? 1 : page) - 1) * Math.max(1, Math.min(query.size, 500));
		return new JsonBean<>(msgCenter().getStore().page(query));
	}

	/**
	 * 消息详情（含报文、执行轨迹、回调回执）
	 */
	@GetMapping("/flow/msg/detail")
	@ResponseBody
	public JsonBean<FlowMessage> msgDetail(String id) {
		FlowMessage message = StringUtils.isBlank(id) ? null : msgCenter().getStore().find(id);
		isTrue(message != null, FLOW_MSG_NOT_FOUND.format(id));
		return new JsonBean<>(message);
	}

	/**
	 * 重新处理（死信、已忽略、待重试），重试次数清零
	 *
	 * @param ids 逗号分隔
	 */
	@PostMapping("/flow/msg/retry")
	@ResponseBody
	public JsonBean<Integer> msgRetry(String ids) {
		int count = msgCenter().getStore().retry(split(ids));
		if (services.msgDispatcher != null) {
			services.msgDispatcher.wake();
		}
		return new JsonBean<>(count);
	}

	/**
	 * 忽略（待处理、待重试、死信）；顺序消费时放行后面的消息
	 */
	@PostMapping("/flow/msg/ignore")
	@ResponseBody
	public JsonBean<Integer> msgIgnore(String ids) {
		int count = msgCenter().getStore().ignore(split(ids));
		if (services.msgDispatcher != null) {
			services.msgDispatcher.wake();
		}
		return new JsonBean<>(count);
	}

	/**
	 * 重新回调
	 */
	@PostMapping("/flow/msg/callback")
	@ResponseBody
	public JsonBean<Integer> msgCallback(String ids) {
		return new JsonBean<>(msgCenter().getStore().resendCallback(split(ids)));
	}

	private MsgCenter msgCenter() {
		isTrue(services.msgCenter != null, FLOW_MSG_DISABLED);
		return services.msgCenter;
	}

	private static List<String> split(String ids) {
		List<String> result = new ArrayList<>();
		if (ids != null) {
			for (String id : ids.split("[,，\\s]+")) {
				if (!id.trim().isEmpty()) {
					result.add(id.trim());
				}
			}
		}
		return result;
	}

	private static Date parseTime(String text, boolean end) {
		String value = StringUtils.trimToNull(text);
		if (value == null) {
			return null;
		}
		try {
			if (value.length() <= 10) {
				Date day = new java.text.SimpleDateFormat("yyyy-MM-dd").parse(value);
				return end ? new Date(day.getTime() + 24L * 3600 * 1000) : day;
			}
			return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(value);
		} catch (java.text.ParseException e) {
			throw new InvalidArgumentException(FLOW_DEFINITION_INVALID.format("时间格式应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss：" + value));
		}
	}

	// ------------------------------------------------------------------ 工具

	private FlowInfo saved(String id) {
		MagicEntity entity = StringUtils.isBlank(id) ? null : MagicConfiguration.getMagicResourceService().file(id);
		isTrue(entity instanceof FlowInfo, FLOW_NOT_FOUND.format(id));
		return (FlowInfo) entity;
	}

	private FlowInfo timer(String id) {
		FlowInfo info = saved(id);
		isTrue(TriggerNodeHandler.Timer.TYPE.equals(startType(info)), FLOW_NOT_TIMER.format(info.getName()));
		return info;
	}

	private static String startType(FlowInfo info) {
		try {
			FlowDefinition.Node start = FlowEngine.startNode(FlowDefinitions.parse(info.getScript()));
			return start == null ? null : start.getType();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private String definition(String id, String definition) {
		if (StringUtils.isNotBlank(definition)) {
			return definition;
		}
		return saved(id).getScript();
	}

	private static Map<String, Object> parseInput(String input) {
		if (StringUtils.isBlank(input)) {
			return new LinkedHashMap<>();
		}
		try {
			return FlowJson.MAPPER.readValue(input, new TypeReference<LinkedHashMap<String, Object>>() {
			});
		} catch (JsonProcessingException e) {
			throw new InvalidArgumentException(FLOW_DEFINITION_INVALID.format("调试输入必须是 JSON 对象：" + e.getOriginalMessage()));
		}
	}

	/**
	 * 控制器用到的服务
	 */
	public static class Services {

		final FlowEngine engine;

		final FlowRunner runner;

		final FlowJobService jobService;

		final FlowVariables variables;

		final FlowTemplates templates;

		final FlowPackageService packages;

		final MsgCenter msgCenter;

		final MsgDispatcher msgDispatcher;

		public Services(FlowEngine engine, FlowRunner runner, FlowJobService jobService, FlowVariables variables,
						FlowTemplates templates, FlowPackageService packages) {
			this(engine, runner, jobService, variables, templates, packages, null, null);
		}

		public Services(FlowEngine engine, FlowRunner runner, FlowJobService jobService, FlowVariables variables,
						FlowTemplates templates, FlowPackageService packages, MsgCenter msgCenter, MsgDispatcher msgDispatcher) {
			this.engine = engine;
			this.runner = runner;
			this.jobService = jobService;
			this.variables = variables;
			this.templates = templates;
			this.packages = packages;
			this.msgCenter = msgCenter;
			this.msgDispatcher = msgDispatcher;
		}
	}
}
