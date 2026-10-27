package org.ssssssss.magicapi.upload.channel;

/**
 * 请求签名扩展
 * <p>
 * 各平台签名规则不同（MD5、HMAC、SM3 等），医院可注册自己的实现为 Spring Bean，在通道配置中按名称引用；
 * 也可以在通道的报文转换脚本中用 uploader 模块的签名函数自行计算。
 */
public interface UploadSigner {

	/**
	 * 签名器名称，通道配置中按此名称引用
	 */
	default String name() {
		return getClass().getSimpleName();
	}

	/**
	 * 在请求发出前调用，可修改请求头、查询参数
	 */
	void sign(SignContext context) throws Exception;
}
