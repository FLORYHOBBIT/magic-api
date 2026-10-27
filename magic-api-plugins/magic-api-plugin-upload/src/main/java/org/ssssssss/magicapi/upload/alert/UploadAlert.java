package org.ssssssss.magicapi.upload.alert;

import java.util.Date;

/**
 * 上传告警
 */
public class UploadAlert {

	public enum Type {
		/**
		 * 任务失败中止（STOP 策略下批次失败、读取数据失败、保存断点失败等）
		 */
		JOB_FAILED,
		/**
		 * 任务完成，但有批次失败被跳过（SKIP 策略）
		 */
		JOB_PARTIAL,
		/**
		 * 补传失败批次时仍有失败
		 */
		RETRY_FAILED
	}

	private final Type type;

	private final String jobName;

	private final String hospital;

	private final String status;

	private final String message;

	private final long totalRows;

	private final long failedRows;

	private final int failedBatches;

	private final Date time;

	public UploadAlert(Type type, String jobName, String hospital, String status, String message,
					   long totalRows, long failedRows, int failedBatches, Date time) {
		this.type = type;
		this.jobName = jobName;
		this.hospital = hospital;
		this.status = status;
		this.message = message;
		this.totalRows = totalRows;
		this.failedRows = failedRows;
		this.failedBatches = failedBatches;
		this.time = time;
	}

	public Type getType() {
		return type;
	}

	public String getJobName() {
		return jobName;
	}

	public String getHospital() {
		return hospital;
	}

	public String getStatus() {
		return status;
	}

	public String getMessage() {
		return message;
	}

	public long getTotalRows() {
		return totalRows;
	}

	public long getFailedRows() {
		return failedRows;
	}

	public int getFailedBatches() {
		return failedBatches;
	}

	public Date getTime() {
		return time;
	}

	public String getTitle() {
		String what;
		switch (type) {
			case JOB_FAILED:
				what = "上传失败";
				break;
			case JOB_PARTIAL:
				what = "部分批次上传失败";
				break;
			default:
				what = "补传失败";
		}
		return (hospital == null || hospital.isEmpty() ? "" : "[" + hospital + "] ") + "数据上传任务 " + jobName + " " + what;
	}

	/**
	 * 纯文本内容，用于日志与机器人消息
	 */
	public String getText() {
		StringBuilder sb = new StringBuilder(getTitle());
		sb.append("\n状态: ").append(status);
		sb.append("\n时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(time));
		if (totalRows > 0 || failedRows > 0) {
			sb.append("\n数据: 共 ").append(totalRows).append(" 条，失败 ").append(failedRows).append(" 条");
		}
		if (failedBatches > 0) {
			sb.append("，失败批次 ").append(failedBatches);
		}
		if (message != null && !message.isEmpty()) {
			sb.append("\n原因: ").append(message);
		}
		return sb.toString();
	}
}
