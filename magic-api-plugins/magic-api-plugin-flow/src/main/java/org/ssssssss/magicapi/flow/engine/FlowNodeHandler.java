package org.ssssssss.magicapi.flow.engine;

/**
 * 节点类型扩展点。每种节点（查库、写库、脚本、调用外部系统……）实现一个，注册为 Spring Bean 即可被引擎使用。
 */
public interface FlowNodeHandler {

	/**
	 * 节点类型，对应流程定义中 node.type
	 */
	String type();

	/**
	 * 执行前检查节点配置（必填项等），不访问数据库或外部系统。出错时抛出 InvalidArgumentException。
	 */
	default void check(FlowNodeContext node) {
	}

	/**
	 * 执行节点，返回值作为节点输出：后续节点可以用节点 id 或 prev 引用。
	 */
	Object execute(FlowNodeContext node) throws Exception;
}
