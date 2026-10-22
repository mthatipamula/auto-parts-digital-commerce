package com.autoparts.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.autoparts")
public class CatalogApplication {
    public static void main(String[] args) { SpringApplication.run(CatalogApplication.class, args); }
}
