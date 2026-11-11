package org.ssssssss.magicapi.core.web;

import org.junit.jupiter.api.Test;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.interceptor.AuthorizationInterceptor;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionRecordControllerTest {
	@Test void downloadStreamsRawUtf8WithViewAuthorization() throws Exception {
		MagicConfiguration config = new MagicConfiguration();
		MagicResourceService previous = MagicConfiguration.getMagicResourceService();
		org.ssssssss.magicapi.core.record.ExecutionRecordStore previousStore = MagicConfiguration.getExecutionRecordStore();
		MagicResourceService resources = mock(MagicResourceService.class);
		org.ssssssss.magicapi.core.record.ExecutionRecordStore store = mock(org.ssssssss.magicapi.core.record.ExecutionRecordStore.class);
		MagicEntity entity = new MagicEntity();
		when(resources.file("one")).thenReturn(entity);
		config.setMagicResourceService(resources); config.setExecutionRecordStore(store);
		AuthorizationInterceptor authorization = mock(AuthorizationInterceptor.class);
		config.setAuthorizationInterceptor(authorization);
		MagicHttpServletRequest request = mock(MagicHttpServletRequest.class);
		when(authorization.allowVisit(any(), eq(request), eq(org.ssssssss.magicapi.core.interceptor.Authorization.VIEW), eq(entity))).thenReturn(true);
		String runId = "01234567890123456789012345678901";
		byte[] text = "中文SQL与完整医保响应😀\n尾部".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		when(store.openLog("one", runId)).thenReturn(new java.io.ByteArrayInputStream(text));
		java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
		org.ssssssss.magicapi.core.servlet.MagicHttpServletResponse response = mock(org.ssssssss.magicapi.core.servlet.MagicHttpServletResponse.class);
		when(response.getOutputStream()).thenReturn(output);
		try {
			new ExecutionRecordController(config).download("one", runId, request, response);
			assertArrayEquals(text, output.toByteArray());
			verify(response).setContentType("text/plain;charset=UTF-8");
			verify(response).setHeader("Cache-Control", "no-store");
		} finally { config.setMagicResourceService(previous); config.setExecutionRecordStore(previousStore); }
	}
	@Test void bothEndpointsEnforceResourceViewPermission() {
		MagicConfiguration config = new MagicConfiguration();
		MagicResourceService previous = MagicConfiguration.getMagicResourceService();
		MagicResourceService resources = mock(MagicResourceService.class);
		MagicEntity entity = new MagicEntity();
		when(resources.file("private")).thenReturn(entity);
		config.setMagicResourceService(resources);
		config.setAuthorizationInterceptor(mock(AuthorizationInterceptor.class));
		try {
			ExecutionRecordController controller = new ExecutionRecordController(config);
			MagicHttpServletRequest request = mock(MagicHttpServletRequest.class);
			assertThrows(InvalidArgumentException.class, () -> controller.list("private", request));
			assertThrows(InvalidArgumentException.class, () -> controller.logs("private", "01234567890123456789012345678901", request));
			assertThrows(InvalidArgumentException.class, () -> controller.logs("missing", "../outside", request));
			assertThrows(InvalidArgumentException.class, () -> controller.list(null, request));
			org.ssssssss.magicapi.core.servlet.MagicHttpServletResponse response = mock(org.ssssssss.magicapi.core.servlet.MagicHttpServletResponse.class);
			assertThrows(InvalidArgumentException.class, () -> controller.download("private", "01234567890123456789012345678901", request, response));
			assertThrows(InvalidArgumentException.class, () -> controller.download("missing", "../outside", request, response));
			verifyNoInteractions(response);
		} finally { config.setMagicResourceService(previous); }
	}
}
