package org.ssssssss.magicapi.flow.trigger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.datasource.model.MagicDynamicDataSource;
import org.ssssssss.magicapi.flow.FlowJsonCodes;
import org.ssssssss.magicapi.flow.engine.FlowEngine;
import org.ssssssss.magicapi.flow.engine.FlowNodeContext;
import org.ssssssss.magicapi.flow.engine.FlowNodeException;
import org.ssssssss.magicapi.flow.engine.FlowResult;
import org.ssssssss.magicapi.flow.model.FlowDefinition;
import org.ssssssss.magicapi.flow.model.FlowInfo;
import org.ssssssss.magicapi.flow.service.FlowRunner;
import org.ssssssss.magicapi.flow.support.FlowJson;
import org.ssssssss.magicapi.modules.db.dialect.Dialect;
import org.ssssssss.magicapi.modules.db.dialect.DialectAdapter;
import org.ssssssss.magicapi.upload.engine.*;
import org.ssssssss.magicapi.upload.service.RetryResult;
import org.ssssssss.magicapi.upload.service.UploadJobRunner;
import org.ssssssss.magicapi.upload.source.KeysetSqlSource;
import org.ssssssss.magicapi.upload.source.OffsetSqlSource;
import org.ssssssss.magicapi.upload.store.FailedBatch;
import org.ssssssss.magicapi.upload.store.UploadRecord;

import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/**
 * 定时流程的执行：复用上传插件的分批引擎、任务锁、断点、执行记录、失败批次与告警。
 * <p>
 * 每批数据执行一次流程（触发节点之后的部分），input.rows 为本批数据、input.batch 为批次信息；
 * 流程成功即本批成功（断点前进），流程失败即本批失败（按 retry 重试，按 failurePolicy 停止或记录后跳过）。
 * 调用节点返回的业务拒绝不重试，网络错误、超时、5xx 重试。
 * <p>
 * 任务名为 "flow:" + 流程路径，改流程路径相当于新任务（断点从头开始）。
 */
public class FlowJobService {

	private static final Logger logger = LoggerFactory.getLogger(FlowJobService.class);

	private final Supplier<FlowRunner> runnerSupplier;

	private final Supplier<UploadJobRunner> jobRunnerSupplier;

	private final Supplier<MagicDynamicDataSource> dataSourceSupplier;

	private final DialectAdapter dialectAdapter;

	public FlowJobService(Supplier<FlowRunner> runnerSupplier, Supplier<UploadJobRunner> jobRunnerSupplier,
						  Supplier<MagicDynamicDataSource> dataSourceSupplier, DialectAdapter dialectAdapter) {
		this.runnerSupplier = runnerSupplier;
		this.jobRunnerSupplier = jobRunnerSupplier;
		this.dataSourceSupplier = dataSourceSupplier;
		this.dialectAdapter = dialectAdapter == null ? new DialectAdapter() : dialectAdapter;
	}

	public String jobName(FlowInfo info) {
		return "flow:" + runnerSupplier.get().getRegistry().mappingKey(info);
	}

	/**
	 * 定时调度调用：异常只记录日志
	 */
	public void runScheduled(String flowId) {
		FlowInfo info = runnerSupplier.get().flow(flowId);
		if (info == null || !info.isEnabled()) {
			return;
		}
		try {
			JobResult result = run(info, "timer");
			logger.info("定时流程[{}]执行结束：{} {}", info.getName(), result.getStatus(), result.getMessage() == null ? "" : result.getMessage());
		} catch (Exception e) {
			logger.error("定时流程[{}]执行失败", info.getName(), e);
		}
	}

	public JobResult run(FlowInfo info, String trigger) {
		Job job = job(info);
		return jobRunner().run(job.options, job.source, new FlowBatchChannel(info, trigger, job.columnCase));
	}

	public RetryResult retryFailed(FlowInfo info, int limit) {
		Job job = job(info);
		return jobRunner().retryFailed(job.options, new FlowBatchChannel(info, "retry", job.columnCase), Math.max(limit, 0));
	}

	public void resetCheckpoint(FlowInfo info) {
		jobRunner().resetCheckpoint(jobName(info));
	}

	public Map<String, Object> status(FlowInfo info, int limit) {
		String jobName = jobName(info);
		UploadJobRunner runner = jobRunner();
		Map<String, Object> status = new LinkedHashMap<>();
		status.put("jobName", jobName);
		status.put("checkpoint", runner.checkpoint(jobName));
		List<Map<String, Object>> records = new ArrayList<>();
		for (UploadRecord record : runner.records(jobName, limit)) {
			records.add(describe(record));
		}
		status.put("records", records);
		List<Map<String, Object>> failed = new ArrayList<>();
		for (FailedBatch batch : runner.failedBatches(jobName, limit)) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", batch.getId());
			item.put("batchIndex", batch.getBatchIndex());
			item.put("rowCount", batch.getRowCount());
			item.put("message", batch.getMessage());
			item.put("retryCount", batch.getRetryCount());
			item.put("createTime", batch.getCreateTime());
			failed.add(item);
		}
		status.put("failedBatches", failed);
		return status;
	}

	private static Map<String, Object> describe(UploadRecord record) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("id", record.getId());
		item.put("status", record.getStatus());
		item.put("totalRows", record.getTotalRows());
		item.put("successRows", record.getSuccessRows());
		item.put("failedRows", record.getFailedRows());
		item.put("totalBatches", record.getTotalBatches());
		item.put("failedBatches", record.getFailedBatches());
		item.put("startTime", record.getStartTime());
		item.put("endTime", record.getEndTime());
		item.put("costMillis", record.getCostMillis());
		item.put("message", record.getMessage());
		return item;
	}

	private UploadJobRunner jobRunner() {
		UploadJobRunner runner = jobRunnerSupplier.get();
		if (runner == null) {
			throw new IllegalStateException("定时流程需要启用上传插件（magic-api.upload.enable）");
		}
		return runner;
	}

	private Job job(FlowInfo info) {
		FlowRunner runner = runnerSupplier.get();
		FlowDefinition definition = runner.definition(info);
		FlowDefinition.Node start = FlowEngine.startNode(definition);
		if (start == null || !TriggerNodeHandler.Timer.TYPE.equals(start.getType())) {
			throw new InvalidArgumentException(FlowJsonCodes.FLOW_NOT_TIMER.format(info.getName()));
		}
		runner.getEngine().prepare(definition);
		FlowNodeContext config = new FlowNodeContext(start, null, runner.getEngine().getVariables());
		Job job = new Job();
		job.options = new JobOptions(jobName(info))
				.batchSize(config.integer("batchSize", 500))
				.retry(config.integer("retry", 0))
				.retryBackoff(Duration.ofSeconds(Math.max(0, config.integer("retryInterval", 5))))
				.failurePolicy("skip".equals(config.oneOf("failurePolicy", "stop", "stop", "skip")) ? FailurePolicy.SKIP : FailurePolicy.STOP)
				.maxBatches(config.integer("maxBatches", 0));
		job.columnCase = config.oneOf("columnCase", "lower", "lower", "camel", "upper", "raw");
		String mode = config.oneOf("mode", "once", "once", "pending", "keyset", "offset");
		if ("once".equals(mode)) {
			job.source = new SingleRunSource();
		} else {
			MagicDynamicDataSource dynamicDataSource = dataSourceSupplier.get();
			if (dynamicDataSource == null) {
				throw new IllegalStateException("没有可用的数据源");
			}
			MagicDynamicDataSource.DataSourceNode node = dynamicDataSource.getDataSource(config.string("datasource"));
			// 新建 JdbcTemplate，不受数据源上"最大返回行数"的限制
			JdbcTemplate jdbc = new JdbcTemplate(node.getDataSource());
			Dialect dialect = node.getDialect(dialectAdapter);
			Map<String, Object> variables = new LinkedHashMap<>();
			variables.put("now", new Date());
			String sql = config.requireString("sql");
			if ("offset".equals(mode)) {
				job.source = new OffsetSqlSource(jdbc, dialect, sql, variables, config.requireString("orderBy"));
			} else {
				KeysetSqlSource keyset = new KeysetSqlSource(jdbc, dialect, sql, variables, config.requireString("keyColumn"));
				job.source = "pending".equals(mode) ? new PendingSource(keyset) : keyset;
			}
		}
		return job;
	}

	/**
	 * 每次执行读取全部待处理数据：本次内按递增字段分批，处理完清除断点
	 */
	static final class PendingSource implements BatchSource {

		private final BatchSource delegate;

		PendingSource(BatchSource delegate) {
			this.delegate = delegate;
		}

		@Override
		public BatchData fetch(Object checkpoint, int batchSize) throws Exception {
			return delegate.fetch(checkpoint, batchSize);
		}

		@Override
		public boolean isIncremental() {
			return false;
		}
	}

	public static Map<String, Object> convertCase(Map<String, Object> row, String columnCase) {
		if ("raw".equals(columnCase)) {
			return new LinkedHashMap<>(row);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		row.forEach((k, v) -> result.put(convert(k, columnCase), v));
		return result;
	}

	private static String convert(String name, String columnCase) {
		switch (columnCase) {
			case "upper":
				return name.toUpperCase(Locale.ROOT);
			case "camel": {
				StringBuilder sb = new StringBuilder();
				boolean upper = false;
				for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
					if (c == '_') {
						upper = true;
					} else {
						sb.append(upper ? Character.toUpperCase(c) : c);
						upper = false;
					}
				}
				return sb.toString();
			}
			default:
				return name.toLowerCase(Locale.ROOT);
		}
	}

	private static final class Job {

		JobOptions options;

		BatchSource source;

		String columnCase;
	}

	/**
	 * 每次触发执行一次（不读数据）
	 */
	static final class SingleRunSource implements BatchSource {

		@Override
		public BatchData fetch(Object checkpoint, int batchSize) {
			if (checkpoint != null) {
				return new BatchData(Collections.<Map<String, Object>>emptyList(), checkpoint);
			}
			return new BatchData(Collections.<Map<String, Object>>singletonList(new LinkedHashMap<>()), "once");
		}

		@Override
		public boolean isIncremental() {
			return false;
		}
	}

	/**
	 * 把"发送一批"变成"用这批数据执行一次流程"
	 */
	final class FlowBatchChannel implements UploadChannel {

		private final String flowId;

		private final String trigger;

		private final String columnCase;

		FlowBatchChannel(FlowInfo info, String trigger, String columnCase) {
			this.flowId = info.getId();
			this.trigger = trigger;
			this.columnCase = columnCase;
		}

		@Override
		public SendResult send(UploadBatch batch) {
			FlowRunner runner = runnerSupplier.get();
			FlowInfo info = runner.flow(flowId);
			if (info == null) {
				return SendResult.rejected("流程已删除", null);
			}
			List<Map<String, Object>> rows = new ArrayList<>(batch.getRows().size());
			for (Map<String, Object> row : batch.getRows()) {
				rows.add(convertCase(row, columnCase));
			}
			Map<String, Object> batchInfo = new LinkedHashMap<>();
			batchInfo.put("jobName", batch.getJobName());
			batchInfo.put("index", batch.getIndex());
			batchInfo.put("size", batch.getSize());
			batchInfo.put("attempt", batch.getAttempt());
			batchInfo.put("startCheckpoint", batch.getStartCheckpoint());
			batchInfo.put("endCheckpoint", batch.getEndCheckpoint());
			Map<String, Object> input = new LinkedHashMap<>();
			input.put("rows", rows);
			input.put("batch", batchInfo);
			input.put("trigger", trigger);
			input.put("fireTime", new Date());
			FlowResult result = runner.run(info, trigger, input);
			if (result.isSuccess()) {
				return SendResult.success(FlowJson.abbreviate(result.getValue(), 2000));
			}
			Throwable error = result.getError();
			String response = error instanceof FlowNodeException ? FlowJson.abbreviate(((FlowNodeException) error).getOutput(), 2000) : null;
			String message = "节点「" + result.getErrorNode() + "」：" + result.getMessage();
			boolean retryable = !(error instanceof InvalidArgumentException)
					&& !(error instanceof FlowNodeException && !((FlowNodeException) error).isRetryable());
			return retryable ? SendResult.failure(message, response) : SendResult.rejected(message, response);
		}
	}
}
