package org.ssssssss.magicapi.upload.resource;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加密，密文格式 ENC(base64(iv + 密文))
 * <p>
 * 密钥为 SHA-256(secretKey)。没有配置 secretKey 时不加密（敏感参数以明文保存，启动时打印警告）。
 * 更换 secretKey 后旧密文无法解密，需要在界面中重新填写密码。
 */
public class AesUploadChannelEncryptor implements UploadChannelEncryptor {

	private static final String PREFIX = "ENC(";

	private static final String SUFFIX = ")";

	private static final int IV_LENGTH = 12;

	private static final int TAG_BITS = 128;

	private final SecretKeySpec key;

	private final SecureRandom random = new SecureRandom();

	public AesUploadChannelEncryptor(String secretKey) {
		if (secretKey == null || secretKey.trim().isEmpty()) {
			this.key = null;
		} else {
			try {
				byte[] digest = MessageDigest.getInstance("SHA-256").digest(secretKey.trim().getBytes(StandardCharsets.UTF_8));
				this.key = new SecretKeySpec(digest, "AES");
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}

	@Override
	public boolean isEnabled() {
		return key != null;
	}

	@Override
	public boolean isEncrypted(String value) {
		return value != null && value.startsWith(PREFIX) && value.endsWith(SUFFIX);
	}

	@Override
	public String encrypt(String plain) {
		if (plain == null || plain.isEmpty() || key == null || isEncrypted(plain)) {
			return plain;
		}
		try {
			byte[] iv = new byte[IV_LENGTH];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
			buffer.put(iv).put(encrypted);
			return PREFIX + Base64.getEncoder().encodeToString(buffer.array()) + SUFFIX;
		} catch (Exception e) {
			throw new IllegalStateException("加密通道参数失败", e);
		}
	}

	@Override
	public String decrypt(String value) {
		if (!isEncrypted(value)) {
			return value;
		}
		if (key == null) {
			throw new IllegalStateException("通道参数已加密，但没有配置 magic-api.upload.secret-key，无法解密");
		}
		try {
			byte[] data = Base64.getDecoder().decode(value.substring(PREFIX.length(), value.length() - SUFFIX.length()));
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_LENGTH));
			return new String(cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH), StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new IllegalStateException("解密通道参数失败，magic-api.upload.secret-key 可能已更换，请在界面中重新填写", e);
		}
	}
}
