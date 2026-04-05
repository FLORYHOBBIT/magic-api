package org.ssssssss.magicapi.skywalking;

import org.apache.skywalking.apm.toolkit.trace.ActiveSpan;
import org.apache.skywalking.apm.toolkit.trace.SpanRef;
import org.apache.skywalking.apm.toolkit.trace.TraceContext;
import org.apache.skywalking.apm.toolkit.trace.Tracer;
import org.ssssssss.magicapi.core.context.RequestEntity;
import org.ssssssss.magicapi.core.interceptor.RequestInterceptor;
import org.ssssssss.magicapi.core.model.ApiInfo;

/**
 * SkyWalking 集成拦截器
 * 在 magic-api 请求执行过程中创建和管理 SkyWalking span
 *
 * @author magic-api
 */
public class SkyWalkingInterceptor implements RequestInterceptor {

    private static final ThreadLocal<SpanRef> SPAN_HOLDER = new ThreadLocal<>();
    private static final String OPERATION_NAME_PREFIX = "magic-api:";

    @Override
    public Object preHandle(RequestEntity requestEntity) throws Exception {
        ApiInfo apiInfo = requestEntity.getApiInfo();
        if (apiInfo == null) {
            return null;
        }

        String operationName = OPERATION_NAME_PREFIX + apiInfo.getPath();
        
        // 创建 LocalSpan 用于追踪 magic-api 脚本执行
        SpanRef span = Tracer.createLocalSpan(operationName);
        
        // 设置标签
        span.tag("api.id", apiInfo.getId());
        span.tag("api.name", apiInfo.getName());
        span.tag("api.path", apiInfo.getPath());
        span.tag("api.method", requestEntity.getRequest().getMethod());
        
        // 将 span 存储到 ThreadLocal 中
        SPAN_HOLDER.set(span);
        
        return null;
    }

    @Override
    public Object postHandle(RequestEntity requestEntity, Object value) throws Exception {
        // 后置处理，可以在此记录返回值信息
        SpanRef span = SPAN_HOLDER.get();
        if (span != null && value != null) {
            span.tag("result.type", value.getClass().getSimpleName());
        }
        return null;
    }

    @Override
    public void afterCompletion(RequestEntity requestEntity, Object returnValue, Throwable throwable) {
        SpanRef span = SPAN_HOLDER.get();
        if (span == null) {
            return;
        }
        
        try {
            // 如果有异常，标记为错误
            if (throwable != null) {
                ActiveSpan.error(throwable);
                span.log(throwable);
            }
        } finally {
            // 清理 ThreadLocal
            SPAN_HOLDER.remove();
            // 结束 span
            Tracer.stopSpan();
        }
    }

    /**
     * 获取当前 traceId
     */
    public static String getTraceId() {
        return TraceContext.traceId();
    }

    /**
     * 获取当前 segmentId
     */
    public static String getSegmentId() {
        return TraceContext.segmentId();
    }

    /**
     * 获取当前 spanId
     */
    public static int getSpanId() {
        return TraceContext.spanId();
    }

    /**
     * 添加自定义标签到当前 span
     */
    public static void tag(String key, String value) {
        ActiveSpan.tag(key, value);
    }
}
