package com.autoparts.checkout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.autoparts")
@EnableScheduling
public class CheckoutApplication {
    public static void main(String[] args) { SpringApplication.run(CheckoutApplication.class, args); }
}
