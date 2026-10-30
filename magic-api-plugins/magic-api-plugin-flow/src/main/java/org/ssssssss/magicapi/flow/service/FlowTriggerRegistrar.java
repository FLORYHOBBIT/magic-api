package org.ssssssss.magicapi.flow.service;

import org.ssssssss.magicapi.flow.model.FlowInfo;

/**
 * 流程触发器注册：根据流程的起始节点类型（HTTP / WebService / 定时），注册对应的路由或调度。
 * 返回的句柄由注册中心保存，注销时原样传回。可以有多个实现，各自只处理自己的起始节点类型。
 */
public interface FlowTriggerRegistrar {

	FlowTriggerRegistrar NONE = new FlowTriggerRegistrar() {
		@Override
		public Object register(FlowInfo info, String mappingKey) {
			return null;
		}

		@Override
		public void unregister(FlowInfo info, Object handle) {
		}
	};

	/**
	 * @return 注册句柄；起始节点不是本触发器负责的类型时返回 null
	 */
	Object register(FlowInfo info, String mappingKey) throws Exception;

	void unregister(FlowInfo info, Object handle) throws Exception;

	/**
	 * 给界面看的触发器说明，如 "HTTP POST /api/insur/settle"
	 */
	default String describe(Object handle) {
		return String.valueOf(handle);
	}
}
