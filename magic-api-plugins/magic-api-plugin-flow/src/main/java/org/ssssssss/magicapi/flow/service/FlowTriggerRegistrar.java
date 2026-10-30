package org.ssssssss.magicapi.flow.service;

import org.ssssssss.magicapi.flow.model.FlowInfo;

/**
 * 流程触发器注册：根据流程的起始节点类型（HTTP / SOAP / 定时），注册对应的路由或调度。
 * 返回的句柄由注册中心保存，注销时原样传回。
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
	 * @return 注册句柄；不需要注册触发器（如仅供调试的流程）时返回 null
	 */
	Object register(FlowInfo info, String mappingKey) throws Exception;

	void unregister(FlowInfo info, Object handle) throws Exception;
}
