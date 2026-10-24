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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates the Spanner instance (emulator only) and the service's database and schema if they don't exist.
 * In GCP the instance and (empty) databases come from Terraform; the schema is applied here on first start.
 * Schema changes after that would go through a migration tool (e.g. Liquibase's Spanner extension).
 */
public class SpannerSetup {
    private static final Logger log = LoggerFactory.getLogger(SpannerSetup.class);
    private final Spanner spanner;
    private final SpannerDatabaseAdminTemplate admin;
    private final String projectId;
    private final String instanceId;
    private final boolean emulator;
    private static final Pattern FIRST_TABLE = Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)", Pattern.CASE_INSENSITIVE);

    public SpannerSetup(Spanner spanner, SpannerDatabaseAdminTemplate admin, String projectId, String instanceId, boolean emulator) {
        this.spanner = spanner;
        this.admin = admin;
        this.projectId = projectId;
        this.instanceId = instanceId;
        this.emulator = emulator;
    }

    public void ensure(List<String> ddl) {
        if (emulator) createEmulatorInstance();
        // Terraform creates GCP databases empty, so "exists" is not enough: apply the schema until its first table exists.
        // (getTables() is no use here: it also lists information_schema tables, so it is never empty on real Spanner.)
        if (admin.databaseExists() && admin.tableExists(firstTable(ddl))) return;
        admin.executeDdlStrings(ddl, true);
        log.info("Created Spanner database with {} DDL statements", ddl.size());
    }

    private static String firstTable(List<String> ddl) {
        return ddl.stream().map(FIRST_TABLE::matcher).filter(Matcher::find).map(m -> m.group(1)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("DDL has no CREATE TABLE"));
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
