package org.ssssssss.magicapi.upload.channel.file;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;

/**
 * FTP 会话（commons-net），被动模式、二进制传输
 */
public class FtpSession implements RemoteSession {

	private final FTPClient client;

	private FtpSession(FTPClient client) {
		this.client = client;
	}

	public static RemoteFileTarget.SessionFactory factory(String host, int port, String username, String password,
														  boolean passive, String controlEncoding,
														  Duration connectTimeout, Duration readTimeout) {
		return () -> {
			FTPClient client = new FTPClient();
			client.setControlEncoding(controlEncoding == null || controlEncoding.isEmpty() ? "UTF-8" : controlEncoding);
			client.setConnectTimeout((int) connectTimeout.toMillis());
			client.setDefaultTimeout((int) readTimeout.toMillis());
			try {
				client.connect(host, port);
				if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
					throw new IOException("FTP 服务器拒绝连接: " + client.getReplyString());
				}
				if (!client.login(username, password)) {
					throw new IOException("FTP 登录失败: " + client.getReplyString());
				}
				client.setSoTimeout((int) readTimeout.toMillis());
				client.setFileType(FTP.BINARY_FILE_TYPE);
				if (passive) {
					client.enterLocalPassiveMode();
				}
				return new FtpSession(client);
			} catch (IOException | RuntimeException e) {
				disconnect(client);
				throw e;
			}
		};
	}

	@Override
	public void changeDir(String dir) throws IOException {
		if (dir.startsWith("/") && !client.changeWorkingDirectory("/")) {
			throw new IOException("FTP 进入根目录失败: " + client.getReplyString());
		}
		for (String part : dir.split("/")) {
			if (part.isEmpty()) {
				continue;
			}
			if (!client.changeWorkingDirectory(part)) {
				if (!client.makeDirectory(part) || !client.changeWorkingDirectory(part)) {
					throw new IOException("FTP 创建或进入目录失败 " + part + ": " + client.getReplyString());
				}
			}
		}
	}

	@Override
	public void upload(String name, byte[] content) throws IOException {
		if (!client.storeFile(name, new ByteArrayInputStream(content))) {
			throw new IOException("FTP 上传文件失败 " + name + ": " + client.getReplyString());
		}
	}

	@Override
	public void delete(String name) throws IOException {
		client.deleteFile(name);
	}

	@Override
	public void rename(String from, String to) throws IOException {
		if (!client.rename(from, to)) {
			throw new IOException("FTP 文件改名失败 " + from + " -> " + to + ": " + client.getReplyString());
		}
	}

	@Override
	public void close() {
		disconnect(client);
	}

	private static void disconnect(FTPClient client) {
		if (client.isConnected()) {
			try {
				client.logout();
			} catch (IOException ignored) {
			}
			try {
				client.disconnect();
			} catch (IOException ignored) {
			}
		}
	}
}
