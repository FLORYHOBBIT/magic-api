package org.ssssssss.magicapi.flow.starter;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.ssssssss.magicapi.core.config.MagicPluginConfiguration;
import org.ssssssss.magicapi.core.model.Plugin;
import org.ssssssss.magicapi.core.web.MagicControllerRegister;
import org.ssssssss.magicapi.flow.engine.FlowEngine;
import org.ssssssss.magicapi.flow.engine.FlowNodeHandler;
import org.ssssssss.magicapi.flow.engine.node.QueryNodeHandler;
import org.ssssssss.magicapi.flow.engine.node.ReturnNodeHandler;
import org.ssssssss.magicapi.flow.engine.node.ScriptNodeHandler;
import org.ssssssss.magicapi.flow.engine.node.WriteNodeHandler;
import org.ssssssss.magicapi.flow.service.FlowInfoMagicResourceStorage;
import org.ssssssss.magicapi.flow.service.FlowMagicDynamicRegistry;
import org.ssssssss.magicapi.flow.service.FlowTriggerRegistrar;
import org.ssssssss.magicapi.flow.web.MagicFlowController;
import org.ssssssss.magicapi.modules.db.SQLModule;

import java.util.stream.Collectors;

@Configuration
@ConditionalOnProperty(prefix = "magic-api.flow", name = "enable", havingValue = "true", matchIfMissing = true)
public class MagicFlowConfiguration implements MagicPluginConfiguration {

	private final ObjectProvider<FlowEngine> engine;

	public MagicFlowConfiguration(ObjectProvider<FlowEngine> engine) {
		this.engine = engine;
	}

	@Bean
	@ConditionalOnMissingBean
	public FlowInfoMagicResourceStorage flowInfoMagicResourceStorage() {
		return new FlowInfoMagicResourceStorage();
	}

	@Bean
	@ConditionalOnMissingBean
	public FlowMagicDynamicRegistry flowMagicDynamicRegistry(FlowInfoMagicResourceStorage storage,
															 ObjectProvider<FlowTriggerRegistrar> triggerRegistrar) {
		return new FlowMagicDynamicRegistry(storage, triggerRegistrar.getIfAvailable());
	}

	@Bean
	public QueryNodeHandler flowQueryNodeHandler(ObjectProvider<SQLModule> sqlModule) {
		return new QueryNodeHandler(sqlModule.getIfAvailable());
	}

	@Bean
	public WriteNodeHandler flowWriteNodeHandler(ObjectProvider<SQLModule> sqlModule) {
		return new WriteNodeHandler(sqlModule.getIfAvailable());
	}

	@Bean
	public ScriptNodeHandler flowScriptNodeHandler() {
		return new ScriptNodeHandler();
	}

	@Bean
	public ReturnNodeHandler flowReturnNodeHandler() {
		return new ReturnNodeHandler();
	}

	/**
	 * 收集全部节点类型；项目自定义的 FlowNodeHandler Bean 用 @Order 排在前面即可覆盖同名内置节点
	 */
	@Bean
	@ConditionalOnMissingBean
	public FlowEngine flowEngine(ObjectProvider<FlowNodeHandler> handlers) {
		return new FlowEngine(handlers.orderedStream().collect(Collectors.toList()));
	}

	@Override
	public Plugin plugin() {
		return new Plugin("集成流程", "MagicFlow", "magic-flow.1.0.0.iife.js");
	}

	@Override
	public MagicControllerRegister controllerRegister() {
		return (mapping, configuration) -> mapping.registerController(new MagicFlowController(configuration, engine.getObject()));
	}
}
