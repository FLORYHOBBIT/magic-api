package org.ssssssss.magicapi.datasource.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.ssssssss.magicapi.core.annotation.Valid;
import org.ssssssss.magicapi.core.interceptor.Authorization;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletRequest;
import org.ssssssss.magicapi.core.web.MagicExceptionHandler;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.datasource.model.DataSourceInfo;
import org.ssssssss.magicapi.core.model.JsonBean;
import org.ssssssss.magicapi.core.web.MagicController;
import org.ssssssss.magicapi.utils.JdbcUtils;

import java.sql.Connection;

public class MagicDataSourceController extends MagicController implements MagicExceptionHandler {

	public MagicDataSourceController(MagicConfiguration configuration) {
		super(configuration);
	}

	@RequestMapping("/datasource/jdbc/test")
	@ResponseBody
	public JsonBean<String> test(@RequestBody DataSourceInfo properties, MagicHttpServletRequest request) {
		String password = properties.getPassword();
		if ((password == null || password.isEmpty()) && properties.getId() != null) {
			DataSourceInfo saved = findDataSource(properties.getId(), request);
			if (saved.getPassword() != null && !saved.getPassword().isEmpty()) {
				isTrue(properties.hasSameJdbcTarget(saved), DS_PASSWORD_REQUIRED_FOR_CONNECTION_CHANGE);
				password = saved.getPassword();
			}
		}
		try {
			Connection connection = JdbcUtils.getConnection(properties.getDriverClassName(), properties.getUrl(), properties.getUsername(), password);
			JdbcUtils.close(connection);
		} catch (Exception e) {
			return new JsonBean<>("请检查连接配置或查看服务端日志。");
		}
		return new JsonBean<>("ok");
	}

	@PostMapping("/datasource/copy")
	@ResponseBody
	@Valid(readonly = false)
	public JsonBean<MagicEntity> copy(@RequestBody DataSourceInfo requestBody, MagicHttpServletRequest request) {
		DataSourceInfo source = findDataSource(requestBody.getId(), request);
		DataSourceInfo target = source.copy();
		target.setId(null);
		target.setName(requestBody.getName());
		target.setKey(source.getKey() + "_copy");
		target.setPrimary(false);
		isTrue(allowVisit(request, Authorization.SAVE, target), PERMISSION_INVALID);
		return new JsonBean<>(MagicConfiguration.getMagicResourceService().saveFile(target) ? target.simple() : null);
	}

	private DataSourceInfo findDataSource(String id, MagicHttpServletRequest request) {
		notBlank(id, PARAMETER_INVALID);
		MagicEntity entity = MagicConfiguration.getMagicResourceService().file(id);
		isTrue(entity instanceof DataSourceInfo, FILE_NOT_FOUND);
		isTrue(allowVisit(request, Authorization.VIEW, entity), PERMISSION_INVALID);
		return (DataSourceInfo) entity;
	}
}
