package org.ssssssss.magicapi.upload.store;

import java.time.Duration;
import java.util.List;

/**
 * 上传插件的持久化：断点、任务锁、执行记录、批次日志、失败批次
 */
public interface UploadStore {

	/**
	 * 读取断点，没有时返回 null
	 */
	Object loadCheckpoint(String jobName);

	void saveCheckpoint(String jobName, Object checkpoint);

	/**
	 * 清除断点，下次从头上传
	 */
	void resetCheckpoint(String jobName);

	/**
	 * 尝试获取任务锁。锁已被其他 owner 持有且未过期时返回 false。
	 */
	boolean tryLock(String jobName, String owner, Duration timeout);

	/**
	 * 延长锁的过期时间（长任务每批成功后调用）
	 */
	void refreshLock(String jobName, String owner, Duration timeout);

	void unlock(String jobName, String owner);

	void saveRecord(UploadRecord record);

	void saveBatchLog(BatchLog log);

	void saveFailedBatch(FailedBatch batch);

	/**
	 * 待补传的失败批次（含数据），按创建时间先后
	 */
	List<FailedBatch> listPendingFailedBatches(String jobName, int limit);

	/**
	 * 补传成功标记为 DONE；补传失败时重试次数加 1
	 */
	void updateFailedBatch(String id, boolean done, String message);

	/**
	 * 最近的执行记录，按开始时间倒序
	 */
	List<UploadRecord> listRecords(String jobName, int limit);
}
