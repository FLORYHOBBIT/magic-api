package org.ssssssss.magicapi.flow.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个节点的执行记录，调试运行时返回给编辑器。
 */
public class FlowTrace {

	/**
	 * 列表输出在轨迹中最多保留的行数，完整结果仍传给后续节点
	 */
	public static final int PREVIEW_ROWS = 20;

	private String nodeId;

	private String name;

	private String type;

	private boolean success = true;

	private long duration;

	/**
	 * 输出预览：列表超过 {@link #PREVIEW_ROWS} 行时为 {total, rows}
	 */
	private Object output;

	private String error;

	/**
	 * 下一个节点 id，流程在此结束时为空
	 */
	private String next;

	public static Object preview(Object value) {
		if (value instanceof List && ((List<?>) value).size() > PREVIEW_ROWS) {
			List<?> list = (List<?>) value;
			Map<String, Object> preview = new LinkedHashMap<>();
			preview.put("total", list.size());
			preview.put("rows", new ArrayList<>(list.subList(0, PREVIEW_ROWS)));
			return preview;
		}
		return value;
	}

	public String getNodeId() {
		return nodeId;
	}

	public void setNodeId(String nodeId) {
		this.nodeId = nodeId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public boolean isSuccess() {
		return success;
	}

	public void setSuccess(boolean success) {
		this.success = success;
	}

	public long getDuration() {
		return duration;
	}

	public void setDuration(long duration) {
		this.duration = duration;
	}

	public Object getOutput() {
		return output;
	}

	public void setOutput(Object output) {
		this.output = output;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}

	public String getNext() {
		return next;
	}

	public void setNext(String next) {
		this.next = next;
	}
}
