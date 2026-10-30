package org.ssssssss.magicapi.flow.trigger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.ssssssss.magicapi.flow.engine.FlowEngine;
import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.flow.model.FlowDefinition;
import org.ssssssss.magicapi.flow.model.FlowInfo;
import org.ssssssss.magicapi.flow.service.FlowRunner;
import org.ssssssss.magicapi.flow.service.FlowTriggerRegistrar;

import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

/**
 * 定时触发：起始节点为 timer 的已启用流程按 cron 调度。
 * 多实例部署时由任务锁保证同一时刻只有一个实例在执行。
 */
public class TimerFlowTriggerRegistrar implements FlowTriggerRegistrar {

	private static final Logger logger = LoggerFactory.getLogger(TimerFlowTriggerRegistrar.class);

	private final Supplier<FlowRunner> runnerSupplier;

	private final FlowJobService jobService;

	private final TaskScheduler scheduler;

	public TimerFlowTriggerRegistrar(Supplier<FlowRunner> runnerSupplier, FlowJobService jobService, TaskScheduler scheduler) {
		this.runnerSupplier = runnerSupplier;
		this.jobService = jobService;
		this.scheduler = scheduler;
	}

	@Override
	public Object register(FlowInfo info, String mappingKey) {
		FlowRunner runner = runnerSupplier.get();
		FlowDefinition definition = runner.definition(info);
		FlowDefinition.Node start = FlowEngine.startNode(definition);
		if (start == null || !TriggerNodeHandler.Timer.TYPE.equals(start.getType())) {
			return null;
		}
		runner.getEngine().prepare(definition);
		String cron = new FlowNodeContext(start, null, runner.getEngine().getVariables()).requireString("cron");
		String id = info.getId();
		ScheduledFuture<?> future = scheduler.schedule(() -> jobService.runScheduled(id), new CronTrigger(cron));
		logger.info("注册定时流程：{}（{}）", info.getName(), cron);
		return new Handle(future, cron);
	}

	@Override
	public void unregister(FlowInfo info, Object handle) {
		Handle h = (Handle) handle;
		if (h.future != null) {
			// 正在执行的批次不打断，执行完后不再调度
			h.future.cancel(false);
		}
		logger.info("取消定时流程：{}", info.getName());
	}

	@Override
	public String describe(Object handle) {
		return "定时 " + ((Handle) handle).cron;
	}

	static final class Handle {

		final ScheduledFuture<?> future;

		final String cron;

		Handle(ScheduledFuture<?> future, String cron) {
			this.future = future;
			this.cron = cron;
		}
	}
}
