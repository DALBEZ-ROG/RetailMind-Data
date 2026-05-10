package com.retailmind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
public class RetailmindApplication {

    public static void main(String[] args) {
        SpringApplication.run(RetailmindApplication.class, args);
    }
}
