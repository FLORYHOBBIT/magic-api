package org.ssssssss.magicapi.flow;

import org.ssssssss.magicapi.core.model.JsonCode;

/**
 * 集成流程插件的错误码（5000 段，避开核心 1xxx）
 */
public interface FlowJsonCodes {

	JsonCode FLOW_DEFINITION_REQUIRED = new JsonCode(5001, "流程定义不能为空");

	JsonCode FLOW_DEFINITION_INVALID = new JsonCode(5002, "流程定义格式错误：%s");

	JsonCode FLOW_NODE_ID_REQUIRED = new JsonCode(5003, "第 %s 个节点缺少 id");

	JsonCode FLOW_NODE_ID_DUPLICATE = new JsonCode(5004, "节点 id 重复：%s");

	JsonCode FLOW_NODE_TYPE_REQUIRED = new JsonCode(5005, "节点「%s」缺少类型");

	JsonCode FLOW_EDGE_INVALID = new JsonCode(5006, "连线引用了不存在的节点：%s → %s");

	JsonCode FLOW_NOT_FOUND = new JsonCode(5007, "找不到流程：%s");

	JsonCode FLOW_NODE_TYPE_UNKNOWN = new JsonCode(5008, "节点「%s」的类型「%s」不支持");

	JsonCode FLOW_START_NODE_INVALID = new JsonCode(5009, "流程必须有且只有一个起始节点（没有入线的节点），当前：%s");

	JsonCode FLOW_CYCLE_DETECTED = new JsonCode(5010, "流程执行步数超过 %s，节点「%s」可能处在环路中");

	JsonCode FLOW_NODE_CONFIG_REQUIRED = new JsonCode(5011, "节点「%s」缺少配置项：%s");

	JsonCode FLOW_EDGE_DUPLICATE = new JsonCode(5012, "节点「%s」有多条%s连线，只能保留一条");

	JsonCode FLOW_NODE_CONFIG_INVALID = new JsonCode(5013, "节点「%s」的配置项「%s」不正确：%s");

	JsonCode FLOW_TRIGGER_POSITION = new JsonCode(5014, "触发节点「%s」必须是起始节点，不能有入线");

	JsonCode FLOW_VARIABLE_MISSING = new JsonCode(5015, "差异项 var.%s 没有配置，请在「集成配置 → 差异项」中填写");

	JsonCode FLOW_VARIABLE_INVALID = new JsonCode(5016, "差异项名称只能包含中文、字母、数字、下划线、横线和点：%s");

	JsonCode FLOW_NOT_TIMER = new JsonCode(5017, "流程「%s」不是定时流程");

	JsonCode FLOW_PACKAGE_INVALID = new JsonCode(5018, "配置包不正确：%s");

	JsonCode FLOW_TEMPLATE_NOT_FOUND = new JsonCode(5019, "找不到场景模板：%s");

	JsonCode FLOW_HTTP_CONFLICT = new JsonCode(5020, "HTTP 地址 %s 已被其他接口或流程占用");

	JsonCode FLOW_SOAP_CONFLICT = new JsonCode(5021, "WebService %s 的方法 %s 已被流程「%s」使用");

	JsonCode FLOW_MSG_CONFLICT = new JsonCode(5022, "消息类型 %s 已被流程「%s」使用");

	JsonCode FLOW_MSG_DISABLED = new JsonCode(5023, "消息中心没有启用（magic-api.flow.msg.enable）");

	JsonCode FLOW_MSG_NOT_FOUND = new JsonCode(5024, "找不到消息：%s");
}
