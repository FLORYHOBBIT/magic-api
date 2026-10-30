package org.ssssssss.magicapi.flow.msg;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 消息状态与回调状态
 */
public final class MsgStatus {

	/**
	 * 待处理
	 */
	public static final String PENDING = "PENDING";

	/**
	 * 处理中（已被某个节点领取）
	 */
	public static final String PROCESSING = "PROCESSING";

	/**
	 * 失败，等待 next_time 后重试
	 */
	public static final String RETRY = "RETRY";

	public static final String SUCCESS = "SUCCESS";

	/**
	 * 重试次数用完（死信），等待人工重试或忽略
	 */
	public static final String DEAD = "DEAD";

	/**
	 * 人工忽略
	 */
	public static final String IGNORED = "IGNORED";

	public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(PENDING, PROCESSING, RETRY, SUCCESS, DEAD, IGNORED));

	// ---------- 回调状态（cb_status），不需要回调时为 null ----------

	public static final String CB_PENDING = "PENDING";

	public static final String CB_SENDING = "SENDING";

	public static final String CB_RETRY = "RETRY";

	public static final String CB_SUCCESS = "SUCCESS";

	/**
	 * 回调重试次数用完
	 */
	public static final String CB_FAILED = "FAILED";

	private MsgStatus() {
	}
}
