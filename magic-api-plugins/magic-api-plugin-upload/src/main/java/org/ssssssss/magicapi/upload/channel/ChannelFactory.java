package org.ssssssss.magicapi.upload.channel;

import org.ssssssss.magicapi.upload.channel.file.*;
import org.ssssssss.magicapi.upload.channel.http.HttpChannelConfig;
import org.ssssssss.magicapi.upload.channel.http.HttpUploadChannel;
import org.ssssssss.magicapi.upload.channel.soap.SoapChannelConfig;
import org.ssssssss.magicapi.upload.channel.soap.SoapUploadChannel;
import org.ssssssss.magicapi.upload.engine.UploadChannel;

import java.time.Duration;
import java.util.*;

/**
 * 按协议与参数创建上传通道
 * <p>
 * 参数（括号内为默认值）：
 * <ul>
 *     <li>公共：connectTimeout（10000 毫秒）、readTimeout（60000 毫秒）、signer（签名器 Bean 名称）</li>
 *     <li>http：url、method（POST）、contentType（json|form）、headers、params、successField、successValues、messageField、retryOnBusinessFailure</li>
 *     <li>soap：url、version（1.1|1.2）、soapAction、headers、successField（XML 路径）、successValues、messageField、retryOnBusinessFailure</li>
 *     <li>file：dir</li>
 *     <li>ftp：host、port（21）、username、password、dir、passive（true）、controlEncoding（UTF-8）</li>
 *     <li>sftp：host、port（22）、username、password 或 privateKey（+passphrase）、dir、knownHosts、strictHostKey（true）</li>
 *     <li>file/ftp/sftp：format（json|csv）、fileName（{job}_{datetime}_{batch}.{ext}）、encoding（UTF-8）、csvHeader（true）</li>
 * </ul>
 */
public class ChannelFactory {

	public static final String HTTP = "http";

	public static final String SOAP = "soap";

	public static final String FILE = "file";

	public static final String FTP = "ftp";

	public static final String SFTP = "sftp";

	public static final List<String> PROTOCOLS = Collections.unmodifiableList(Arrays.asList(HTTP, SOAP, FILE, FTP, SFTP));

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

	private final Map<String, UploadSigner> signers = new LinkedHashMap<>();

	public ChannelFactory(Collection<? extends UploadSigner> signers) {
		if (signers != null) {
			for (UploadSigner signer : signers) {
				this.signers.put(signer.name(), signer);
			}
		}
	}

	public Set<String> signerNames() {
		return Collections.unmodifiableSet(signers.keySet());
	}

	public static String normalizeProtocol(String protocol) {
		String value = protocol == null ? "" : protocol.trim().toLowerCase(Locale.ROOT);
		if (!PROTOCOLS.contains(value)) {
			throw new IllegalArgumentException("不支持的上传协议: " + protocol + "，可选: " + String.join("、", PROTOCOLS));
		}
		return value;
	}

	/**
	 * @param body 报文转换，可为 null（http 默认发送数据数组，文件类按格式输出数据，soap 必须提供）
	 */
	public UploadChannel create(String protocol, Map<String, ?> options, BodyBuilder body) {
		ChannelOptions o = new ChannelOptions(options);
		Duration connectTimeout = o.millis("connectTimeout", CONNECT_TIMEOUT);
		Duration readTimeout = o.millis("readTimeout", READ_TIMEOUT);
		UploadSigner signer = resolveSigner(o.string("signer"));
		switch (normalizeProtocol(protocol)) {
			case HTTP:
				return new HttpUploadChannel(new HttpChannelConfig(o.required("url", "上传地址"))
						.method(o.string("method", "POST"))
						.contentType(o.string("contentType", HttpChannelConfig.CONTENT_JSON))
						.headers(o.map("headers"))
						.params(o.map("params"))
						.connectTimeout(connectTimeout)
						.readTimeout(readTimeout)
						.responseRule(o.responseRule())
						.bodyBuilder(body)
						.signer(signer));
			case SOAP:
				return new SoapUploadChannel(new SoapChannelConfig(o.required("url", "WebService 地址"))
						.version(o.string("version", SoapChannelConfig.VERSION_11))
						.soapAction(o.string("soapAction", ""))
						.headers(o.map("headers"))
						.connectTimeout(connectTimeout)
						.readTimeout(readTimeout)
						.responseRule(o.responseRule())
						.bodyBuilder(body)
						.signer(signer));
			case FILE:
				return new FileUploadChannel(fileConfig(o, body), new LocalFileTarget(o.required("dir", "文件目录")));
			case FTP: {
				String host = o.required("host", "FTP 地址");
				int port = o.integer("port", 21);
				RemoteFileTarget.SessionFactory factory = FtpSession.factory(host, port,
						o.required("username", "FTP 用户名"), o.string("password", ""),
						o.bool("passive", true), o.string("controlEncoding", "UTF-8"), connectTimeout, readTimeout);
				return new FileUploadChannel(fileConfig(o, body),
						new RemoteFileTarget(factory, o.string("dir", ""), "ftp://" + host + ":" + port + "/" + o.string("dir", "")));
			}
			case SFTP: {
				String host = o.required("host", "SFTP 地址");
				int port = o.integer("port", 22);
				RemoteFileTarget.SessionFactory factory = SftpSession.factory(host, port,
						o.required("username", "SFTP 用户名"), o.string("password"),
						o.string("privateKey"), o.string("passphrase"),
						o.bool("strictHostKey", true), o.string("knownHosts"), connectTimeout, readTimeout);
				return new FileUploadChannel(fileConfig(o, body),
						new RemoteFileTarget(factory, o.string("dir", ""), "sftp://" + host + ":" + port + "/" + o.string("dir", "")));
			}
			default:
				throw new IllegalStateException();
		}
	}

	private static FileChannelConfig fileConfig(ChannelOptions o, BodyBuilder body) {
		return new FileChannelConfig()
				.format(o.string("format", FileChannelConfig.FORMAT_JSON))
				.fileName(o.string("fileName"))
				.encoding(o.string("encoding", "UTF-8"))
				.csvHeader(o.bool("csvHeader", true))
				.bodyBuilder(body);
	}

	private UploadSigner resolveSigner(String name) {
		if (name == null) {
			return null;
		}
		UploadSigner signer = signers.get(name);
		if (signer == null) {
			throw new IllegalArgumentException("找不到签名器 " + name + "，已注册: " + (signers.isEmpty() ? "无" : String.join("、", signers.keySet())));
		}
		return signer;
	}
}
