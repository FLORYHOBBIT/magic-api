package org.ssssssss.magicapi.core.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.model.JsonBean;
import org.ssssssss.magicapi.core.record.ExecutionRecord;
import org.ssssssss.magicapi.core.record.ExecutionRecordStore;

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
	public JsonBean<List<ExecutionRecord>> list(String id) throws Exception {
		ExecutionRecordStore store = MagicConfiguration.getExecutionRecordStore();
		if (store == null) {
			return new JsonBean<>(Collections.emptyList());
		}
		return new JsonBean<>(store.list(id));
	}
}
