package com.example.paymentsystem.fds;

import com.example.paymentsystem.failure.FailureRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackageClasses = {
        FdsApplication.class,
        FailureRegistry.class
})
public class FdsApplication {

    public static void main(String[] args) {
        SpringApplication.run(FdsApplication.class, args);
    }
}
