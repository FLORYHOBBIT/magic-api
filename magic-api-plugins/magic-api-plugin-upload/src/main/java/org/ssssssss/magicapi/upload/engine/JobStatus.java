package org.ssssssss.magicapi.upload.engine;

/**
 * 上传任务的执行结果状态
 */
public enum JobStatus {

	RUNNING,

	/**
	 * 全部批次上传成功
	 */
	SUCCESS,

	/**
	 * 部分批次失败并被跳过（SKIP 策略）
	 */
	PARTIAL,

	/**
	 * 任务中止（STOP 策略下批次失败、读取数据失败、被中断等）
	 */
	FAILED,

	/**
	 * 没有执行（例如同一任务正在其他线程或实例上运行）
	 */
	SKIPPED
}
