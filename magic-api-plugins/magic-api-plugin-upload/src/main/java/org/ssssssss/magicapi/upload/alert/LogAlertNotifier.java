package org.ssssssss.magicapi.upload.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 写入日志（logger 名称 magic-upload-alert，便于单独输出到文件或接入日志告警）
 */
public class LogAlertNotifier implements UploadAlertNotifier {

	private static final Logger logger = LoggerFactory.getLogger("magic-upload-alert");

	@Override
	public void notify(UploadAlert alert) {
		logger.error(alert.getText());
	}
}
