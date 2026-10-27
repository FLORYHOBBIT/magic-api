package org.ssssssss.magicapi.upload.engine;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 数据来源读取到的一批数据
 */
public class BatchData {

	private final List<Map<String, Object>> rows;

	/**
	 * 这批数据之后的断点位置（keyset 模式为最后一行的主键，offset 模式为已读取条数）
	 */
	private final Object nextCheckpoint;

	public BatchData(List<Map<String, Object>> rows, Object nextCheckpoint) {
		this.rows = rows == null ? Collections.<Map<String, Object>>emptyList() : rows;
		this.nextCheckpoint = nextCheckpoint;
	}

	public List<Map<String, Object>> getRows() {
		return rows;
	}

	public Object getNextCheckpoint() {
		return nextCheckpoint;
	}

	public boolean isEmpty() {
		return rows.isEmpty();
	}
}
