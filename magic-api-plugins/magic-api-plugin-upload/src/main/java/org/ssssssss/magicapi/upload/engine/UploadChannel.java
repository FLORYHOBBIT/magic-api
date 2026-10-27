package org.ssssssss.magicapi.upload.engine;

/**
 * 上传通道（HTTP、SOAP、文件、FTP、SFTP 等）
 */
public interface UploadChannel {

	/**
	 * 发送一批数据
	 * <p>
	 * 返回失败结果或抛出异常都视为本次发送失败，由引擎决定是否重试。
	 *
	 * @param batch 待发送的批次
	 * @return 发送结果，不能为 null
	 */
	SendResult send(UploadBatch batch) throws Exception;
}
