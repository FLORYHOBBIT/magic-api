package org.ssssssss.magicapi.upload.channel;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * 常用签名、编码函数，脚本中通过 uploader 模块调用
 */
public final class SignFunctions {

	private SignFunctions() {
	}

	public static String md5(String text) {
		return hex(digest("MD5", bytes(text)));
	}

	public static String sha1(String text) {
		return hex(digest("SHA-1", bytes(text)));
	}

	public static String sha256(String text) {
		return hex(digest("SHA-256", bytes(text)));
	}

	public static String hmacSha256(String key, String text) {
		return hex(hmac("HmacSHA256", key, text));
	}

	public static String hmacSha256Base64(String key, String text) {
		return Base64.getEncoder().encodeToString(hmac("HmacSHA256", key, text));
	}

	public static String hmacSha1Base64(String key, String text) {
		return Base64.getEncoder().encodeToString(hmac("HmacSHA1", key, text));
	}

	public static String base64(String text) {
		return Base64.getEncoder().encodeToString(bytes(text));
	}

	public static String base64Decode(String text) {
		return text == null ? null : new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
	}

	/**
	 * 按参数名升序拼接为 a=1&amp;b=2（跳过空值），很多平台的签名原文是这种格式
	 */
	public static String sortedQuery(Map<String, ?> params) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, ?> entry : new TreeMap<>(params).entrySet()) {
			Object value = entry.getValue();
			if (value == null || value.toString().isEmpty()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append('&');
			}
			sb.append(entry.getKey()).append('=').append(value);
		}
		return sb.toString();
	}

	private static byte[] bytes(String text) {
		return (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] digest(String algorithm, byte[] data) {
		try {
			return MessageDigest.getInstance(algorithm).digest(data);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] hmac(String algorithm, String key, String text) {
		try {
			Mac mac = Mac.getInstance(algorithm);
			mac.init(new SecretKeySpec(bytes(key), algorithm));
			return mac.doFinal(bytes(text));
		} catch (Exception e) {
			throw new IllegalStateException("计算 " + algorithm + " 失败", e);
		}
	}

	private static String hex(byte[] data) {
		StringBuilder sb = new StringBuilder(data.length * 2);
		for (byte b : data) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}
}
