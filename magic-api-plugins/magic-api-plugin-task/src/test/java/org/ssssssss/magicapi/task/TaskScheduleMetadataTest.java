package org.ssssssss.magicapi.task;

import org.junit.jupiter.api.Test;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.task.model.TaskInfo;
import org.ssssssss.magicapi.task.web.MagicTaskController;
import org.ssssssss.magicapi.utils.JsonUtils;

import static org.junit.jupiter.api.Assertions.*;

class TaskScheduleMetadataTest {
	@Test void treeMetadataIncludesActualEnabledStateAndCron() {
		TaskInfo task = new TaskInfo();
		task.setId("task-id"); task.setName("sales"); task.setGroupId("group"); task.setPath("/sales");
		task.setCron("0 0 1 * * ?"); task.setEnabled(true); task.setScript("return 1;");
		TaskInfo simple = (TaskInfo) task.simple();
		assertTrue(simple.isEnabled());
		assertEquals(task.getCron(), simple.getCron());
		assertNull(simple.getScript());
		TaskInfo json = JsonUtils.readValue(JsonUtils.toJsonString(simple), TaskInfo.class);
		assertTrue(json.isEnabled()); assertEquals(task.getCron(), json.getCron());
		task.setEnabled(false);
		assertFalse(((TaskInfo) task.simple()).isEnabled());
	}

	@Test void validationUsesTheSameSixFieldGrammarAsTheScheduler() {
		MagicTaskController controller = new MagicTaskController(new MagicConfiguration());
		assertEquals("0 0 1 * * ?", controller.validateCron("0 0 1 * * ?").getData());
		assertNotNull(controller.validateCron("0 0/30 * * * ?").getData());
		assertNotNull(controller.validateCron("0 0 9 * * MON-FRI").getData());
		assertNotNull(controller.validateCron("0 0 0 L * ?").getData());
		assertEquals("0 0 0 * * *", controller.validateCron("@daily").getData());
	}

	@Test void invalidAndEmptyCronHaveNoDisplayValue() {
		MagicTaskController controller = new MagicTaskController(new MagicConfiguration());
		for (String cron : new String[]{null, "", "bad cron", "0 1 * * *", "0 0 1 * * ? 2026", "60 0 1 * * ?", "0 0 25 * * ?"}) {
			assertNull(controller.validateCron(cron).getData(), cron);
		}
	}
}
