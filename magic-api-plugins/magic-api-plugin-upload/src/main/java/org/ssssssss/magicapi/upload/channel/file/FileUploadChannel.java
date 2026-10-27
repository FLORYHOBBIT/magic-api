package org.ssssssss.magicapi.upload.channel.file;

import org.ssssssss.magicapi.upload.engine.SendResult;
import org.ssssssss.magicapi.upload.engine.UploadBatch;
import org.ssssssss.magicapi.upload.engine.UploadChannel;

import java.util.Objects;

/**
 * 文件上传通道：每批生成一个文件，写入本地目录、FTP 或 SFTP
 * <p>
 * 写入失败（网络、权限、磁盘）抛出异常，由引擎重试；同一批重试时文件名不变，会覆盖上一次写入。
 */
public class FileUploadChannel implements UploadChannel {

	private final FileChannelConfig config;

	private final FileTarget target;

	public FileUploadChannel(FileChannelConfig config, FileTarget target) {
		this.config = Objects.requireNonNull(config, "config");
		this.target = Objects.requireNonNull(target, "target");
	}

	@Override
	public SendResult send(UploadBatch batch) throws Exception {
		String fileName = config.resolveFileName(batch);
		byte[] content = config.render(batch);
		target.write(fileName, content);
		return SendResult.success("{\"file\":\"" + fileName.replace("\"", "") + "\",\"bytes\":" + content.length
				+ ",\"target\":\"" + target.describe().replace("\"", "") + "\"}");
	}

	public FileTarget getTarget() {
		return target;
	}
}
