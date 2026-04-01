package com.camunda.c8processtest;

import org.springframework.boot.SpringApplication;

public class TestC8ProcessTestApplication {

    public static void main(String[] args) {
        SpringApplication.from(C8ProcessTestApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
