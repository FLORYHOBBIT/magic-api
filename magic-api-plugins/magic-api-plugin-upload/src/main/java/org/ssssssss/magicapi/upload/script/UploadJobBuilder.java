package org.ssssssss.magicapi.upload.script;

import org.springframework.jdbc.core.JdbcTemplate;
import org.ssssssss.magicapi.datasource.model.MagicDynamicDataSource;
import org.ssssssss.magicapi.modules.db.dialect.Dialect;
import org.ssssssss.magicapi.upload.channel.BodyBuilder;
import org.ssssssss.magicapi.upload.channel.ChannelRequest;
import org.ssssssss.magicapi.upload.engine.*;
import org.ssssssss.magicapi.upload.service.RetryResult;
import org.ssssssss.magicapi.upload.source.KeysetSqlSource;
import org.ssssssss.magicapi.upload.source.ListSource;
import org.ssssssss.magicapi.upload.source.OffsetSqlSource;
import org.ssssssss.script.annotation.Comment;
import org.ssssssss.script.runtime.RuntimeContext;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/**
 * 一次上传任务的配置，由 uploader.job(name) 创建
 * <pre>
 * return uploader.job('report')
 *     .source('select * from report where org = #{org}', 'id')
 *     .http({url: 'https://...', successField: 'code', successValues: '0'})
 *     .batchSize(500).retry(3).onFailure('skip')
 *     .run()
 * </pre>
 */
public class UploadJobBuilder {

	private final UploadModule module;

	private final JobOptions options;

	private String datasource;

	private SourceSpec source;

	private ChannelSpec channel;

	private Function<Object, Object> body;

	UploadJobBuilder(UploadModule module, JobOptions options) {
		this.module = module;
		this.options = options;
	}

	// ---------- 数据来源 ----------

	@Comment("按唯一递增字段分批读取，只上传新增数据。SQL 中可用 #{变量}")
	public UploadJobBuilder source(RuntimeContext context,
								   @Comment(name = "sql", value = "查询SQL") String sql,
								   @Comment(name = "keyColumn", value = "唯一且递增的字段，如 id") String keyColumn) {
		return source(context, sql, keyColumn, null);
	}

	@Comment("按唯一递增字段分批读取，并传入SQL变量")
	public UploadJobBuilder source(RuntimeContext context,
								   @Comment(name = "sql", value = "查询SQL") String sql,
								   @Comment(name = "keyColumn", value = "唯一且递增的字段，如 id") String keyColumn,
								   @Comment(name = "params", value = "SQL变量") Map<String, Object> params) {
		this.source = new SourceSpec(SourceSpec.KEYSET, sql, keyColumn, variables(context, params), null);
		return this;
	}

	@Comment("按排序字段分页读取全部数据（每次执行都全量上传），用于没有递增字段的数据")
	public UploadJobBuilder page(RuntimeContext context,
								 @Comment(name = "sql", value = "查询SQL") String sql,
								 @Comment(name = "orderBy", value = "排序字段，如 'dept, id desc'") String orderBy) {
		return page(context, sql, orderBy, null);
	}

	@Comment("按排序字段分页读取全部数据，并传入SQL变量")
	public UploadJobBuilder page(RuntimeContext context,
								 @Comment(name = "sql", value = "查询SQL") String sql,
								 @Comment(name = "orderBy", value = "排序字段") String orderBy,
								 @Comment(name = "params", value = "SQL变量") Map<String, Object> params) {
		this.source = new SourceSpec(SourceSpec.OFFSET, sql, orderBy, variables(context, params), null);
		return this;
	}

	@Comment("上传脚本中准备好的数据列表")
	public UploadJobBuilder data(@Comment(name = "rows", value = "数据列表") Collection<?> rows) {
		this.source = new SourceSpec(SourceSpec.LIST, null, null, null, rows);
		return this;
	}

	@Comment("读取数据使用的数据源，默认主数据源")
	public UploadJobBuilder datasource(@Comment(name = "key", value = "数据源key") String key) {
		this.datasource = key == null || key.trim().isEmpty() ? null : key.trim();
		return this;
	}

	// ---------- 通道 ----------

	@Comment("使用界面中维护的上传通道")
	public UploadJobBuilder channel(@Comment(name = "name", value = "通道名称") String name) {
		this.channel = new ChannelSpec(name, null, null);
		return this;
	}

	@Comment("HTTP 上传，参数：url、method、contentType、headers、params、successField、successValues、messageField、connectTimeout、readTimeout")
	public UploadJobBuilder http(@Comment(name = "options", value = "通道参数") Map<String, Object> options) {
		return protocol("http", options);
	}

	@Comment("WebService 上传，参数：url、version、soapAction、headers、successField（XML路径）、successValues；需用 body() 返回请求 XML")
	public UploadJobBuilder soap(@Comment(name = "options", value = "通道参数") Map<String, Object> options) {
		return protocol("soap", options);
	}

	@Comment("写入本地或共享目录，参数：dir、format（json|csv）、fileName、encoding")
	public UploadJobBuilder file(@Comment(name = "options", value = "通道参数") Map<String, Object> options) {
		return protocol("file", options);
	}

	@Comment("FTP 上传，参数：host、port、username、password、dir、passive、format、fileName、encoding")
	public UploadJobBuilder ftp(@Comment(name = "options", value = "通道参数") Map<String, Object> options) {
		return protocol("ftp", options);
	}

	@Comment("SFTP 上传，参数：host、port、username、password 或 privateKey、knownHosts、strictHostKey、dir、format、fileName")
	public UploadJobBuilder sftp(@Comment(name = "options", value = "通道参数") Map<String, Object> options) {
		return protocol("sftp", options);
	}

	private UploadJobBuilder protocol(String protocol, Map<String, Object> options) {
		this.channel = new ChannelSpec(null, protocol, options == null ? new LinkedHashMap<>() : new LinkedHashMap<>(options));
		return this;
	}

	@Comment("自定义请求体：batch => { ... }，batch 含 rows、jobName、index、size、attempt、headers、params")
	public UploadJobBuilder body(@Comment(name = "function", value = "报文转换函数") Function<Object, Object> function) {
		this.body = function;
		return this;
	}

	// ---------- 参数 ----------

	@Comment("每批条数，默认 1000")
	public UploadJobBuilder batchSize(@Comment(name = "size", value = "每批条数") int size) {
		options.batchSize(size);
		return this;
	}

	@Comment("每批失败后的重试次数，默认 3")
	public UploadJobBuilder retry(@Comment(name = "times", value = "重试次数") int times) {
		options.retry(times);
		return this;
	}

	@Comment("首次重试等待毫秒数，之后每次翻倍")
	public UploadJobBuilder retryBackoff(@Comment(name = "millis", value = "毫秒") long millis) {
		options.retryBackoff(Duration.ofMillis(millis));
		return this;
	}

	@Comment("重试后仍失败的处理：stop（停止，下次从这一批继续，默认）或 skip（跳过并保存，之后补传）")
	public UploadJobBuilder onFailure(@Comment(name = "policy", value = "stop 或 skip") String policy) {
		options.failurePolicy(FailurePolicy.of(policy));
		return this;
	}

	@Comment("本次最多上传多少批，剩余数据下次继续，0 表示不限制")
	public UploadJobBuilder maxBatches(@Comment(name = "count", value = "批次数") int count) {
		options.maxBatches(count);
		return this;
	}

	// ---------- 执行 ----------

	@Comment("执行上传，返回结果：status、totalRows、successRows、failedBatches、message 等")
	public JobResult run() {
		if (source == null) {
			throw new IllegalStateException("请先用 source()、page() 或 data() 指定要上传的数据");
		}
		return module.getRunner().run(options, createSource(), createChannel());
	}

	@Comment("补传之前跳过的失败批次（使用当前任务的通道）")
	public RetryResult retryFailed() {
		return retryFailed(0);
	}

	@Comment("补传之前跳过的失败批次，最多 limit 批")
	public RetryResult retryFailed(@Comment(name = "limit", value = "最多补传批次数，0 表示全部") int limit) {
		return module.getRunner().retryFailed(options, createChannel(), limit);
	}

	private BatchSource createSource() {
		switch (source.type) {
			case SourceSpec.LIST:
				return new ListSource(source.rows);
			case SourceSpec.KEYSET:
			case SourceSpec.OFFSET: {
				MagicDynamicDataSource.DataSourceNode node = module.dataSourceNode(datasource);
				// 新建 JdbcTemplate：数据源上配置的最大返回行数会截断批次，导致引擎误判为最后一批
				JdbcTemplate jdbc = new JdbcTemplate(node.getDataSource());
				Dialect dialect = module.dialect(node);
				return source.type.equals(SourceSpec.KEYSET)
						? new KeysetSqlSource(jdbc, dialect, source.sql, source.variables, source.column)
						: new OffsetSqlSource(jdbc, dialect, source.sql, source.variables, source.column);
			}
			default:
				throw new IllegalStateException();
		}
	}

	private UploadChannel createChannel() {
		if (channel == null) {
			throw new IllegalStateException("请先用 channel()、http()、soap()、file()、ftp() 或 sftp() 指定上传通道");
		}
		if (channel.name != null) {
			if (body != null) {
				throw new IllegalStateException("使用界面中维护的通道时，报文转换请写在通道里，不要再调用 body()");
			}
			return module.channelByName(channel.name);
		}
		return module.getChannelFactory().create(channel.protocol, channel.options, body == null ? null : new FunctionBodyBuilder(body));
	}

	private static Map<String, Object> variables(RuntimeContext context, Map<String, Object> params) {
		if (params != null && !params.isEmpty()) {
			return new HashMap<>(params);
		}
		return context == null ? new HashMap<>() : new HashMap<>(context.getVarMap());
	}

	/**
	 * 调用脚本中的报文转换函数
	 */
	private static class FunctionBodyBuilder implements BodyBuilder {

		private final Function<Object, Object> function;

		FunctionBodyBuilder(Function<Object, Object> function) {
			this.function = function;
		}

		@Override
		public Object build(UploadBatch batch) {
			Map<String, Object> headers = new LinkedHashMap<>();
			Map<String, Object> params = new LinkedHashMap<>();
			Map<String, Object> arg = new LinkedHashMap<>();
			arg.put("rows", batch.getRows());
			arg.put("jobName", batch.getJobName());
			arg.put("index", batch.getIndex());
			arg.put("size", batch.getSize());
			arg.put("attempt", batch.getAttempt());
			arg.put("headers", headers);
			arg.put("params", params);
			Object body = function.apply(arg);
			return new ChannelRequest(body, headers, params);
		}
	}

	private static class SourceSpec {

		static final String KEYSET = "keyset";

		static final String OFFSET = "offset";

		static final String LIST = "list";

		final String type;

		final String sql;

		final String column;

		final Map<String, Object> variables;

		final Collection<?> rows;

		SourceSpec(String type, String sql, String column, Map<String, Object> variables, Collection<?> rows) {
			this.type = type;
			this.sql = sql;
			this.column = column;
			this.variables = variables;
			this.rows = rows;
		}
	}

	private static class ChannelSpec {

		final String name;

		final String protocol;

		final Map<String, Object> options;

		ChannelSpec(String name, String protocol, Map<String, Object> options) {
			this.name = name;
			this.protocol = protocol;
			this.options = options;
		}
	}
}
