package com.autoparts.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.autoparts")
@EnableScheduling
public class AccountApplication {
    public static void main(String[] args) { SpringApplication.run(AccountApplication.class, args); }
}
