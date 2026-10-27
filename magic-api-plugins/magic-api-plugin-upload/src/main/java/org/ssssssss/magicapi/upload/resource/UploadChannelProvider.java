package org.ssssssss.magicapi.upload.resource;

import org.ssssssss.magicapi.upload.channel.BodyBuilder;
import org.ssssssss.magicapi.upload.channel.ChannelFactory;
import org.ssssssss.magicapi.upload.engine.SendResult;
import org.ssssssss.magicapi.upload.engine.UploadBatch;
import org.ssssssss.magicapi.upload.engine.UploadChannel;

import java.util.*;

/**
 * 根据界面中维护的通道创建上传通道
 */
public class UploadChannelProvider {

	private final UploadChannelMagicDynamicRegistry registry;

	private final UploadChannelMagicResourceStorage storage;

	private final ChannelFactory channelFactory;

	public UploadChannelProvider(UploadChannelMagicDynamicRegistry registry, UploadChannelMagicResourceStorage storage, ChannelFactory channelFactory) {
		this.registry = registry;
		this.storage = storage;
		this.channelFactory = channelFactory;
	}

	public UploadChannel create(String name) {
		UploadChannelInfo info = registry.find(name);
		if (info == null) {
			throw new IllegalArgumentException("找不到上传通道: " + name + "，请先在界面中创建并保存");
		}
		return create(info);
	}

	public UploadChannel create(UploadChannelInfo info) {
		Map<String, Object> options = storage.decrypt(info.getOptions());
		BodyBuilder body = null;
		if (info.getScript() != null && !info.getScript().trim().isEmpty()) {
			body = new ScriptBodyBuilder(info.getScript(), "upload-channel:" + info.getName(), options);
		}
		return channelFactory.create(info.getProtocol(), options, body);
	}

	/**
	 * 用样例数据发送一次（不重试、不写记录），供界面"测试"使用
	 *
	 * @param rows 样例数据，为空时发送一行 {"test": true}
	 */
	public Map<String, Object> test(UploadChannelInfo info, List<Map<String, Object>> rows) {
		List<Map<String, Object>> sample = rows;
		if (sample == null || sample.isEmpty()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("test", true);
			sample = Collections.singletonList(row);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		long start = System.currentTimeMillis();
		try {
			SendResult sent = create(info).send(new UploadBatch("channel-test-" + info.getName(), 1, sample, null, null));
			result.put("success", sent.isSuccess());
			result.put("retryable", sent.isRetryable());
			result.put("message", sent.getMessage());
			result.put("response", sent.getResponse());
		} catch (Exception e) {
			result.put("success", false);
			result.put("retryable", true);
			result.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
			result.put("response", null);
		}
		result.put("costMillis", System.currentTimeMillis() - start);
		return result;
	}
}
