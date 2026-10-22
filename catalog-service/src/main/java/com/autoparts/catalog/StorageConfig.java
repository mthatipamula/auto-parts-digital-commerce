package com.autoparts.catalog;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Local GCS emulator (fake-gcs-server): no credentials. In GCP the starter's client uses Workload Identity. */
@Configuration
@ConditionalOnProperty(name = "platform.gcs.emulator", havingValue = "true")
public class StorageConfig {
    @Bean
    Storage storage(@Value("${platform.gcs.emulator-host}") String host, @Value("${spring.cloud.gcp.project-id}") String project) {
        return StorageOptions.newBuilder().setHost(host).setProjectId(project).setCredentials(NoCredentials.getInstance()).build().getService();
    }
}
