package org.ssssssss.magicapi.upload.alert;

/**
 * 上传告警通知
 * <p>
 * 插件内置日志通知与 Webhook（钉钉、企业微信、飞书、通用 JSON）。其他方式（短信、邮件、医院内部消息平台）
 * 注册一个实现该接口的 Spring Bean 即可。实现中抛出的异常只记录日志，不影响上传。
 */
public interface UploadAlertNotifier {

	void notify(UploadAlert alert) throws Exception;
}
