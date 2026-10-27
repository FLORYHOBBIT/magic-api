package org.ssssssss.magicapi.upload.resource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.ssssssss.magicapi.core.annotation.Valid;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.interceptor.Authorization;
import org.ssssssss.magicapi.core.model.JsonBean;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.web.MagicController;
import org.ssssssss.magicapi.core.web.MagicExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * 上传通道的界面接口
 */
public class UploadChannelController extends MagicController implements MagicExceptionHandler {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<List<Map<String, Object>>>() {
	};

	private final UploadChannelProvider provider;

	public UploadChannelController(MagicConfiguration configuration, UploadChannelProvider provider) {
		super(configuration);
		this.provider = provider;
	}

	/**
	 * 用已保存的通道配置发送一次样例数据
	 *
	 * @param id   通道 ID
	 * @param rows 样例数据（JSON 数组），可为空
	 */
	@PostMapping("/upload/channel/test")
	@ResponseBody
	@Valid(authorization = Authorization.SAVE, readonly = true)
	public JsonBean<Map<String, Object>> test(String id, String rows) throws Exception {
		MagicEntity entity = MagicConfiguration.getMagicResourceService().file(id);
		notNull(entity, FILE_NOT_FOUND);
		isTrue(entity instanceof UploadChannelInfo, FILE_NOT_FOUND);
		List<Map<String, Object>> sample = rows == null || rows.trim().isEmpty() ? null : MAPPER.readValue(rows, ROWS);
		return new JsonBean<>(provider.test((UploadChannelInfo) entity, sample));
	}
}
