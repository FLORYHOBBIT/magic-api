package org.ssssssss.magicapi.upload.source;

import org.ssssssss.magicapi.upload.engine.BatchData;
import org.ssssssss.magicapi.upload.engine.BatchSource;

import java.util.*;

/**
 * 脚本直接传入的数据列表（例如调用其他接口取回的数据、脚本里加工过的数据）
 * <p>
 * 断点只用于中断后续传，全部上传后清除。断点带有数据指纹，传入的数据变化时从头开始。
 */
public class ListSource implements BatchSource {

	private final List<Map<String, Object>> rows;

	private final String scope;

	public ListSource(Collection<?> data) {
		if (data == null) {
			throw new IllegalArgumentException("上传数据不能为空");
		}
		List<Map<String, Object>> list = new ArrayList<>(data.size());
		int index = 0;
		for (Object item : data) {
			if (!(item instanceof Map)) {
				throw new IllegalArgumentException("上传数据的第 " + (index + 1) + " 项不是对象（Map），实际类型: "
						+ (item == null ? "null" : item.getClass().getSimpleName()));
			}
			Map<String, Object> row = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : ((Map<?, ?>) item).entrySet()) {
				row.put(String.valueOf(entry.getKey()), entry.getValue());
			}
			list.add(row);
			index++;
		}
		this.rows = Collections.unmodifiableList(list);
		this.scope = SqlSupport.fingerprint(list.size() + "|" + (list.isEmpty() ? "" : list.get(0) + "|" + list.get(list.size() - 1)));
	}

	@Override
	public BatchData fetch(Object checkpoint, int batchSize) {
		int offset = (int) Math.min(SqlSupport.offsetOf(checkpoint, scope), rows.size());
		int end = Math.min(rows.size(), offset + batchSize);
		return new BatchData(new ArrayList<>(rows.subList(offset, end)), scope + ":" + end);
	}

	@Override
	public boolean isIncremental() {
		return false;
	}

	public int size() {
		return rows.size();
	}
}
