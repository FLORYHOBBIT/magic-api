package org.ssssssss.magicapi.core.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.model.JsonBean;
import org.ssssssss.magicapi.core.record.ExecutionRecord;
import org.ssssssss.magicapi.core.record.ExecutionRecordStore;
import org.ssssssss.magicapi.core.record.ExecutionLog;
import org.ssssssss.magicapi.core.interceptor.Authorization;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletRequest;

import java.util.Collections;
import java.util.List;

/**
 * 执行记录查询接口
 */
public class ExecutionRecordController extends MagicController implements MagicExceptionHandler {

	public ExecutionRecordController(MagicConfiguration configuration) {
		super(configuration);
	}

	/**
	 * @param id 资源 ID
	 */
	@GetMapping("/execution-records")
	@ResponseBody
	public JsonBean<List<ExecutionRecord>> list(String id, MagicHttpServletRequest request) throws Exception {
		checkView(id, request);
		ExecutionRecordStore store = MagicConfiguration.getExecutionRecordStore();
		if (store == null) {
			return new JsonBean<>(Collections.emptyList());
		}
		return new JsonBean<>(store.list(id));
	}

	@GetMapping("/execution-records/logs")
	@ResponseBody
	public JsonBean<ExecutionLog> logs(String id, String runId, MagicHttpServletRequest request) throws Exception {
		checkView(id, request);
		ExecutionRecordStore store = MagicConfiguration.getExecutionRecordStore();
		return new JsonBean<>(store == null ? new ExecutionLog(false, false, "") : store.logs(id, runId));
	}

	private void checkView(String id, MagicHttpServletRequest request) {
		notBlank(id, FILE_NOT_FOUND);
		MagicEntity entity = MagicConfiguration.getMagicResourceService().file(id);
		notNull(entity, FILE_NOT_FOUND);
		isTrue(allowVisit(request, Authorization.VIEW, entity), PERMISSION_INVALID);
	}
}
