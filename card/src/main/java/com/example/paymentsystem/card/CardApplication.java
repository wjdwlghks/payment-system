package com.example.paymentsystem.card;

import com.example.paymentsystem.failure.FailureRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackageClasses = {
        CardApplication.class,
        FailureRegistry.class
})
public class CardApplication {

    public static void main(String[] args) {
        SpringApplication.run(CardApplication.class, args);
    }
}
