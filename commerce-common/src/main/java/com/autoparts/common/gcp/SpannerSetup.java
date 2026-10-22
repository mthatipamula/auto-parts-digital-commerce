package com.autoparts.common.gcp;

import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.InstanceConfigId;
import com.google.cloud.spanner.InstanceId;
import com.google.cloud.spanner.InstanceInfo;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spring.data.spanner.core.admin.SpannerDatabaseAdminTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Creates the Spanner instance (emulator only) and the service's database and schema if they don't exist.
 * In GCP the instance comes from Terraform; databases and schema are created here on first start.
 * Schema changes after that would go through a migration tool (e.g. Liquibase's Spanner extension).
 */
public class SpannerSetup {
    private static final Logger log = LoggerFactory.getLogger(SpannerSetup.class);
    private final Spanner spanner;
    private final SpannerDatabaseAdminTemplate admin;
    private final String projectId;
    private final String instanceId;
    private final boolean emulator;

    public SpannerSetup(Spanner spanner, SpannerDatabaseAdminTemplate admin, String projectId, String instanceId, boolean emulator) {
        this.spanner = spanner;
        this.admin = admin;
        this.projectId = projectId;
        this.instanceId = instanceId;
        this.emulator = emulator;
    }

    public void ensure(List<String> ddl) {
        if (emulator) createEmulatorInstance();
        if (admin.databaseExists()) return;
        admin.executeDdlStrings(ddl, true);
        log.info("Created Spanner database with {} DDL statements", ddl.size());
    }

    private void createEmulatorInstance() {
        try {
            spanner.getInstanceAdminClient().createInstance(InstanceInfo.newBuilder(InstanceId.of(projectId, instanceId))
                    .setInstanceConfigId(InstanceConfigId.of(projectId, "emulator-config"))
                    .setDisplayName(instanceId).setNodeCount(1).build()).get();
            log.info("Created emulator Spanner instance {}", instanceId);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof SpannerException se && se.getErrorCode() == ErrorCode.ALREADY_EXISTS)) {
                throw new IllegalStateException("Could not create Spanner instance", e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
