package org.ssssssss.magicapi.upload.engine;

/**
 * 重试前的等待，测试时可替换为不真正等待的实现
 */
@FunctionalInterface
public interface Sleeper {

	Sleeper DEFAULT = millis -> {
		if (millis > 0) {
			Thread.sleep(millis);
		}
	};

	void sleep(long millis) throws InterruptedException;
}
