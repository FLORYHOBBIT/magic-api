package org.ssssssss.magicapi.upload.channel.file;

/**
 * FTP、SFTP 写入目标：每批新建一个连接，写临时文件后改名
 */
public class RemoteFileTarget implements FileTarget {

	/**
	 * 打开一次连接会话
	 */
	@FunctionalInterface
	public interface SessionFactory {
		RemoteSession open() throws Exception;
	}

	private final SessionFactory sessionFactory;

	private final String dir;

	private final String description;

	public RemoteFileTarget(SessionFactory sessionFactory, String dir, String description) {
		this.sessionFactory = sessionFactory;
		this.dir = dir == null ? "" : dir.trim();
		this.description = description;
	}

	@Override
	public void write(String fileName, byte[] content) throws Exception {
		String temp = fileName + ".tmp";
		try (RemoteSession session = sessionFactory.open()) {
			if (!dir.isEmpty()) {
				session.changeDir(dir);
			}
			session.upload(temp, content);
			// 部分 FTP 服务器改名时不覆盖已存在的文件，先删除（重试时会出现同名文件）
			session.delete(fileName);
			session.rename(temp, fileName);
		}
	}

	@Override
	public String describe() {
		return description;
	}
}
