package org.ssssssss.magicapi.task.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.scheduling.support.CronExpression;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.config.WebSocketSessionManager;
import org.ssssssss.magicapi.core.logging.MagicLoggerContext;
import org.ssssssss.magicapi.core.record.ExecutionRecord;
import org.ssssssss.magicapi.core.record.ExecutionRecordStore;
import org.ssssssss.magicapi.core.record.ExecutionLogCapture;
import org.ssssssss.magicapi.core.model.DebugRequest;
import org.ssssssss.magicapi.core.model.JsonBean;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletRequest;
import org.ssssssss.magicapi.core.web.MagicController;
import org.ssssssss.magicapi.core.web.MagicExceptionHandler;
import org.ssssssss.magicapi.utils.ScriptManager;
import org.ssssssss.script.MagicScriptDebugContext;


public class MagicTaskController extends MagicController implements MagicExceptionHandler {

	public MagicTaskController(MagicConfiguration configuration) {
		super(configuration);
	}

	/** 使用与调度器相同的解析器校验并展开宏，不创建或执行任务。 */
	@GetMapping("/task/cron/validate")
	@ResponseBody
	public JsonBean<String> validateCron(String expression) {
		String normalized = null;
		if (expression != null && !expression.trim().isEmpty()) {
			try {
				normalized = CronExpression.parse(expression.trim()).toString();
			} catch (IllegalArgumentException ignored) {
				// 编辑过程中可能暂时不合法，此时不展示频率说明。
			}
		}
		return new JsonBean<>(normalized);
	}

	@PostMapping("/task/execute")
	@ResponseBody
	public JsonBean<Object> execute(String id, MagicHttpServletRequest request){
		MagicEntity entity = MagicConfiguration.getMagicResourceService().file(id);
		notNull(entity, FILE_NOT_FOUND);
		String script = entity.getScript();
		String name = MagicConfiguration.getMagicResourceService().getScriptName(entity);
		DebugRequest debugRequest = DebugRequest.create(request);
		MagicLoggerContext.SESSION.set(debugRequest.getRequestedClientId());
		String testRunId = request.getHeader("X-Magic-Task-Test-Run");
		if (testRunId != null && testRunId.matches("[0-9A-Za-z_-]{1,64}")) {
			MagicLoggerContext.TASK_TEST_RUN.set(testRunId);
		}
		String sessionAndScriptId = debugRequest.getRequestedClientId() + debugRequest.getRequestedScriptId();
		long startTime = System.currentTimeMillis();
		boolean success = false;
		String message = null;
		ExecutionLogCapture capture = ExecutionLogCapture.start(name);
		try {
			MagicScriptDebugContext magicScriptContext = debugRequest.createMagicScriptContext(configuration.getDebugTimeout());
			WebSocketSessionManager.addMagicScriptContext(sessionAndScriptId, magicScriptContext);
			magicScriptContext.setScriptName(name);
			Object value = ScriptManager.executeScript(script, magicScriptContext);
			success = true;
			return new JsonBean<>(value);
		} catch (RuntimeException | Error e) {
			message = ExecutionRecordStore.messageOf(e);
			capture.error(e);
			throw e;
		} finally {
			WebSocketSessionManager.removeMagicScriptContext(sessionAndScriptId);
			MagicLoggerContext.SESSION.remove();
			MagicLoggerContext.TASK_TEST_RUN.remove();
			capture.finish(new ExecutionRecord(id, "task", name, "test", startTime, System.currentTimeMillis() - startTime, success, message));
		}
	}
}
