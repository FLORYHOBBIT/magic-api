package org.ssssssss.magicapi.upload.engine;

import java.util.Locale;

/**
 * 某一批重试后仍失败时的处理方式
 */
public enum FailurePolicy {

	/**
	 * 停止任务，断点停在失败批次之前，下次执行从这一批继续
	 */
	STOP,

	/**
	 * 记录失败批次并跳过，继续上传后面的数据，之后可以补传
	 */
	SKIP;

	public static FailurePolicy of(String value) {
		if (value == null || value.trim().isEmpty()) {
			return STOP;
		}
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("不支持的失败策略: " + value + "，可选值: stop、skip");
		}
	}
}
