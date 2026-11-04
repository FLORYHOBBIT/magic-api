package org.ssssssss.magicapi.task;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.logging.LoggerManager;
import org.ssssssss.magicapi.core.logging.MagicLoggerContext;
import org.ssssssss.magicapi.core.record.ExecutionRecord;
import org.ssssssss.magicapi.core.record.ExecutionRecordStore;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletRequest;
import org.ssssssss.magicapi.task.model.TaskInfo;
import org.ssssssss.magicapi.task.service.TaskInfoMagicResourceStorage;
import org.ssssssss.magicapi.task.service.TaskMagicDynamicRegistry;
import org.ssssssss.magicapi.task.web.MagicTaskController;

import java.nio.file.Path;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskExecutionLogTest {
	@TempDir Path dir;
	MagicConfiguration config;
	MagicResourceService previousResources;
	ExecutionRecordStore previousStore;
	MagicResourceService resources;
	ExecutionRecordStore store;

	@BeforeAll static void logging() { LoggerManager.createMagicAppender(); }
	@BeforeEach void setup() {
		previousResources = MagicConfiguration.getMagicResourceService();
		previousStore = MagicConfiguration.getExecutionRecordStore();
		config = new MagicConfiguration();
		resources = mock(MagicResourceService.class);
		store = new ExecutionRecordStore(dir, 5);
		config.setMagicResourceService(resources);
		config.setExecutionRecordStore(store);
		config.setDebugTimeout(10000);
	}
	@AfterEach void restore() {
		MagicLoggerContext.SESSION.remove();
		config.setMagicResourceService(previousResources);
		config.setExecutionRecordStore(previousStore);
	}

	TaskInfo task(String id, String message) {
		TaskInfo info = new TaskInfo();
		info.setId(id); info.setName(id); info.setPath(id); info.setGroupId("group");
		info.setCron("0 * * * * *"); info.setEnabled(true);
		info.setScript("import 'org.slf4j.LoggerFactory' as LoggerFactory; LoggerFactory.getLogger('test.task').info('" + message + "'); return 1;");
		when(resources.getScriptName(info)).thenReturn(id);
		when(resources.file(id)).thenReturn(info);
		return info;
	}

	Runnable scheduled(TaskInfo info) {
		TaskInfoMagicResourceStorage storage = new TaskInfoMagicResourceStorage();
		storage.setMagicResourceService(resources);
		TaskScheduler scheduler = mock(TaskScheduler.class);
		AtomicReference<Runnable> run = new AtomicReference<>();
		when(scheduler.schedule(any(Runnable.class), any(Trigger.class))).thenAnswer(call -> {
			run.set(call.getArgument(0)); return mock(ScheduledFuture.class);
		});
		assertTrue(new TaskMagicDynamicRegistry(storage, scheduler, false).register(info));
		return run.get();
	}

	@Test void cronCapturesScriptLogsWithoutBrowserAndSeparatesExecutions() throws Exception {
		TaskInfo one = task("one", "cron-detail-one");
		TaskInfo two = task("two", "cron-detail-two");
		MagicLoggerContext.SESSION.set("stale-client");
		scheduled(one).run(); scheduled(two).run();
		ExecutionRecord record = store.list("one").get(0);
		assertTrue(record.isSuccess()); assertEquals("cron", record.getSource());
		String log = store.logs("one", record.getRunId()).getText();
		assertTrue(log.contains("cron-detail-one"), log);
		assertFalse(log.contains("cron-detail-two"));
		assertNull(MagicLoggerContext.SESSION.get());
	}

	@Test void manualTestSavesDetailedLogs() throws Exception {
		task("manual", "manual-detail");
		new MagicTaskController(config).execute("manual", mock(MagicHttpServletRequest.class));
		ExecutionRecord record = store.list("manual").get(0);
		assertTrue(record.isSuccess()); assertEquals("test", record.getSource());
		assertTrue(store.logs("manual", record.getRunId()).getText().contains("manual-detail"));
		assertNull(MagicLoggerContext.SESSION.get());
	}

	@Test void failurePersistsStackTraceAndFailureSummary() throws Exception {
		TaskInfo info = task("failed", "unused");
		info.setScript("import 'java.lang.IllegalStateException' as Err; throw new Err('task-failed');");
		scheduled(info).run();
		ExecutionRecord record = store.list("failed").get(0);
		assertFalse(record.isSuccess());
		assertTrue(record.getMessage().contains("task-failed"));
		assertTrue(store.logs("failed", record.getRunId()).getText().contains("IllegalStateException"));
	}
}
