package com.autoparts.common.gcp;

import com.google.cloud.spanner.Spanner;
import com.google.cloud.spring.data.spanner.core.admin.SpannerDatabaseAdminTemplate;
import com.google.cloud.spring.pubsub.PubSubAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@AutoConfiguration(afterName = "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration")
public class GcpSetupAutoConfiguration {

    /** Spring Boot 4 moved Gson auto-configuration to its own module; the Spanner starter still needs a Gson bean. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.google.gson.Gson")
    static class GsonConfig {
        @Bean
        @ConditionalOnMissingBean
        com.google.gson.Gson gson() { return new com.google.gson.Gson(); }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({Spanner.class, SpannerDatabaseAdminTemplate.class})  // the service includes the Spanner starter
    static class SpannerConfig {
        @Bean
        SpannerSetup spannerSetup(Spanner spanner, SpannerDatabaseAdminTemplate admin,
                                  @Value("${spring.cloud.gcp.spanner.project-id:${spring.cloud.gcp.project-id}}") String project,
                                  @Value("${spring.cloud.gcp.spanner.instance-id}") String instance,
                                  @Value("${spring.cloud.gcp.spanner.emulator.enabled:false}") boolean emulator) {
            return new SpannerSetup(spanner, admin, project, instance, emulator);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(PubSubAdmin.class)  // the service includes the Pub/Sub starter
    static class PubSubConfig {
        @Bean
        PubSubSetup pubSubSetup(PubSubAdmin admin, @Value("${spring.cloud.gcp.pubsub.emulator-host:}") String emulatorHost) {
            return new PubSubSetup(admin, !emulatorHost.isBlank() && !"false".equals(emulatorHost));
        }
    }
}
