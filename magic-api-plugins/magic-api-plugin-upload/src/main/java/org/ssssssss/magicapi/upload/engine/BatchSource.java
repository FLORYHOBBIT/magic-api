package org.ssssssss.magicapi.upload.engine;

/**
 * 分批数据来源
 */
public interface BatchSource {

	/**
	 * 读取下一批数据
	 *
	 * @param checkpoint 上一批结束的位置；首次执行为 null 或已保存的断点
	 * @param batchSize  每批条数
	 * @return 下一批数据；返回 null 或空数据表示已读完
	 */
	BatchData fetch(Object checkpoint, int batchSize) throws Exception;

	/**
	 * 断点是否跨次执行保留
	 * <p>
	 * true（按递增字段读取）：断点一直保留，下次只上传新增的数据。
	 * false（按 offset 分页、脚本传入的列表）：断点只用于中断后续传，数据全部读完后清除，下次从头开始。
	 */
	default boolean isIncremental() {
		return true;
	}
}
