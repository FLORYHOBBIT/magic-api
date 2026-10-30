package org.ssssssss.magicapi.flow.msg;

import java.util.Date;

/**
 * 消息表中的一条消息
 */
public class FlowMessage {

	private String id;

	private String type;

	private String msgId;

	/**
	 * 接收序号（单调递增），顺序消费按它排序
	 */
	private long seq;

	/**
	 * 顺序分组键；不需要顺序时为 null，按类型顺序时为空串
	 */
	private String orderKey;

	private String status;

	/**
	 * 原始报文
	 */
	private String payload;

	/**
	 * 接收时的附加信息（请求头、参数、来源 IP），JSON
	 */
	private String meta;

	private String clientIp;

	private int attempts;

	private Date nextTime;

	private String owner;

	private Date lockTime;

	private String error;

	private String errorNode;

	/**
	 * 最后一次失败时的执行轨迹（JSON），成功时不保存
	 */
	private String trace;

	/**
	 * 流程返回值（JSON，截断）
	 */
	private String result;

	private Date receiveTime;

	private Date startTime;

	private Date finishTime;

	private long costMs;

	private String cbStatus;

	private int cbAttempts;

	private Date cbNextTime;

	private String cbError;

	private String cbResponse;

	private Date cbTime;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getMsgId() {
		return msgId;
	}

	public void setMsgId(String msgId) {
		this.msgId = msgId;
	}

	public long getSeq() {
		return seq;
	}

	public void setSeq(long seq) {
		this.seq = seq;
	}

	public String getOrderKey() {
		return orderKey;
	}

	public void setOrderKey(String orderKey) {
		this.orderKey = orderKey;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getPayload() {
		return payload;
	}

	public void setPayload(String payload) {
		this.payload = payload;
	}

	public String getMeta() {
		return meta;
	}

	public void setMeta(String meta) {
		this.meta = meta;
	}

	public String getClientIp() {
		return clientIp;
	}

	public void setClientIp(String clientIp) {
		this.clientIp = clientIp;
	}

	public int getAttempts() {
		return attempts;
	}

	public void setAttempts(int attempts) {
		this.attempts = attempts;
	}

	public Date getNextTime() {
		return nextTime;
	}

	public void setNextTime(Date nextTime) {
		this.nextTime = nextTime;
	}

	public String getOwner() {
		return owner;
	}

	public void setOwner(String owner) {
		this.owner = owner;
	}

	public Date getLockTime() {
		return lockTime;
	}

	public void setLockTime(Date lockTime) {
		this.lockTime = lockTime;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}

	public String getErrorNode() {
		return errorNode;
	}

	public void setErrorNode(String errorNode) {
		this.errorNode = errorNode;
	}

	public String getTrace() {
		return trace;
	}

	public void setTrace(String trace) {
		this.trace = trace;
	}

	public String getResult() {
		return result;
	}

	public void setResult(String result) {
		this.result = result;
	}

	public Date getReceiveTime() {
		return receiveTime;
	}

	public void setReceiveTime(Date receiveTime) {
		this.receiveTime = receiveTime;
	}

	public Date getStartTime() {
		return startTime;
	}

	public void setStartTime(Date startTime) {
		this.startTime = startTime;
	}

	public Date getFinishTime() {
		return finishTime;
	}

	public void setFinishTime(Date finishTime) {
		this.finishTime = finishTime;
	}

	public long getCostMs() {
		return costMs;
	}

	public void setCostMs(long costMs) {
		this.costMs = costMs;
	}

	public String getCbStatus() {
		return cbStatus;
	}

	public void setCbStatus(String cbStatus) {
		this.cbStatus = cbStatus;
	}

	public int getCbAttempts() {
		return cbAttempts;
	}

	public void setCbAttempts(int cbAttempts) {
		this.cbAttempts = cbAttempts;
	}

	public Date getCbNextTime() {
		return cbNextTime;
	}

	public void setCbNextTime(Date cbNextTime) {
		this.cbNextTime = cbNextTime;
	}

	public String getCbError() {
		return cbError;
	}

	public void setCbError(String cbError) {
		this.cbError = cbError;
	}

	public String getCbResponse() {
		return cbResponse;
	}

	public void setCbResponse(String cbResponse) {
		this.cbResponse = cbResponse;
	}

	public Date getCbTime() {
		return cbTime;
	}

	public void setCbTime(Date cbTime) {
		this.cbTime = cbTime;
	}
}
