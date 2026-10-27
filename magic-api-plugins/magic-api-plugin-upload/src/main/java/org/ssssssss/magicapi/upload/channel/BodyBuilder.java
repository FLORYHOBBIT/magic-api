package org.ssssssss.magicapi.upload.channel;

import org.ssssssss.magicapi.upload.engine.UploadBatch;

/**
 * 把一批数据转换为请求体
 * <p>
 * 返回 String 时原样发送；返回其他对象时按通道的内容类型序列化（JSON 或表单）。
 */
@FunctionalInterface
public interface BodyBuilder {

	/**
	 * 默认：直接发送这批数据（JSON 数组）
	 */
	BodyBuilder ROWS = UploadBatch::getRows;

	Object build(UploadBatch batch) throws Exception;
}
