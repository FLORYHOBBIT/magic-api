package org.ssssssss.magicapi.upload.resource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 上传通道敏感参数的加解密
 * <p>
 * 默认实现为 AES-GCM（{@link AesUploadChannelEncryptor}），密钥来自 magic-api.upload.secret-key。
 * 需要接入国密或密钥管理服务时，注册一个实现该接口的 Bean 替换默认实现。
 */
public interface UploadChannelEncryptor {

	/**
	 * 需要加密保存的参数名
	 */
	List<String> SENSITIVE_KEYS = Collections.unmodifiableList(Arrays.asList(
			"password", "passphrase", "privateKey", "secret", "appSecret", "clientSecret", "token", "accessToken"));

	/**
	 * 已加密的值原样返回
	 */
	String encrypt(String plain);

	/**
	 * 未加密的值原样返回
	 */
	String decrypt(String value);

	boolean isEncrypted(String value);

	/**
	 * 是否真正启用了加密（未配置密钥时为 false，敏感参数以明文保存）
	 */
	boolean isEnabled();
}
