/*
 * Copyright Skodjob authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.odh.test.utils;

import io.odh.test.framework.manager.ResourceManager;

import static io.odh.test.framework.matchers.Matchers.logHasNoUnexpectedErrors;
import static org.hamcrest.MatcherAssert.assertThat;

public class UpgradeUtils {

    public static void deploymentLogIsErrorEmpty(String namespace, String deploymentName) {
        // Check that operator doesn't contain errors in logs
        String operatorLog = ResourceManager.getClient().getClient().apps().deployments()
                .inNamespace(namespace).withName(deploymentName).getLog();

        assertThat(operatorLog, logHasNoUnexpectedErrors());
    }
}
