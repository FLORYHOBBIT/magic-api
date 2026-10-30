package org.ssssssss.magicapi.flow.msg;

import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.flow.support.FlowTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 一种消息类型的配置，来自流程「消息」触发节点（差异项已替换）。字段说明见 {@link MsgTriggerNodeHandler}。
 */
public class MsgTypeConfig {

	public static final String RECEIVE_HTTP = "http";

	public static final String RECEIVE_SOAP = "soap";

	public static final String ORDER_NONE = "none";

	public static final String ORDER_TYPE = "type";

	public static final String ORDER_KEY = "key";

	public static final String CALLBACK_NONE = "none";

	public static final String CALLBACK_SUCCESS = "success";

	public static final String CALLBACK_DEAD = "dead";

	public static final String CALLBACK_BOTH = "both";

	private static final String TYPE_PATTERN = "[A-Za-z0-9_.\\-]{1,64}";

	private static final String NAME_PATTERN = "[A-Za-z_][A-Za-z0-9_\\-]*";

	String type;

	String receive;

	String service;

	String operation;

	String namespace;

	List<String> params;

	String resultName;

	String typeFrom;

	String msgIdExpression;

	String auth;

	String tokenHeader;

	String token;

	String allowIps;

	String reply;

	String replyFormat;

	int concurrency;

	int maxRetries;

	List<Long> backoff;

	String order;

	String orderKeyExpression;

	boolean blockOnDead;

	String callback;

	String callbackChannel;

	String callbackBody;

	String callbackDeadBody;

	String callbackFormat;

	Map<String, String> callbackHeaders;

	int callbackRetries;

	List<Long> callbackBackoff;

	boolean alertDead;

	int alertBacklog;

	int alertDelayMinutes;

	boolean alertCallback;

	boolean alertBlocked;

	/**
	 * 解析并校验；配置不合法时抛出 InvalidArgumentException（节点名 + 字段 + 原因）
	 */
	public static MsgTypeConfig from(FlowNodeContext node) {
		MsgTypeConfig c = new MsgTypeConfig();
		c.type = node.requireString("type");
		if (!c.type.matches(TYPE_PATTERN) && !c.type.contains("${var.")) {
			throw node.invalid("type", "只能包含字母、数字、下划线、点、横线，最长 64 位：" + c.type);
		}
		c.receive = node.oneOf("receive", RECEIVE_HTTP, RECEIVE_HTTP, RECEIVE_SOAP);
		if (RECEIVE_SOAP.equals(c.receive)) {
			c.service = name(node, "service", node.requireString("service"));
			c.operation = name(node, "operation", node.requireString("operation"));
			c.params = new ArrayList<>();
			for (String param : node.string("params", "message").split(",")) {
				if (!param.trim().isEmpty()) {
					c.params.add(name(node, "params", param.trim()));
				}
			}
			c.namespace = node.string("namespace");
			c.resultName = name(node, "resultName", node.string("resultName", "return"));
			c.typeFrom = node.string("typeFrom");
		} else {
			c.params = Collections.emptyList();
		}
		c.msgIdExpression = node.string("msgId", "input.msgId");
		c.auth = node.oneOf("auth", "none", "none", "token");
		c.tokenHeader = node.string("tokenHeader", "X-Token");
		c.token = "token".equals(c.auth) ? node.requireString("token") : null;
		c.allowIps = node.string("allowIps");
		c.reply = node.string("reply");
		c.replyFormat = node.oneOf("replyFormat", "auto", "auto", FlowTemplate.JSON, FlowTemplate.XML, FlowTemplate.TEXT);
		if (c.reply != null) {
			checkTemplate(node, "reply", c.reply);
		}
		c.concurrency = positive(node, "concurrency", 2);
		c.maxRetries = nonNegative(node, "maxRetries", 5);
		c.backoff = seconds(node, "backoff", "60,300,1800");
		c.order = node.oneOf("order", ORDER_NONE, ORDER_NONE, ORDER_TYPE, ORDER_KEY);
		if (ORDER_KEY.equals(c.order)) {
			c.orderKeyExpression = node.requireString("orderKey");
		}
		c.blockOnDead = "block".equals(node.oneOf("onDead", "block", "block", "skip"));
		c.callback = node.oneOf("callback", CALLBACK_NONE, CALLBACK_NONE, CALLBACK_SUCCESS, CALLBACK_DEAD, CALLBACK_BOTH);
		if (!CALLBACK_NONE.equals(c.callback)) {
			c.callbackChannel = node.requireString("callbackChannel");
			c.callbackBody = node.requireString("callbackBody");
			checkTemplate(node, "callbackBody", c.callbackBody);
			c.callbackDeadBody = node.string("callbackDeadBody");
			if (c.callbackDeadBody != null) {
				checkTemplate(node, "callbackDeadBody", c.callbackDeadBody);
			}
		}
		c.callbackFormat = node.oneOf("callbackFormat", FlowTemplate.JSON, FlowTemplate.JSON, FlowTemplate.XML, FlowTemplate.TEXT);
		c.callbackHeaders = node.stringMap("callbackHeaders");
		c.callbackRetries = nonNegative(node, "callbackRetries", 5);
		c.callbackBackoff = seconds(node, "callbackBackoff", "60,300,1800");
		c.alertDead = node.bool("alertDead", false);
		c.alertBacklog = nonNegative(node, "alertBacklog", 0);
		c.alertDelayMinutes = nonNegative(node, "alertDelay", 0);
		c.alertCallback = node.bool("alertCallback", false);
		c.alertBlocked = node.bool("alertBlocked", false);
		return c;
	}

	private static String name(FlowNodeContext node, String key, String value) {
		if (!value.matches(NAME_PATTERN)) {
			throw node.invalid(key, "只能包含字母、数字、下划线、横线，且以字母开头：" + value);
		}
		return value;
	}

	private static void checkTemplate(FlowNodeContext node, String key, String template) {
		try {
			FlowTemplate.render(template, FlowTemplate.TEXT, (expression, scope) -> "");
		} catch (IllegalArgumentException e) {
			throw node.invalid(key, e.getMessage());
		}
	}

	private static int positive(FlowNodeContext node, String key, int defaultValue) {
		int value = node.integer(key, defaultValue);
		if (value <= 0) {
			throw node.invalid(key, "必须大于 0");
		}
		return value;
	}

	private static int nonNegative(FlowNodeContext node, String key, int defaultValue) {
		int value = node.integer(key, defaultValue);
		if (value < 0) {
			throw node.invalid(key, "不能小于 0");
		}
		return value;
	}

	private static List<Long> seconds(FlowNodeContext node, String key, String defaultValue) {
		List<Long> result = new ArrayList<>();
		for (String part : node.string(key, defaultValue).split("[,，\\s]+")) {
			if (part.isEmpty()) {
				continue;
			}
			try {
				long value = Long.parseLong(part);
				if (value < 0) {
					throw new NumberFormatException();
				}
				result.add(value * 1000);
			} catch (NumberFormatException e) {
				throw node.invalid(key, "写秒数，逗号分隔，如 60,300,1800：" + part);
			}
		}
		if (result.isEmpty()) {
			result.add(60_000L);
		}
		return result;
	}

	/**
	 * 第 attempt 次失败后的等待毫秒数（超出列表时用最后一个）
	 */
	static long delay(List<Long> backoff, int attempt) {
		return backoff.get(Math.min(Math.max(attempt, 1), backoff.size()) - 1);
	}

	public boolean ordered() {
		return !ORDER_NONE.equals(order);
	}

	public boolean callbackOnSuccess() {
		return CALLBACK_SUCCESS.equals(callback) || CALLBACK_BOTH.equals(callback);
	}

	public boolean callbackOnDead() {
		return CALLBACK_DEAD.equals(callback) || CALLBACK_BOTH.equals(callback);
	}

	public String getType() {
		return type;
	}

	public String getReceive() {
		return receive;
	}

	public String getService() {
		return service;
	}

	public String getOperation() {
		return operation;
	}

	public String getTypeFrom() {
		return typeFrom;
	}

	public int getConcurrency() {
		return concurrency;
	}

	public int getMaxRetries() {
		return maxRetries;
	}

	public String getOrder() {
		return order;
	}

	public boolean isBlockOnDead() {
		return blockOnDead;
	}

	public String getCallback() {
		return callback;
	}
}
