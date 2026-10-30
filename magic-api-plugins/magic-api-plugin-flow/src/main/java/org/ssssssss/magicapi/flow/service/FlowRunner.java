package org.ssssssss.magicapi.flow.service;

import org.ssssssss.magicapi.flow.engine.FlowEngine;
import org.ssssssss.magicapi.flow.engine.FlowResult;
import org.ssssssss.magicapi.flow.model.FlowDefinition;
import org.ssssssss.magicapi.flow.model.FlowDefinitions;
import org.ssssssss.magicapi.flow.model.FlowInfo;
import org.ssssssss.magicapi.flow.support.FlowJson;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 触发器执行流程的统一入口：缓存解析后的定义、执行、保留最近运行记录。
 */
public class FlowRunner {

	private static final int PREVIEW = 2000;

	private final FlowEngine engine;

	private final FlowMagicDynamicRegistry registry;

	private final int recordSize;

	private final Map<String, Deque<FlowRunRecord>> records = new ConcurrentHashMap<>();

	private final Map<String, Cached> definitions = new ConcurrentHashMap<>();

	public FlowRunner(FlowEngine engine, FlowMagicDynamicRegistry registry, int recordSize) {
		this.engine = engine;
		this.registry = registry;
		this.recordSize = Math.max(recordSize, 1);
	}

	public FlowEngine getEngine() {
		return engine;
	}

	public FlowMagicDynamicRegistry getRegistry() {
		return registry;
	}

	/**
	 * 按 id 取当前（最新保存的）流程
	 */
	public FlowInfo flow(String id) {
		return registry.getMapping(id);
	}

	public FlowDefinition definition(FlowInfo info) {
		Cached cached = definitions.get(info.getId());
		if (cached == null || !Objects.equals(cached.script, info.getScript())) {
			cached = new Cached(info.getScript(), FlowDefinitions.parse(info.getScript()));
			definitions.put(info.getId(), cached);
		}
		return cached.definition;
	}

	public FlowResult run(FlowInfo info, String trigger, Map<String, Object> input) {
		long start = System.currentTimeMillis();
		FlowResult result;
		try {
			result = engine.run(definition(info), info.getName(), input, false);
		} catch (RuntimeException e) {
			record(info, trigger, input, start, null, e);
			throw e;
		}
		record(info, trigger, input, start, result, null);
		return result;
	}

	private void record(FlowInfo info, String trigger, Map<String, Object> input, long start, FlowResult result, RuntimeException error) {
		FlowRunRecord record = new FlowRunRecord();
		record.setFlowId(info.getId());
		record.setFlowName(info.getName());
		record.setTrigger(trigger);
		record.setStartTime(start);
		record.setDuration(System.currentTimeMillis() - start);
		record.setInput(FlowJson.abbreviate(input, PREVIEW));
		if (result != null) {
			record.setSuccess(result.isSuccess());
			record.setErrorNode(result.getErrorNode());
			record.setMessage(result.getMessage());
			record.setOutput(FlowJson.abbreviate(result.getValue(), PREVIEW));
		} else {
			record.setSuccess(false);
			record.setMessage(FlowEngine.message(error));
		}
		Deque<FlowRunRecord> deque = records.computeIfAbsent(info.getId(), k -> new ArrayDeque<>());
		synchronized (deque) {
			deque.addFirst(record);
			while (deque.size() > recordSize) {
				deque.removeLast();
			}
		}
	}

	public List<FlowRunRecord> records(String id) {
		Deque<FlowRunRecord> deque = records.get(id);
		if (deque == null) {
			return Collections.emptyList();
		}
		synchronized (deque) {
			return new ArrayList<>(deque);
		}
	}

	private static final class Cached {

		final String script;

		final FlowDefinition definition;

		Cached(String script, FlowDefinition definition) {
			this.script = script;
			this.definition = definition;
		}
	}
}
