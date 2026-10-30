package org.ssssssss.magicapi.flow.trigger;

import org.springframework.scheduling.support.CronTrigger;
import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.flow.engine.FlowNodeHandler;

/**
 * 触发节点：放在流程最前面，决定流程如何被调用。执行时直接输出触发输入（input）。
 */
public abstract class TriggerNodeHandler implements FlowNodeHandler {

	@Override
	public boolean trigger() {
		return true;
	}

	@Override
	public Object execute(FlowNodeContext node) {
		return node.variables().get("input");
	}

	/**
	 * HTTP 触发：发布为 HTTP 接口，地址为 magic-api.prefix + magic-api.flow.http-prefix + 流程分组路径 + 流程路径。
	 * <pre>
	 * config:
	 *   method       GET / POST（默认）/ PUT / DELETE
	 *   auth         none（默认）/ token：请求头中的令牌必须与 token 一致
	 *   tokenHeader  令牌所在请求头，默认 X-Token
	 *   token        令牌，建议写成差异项 ${var.xxx}
	 *   allowIps     允许访问的 IP，逗号分隔，支持 192.168.1.*；为空不限制
	 *   contentType  auto（默认：对象按 JSON，字符串以 &lt; 开头按 XML，否则文本）/ json / xml / text
	 *   errorStatus  流程失败时的 HTTP 状态码，默认 500
	 * </pre>
	 */
	public static class Http extends TriggerNodeHandler {

		public static final String TYPE = "http";

		@Override
		public String type() {
			return TYPE;
		}

		@Override
		public void check(FlowNodeContext node) {
			node.oneOf("method", "POST", "GET", "POST", "PUT", "DELETE");
			String auth = node.oneOf("auth", "none", "none", "token");
			if ("token".equals(auth)) {
				node.requireString("token");
			}
			node.oneOf("contentType", "auto", "auto", "json", "xml", "text");
			int status = node.integer("errorStatus", 500);
			if (status < 200 || status > 599) {
				throw node.invalid("errorStatus", "必须是 200～599");
			}
		}
	}

	/**
	 * WebService 触发：发布为 SOAP 服务的一个方法。同一 service 的多个流程合并为一个服务、一份 WSDL。
	 * <pre>
	 * config:
	 *   service     服务名，地址为 magic-api.prefix + magic-api.flow.soap-prefix + /服务名，WSDL 为地址 + ?wsdl
	 *   operation   方法名
	 *   namespace   命名空间，默认 http://flow.magicapi.ssssssss.org/服务名
	 *   params      参数名，逗号分隔，默认 message；参数均为字符串，流程中用 input.参数名 读取
	 *   resultName  返回值元素名，默认 return
	 * </pre>
	 */
	public static class Soap extends TriggerNodeHandler {

		public static final String TYPE = "soap";

		@Override
		public String type() {
			return TYPE;
		}

		@Override
		public void check(FlowNodeContext node) {
			checkName(node, "service", node.requireString("service"));
			checkName(node, "operation", node.requireString("operation"));
			for (String param : node.string("params", "message").split(",")) {
				checkName(node, "params", param.trim());
			}
			checkName(node, "resultName", node.string("resultName", "return"));
		}

		private static void checkName(FlowNodeContext node, String key, String value) {
			if (!value.matches("[A-Za-z_][A-Za-z0-9_\\-]*")) {
				throw node.invalid(key, "只能包含字母、数字、下划线、横线，且以字母开头：" + value);
			}
		}
	}

	/**
	 * 定时触发：按 cron 执行；可配置分批读取数据，每批执行一次后续节点（变量 input.rows 为本批数据）。
	 * <pre>
	 * config:
	 *   cron           6 位 cron（秒 分 时 日 月 周），如 0 0/5 * * * ?
	 *   mode           once（默认，每次触发执行一次）/ pending（每次读取 SQL 查出的全部待处理数据，如 upload_flag = '0'，
	 *                  本次内按 keyColumn 分批，处理完不保留断点，适合"查未上传 → 上传 → 回写标志"）/
	 *                  keyset（按递增字段增量读取，断点跨次保留，只读新增）/ offset（按排序分页读取全部）
	 *   sql            分批读取的查询语句（mode 不为 once 时必填）
	 *   datasource     数据源 key，空为主数据源
	 *   keyColumn      keyset 模式的唯一递增字段（如自增主键、流水号），断点跨次保留，下次只读新增
	 *   orderBy        offset 模式的排序字段，如 "dept, id desc"
	 *   batchSize      每批条数，默认 500
	 *   retry          每批失败后的重试次数，默认 0
	 *   retryInterval  首次重试等待秒数，之后翻倍，默认 5
	 *   failurePolicy  stop（默认：本批失败则停止，下次从这一批继续）/ skip（记录失败批次后继续，之后可补传）
	 *   maxBatches     单次最多处理批数，0 不限
	 *   columnCase     lower（默认）/ camel / upper / raw
	 * </pre>
	 */
	public static class Timer extends TriggerNodeHandler {

		public static final String TYPE = "timer";

		@Override
		public String type() {
			return TYPE;
		}

		@Override
		public void check(FlowNodeContext node) {
			String cron = node.requireString("cron");
			if (!cron.contains("${var.")) {
				try {
					new CronTrigger(cron);
				} catch (IllegalArgumentException e) {
					throw node.invalid("cron", "格式错误（6 位：秒 分 时 日 月 周）：" + e.getMessage());
				}
			}
			String mode = node.oneOf("mode", "once", "once", "pending", "keyset", "offset");
			if (!"once".equals(mode)) {
				node.requireString("sql");
			}
			if ("keyset".equals(mode) || "pending".equals(mode)) {
				node.requireString("keyColumn");
			}
			if ("offset".equals(mode)) {
				node.requireString("orderBy");
			}
			if (node.integer("batchSize", 500) <= 0) {
				throw node.invalid("batchSize", "必须大于 0");
			}
			if (node.integer("retry", 0) < 0) {
				throw node.invalid("retry", "不能小于 0");
			}
			node.oneOf("failurePolicy", "stop", "stop", "skip");
			node.oneOf("columnCase", "lower", "lower", "camel", "upper", "raw");
		}
	}
}
