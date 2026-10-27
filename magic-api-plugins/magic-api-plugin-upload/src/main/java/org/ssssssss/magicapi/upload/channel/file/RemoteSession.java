package org.ssssssss.magicapi.upload.channel.file;

/**
 * FTP、SFTP 的一次连接会话
 */
public interface RemoteSession extends AutoCloseable {

	/**
	 * 进入目录，不存在时逐级创建
	 */
	void changeDir(String dir) throws Exception;

	void upload(String name, byte[] content) throws Exception;

	/**
	 * 文件不存在时不报错
	 */
	void delete(String name) throws Exception;

	void rename(String from, String to) throws Exception;

	@Override
	void close();
}
