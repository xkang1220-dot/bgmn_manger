package com.kk;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.kk")
@MapperScan("com.kk.**.mapper")
public class KkApplication {

    public static void main(String[] args) {
        SpringApplication.run(KkApplication.class, args);
    }
}
