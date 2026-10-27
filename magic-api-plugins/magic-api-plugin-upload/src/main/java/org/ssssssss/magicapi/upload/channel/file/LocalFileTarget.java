package org.ssssssss.magicapi.upload.channel.file;

import java.nio.file.*;

/**
 * 写入本地目录或已挂载的共享目录
 */
public class LocalFileTarget implements FileTarget {

	private final Path dir;

	public LocalFileTarget(String dir) {
		if (dir == null || dir.trim().isEmpty()) {
			throw new IllegalArgumentException("文件目录（dir）不能为空");
		}
		this.dir = Paths.get(dir.trim()).toAbsolutePath().normalize();
	}

	@Override
	public void write(String fileName, byte[] content) throws Exception {
		Files.createDirectories(dir);
		Path target = dir.resolve(fileName).normalize();
		if (!target.getParent().equals(dir)) {
			throw new IllegalArgumentException("文件名不能包含目录: " + fileName);
		}
		Path temp = dir.resolve(fileName + ".tmp");
		Files.write(temp, content);
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	@Override
	public String describe() {
		return dir.toString();
	}
}
