package org.ssssssss.magicapi.flow.msg;

import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.flow.trigger.TriggerNodeHandler;

/**
 * 消息触发：院内系统把消息发到消息中心，入库后立即回执，再由本流程异步消费（一种消息类型对应一个流程）。
 * <p>
 * 流程中的 input：{msgId, type, payload（原始报文）, body（报文为 JSON 时的对象）, xml（报文为 XML 时的对象）,
 * params（HTTP 查询参数 / WebService 参数）, headers, clientIp, attempt（第几次处理）, receiveTime, id（消息表主键）}。
 * <pre>
 * 接收
 *   type          消息类型，如 lis.result
 *   receive       http（默认：POST /msg/类型，或 WebService /ws/msg 的 sendMessage(type, msgId, payload)）/
 *                 soap（自定义 WebService 方法，兼容改不了调用方式的老系统）
 *   service / operation / params / namespace / resultName   receive=soap 时的服务名、方法名、参数名（逗号分隔，默认 message）等
 *   typeFrom      receive=soap 且多个类型共用一个方法时，区分类型的表达式，如 input.action
 *   msgId         取消息 id 的表达式，默认 input.msgId；如 input.xml.Header.MsgId、input.headers['x-msg-id']
 *   auth / tokenHeader / token / allowIps   鉴权，同 HTTP 触发
 *   reply         回执模板（可用 msgId、type、id、duplicate），为空用默认回执
 *   replyFormat   auto（HTTP 为 JSON，WebService 为 XML）/ json / xml / text
 * 消费
 *   concurrency   同时处理的条数，默认 2
 *   maxRetries    失败后重试次数，默认 5，用完转死信
 *   backoff       每次重试前等待的秒数，逗号分隔，默认 60,300,1800（超出时用最后一个）
 *   order         none（默认，不保证顺序）/ type（整个类型按接收顺序逐条处理）/ key（按分组键，组内有序、组间并发）
 *   orderKey      order=key 时取分组键的表达式，如 input.body.patientId
 *   onDead        顺序消费时前一条成为死信：block（默认，后面的等待人工重试或忽略）/ skip（跳过，继续后面的）
 * 回调（处理完通知发送方，按类型配置）
 *   callback          none（默认）/ success / dead / both
 *   callbackChannel   外部连接名称（HTTP / WebService）
 *   callbackBody      回调报文模板，可用 msgId、type、status、result（流程返回值）、error、payload、body、xml
 *   callbackDeadBody  死信时的模板，为空用 callbackBody
 *   callbackFormat    json（默认）/ xml / text
 *   callbackHeaders   追加的请求头
 *   callbackRetries   回调失败重试次数，默认 5
 *   callbackBackoff   回调重试等待秒数，默认 60,300,1800
 * 告警（通过上传插件的告警通道发送，同类告警在静默期内只发一次）
 *   alertDead       消息成为死信时告警
 *   alertBacklog    待处理消息超过 N 条时告警，0 不告警
 *   alertDelay      最早一条待处理消息超过 N 分钟时告警，0 不告警
 *   alertCallback   回调最终失败时告警
 *   alertBlocked    顺序消费被死信阻塞时告警
 * </pre>
 */
public class MsgTriggerNodeHandler extends TriggerNodeHandler {

	public static final String TYPE = "msg";

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void check(FlowNodeContext node) {
		MsgTypeConfig.from(node);
	}
}
