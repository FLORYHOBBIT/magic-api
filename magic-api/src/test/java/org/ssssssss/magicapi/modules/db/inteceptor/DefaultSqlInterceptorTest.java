package org.ssssssss.magicapi.modules.db.inteceptor;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.ssssssss.magicapi.core.context.RequestEntity;
import org.ssssssss.magicapi.modules.db.BoundSql;
import org.ssssssss.magicapi.modules.db.SQLModule;
import org.ssssssss.script.MagicScriptContext;
import org.ssssssss.script.runtime.RuntimeContext;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultSqlInterceptorTest {
	private Logger root;
	private ListAppender<ILoggingEvent> logs;
	private final DefaultSqlInterceptor interceptor = new DefaultSqlInterceptor();

	@BeforeEach void collect() {
		root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		logs = new ListAppender<>(); logs.start(); root.addAppender(logs);
	}
	@AfterEach void detach() { root.detachAppender(logs); logs.stop(); }

	private BoundSql sql(String name) {
		MagicScriptContext context = new MagicScriptContext(); context.setScriptName(name);
		RuntimeContext runtime = mock(RuntimeContext.class);
		when(runtime.getScriptContext()).thenReturn(context);
		SQLModule module = mock(SQLModule.class);
		when(module.getDataSourceName()).thenReturn("业务库");
		return new BoundSql(runtime, "select ?", Collections.singletonList("parameter"), module);
	}

	@Test void scheduledSqlUsesTaskPathForSqlDatasourceAndParameters() {
		interceptor.postHandle(sql("/任务/挂号推送"), new Object(), null);
		assertEquals(1, logs.list.size());
		assertTrue(logs.list.stream().allMatch(event -> event.getLoggerName().equals("/任务/挂号推送")));
		assertTrue(logs.list.get(0).getFormattedMessage().contains("select 'parameter'"));
		assertTrue(logs.list.get(0).getFormattedMessage().contains("[数据源：业务库]"));
		assertFalse(logs.list.stream().anyMatch(event -> event.getFormattedMessage().startsWith("数据源：")));
	}

	@Test void requestScriptNameIsPreserved() {
		RequestEntity request = mock(RequestEntity.class);
		MagicScriptContext context = new MagicScriptContext(); context.setScriptName("/api/current");
		when(request.getMagicScriptContext()).thenReturn(context);
		interceptor.postHandle(sql("/other"), null, request);
		assertTrue(logs.list.stream().allMatch(event -> event.getLoggerName().equals("/api/current")));
	}

	@Test void sqlFailureAlsoUsesTheRuntimeScriptNameWhenRequestContextIsIncomplete() {
		interceptor.handleException(sql("/任务/失败任务"), new IllegalStateException("failed"), mock(RequestEntity.class));
		assertEquals(1, logs.list.size());
		assertTrue(logs.list.stream().allMatch(event -> event.getLoggerName().equals("/任务/失败任务")));
	}

	@Test void unknownIsRetainedOnlyWhenBothContextsHaveNoScriptName() {
		interceptor.postHandle(sql(" "), null, null);
		assertTrue(logs.list.stream().allMatch(event -> event.getLoggerName().equals("Unknown")));
	}

	@Test void renderingDoesNotChangeExecutedSqlOrParameters() {
		BoundSql bound = sql("/task");
		interceptor.postHandle(bound, null, null);
		assertEquals("select ?", bound.getSql());
		assertArrayEquals(new Object[]{"parameter"}, bound.getParameters());
	}

	@Test void renderingFailureDoesNotReplaceTheOriginalDatabaseFailure() {
		BoundSql bound = sql("/task");
		bound.setSql("select ?, ?");
		assertDoesNotThrow(() -> interceptor.handleException(bound, new IllegalStateException("database failed"), null));
		assertTrue(logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains("日志参数未展开")));
		assertTrue(logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains("[数据源：业务库]")));
	}

	@Test void sqlWithNoDatasourceStillProducesOnePlainSqlEntry() {
		interceptor.handleLog(new BoundSql(null, "select 1", Collections.emptyList(), null), null);
		assertEquals(1, logs.list.size());
		assertEquals("执行SQL：select 1", logs.list.get(0).getFormattedMessage());
	}
}
