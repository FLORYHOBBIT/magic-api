package org.ssssssss.magicapi.task.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.config.WebSocketSessionManager;
import org.ssssssss.magicapi.core.logging.MagicLoggerContext;
import org.ssssssss.magicapi.core.record.ExecutionRecord;
import org.ssssssss.magicapi.core.record.ExecutionRecordStore;
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

	@PostMapping("/task/execute")
	@ResponseBody
	public JsonBean<Object> execute(String id, MagicHttpServletRequest request){
		MagicEntity entity = MagicConfiguration.getMagicResourceService().file(id);
		notNull(entity, FILE_NOT_FOUND);
		String script = entity.getScript();
		String name = MagicConfiguration.getMagicResourceService().getScriptName(entity);
		DebugRequest debugRequest = DebugRequest.create(request);
		MagicLoggerContext.SESSION.set(debugRequest.getRequestedClientId());
		String sessionAndScriptId = debugRequest.getRequestedClientId() + debugRequest.getRequestedScriptId();
		long startTime = System.currentTimeMillis();
		boolean success = false;
		String message = null;
		try {
			MagicScriptDebugContext magicScriptContext = debugRequest.createMagicScriptContext(configuration.getDebugTimeout());
			WebSocketSessionManager.addMagicScriptContext(sessionAndScriptId, magicScriptContext);
			magicScriptContext.setScriptName(name);
			Object value = ScriptManager.executeScript(script, magicScriptContext);
			success = true;
			return new JsonBean<>(value);
		} catch (RuntimeException e) {
			message = ExecutionRecordStore.messageOf(e);
			throw e;
		} finally {
			WebSocketSessionManager.removeMagicScriptContext(sessionAndScriptId);
			MagicLoggerContext.SESSION.remove();
			ExecutionRecordStore.record(new ExecutionRecord(id, "task", name, "test", startTime, System.currentTimeMillis() - startTime, success, message));
		}
	}
}
