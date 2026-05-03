package com.example.paymentsystem.fds;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.example.paymentsystem.fds",
        "com.example.paymentsystem.failure"
})
public class FdsApplication {

    public static void main(String[] args) {
        SpringApplication.run(FdsApplication.class, args);
    }
}
