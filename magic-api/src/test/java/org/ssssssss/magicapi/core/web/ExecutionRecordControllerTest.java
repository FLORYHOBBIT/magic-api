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
		} finally { config.setMagicResourceService(previous); }
	}
}
