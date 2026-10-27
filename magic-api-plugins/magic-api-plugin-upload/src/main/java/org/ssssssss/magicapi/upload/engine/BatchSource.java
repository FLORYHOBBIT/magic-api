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
}
