package org.ssssssss.magicapi.flow.starter;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.ssssssss.magicapi.backup.service.MagicBackupService;
import org.ssssssss.magicapi.core.config.MagicAPIProperties;
import org.ssssssss.magicapi.core.config.MagicPluginConfiguration;
import org.ssssssss.magicapi.core.model.Plugin;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.core.web.MagicControllerRegister;
import org.ssssssss.magicapi.datasource.model.MagicDynamicDataSource;
import org.ssssssss.magicapi.flow.engine.FlowEngine;
import org.ssssssss.magicapi.flow.engine.FlowNodeHandler;
import org.ssssssss.magicapi.flow.engine.node.*;
import org.ssssssss.magicapi.flow.msg.JdbcMsgStore;
import org.ssssssss.magicapi.flow.msg.MsgCenter;
import org.ssssssss.magicapi.flow.proc.ProcModule;
import org.ssssssss.magicapi.flow.proc.ProcNodeHandler;
import org.ssssssss.magicapi.flow.proc.ProcService;
import org.ssssssss.magicapi.flow.msg.MsgDispatcher;
import org.ssssssss.magicapi.flow.msg.MsgTriggerNodeHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.ssssssss.magicapi.upload.alert.UploadAlertService;
import org.ssssssss.magicapi.flow.service.*;
import org.ssssssss.magicapi.flow.support.FlowVariables;
import org.ssssssss.magicapi.flow.trigger.*;
import org.ssssssss.magicapi.flow.web.MagicFlowController;
import org.ssssssss.magicapi.modules.db.SQLModule;
import org.ssssssss.magicapi.modules.db.dialect.Dialect;
import org.ssssssss.magicapi.modules.db.dialect.DialectAdapter;
import org.ssssssss.magicapi.upload.channel.ChannelFactory;
import org.ssssssss.magicapi.upload.resource.UploadChannelEncryptor;
import org.ssssssss.magicapi.upload.resource.UploadChannelMagicDynamicRegistry;
import org.ssssssss.magicapi.upload.resource.UploadChannelMagicResourceStorage;
import org.ssssssss.magicapi.upload.service.UploadJobRunner;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 集成流程插件自动配置，magic-api.flow.enable=false 关闭
 */
@Configuration
@ConditionalOnProperty(prefix = "magic-api.flow", name = "enable", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MagicFlowProperties.class)
public class MagicFlowConfiguration implements MagicPluginConfiguration {

	public static final String PLUGIN_NAME = "集成流程";

	private final ObjectProvider<MagicFlowController.Services> services;

	public MagicFlowConfiguration(ObjectProvider<MagicFlowController.Services> services) {
		this.services = services;
	}

	@Bean
	@ConditionalOnMissingBean
	public FlowVariables flowVariables(MagicFlowProperties properties, ObjectProvider<UploadChannelEncryptor> encryptor, Environment environment) {
		return new FlowVariables(properties.getVariablesFile(), encryptor.getIfAvailable(), name -> {
			String value = properties.getVars().get(name);
			return value != null ? value : environment.getProperty("magic-api.flow.vars." + name);
		});
	}

	@Bean
	@ConditionalOnMissingBean
	public FlowInfoMagicResourceStorage flowInfoMagicResourceStorage() {
		return new FlowInfoMagicResourceStorage();
	}

	@Bean
	@ConditionalOnMissingBean
	public FlowMagicDynamicRegistry flowMagicDynamicRegistry(FlowInfoMagicResourceStorage storage,
															 ObjectProvider<FlowTriggerRegistrar> registrars) {
		return new FlowMagicDynamicRegistry(storage, registrars.orderedStream().collect(Collectors.toList()));
	}

	// ------------------------------------------------------------------ 节点

	@Bean
	public QueryNodeHandler flowQueryNodeHandler(ObjectProvider<SQLModule> sqlModule) {
		return new QueryNodeHandler(sqlModule.getIfAvailable());
	}

	@Bean
	public WriteNodeHandler flowWriteNodeHandler(ObjectProvider<SQLModule> sqlModule) {
		return new WriteNodeHandler(sqlModule.getIfAvailable());
	}

	@Bean
	public SaveNodeHandler flowSaveNodeHandler(ObjectProvider<SQLModule> sqlModule) {
		return new SaveNodeHandler(sqlModule.getIfAvailable());
	}

	@Bean
	public ProcService flowProcService(ObjectProvider<SQLModule> sqlModule) {
		return new ProcService(sqlModule::getIfAvailable);
	}

	@Bean
	public ProcNodeHandler flowProcNodeHandler(ProcService procService) {
		return new ProcNodeHandler(procService);
	}

	@Bean
	public ProcModule flowProcModule(ProcService procService) {
		return new ProcModule(procService);
	}

	@Bean
	public ScriptNodeHandler flowScriptNodeHandler() {
		return new ScriptNodeHandler();
	}

	@Bean
	public ReturnNodeHandler flowReturnNodeHandler() {
		return new ReturnNodeHandler();
	}

	@Bean
	public MapNodeHandler flowMapNodeHandler() {
		return new MapNodeHandler();
	}

	@Bean
	public NestNodeHandler flowNestNodeHandler() {
		return new NestNodeHandler();
	}

	@Bean
	public ValidateNodeHandler flowValidateNodeHandler() {
		return new ValidateNodeHandler();
	}

	@Bean
	public MessageNodeHandlers.Template flowTemplateNodeHandler() {
		return new MessageNodeHandlers.Template();
	}

	@Bean
	public MessageNodeHandlers.Xml flowXmlNodeHandler() {
		return new MessageNodeHandlers.Xml();
	}

	@Bean
	public MessageNodeHandlers.Json flowJsonNodeHandler() {
		return new MessageNodeHandlers.Json();
	}

	@Bean
	public CallNodeHandler flowCallNodeHandler(ObjectProvider<UploadChannelMagicDynamicRegistry> registry,
											   ObjectProvider<UploadChannelMagicResourceStorage> storage,
											   ObjectProvider<ChannelFactory> factory,
											   ObjectProvider<FlowVariables> variables) {
		return new CallNodeHandler(registry::getIfAvailable, storage::getIfAvailable, factory::getIfAvailable, variables::getIfAvailable);
	}

	@Bean
	public TriggerNodeHandler.Http flowHttpTriggerNodeHandler() {
		return new TriggerNodeHandler.Http();
	}

	@Bean
	public TriggerNodeHandler.Soap flowSoapTriggerNodeHandler() {
		return new TriggerNodeHandler.Soap();
	}

	@Bean
	public TriggerNodeHandler.Timer flowTimerTriggerNodeHandler() {
		return new TriggerNodeHandler.Timer();
	}

	/**
	 * 收集全部节点类型；项目自定义的 FlowNodeHandler Bean 用 @Order 排在前面即可覆盖同名内置节点
	 */
	@Bean
	@ConditionalOnMissingBean
	public FlowEngine flowEngine(ObjectProvider<FlowNodeHandler> handlers, FlowVariables variables, MagicFlowProperties properties) {
		FlowEngine engine = new FlowEngine(handlers.orderedStream().collect(Collectors.toList()));
		engine.setVariables(variables);
		engine.setMaxSteps(properties.getMaxSteps());
		return engine;
	}

	@Bean
	@ConditionalOnMissingBean
	public FlowRunner flowRunner(FlowEngine engine, FlowMagicDynamicRegistry registry, MagicFlowProperties properties) {
		return new FlowRunner(engine, registry, properties.getRecordSize());
	}

	// ------------------------------------------------------------------ 触发

	@Bean(destroyMethod = "shutdown")
	@ConditionalOnMissingBean(name = "magicFlowTaskScheduler")
	public ThreadPoolTaskScheduler magicFlowTaskScheduler(MagicFlowProperties properties) {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(Math.max(1, properties.getSchedulerPoolSize()));
		scheduler.setThreadNamePrefix("magic-flow-");
		scheduler.setWaitForTasksToCompleteOnShutdown(false);
		scheduler.initialize();
		return scheduler;
	}

	@Bean
	@ConditionalOnMissingBean
	public FlowJobService flowJobService(ObjectProvider<FlowRunner> runner, ObjectProvider<UploadJobRunner> jobRunner,
										 ObjectProvider<MagicDynamicDataSource> dataSource, ObjectProvider<List<Dialect>> dialects) {
		DialectAdapter adapter = new DialectAdapter();
		dialects.getIfAvailable(Collections::emptyList).forEach(adapter::add);
		return new FlowJobService(runner::getObject, jobRunner::getIfAvailable, dataSource::getIfAvailable, adapter);
	}

	@Bean
	public HttpFlowTriggerRegistrar flowHttpTriggerRegistrar(@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mapping,
															 ObjectProvider<FlowRunner> runner, ObjectProvider<MagicAPIProperties> apiProperties,
															 MagicFlowProperties properties) {
		return new HttpFlowTriggerRegistrar(mapping::getObject, runner::getObject, basePath(apiProperties, properties.getHttpPrefix()));
	}

	@Bean
	public SoapFlowTriggerRegistrar flowSoapTriggerRegistrar(@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mapping,
															 ObjectProvider<FlowRunner> runner, ObjectProvider<MagicAPIProperties> apiProperties,
															 MagicFlowProperties properties) {
		return new SoapFlowTriggerRegistrar(mapping::getObject, runner::getObject, basePath(apiProperties, properties.getSoapPrefix()));
	}

	@Bean
	public TimerFlowTriggerRegistrar flowTimerTriggerRegistrar(ObjectProvider<FlowRunner> runner, FlowJobService jobService,
															   @Qualifier("magicFlowTaskScheduler") ThreadPoolTaskScheduler scheduler) {
		return new TimerFlowTriggerRegistrar(runner::getObject, jobService, scheduler);
	}

	// ------------------------------------------------------------------ 消息中心

	@Bean
	public MsgTriggerNodeHandler flowMsgTriggerNodeHandler() {
		return new MsgTriggerNodeHandler();
	}

	/**
	 * 消息表的数据源：magic-api.flow.msg.datasource → magic-api.upload.datasource → 主数据源，首次使用时解析
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "magic-api.flow.msg", name = "enable", havingValue = "true", matchIfMissing = true)
	public JdbcMsgStore flowMsgStore(ObjectProvider<MagicDynamicDataSource> dynamicDataSource, MagicFlowProperties properties, Environment environment) {
		MagicFlowProperties.Msg msg = properties.getMsg();
		String key = StringUtils.trimToNull(StringUtils.defaultIfBlank(msg.getDatasource(), environment.getProperty("magic-api.upload.datasource")));
		return new JdbcMsgStore(() -> {
			MagicDynamicDataSource ds = dynamicDataSource.getIfAvailable();
			if (ds == null || ds.isEmpty()) {
				return null;
			}
			return new JdbcTemplate(ds.getDataSource(key).getDataSource());
		}, msg.getTablePrefix(), msg.isInitSchema());
	}

	@Bean
	@ConditionalOnProperty(prefix = "magic-api.flow.msg", name = "enable", havingValue = "true", matchIfMissing = true)
	public MsgCenter flowMsgCenter(@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mapping,
								   ObjectProvider<FlowRunner> runner, SoapFlowTriggerRegistrar soap, JdbcMsgStore store,
								   ObjectProvider<MagicAPIProperties> apiProperties, MagicFlowProperties properties) {
		MagicFlowProperties.Msg msg = properties.getMsg();
		return new MsgCenter(mapping::getObject, runner::getObject, soap, store, basePath(apiProperties, msg.getHttpPath()), msg.getSoapService());
	}

	@Bean(initMethod = "start", destroyMethod = "stop")
	@ConditionalOnProperty(prefix = "magic-api.flow.msg", name = "enable", havingValue = "true", matchIfMissing = true)
	public MsgDispatcher flowMsgDispatcher(MsgCenter center, ObjectProvider<FlowRunner> runner, ObjectProvider<CallNodeHandler> caller,
										   ObjectProvider<UploadAlertService> alerts, MagicFlowProperties properties,
										   @Qualifier("magicFlowTaskScheduler") ThreadPoolTaskScheduler scheduler) {
		MagicFlowProperties.Msg msg = properties.getMsg();
		return new MsgDispatcher(center, runner::getObject, caller::getIfAvailable, alerts::getIfAvailable, msg.getThreads(),
				msg.getPollInterval(), msg.getProcessingTimeout(), msg.getAlertInterval(), msg.getRetainDays(), msg.getCleanupCron(), scheduler);
	}

	private static String basePath(ObjectProvider<MagicAPIProperties> apiProperties, String sub) {
		MagicAPIProperties api = apiProperties.getIfAvailable();
		String prefix = api == null ? "" : StringUtils.defaultString(api.getPrefix());
		return "/" + prefix + "/" + StringUtils.defaultString(sub);
	}

	// ------------------------------------------------------------------ 模板与配置包

	@Bean
	@ConditionalOnMissingBean
	public FlowTemplates flowTemplates() {
		return new FlowTemplates();
	}

	@Bean
	@ConditionalOnMissingBean
	public FlowPackageService flowPackageService(ObjectProvider<MagicResourceService> resourceService, FlowInfoMagicResourceStorage storage,
												 ObjectProvider<UploadChannelMagicResourceStorage> channelStorage, FlowVariables variables,
												 ObjectProvider<MagicBackupService> backupService, MagicFlowProperties properties) {
		return new FlowPackageService(resourceService::getObject, storage, channelStorage::getIfAvailable, variables,
				backupService::getIfAvailable, properties.getBackupDir());
	}

	@Bean
	public MagicFlowController.Services flowControllerServices(FlowEngine engine, FlowRunner runner, FlowJobService jobService,
															   FlowVariables variables, FlowTemplates templates, FlowPackageService packages,
															   ObjectProvider<MsgCenter> msgCenter, ObjectProvider<MsgDispatcher> msgDispatcher,
															   ProcService procService) {
		return new MagicFlowController.Services(engine, runner, jobService, variables, templates, packages,
				msgCenter.getIfAvailable(), msgDispatcher.getIfAvailable(), procService);
	}

	@Override
	public Plugin plugin() {
		return new Plugin(PLUGIN_NAME, "MagicFlow", "magic-flow.1.0.0.iife.js");
	}

	@Override
	public MagicControllerRegister controllerRegister() {
		return (mapping, configuration) -> mapping.registerController(new MagicFlowController(configuration, services.getObject()));
	}
}
