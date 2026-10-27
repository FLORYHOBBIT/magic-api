package org.ssssssss.magicapi.upload.channel.file;

/**
 * 文件写入目标。实现方需要先写临时文件再改名，避免对方读到写了一半的文件；同名文件直接覆盖（重试时会再次写同一个文件）。
 */
public interface FileTarget {

	void write(String fileName, byte[] content) throws Exception;

	/**
	 * 用于日志与上传记录，不能包含密码
	 */
	String describe();
}
