package org.ssssssss.magicapi.upload.channel.file;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * SFTP 会话（JSch）
 * <p>
 * 默认校验服务器主机密钥（需配置 known_hosts 文件），只有明确设置 strictHostKey=false 时才跳过校验。
 */
public class SftpSession implements RemoteSession {

	private final Session session;

	private final ChannelSftp sftp;

	private SftpSession(Session session, ChannelSftp sftp) {
		this.session = session;
		this.sftp = sftp;
	}

	public static RemoteFileTarget.SessionFactory factory(String host, int port, String username, String password,
														  String privateKey, String passphrase,
														  boolean strictHostKey, String knownHosts,
														  Duration connectTimeout, Duration readTimeout) {
		if (strictHostKey && (knownHosts == null || knownHosts.trim().isEmpty())) {
			throw new IllegalArgumentException("SFTP 默认校验服务器主机密钥，请配置 knownHosts（known_hosts 文件路径），"
					+ "或在确认网络可信时设置 strictHostKey=false");
		}
		if ((password == null || password.isEmpty()) && (privateKey == null || privateKey.trim().isEmpty())) {
			throw new IllegalArgumentException("SFTP 需要配置密码（password）或私钥（privateKey）");
		}
		return () -> {
			JSch jsch = new JSch();
			if (privateKey != null && !privateKey.trim().isEmpty()) {
				jsch.addIdentity("magic-upload", privateKey.trim().getBytes(StandardCharsets.UTF_8), null,
						passphrase == null || passphrase.isEmpty() ? null : passphrase.getBytes(StandardCharsets.UTF_8));
			}
			if (strictHostKey) {
				jsch.setKnownHosts(knownHosts.trim());
			}
			Session session = jsch.getSession(username, host, port);
			if (password != null && !password.isEmpty()) {
				session.setPassword(password);
			}
			session.setConfig("StrictHostKeyChecking", strictHostKey ? "yes" : "no");
			ChannelSftp sftp = null;
			try {
				session.connect((int) connectTimeout.toMillis());
				session.setTimeout((int) readTimeout.toMillis());
				sftp = (ChannelSftp) session.openChannel("sftp");
				sftp.connect((int) connectTimeout.toMillis());
				return new SftpSession(session, sftp);
			} catch (Exception e) {
				if (sftp != null) {
					sftp.disconnect();
				}
				session.disconnect();
				throw e;
			}
		};
	}

	@Override
	public void changeDir(String dir) throws SftpException {
		if (dir.startsWith("/")) {
			sftp.cd("/");
		}
		for (String part : dir.split("/")) {
			if (part.isEmpty()) {
				continue;
			}
			try {
				sftp.cd(part);
			} catch (SftpException e) {
				sftp.mkdir(part);
				sftp.cd(part);
			}
		}
	}

	@Override
	public void upload(String name, byte[] content) throws SftpException {
		sftp.put(new ByteArrayInputStream(content), name, ChannelSftp.OVERWRITE);
	}

	@Override
	public void delete(String name) throws SftpException {
		try {
			sftp.rm(name);
		} catch (SftpException e) {
			if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
				throw e;
			}
		}
	}

	@Override
	public void rename(String from, String to) throws SftpException {
		sftp.rename(from, to);
	}

	@Override
	public void close() {
		sftp.disconnect();
		session.disconnect();
	}
}
