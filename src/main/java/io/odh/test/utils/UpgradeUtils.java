/*
 * Copyright Skodjob authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.odh.test.utils;

import io.odh.test.TestConstants;
import io.skodjob.testframe.resources.KubeResourceManager;

import java.time.Instant;

import static io.odh.test.framework.matchers.Matchers.logHasNoUnexpectedErrors;
import static org.hamcrest.MatcherAssert.assertThat;

public class UpgradeUtils {

    public static void deploymentLogIsErrorEmpty(String namespace, String deploymentName, Instant sinceTimestamp) {
        // Check that operator doesn't contain errors in logs since sec
        String operatorLog = KubeResourceManager.getKubeClient().getClient().apps().deployments()
                .inNamespace(namespace).withName(deploymentName).sinceTime(TestConstants.TIMESTAMP_DATE_FORMAT.format(sinceTimestamp)).getLog();

        assertThat(operatorLog, logHasNoUnexpectedErrors());
    }

    public static void deploymentLogIsErrorEmpty(String namespace, String deploymentName) {
        // Check that operator doesn't contain errors in logs
        String operatorLog = KubeResourceManager.getKubeClient().getClient().apps().deployments()
                .inNamespace(namespace).withName(deploymentName).getLog();

        assertThat(operatorLog, logHasNoUnexpectedErrors());
    }
}
