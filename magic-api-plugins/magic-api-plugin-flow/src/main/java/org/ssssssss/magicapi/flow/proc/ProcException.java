package org.ssssssss.magicapi.flow.proc;

/**
 * 存储过程调用失败（含 ORA 错误、出参解析失败、入参转换失败），信息可直接展示给使用者。
 */
public class ProcException extends RuntimeException {

	public ProcException(String message) {
		super(message);
	}

	public ProcException(String message, Throwable cause) {
		super(message, cause);
	}
}
