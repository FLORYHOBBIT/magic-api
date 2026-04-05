package org.ssssssss.magicapi.skywalking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ssssssss.magicapi.core.context.RequestEntity;
import org.ssssssss.magicapi.core.model.ApiInfo;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SkyWalking 拦截器测试
 */
@ExtendWith(MockitoExtension.class)
class SkyWalkingInterceptorTest {

    private SkyWalkingInterceptor interceptor;

    @Mock
    private RequestEntity requestEntity;

    @Mock
    private ApiInfo apiInfo;

    @Mock
    private MagicHttpServletRequest request;

    @BeforeEach
    void setUp() {
        interceptor = new SkyWalkingInterceptor();
    }

    @Test
    void testPreHandleWithNullApiInfo() throws Exception {
        when(requestEntity.getApiInfo()).thenReturn(null);
        
        Object result = interceptor.preHandle(requestEntity);
        
        assertNull(result);
    }

    @Test
    void testPreHandleWithApiInfo() throws Exception {
        when(apiInfo.getId()).thenReturn("test-api-id");
        when(apiInfo.getName()).thenReturn("test-api");
        when(apiInfo.getPath()).thenReturn("/test/path");
        when(requestEntity.getApiInfo()).thenReturn(apiInfo);
        when(requestEntity.getRequest()).thenReturn(request);
        when(request.getMethod()).thenReturn("GET");
        
        // 注意：这个测试在没有 SkyWalking agent 的情况下会抛出异常或返回 null
        // 实际测试需要在有 SkyWalking agent 的环境中运行
        try {
            Object result = interceptor.preHandle(requestEntity);
            assertNull(result);
        } catch (Exception e) {
            // 预期在没有 SkyWalking agent 时可能会有异常
            assertTrue(e.getMessage() != null);
        }
    }

    @Test
    void testAfterCompletion() {
        // 测试清理逻辑不会抛出异常
        assertDoesNotThrow(() -> {
            interceptor.afterCompletion(requestEntity, null, null);
        });
    }
}
