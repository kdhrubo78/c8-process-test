package com.camunda.c8processtest;

import io.camunda.client.annotation.Deployment;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@Deployment(resources = {"classpath*:/bpmn/**/*.bpmn", "classpath*:/dmn/**/*.dmn"})
public class C8ProcessTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(C8ProcessTestApplication.class, args);
    }

}
