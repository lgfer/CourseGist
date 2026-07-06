package com.coursegist.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
@MapperScan("com.coursegist.server.mapper")
public class CourseGistApplication {

	public static void main(String[] args) {
		// 显式指定为 SERVLET 应用，避免多 starter 场景下类型推断出错
		new SpringApplicationBuilder(CourseGistApplication.class)
				.web(WebApplicationType.SERVLET)
				.run(args);
	}
}