package org.ssssssss.magicapi.upload.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.magicapi.upload.engine.JobListener;
import org.ssssssss.magicapi.upload.engine.JobOptions;
import org.ssssssss.magicapi.upload.engine.JobResult;
import org.ssssssss.magicapi.upload.engine.JobStatus;
import org.ssssssss.magicapi.upload.service.RetryResult;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上传告警
 * <p>
 * 任务失败（FAILED）、部分失败（PARTIAL）、补传仍失败时告警。同一任务同一类告警在静默期内只发一次，
 * 避免秒级、分钟级任务在对方平台故障期间不停刷屏。
 */
public class UploadAlertService {

	private static final Logger logger = LoggerFactory.getLogger(UploadAlertService.class);

	private final List<UploadAlertNotifier> notifiers;

	private final String hospital;

	private final Duration silence;

	private final boolean alertOnPartial;

	private final Clock clock;

	private final Map<String, Long> lastSent = new ConcurrentHashMap<>();

	public UploadAlertService(List<UploadAlertNotifier> notifiers, String hospital, Duration silence, boolean alertOnPartial) {
		this(notifiers, hospital, silence, alertOnPartial, Clock.systemDefaultZone());
	}

	public UploadAlertService(List<UploadAlertNotifier> notifiers, String hospital, Duration silence, boolean alertOnPartial, Clock clock) {
		this.notifiers = notifiers == null ? Collections.<UploadAlertNotifier>emptyList() : new ArrayList<>(notifiers);
		this.hospital = hospital;
		this.silence = silence == null ? Duration.ZERO : silence;
		this.alertOnPartial = alertOnPartial;
		this.clock = clock;
	}

	/**
	 * 作为任务监听器接入执行器
	 */
	public JobListener listener() {
		return new JobListener() {
			@Override
			public void onJobEnd(JobOptions options, JobResult result) {
				jobEnded(result);
			}
		};
	}

	public void jobEnded(JobResult result) {
		if (result.getStatus() == JobStatus.FAILED) {
			send(new UploadAlert(UploadAlert.Type.JOB_FAILED, result.getJobName(), hospital, result.getStatus().name(),
					result.getMessage(), result.getTotalRows(), result.getFailedRows(), result.getFailedBatches(), new Date(clock.millis())));
		} else if (result.getStatus() == JobStatus.PARTIAL && alertOnPartial) {
			send(new UploadAlert(UploadAlert.Type.JOB_PARTIAL, result.getJobName(), hospital, result.getStatus().name(),
					result.getMessage() == null ? "失败批次已保存，可调用 retryFailed 补传" : result.getMessage(),
					result.getTotalRows(), result.getFailedRows(), result.getFailedBatches(), new Date(clock.millis())));
		}
	}

	public void retryEnded(RetryResult result) {
		if (!result.isSkipped() && result.getFailedBatches() > 0) {
			String message = String.join("；", result.getErrors().subList(0, Math.min(3, result.getErrors().size())));
			send(new UploadAlert(UploadAlert.Type.RETRY_FAILED, result.getJobName(), hospital, "RETRY_FAILED", message,
					result.getSuccessRows() + result.getFailedRows(), result.getFailedRows(), result.getFailedBatches(), new Date(clock.millis())));
		}
	}

	void send(UploadAlert alert) {
		String key = alert.getJobName() + "|" + alert.getType();
		long now = clock.millis();
		Long last = lastSent.get(key);
		if (last != null && now - last < silence.toMillis()) {
			logger.debug("上传告警处于静默期，跳过: {}", alert.getTitle());
			return;
		}
		lastSent.put(key, now);
		for (UploadAlertNotifier notifier : notifiers) {
			try {
				notifier.notify(alert);
			} catch (Exception e) {
				logger.warn("上传告警发送失败（{}），不影响上传", notifier.getClass().getName(), e);
			}
		}
	}
}
