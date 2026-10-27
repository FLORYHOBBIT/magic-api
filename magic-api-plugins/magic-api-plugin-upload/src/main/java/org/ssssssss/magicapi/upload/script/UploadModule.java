package org.ssssssss.magicapi.upload.script;

import org.ssssssss.magicapi.core.annotation.MagicModule;
import org.ssssssss.magicapi.datasource.model.MagicDynamicDataSource;
import org.ssssssss.magicapi.modules.db.dialect.Dialect;
import org.ssssssss.magicapi.modules.db.dialect.DialectAdapter;
import org.ssssssss.magicapi.upload.channel.ChannelFactory;
import org.ssssssss.magicapi.upload.channel.SignFunctions;
import org.ssssssss.magicapi.upload.channel.http.HttpUploadChannel;
import org.ssssssss.magicapi.upload.engine.JobOptions;
import org.ssssssss.magicapi.upload.engine.UploadChannel;
import org.ssssssss.magicapi.upload.resource.UploadChannelProvider;
import org.ssssssss.magicapi.upload.service.UploadJobRunner;
import org.ssssssss.magicapi.upload.store.FailedBatch;
import org.ssssssss.magicapi.upload.store.UploadRecord;
import org.ssssssss.script.annotation.Comment;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 数据上传模块，脚本中 import uploader 后使用
 */
@MagicModule("uploader")
public class UploadModule {

	private final UploadJobRunner runner;

	private final ChannelFactory channelFactory;

	private final Supplier<MagicDynamicDataSource> dataSourceSupplier;

	private final DialectAdapter dialectAdapter;

	private final Supplier<UploadChannelProvider> channelProviderSupplier;

	private final int defaultBatchSize;

	private final int defaultRetry;

	private final Duration defaultBackoff;

	public UploadModule(UploadJobRunner runner, ChannelFactory channelFactory, Supplier<MagicDynamicDataSource> dataSourceSupplier,
						DialectAdapter dialectAdapter, Supplier<UploadChannelProvider> channelProviderSupplier,
						int defaultBatchSize, int defaultRetry, Duration defaultBackoff) {
		this.runner = runner;
		this.channelFactory = channelFactory;
		this.dataSourceSupplier = dataSourceSupplier;
		this.dialectAdapter = dialectAdapter;
		this.channelProviderSupplier = channelProviderSupplier;
		this.defaultBatchSize = defaultBatchSize;
		this.defaultRetry = defaultRetry;
		this.defaultBackoff = defaultBackoff;
	}

	@Comment("创建上传任务，任务名用于保存断点、记录与任务锁，同一份数据请固定使用同一个任务名")
	public UploadJobBuilder job(@Comment(name = "name", value = "任务名") String name) {
		JobOptions options = new JobOptions(name).batchSize(defaultBatchSize).retry(defaultRetry).retryBackoff(defaultBackoff);
		return new UploadJobBuilder(this, options);
	}

	@Comment("最近的执行记录")
	public List<UploadRecord> records(@Comment(name = "job", value = "任务名") String job,
									  @Comment(name = "limit", value = "条数") int limit) {
		return runner.records(job, limit);
	}

	@Comment("待补传的失败批次（含数据）")
	public List<FailedBatch> failed(@Comment(name = "job", value = "任务名") String job,
									@Comment(name = "limit", value = "批次数") int limit) {
		return runner.failedBatches(job, limit);
	}

	@Comment("当前断点")
	public Object checkpoint(@Comment(name = "job", value = "任务名") String job) {
		return runner.checkpoint(job);
	}

	@Comment("清除断点，下次从头上传")
	public void reset(@Comment(name = "job", value = "任务名") String job) {
		runner.resetCheckpoint(job);
	}

	// ---------- 签名与编码 ----------

	@Comment("MD5，返回小写十六进制")
	public String md5(String text) {
		return SignFunctions.md5(text);
	}

	@Comment("SHA-1，返回小写十六进制")
	public String sha1(String text) {
		return SignFunctions.sha1(text);
	}

	@Comment("SHA-256，返回小写十六进制")
	public String sha256(String text) {
		return SignFunctions.sha256(text);
	}

	@Comment("HMAC-SHA256，返回小写十六进制")
	public String hmacSha256(String key, String text) {
		return SignFunctions.hmacSha256(key, text);
	}

	@Comment("HMAC-SHA256，返回 Base64")
	public String hmacSha256Base64(String key, String text) {
		return SignFunctions.hmacSha256Base64(key, text);
	}

	@Comment("HMAC-SHA1，返回 Base64")
	public String hmacSha1Base64(String key, String text) {
		return SignFunctions.hmacSha1Base64(key, text);
	}

	@Comment("Base64 编码")
	public String base64(String text) {
		return SignFunctions.base64(text);
	}

	@Comment("Base64 解码")
	public String base64Decode(String text) {
		return SignFunctions.base64Decode(text);
	}

	@Comment("按参数名升序拼接为 a=1&b=2（跳过空值）")
	public String sortedQuery(Map<String, Object> params) {
		return SignFunctions.sortedQuery(params);
	}

	@Comment("转为 JSON 字符串（日期格式 yyyy-MM-dd HH:mm:ss.SSS），用于先序列化再签名")
	public String json(Object value) throws Exception {
		return HttpUploadChannel.toJson(value);
	}

	// ---------- 内部 ----------

	UploadJobRunner getRunner() {
		return runner;
	}

	ChannelFactory getChannelFactory() {
		return channelFactory;
	}

	MagicDynamicDataSource.DataSourceNode dataSourceNode(String key) {
		MagicDynamicDataSource dataSource = dataSourceSupplier.get();
		if (dataSource == null || dataSource.isEmpty()) {
			throw new IllegalStateException("没有可用的数据源");
		}
		return dataSource.getDataSource(key);
	}

	Dialect dialect(MagicDynamicDataSource.DataSourceNode node) {
		return node.getDialect(dialectAdapter);
	}

	UploadChannel channelByName(String name) {
		UploadChannelProvider provider = channelProviderSupplier.get();
		if (provider == null) {
			throw new IllegalStateException("上传通道管理未启用");
		}
		return provider.create(name);
	}
}
