package org.ssssssss.magicapi.upload.resource;

import org.ssssssss.magicapi.upload.channel.BodyBuilder;
import org.ssssssss.magicapi.upload.channel.ChannelRequest;
import org.ssssssss.magicapi.upload.engine.UploadBatch;
import org.ssssssss.script.MagicScript;
import org.ssssssss.script.MagicScriptContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行通道的报文转换脚本
 * <p>
 * 脚本可用变量：rows、batch、options、headers、params。修改 headers、params（如 headers['X-Sign'] = ...）会追加到本批请求；
 * 返回值为请求体（对象按 JSON/表单序列化，字符串原样发送）。脚本只编译一次。
 */
public class ScriptBodyBuilder implements BodyBuilder {

	private final MagicScript script;

	private final String scriptName;

	private final Map<String, Object> options;

	public ScriptBodyBuilder(String script, String scriptName, Map<String, Object> options) {
		this.script = MagicScript.create(script, null);
		this.scriptName = scriptName;
		this.options = options == null ? Collections.<String, Object>emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(options));
	}

	@Override
	public Object build(UploadBatch batch) {
		Map<String, Object> headers = new LinkedHashMap<>();
		Map<String, Object> params = new LinkedHashMap<>();
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("jobName", batch.getJobName());
		info.put("index", batch.getIndex());
		info.put("size", batch.getSize());
		info.put("attempt", batch.getAttempt());
		info.put("startCheckpoint", batch.getStartCheckpoint());
		info.put("endCheckpoint", batch.getEndCheckpoint());

		MagicScriptContext context = new MagicScriptContext();
		context.setScriptName(scriptName);
		context.set("rows", batch.getRows());
		context.set("batch", info);
		context.set("options", options);
		context.set("headers", headers);
		context.set("params", params);
		Object body = script.execute(context);
		return new ChannelRequest(body, headers, params);
	}
}
