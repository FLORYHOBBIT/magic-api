package org.ssssssss.magicapi.upload.resource;

import org.ssssssss.magicapi.core.model.MagicEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 上传通道（在 magic-api 界面中维护）
 * <p>
 * 脚本中用 uploader.job(...).channel("通道名称") 引用。script 为报文转换脚本，可为空：
 * 可用变量 rows（本批数据）、batch（jobName、index、size、attempt）、options（通道参数，已解密）、
 * headers、params（可修改，追加到本批请求），返回值为请求体。
 */
public class UploadChannelInfo extends MagicEntity {

	/**
	 * http、soap、file、ftp、sftp
	 */
	private String protocol = "http";

	/**
	 * 通道参数，见 {@link org.ssssssss.magicapi.upload.channel.ChannelFactory}
	 */
	private Map<String, Object> options = new LinkedHashMap<>();

	private String description;

	public String getProtocol() {
		return protocol;
	}

	public void setProtocol(String protocol) {
		this.protocol = protocol;
	}

	public Map<String, Object> getOptions() {
		return options;
	}

	public void setOptions(Map<String, Object> options) {
		this.options = options == null ? new LinkedHashMap<>() : options;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@Override
	public UploadChannelInfo copy() {
		UploadChannelInfo info = new UploadChannelInfo();
		super.copyTo(info);
		info.setProtocol(this.protocol);
		info.setOptions(new LinkedHashMap<>(this.options));
		info.setDescription(this.description);
		return info;
	}

	@Override
	public MagicEntity simple() {
		UploadChannelInfo info = new UploadChannelInfo();
		super.simple(info);
		info.setProtocol(this.protocol);
		return info;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof UploadChannelInfo)) return false;
		if (!super.equals(o)) return false;
		UploadChannelInfo that = (UploadChannelInfo) o;
		return Objects.equals(protocol, that.protocol) && Objects.equals(options, that.options)
				&& Objects.equals(description, that.description);
	}

	@Override
	public int hashCode() {
		return Objects.hash(super.hashCode(), protocol, options, description);
	}
}
