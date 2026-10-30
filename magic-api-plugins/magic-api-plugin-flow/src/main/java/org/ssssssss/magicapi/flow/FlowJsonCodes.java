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

	JsonCode FLOW_CYCLE_DETECTED = new JsonCode(5010, "流程存在环路，节点「%s」被重复执行");
}
