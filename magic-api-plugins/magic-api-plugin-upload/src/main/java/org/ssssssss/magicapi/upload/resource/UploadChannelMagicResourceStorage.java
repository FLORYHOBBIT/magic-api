package org.ssssssss.magicapi.upload.resource;

import org.ssssssss.magicapi.core.config.JsonCodeConstants;
import org.ssssssss.magicapi.core.model.JsonCode;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.core.service.MagicResourceStorage;
import org.ssssssss.magicapi.upload.channel.BodyBuilder;
import org.ssssssss.magicapi.upload.channel.ChannelFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 上传通道的存储（目录 upload-channel，文件格式与接口相同：JSON 头 + 报文转换脚本）
 * <p>
 * 敏感参数在写入时加密，内存与界面中保留密文，只在创建通道时解密。
 */
public class UploadChannelMagicResourceStorage implements MagicResourceStorage<UploadChannelInfo>, JsonCodeConstants {

	public static final String FOLDER = "upload-channel";

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-\\u4e00-\\u9fa5]{1,64}");

	private static final JsonCode NAME_REQUIRED = new JsonCode(5101, "通道名称不能为空");

	private static final JsonCode NAME_INVALID = new JsonCode(5102, "通道名称只能包含中文、字母、数字、下划线、横线，最长 64 位");

	private static final JsonCode NAME_CONFLICT = new JsonCode(5103, "通道名称已存在");

	private static final JsonCode OPTIONS_INVALID = new JsonCode(5104, "通道参数不正确: %s");

	private final ChannelFactory channelFactory;

	private final UploadChannelEncryptor encryptor;

	private MagicResourceService magicResourceService;

	public UploadChannelMagicResourceStorage(ChannelFactory channelFactory, UploadChannelEncryptor encryptor) {
		this.channelFactory = channelFactory;
		this.encryptor = encryptor;
	}

	@Override
	public String folder() {
		return FOLDER;
	}

	@Override
	public String suffix() {
		return ".ms";
	}

	@Override
	public Class<UploadChannelInfo> magicClass() {
		return UploadChannelInfo.class;
	}

	@Override
	public boolean requirePath() {
		return false;
	}

	@Override
	public String buildMappingKey(UploadChannelInfo entity) {
		return entity.getName();
	}

	@Override
	public void validate(UploadChannelInfo entity) {
		notBlank(entity.getName(), NAME_REQUIRED);
		isTrue(NAME.matcher(entity.getName().trim()).matches(), NAME_INVALID);
		if (magicResourceService != null) {
			boolean conflict = magicResourceService.<UploadChannelInfo>files(FOLDER).stream()
					.anyMatch(it -> !Objects.equals(it.getId(), entity.getId()) && Objects.equals(it.getName(), entity.getName()));
			isTrue(!conflict, NAME_CONFLICT);
		}
		try {
			String protocol = ChannelFactory.normalizeProtocol(entity.getProtocol());
			entity.setProtocol(protocol);
			// SOAP 必须有报文转换，这里只做参数校验，用占位的报文转换
			BodyBuilder placeholder = ChannelFactory.SOAP.equals(protocol) ? batch -> "" : null;
			if (ChannelFactory.SOAP.equals(protocol) && (entity.getScript() == null || entity.getScript().trim().isEmpty())) {
				throw new IllegalArgumentException("WebService 通道必须编写报文转换脚本，返回请求 XML");
			}
			channelFactory.create(protocol, entity.getOptions(), placeholder);
		} catch (IllegalArgumentException e) {
			isTrue(false, OPTIONS_INVALID.format(e.getMessage()));
		}
	}

	@Override
	public byte[] write(MagicEntity entity) {
		UploadChannelInfo copy = ((UploadChannelInfo) entity).copy();
		copy.setOptions(encrypt(copy.getOptions()));
		return MagicResourceStorage.super.write(copy);
	}

	Map<String, Object> encrypt(Map<String, Object> options) {
		Map<String, Object> result = new LinkedHashMap<>(options);
		for (String key : UploadChannelEncryptor.SENSITIVE_KEYS) {
			Object value = result.get(key);
			if (value instanceof String && !((String) value).isEmpty()) {
				result.put(key, encryptor.encrypt((String) value));
			}
		}
		return result;
	}

	/**
	 * 创建通道前解密
	 */
	public Map<String, Object> decrypt(Map<String, Object> options) {
		Map<String, Object> result = new LinkedHashMap<>(options);
		for (String key : UploadChannelEncryptor.SENSITIVE_KEYS) {
			Object value = result.get(key);
			if (value instanceof String) {
				result.put(key, encryptor.decrypt((String) value));
			}
		}
		return result;
	}

	@Override
	public void setMagicResourceService(MagicResourceService magicResourceService) {
		this.magicResourceService = magicResourceService;
	}
}
