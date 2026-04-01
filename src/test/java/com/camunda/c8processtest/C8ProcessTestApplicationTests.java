package com.camunda.c8processtest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class C8ProcessTestApplicationTests {

    @Test
    void contextLoads() {
    }

}
