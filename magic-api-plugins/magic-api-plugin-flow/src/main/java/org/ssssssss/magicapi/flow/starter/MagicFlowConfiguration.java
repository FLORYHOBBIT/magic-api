package org.ssssssss.magicapi.flow.starter;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.ssssssss.magicapi.core.config.MagicPluginConfiguration;
import org.ssssssss.magicapi.core.model.Plugin;
import org.ssssssss.magicapi.core.web.MagicControllerRegister;
import org.ssssssss.magicapi.flow.service.FlowInfoMagicResourceStorage;
import org.ssssssss.magicapi.flow.service.FlowMagicDynamicRegistry;
import org.ssssssss.magicapi.flow.service.FlowTriggerRegistrar;
import org.ssssssss.magicapi.flow.web.MagicFlowController;

@Configuration
@ConditionalOnProperty(prefix = "magic-api.flow", name = "enable", havingValue = "true", matchIfMissing = true)
public class MagicFlowConfiguration implements MagicPluginConfiguration {

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

	@Override
	public Plugin plugin() {
		return new Plugin("集成流程", "MagicFlow", "magic-flow.1.0.0.iife.js");
	}

	@Override
	public MagicControllerRegister controllerRegister() {
		return (mapping, configuration) -> mapping.registerController(new MagicFlowController(configuration));
	}
}
