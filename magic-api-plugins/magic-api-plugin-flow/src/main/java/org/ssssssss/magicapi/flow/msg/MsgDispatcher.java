package org.ssssssss.magicapi.flow.msg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.ssssssss.magicapi.flow.engine.FlowEngine;
import org.ssssssss.magicapi.flow.engine.FlowResult;
import org.ssssssss.magicapi.flow.engine.node.CallNodeHandler;
import org.ssssssss.magicapi.flow.model.FlowInfo;
import org.ssssssss.magicapi.flow.service.FlowRunner;
import org.ssssssss.magicapi.flow.support.FlowJson;
import org.ssssssss.magicapi.flow.support.FlowScripts;
import org.ssssssss.magicapi.flow.support.FlowTemplate;
import org.ssssssss.magicapi.upload.alert.UploadAlert;
import org.ssssssss.magicapi.upload.alert.UploadAlertService;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 消息消费调度：领取待处理消息交给对应流程，失败按退避重试，用完转死信；顺序消费、完成后回调、告警按类型配置。
 * <ul>
 *     <li>按 poll-interval 轮询，收到消息时立即唤醒</li>
 *     <li>领取是带条件的 UPDATE，多台服务共用一张表时同一条消息只处理一次</li>
 *     <li>处理中超过 processing-timeout 的消息（宕机、卡死）恢复为待重试，回调同理</li>
 *     <li>每天按 cleanup-cron 清理超过 retain-days 的成功、已忽略消息</li>
 * </ul>
 */
public class MsgDispatcher {

	private static final Logger logger = LoggerFactory.getLogger(MsgDispatcher.class);

	private static final int ORDER_WINDOW = 500;

	private static final int MAX_TRACE = 200_000;

	private static final long CALLBACK_TIMEOUT = 120_000;

	private final MsgCenter center;

	private final JdbcMsgStore store;

	private final Supplier<FlowRunner> runner;

	private final Supplier<CallNodeHandler> caller;

	private final Supplier<UploadAlertService> alerts;

	private final int threads;

	private final Duration pollInterval;

	private final Duration processingTimeout;

	private final Duration alertInterval;

	private final int retainDays;

	private final String cleanupCron;

	private final TaskScheduler scheduler;

	private final String owner;

	private final Map<String, AtomicInteger> inflight = new ConcurrentHashMap<>();

	private final AtomicInteger totalInflight = new AtomicInteger();

	private final AtomicBoolean pollQueued = new AtomicBoolean();

	private final AtomicBoolean callbackQueued = new AtomicBoolean();

	private final ReentrantLock pollLock = new ReentrantLock();

	private final ReentrantLock callbackLock = new ReentrantLock();

	private final List<ScheduledFuture<?>> tasks = new ArrayList<>();

	private volatile ThreadPoolExecutor workers;

	private volatile ScheduledExecutorService ticker;

	private volatile long lastErrorLog;

	public MsgDispatcher(MsgCenter center, Supplier<FlowRunner> runner, Supplier<CallNodeHandler> caller, Supplier<UploadAlertService> alerts,
						 int threads, Duration pollInterval, Duration processingTimeout, Duration alertInterval, int retainDays,
						 String cleanupCron, TaskScheduler scheduler) {
		this.center = center;
		this.store = center.getStore();
		this.runner = runner;
		this.caller = caller;
		this.alerts = alerts;
		this.threads = Math.max(1, threads);
		this.pollInterval = pollInterval == null || pollInterval.isZero() ? Duration.ofSeconds(1) : pollInterval;
		this.processingTimeout = processingTimeout == null || processingTimeout.isZero() ? Duration.ofMinutes(10) : processingTimeout;
		this.alertInterval = alertInterval == null || alertInterval.isZero() ? Duration.ofMinutes(1) : alertInterval;
		this.retainDays = retainDays;
		this.cleanupCron = cleanupCron;
		this.scheduler = scheduler;
		this.owner = owner();
	}

	private static String owner() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		} catch (Exception e) {
			host = "unknown";
		}
		String pid = ManagementFactory.getRuntimeMXBean().getName();
		int at = pid.indexOf('@');
		return JdbcMsgStore.truncate(host + ":" + (at > 0 ? pid.substring(0, at) : pid) + ":" + UUID.randomUUID().toString().substring(0, 8), 128);
	}

	public String getOwner() {
		return owner;
	}

	public synchronized void start() {
		if (workers != null) {
			return;
		}
		AtomicInteger seq = new AtomicInteger();
		workers = new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
			Thread thread = new Thread(r, "magic-flow-msg-" + seq.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		ticker = Executors.newScheduledThreadPool(2, r -> {
			Thread thread = new Thread(r, "magic-flow-msg-tick");
			thread.setDaemon(true);
			return thread;
		});
		long poll = pollInterval.toMillis();
		ticker.scheduleWithFixedDelay(guard("消费", this::pollOnce), poll, poll, TimeUnit.MILLISECONDS);
		ticker.scheduleWithFixedDelay(guard("回调", this::pollCallbacks), poll, Math.max(poll, 5000), TimeUnit.MILLISECONDS);
		ticker.scheduleWithFixedDelay(guard("超时恢复", () -> store.recoverTimeout(processingTimeout.toMillis())), 5000, 60_000, TimeUnit.MILLISECONDS);
		ticker.scheduleWithFixedDelay(guard("告警检查", this::checkAlerts), alertInterval.toMillis(), alertInterval.toMillis(), TimeUnit.MILLISECONDS);
		center.onReceived(type -> wake());
		if (retainDays > 0 && scheduler != null && cleanupCron != null && !cleanupCron.trim().isEmpty()) {
			tasks.add(scheduler.schedule(guard("清理", this::cleanup), new CronTrigger(cleanupCron.trim())));
		}
		logger.info("消息中心消费调度已启动：{} 个线程，轮询间隔 {} ms，节点 {}", threads, poll, owner);
	}

	public synchronized void stop() {
		center.onReceived(null);
		tasks.forEach(task -> task.cancel(false));
		tasks.clear();
		if (ticker != null) {
			ticker.shutdownNow();
			ticker = null;
		}
		if (workers != null) {
			workers.shutdown();
			try {
				if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
					logger.warn("消息中心停止时仍有消息未处理完，这些消息将在超时后由其他节点或下次启动重新处理");
					workers.shutdownNow();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				workers.shutdownNow();
			}
			workers = null;
		}
	}

	/**
	 * 立即领取一轮（收到消息、处理完一条时调用）
	 */
	public void wake() {
		ScheduledExecutorService t = ticker;
		if (t != null && pollQueued.compareAndSet(false, true)) {
			try {
				t.execute(guard("消费", () -> {
					pollQueued.set(false);
					pollOnce();
				}));
			} catch (RejectedExecutionException e) {
				pollQueued.set(false);
			}
		}
	}

	private void wakeCallbacks() {
		ScheduledExecutorService t = ticker;
		if (t != null && callbackQueued.compareAndSet(false, true)) {
			try {
				t.execute(guard("回调", () -> {
					callbackQueued.set(false);
					pollCallbacks();
				}));
			} catch (RejectedExecutionException e) {
				callbackQueued.set(false);
			}
		}
	}

	// ------------------------------------------------------------------ 消费

	/**
	 * 领取一轮：每种已登记类型按剩余并发数领取
	 */
	public void pollOnce() {
		ThreadPoolExecutor pool = workers;
		if (pool == null || !pollLock.tryLock()) {
			return;
		}
		try {
			for (Map.Entry<String, String> binding : center.bindings().entrySet()) {
				String type = binding.getKey();
				int globalFree = threads - totalInflight.get();
				if (globalFree <= 0) {
					return;
				}
				MsgTypeConfig config;
				try {
					config = center.config(type);
				} catch (RuntimeException e) {
					logError("类型 " + type + " 的配置不正确，暂停消费：" + FlowEngine.message(e), null);
					continue;
				}
				if (config == null) {
					continue;
				}
				AtomicInteger running = inflight.computeIfAbsent(type, k -> new AtomicInteger());
				int free = Math.min(config.concurrency - running.get(), globalFree);
				if (free <= 0) {
					continue;
				}
				boolean ordered = config.ordered();
				List<String> ids = ordered ? store.dueHeads(type, config.blockOnDead, free, ORDER_WINDOW) : store.due(type, free);
				for (String id : ids) {
					FlowMessage message = store.claim(id, owner, ordered, config.blockOnDead);
					if (message == null) {
						continue;
					}
					running.incrementAndGet();
					totalInflight.incrementAndGet();
					try {
						pool.execute(() -> {
							try {
								process(message, config);
							} finally {
								running.decrementAndGet();
								totalInflight.decrementAndGet();
								wake();
							}
						});
					} catch (RejectedExecutionException e) {
						running.decrementAndGet();
						totalInflight.decrementAndGet();
						// 停止中：消息保持处理中，超时后恢复
						return;
					}
				}
			}
		} finally {
			pollLock.unlock();
		}
	}

	void process(FlowMessage message, MsgTypeConfig config) {
		FlowInfo info = center.flow(message.getType());
		long start = System.currentTimeMillis();
		FlowResult result = null;
		String error;
		if (info == null) {
			error = "消息类型 " + message.getType() + " 没有对应的已启用流程";
		} else {
			try {
				result = runner.get().run(info, "msg", MsgInput.fromMessage(message));
				error = result.isSuccess() ? null : result.getMessage();
			} catch (RuntimeException e) {
				error = FlowEngine.message(e);
			}
		}
		long cost = System.currentTimeMillis() - start;
		try {
			if (error == null) {
				boolean callback = config.callbackOnSuccess();
				store.success(message.getId(), owner, FlowJson.abbreviate(result.getValue(), 1800), cost, callback);
				if (callback) {
					wakeCallbacks();
				}
				return;
			}
			int attempt = message.getAttempts();
			boolean dead = attempt > config.maxRetries;
			Date next = dead ? null : new Date(System.currentTimeMillis() + MsgTypeConfig.delay(config.backoff, attempt));
			String trace = result == null || result.getTrace() == null ? null : traceJson(result);
			boolean callback = dead && config.callbackOnDead();
			if (!store.fail(message.getId(), owner, error, result == null ? null : result.getErrorNode(), trace, cost, next, callback)) {
				logger.warn("消息 {}/{} 的处理结果没有保存（可能已超时被恢复）", message.getType(), message.getMsgId());
				return;
			}
			if (dead) {
				logger.warn("消息 {}/{} 第 {} 次处理失败，转为死信：{}", message.getType(), message.getMsgId(), attempt, error);
				if (config.alertDead) {
					alert(config.type, "DEAD", "消息类型 " + config.type + " 有消息成为死信",
							"消息 id：" + message.getMsgId() + "\n已处理 " + attempt + " 次\n原因：" + error);
				}
				if (config.ordered() && config.blockOnDead && config.alertBlocked) {
					alert(config.type, "BLOCKED", "消息类型 " + config.type + " 顺序消费被死信阻塞",
							"消息 id：" + message.getMsgId() + (JdbcMsgStore.WHOLE_TYPE.equals(message.getOrderKey()) ? "（整个类型）" : "（分组 " + message.getOrderKey() + "）")
									+ "\n后面的消息暂停处理，请在消息中心重试或忽略这条消息\n原因：" + error);
				}
				if (callback) {
					wakeCallbacks();
				}
			} else {
				logger.info("消息 {}/{} 第 {} 次处理失败，{} 秒后重试：{}", message.getType(), message.getMsgId(), attempt,
						(next.getTime() - System.currentTimeMillis()) / 1000, error);
			}
		} catch (RuntimeException e) {
			logError("保存消息 " + message.getType() + "/" + message.getMsgId() + " 的处理结果失败", e);
		}
	}

	// ------------------------------------------------------------------ 回调

	public void pollCallbacks() {
		ThreadPoolExecutor pool = workers;
		if (pool == null || !callbackLock.tryLock()) {
			return;
		}
		try {
			int free = threads - totalInflight.get();
			if (free <= 0) {
				return;
			}
			for (String id : store.dueCallbacks(free)) {
				FlowMessage message = store.claimCallback(id, CALLBACK_TIMEOUT);
				if (message == null) {
					continue;
				}
				totalInflight.incrementAndGet();
				try {
					pool.execute(() -> {
						try {
							callback(message);
						} finally {
							totalInflight.decrementAndGet();
						}
					});
				} catch (RejectedExecutionException e) {
					totalInflight.decrementAndGet();
					return;
				}
			}
		} finally {
			callbackLock.unlock();
		}
	}

	void callback(FlowMessage message) {
		MsgTypeConfig config;
		try {
			config = center.config(message.getType());
		} catch (RuntimeException e) {
			config = null;
		}
		if (config == null || MsgTypeConfig.CALLBACK_NONE.equals(config.callback)) {
			store.callbackDone(message.getId(), false, "消息类型 " + message.getType() + " 没有登记或没有配置回调", null, null);
			return;
		}
		String error;
		String response = null;
		try {
			Map<String, Object> vars = callbackVariables(message);
			String template = MsgStatus.DEAD.equals(message.getStatus()) && config.callbackDeadBody != null ? config.callbackDeadBody : config.callbackBody;
			String name = "消息/" + config.type + "/回调";
			String body = FlowTemplate.render(template, config.callbackFormat, (expression, scope) -> FlowScripts.evaluate(expression, merge(vars, scope), name));
			Map<String, String> headers = new LinkedHashMap<>();
			config.callbackHeaders.forEach((k, v) -> headers.put(k, FlowTemplate.interpolate(v, (expression, scope) -> FlowScripts.evaluate(expression, merge(vars, scope), name))));
			CallNodeHandler handler = caller.get();
			if (handler == null) {
				throw new IllegalStateException("没有启用调用外部系统节点，无法回调");
			}
			Map<String, Object> output = handler.callChannel(config.callbackChannel, body, headers, "消息回调/" + config.type);
			response = output.get("response") == null ? null : output.get("response").toString();
			error = Boolean.TRUE.equals(output.get("success")) ? null : String.valueOf(output.get("message"));
		} catch (RuntimeException e) {
			error = FlowEngine.message(e);
		}
		if (error == null) {
			store.callbackDone(message.getId(), true, null, response, null);
			return;
		}
		int attempt = message.getCbAttempts();
		boolean giveUp = attempt > config.callbackRetries;
		Date next = giveUp ? null : new Date(System.currentTimeMillis() + MsgTypeConfig.delay(config.callbackBackoff, attempt));
		store.callbackDone(message.getId(), false, error, response, next);
		if (giveUp) {
			logger.warn("消息 {}/{} 回调失败 {} 次，不再重试：{}", message.getType(), message.getMsgId(), attempt, error);
			if (config.alertCallback) {
				alert(config.type, "CALLBACK", "消息类型 " + config.type + " 回调失败",
						"消息 id：" + message.getMsgId() + "\n已回调 " + attempt + " 次\n原因：" + error);
			}
		}
	}

	/**
	 * 执行轨迹（与调试运行相同的结构）；过长时去掉各节点输出，只保留节点、状态、耗时、错误
	 */
	static String traceJson(FlowResult result) {
		try {
			String json = FlowJson.MAPPER.writeValueAsString(result.getTrace());
			if (json.length() <= MAX_TRACE) {
				return json;
			}
			List<Map<String, Object>> brief = new ArrayList<>();
			for (org.ssssssss.magicapi.flow.engine.FlowTrace t : result.getTrace()) {
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("nodeId", t.getNodeId());
				item.put("name", t.getName());
				item.put("type", t.getType());
				item.put("success", t.isSuccess());
				item.put("duration", t.getDuration());
				item.put("error", t.getError());
				item.put("next", t.getNext());
				brief.add(item);
			}
			return FlowJson.abbreviate(brief, MAX_TRACE);
		} catch (Exception e) {
			return null;
		}
	}

	private static Map<String, Object> callbackVariables(FlowMessage message) {
		Map<String, Object> input = MsgInput.fromMessage(message);
		Map<String, Object> vars = new LinkedHashMap<>(input);
		Object result = message.getResult() == null ? null : FlowJson.tryParse(message.getResult());
		vars.put("result", result == null ? message.getResult() : result);
		vars.put("status", message.getStatus());
		vars.put("error", message.getError());
		vars.put("input", input);
		return vars;
	}

	private static Map<String, Object> merge(Map<String, Object> vars, Map<String, Object> scope) {
		if (scope == null || scope.isEmpty()) {
			return vars;
		}
		Map<String, Object> merged = new LinkedHashMap<>(vars);
		merged.putAll(scope);
		return merged;
	}

	// ------------------------------------------------------------------ 告警与清理

	/**
	 * 积压、延迟告警（按类型阈值）
	 */
	public void checkAlerts() {
		Map<String, String> bindings = center.bindings();
		if (bindings.isEmpty()) {
			return;
		}
		Map<String, JdbcMsgStore.TypeStats> stats = null;
		for (String type : bindings.keySet()) {
			MsgTypeConfig config;
			try {
				config = center.config(type);
			} catch (RuntimeException e) {
				continue;
			}
			if (config == null || (config.alertBacklog <= 0 && config.alertDelayMinutes <= 0)) {
				continue;
			}
			if (stats == null) {
				stats = store.stats();
			}
			JdbcMsgStore.TypeStats s = stats.get(type);
			if (s == null) {
				continue;
			}
			if (config.alertBacklog > 0 && s.getBacklog() >= config.alertBacklog) {
				alert(type, "BACKLOG", "消息类型 " + type + " 积压 " + s.getBacklog() + " 条",
						"待处理 " + (s.getBacklog() - s.getRetrying()) + " 条，待重试 " + s.getRetrying() + " 条，处理中 " + s.getProcessing() + " 条（阈值 " + config.alertBacklog + "）");
			}
			if (config.alertDelayMinutes > 0 && s.getOldest() != null) {
				long minutes = (System.currentTimeMillis() - s.getOldest().getTime()) / 60_000;
				if (minutes >= config.alertDelayMinutes) {
					alert(type, "DELAY", "消息类型 " + type + " 有消息 " + minutes + " 分钟未处理",
							"最早一条待处理消息接收于 " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(s.getOldest()) + "（阈值 " + config.alertDelayMinutes + " 分钟）");
				}
			}
		}
	}

	private void alert(String type, String kind, String title, String message) {
		UploadAlertService service = alerts.get();
		if (service == null) {
			logger.warn("消息中心告警（上传插件告警未启用，只记日志）：{}\n{}", title, message);
			return;
		}
		service.send(new UploadAlert(UploadAlert.Type.MESSAGE, "消息 " + type, service.getHospital(), kind, title, message, new Date()));
	}

	public int cleanup() {
		if (retainDays <= 0) {
			return 0;
		}
		Date before = new Date(System.currentTimeMillis() - retainDays * 24L * 3600 * 1000);
		int total = 0;
		for (int i = 0; i < 200; i++) {
			int deleted = store.cleanup(before, 1000);
			total += deleted;
			if (deleted < 1000) {
				break;
			}
		}
		if (total > 0) {
			logger.info("消息中心清理了 {} 条 {} 天前完成的消息", total, retainDays);
		}
		return total;
	}

	// ------------------------------------------------------------------ 工具

	private Runnable guard(String what, Runnable action) {
		return () -> {
			try {
				action.run();
			} catch (RuntimeException e) {
				logError("消息中心" + what + "失败", e);
			}
		};
	}

	/**
	 * 数据库不可用时每分钟最多记一次详细日志
	 */
	private void logError(String message, Throwable e) {
		long now = System.currentTimeMillis();
		if (now - lastErrorLog > 60_000) {
			lastErrorLog = now;
			logger.error(message, e);
		} else {
			logger.debug(message, e);
		}
	}
}
