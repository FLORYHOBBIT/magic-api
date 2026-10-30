package org.ssssssss.magicapi.standalone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.ApplicationPidFileWriter;

/**
 * 独立运行的 magic-api 服务。
 * <p>
 * 主数据源是可选的：没有配置 spring.datasource.url 时不创建主数据源，服务照常启动，
 * 数据源可以之后在配置文件中补充，或在 magic-api 界面中在线添加。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class MagicApiServerApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(MagicApiServerApplication.class);
		// 启动后在工作目录写入 PID 文件（位置由 spring.pid.file 决定），stop.cmd 据此停止进程
		app.addListeners(new ApplicationPidFileWriter());
		app.run(args);
	}
}
