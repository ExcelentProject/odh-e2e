/*
 * Copyright Skodjob authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.odh.test.e2e.upgrade;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.InstallPlan;
import io.odh.test.Environment;
import io.odh.test.OdhConstants;
import io.odh.test.framework.manager.ResourceManager;
import io.odh.test.install.InstallTypes;
import io.odh.test.install.OlmInstall;
import io.odh.test.platform.KubeUtils;
import io.odh.test.utils.DeploymentUtils;
import io.odh.test.utils.PodUtils;
import io.odh.test.utils.UpgradeUtils;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class OlmUpgradeST extends UpgradeAbstract {

    private static final Logger LOGGER = LoggerFactory.getLogger(OlmUpgradeST.class);
    private static final String DS_PROJECT_NAME = "upgrade-dsc";

    private final String startingVersion = Environment.OLM_UPGRADE_STARTING_VERSION;

    @Test
    void testUpgradeOlm() throws IOException, InterruptedException {
        assumeTrue(Objects.equals(Environment.OPERATOR_INSTALL_TYPE.toLowerCase(Locale.ENGLISH),
                InstallTypes.OLM.name().toLowerCase(Locale.ENGLISH)));
        String ntbName = "test-odh-notebook";
        String ntbNamespace = "test-odh-notebook-upgrade";

        OlmInstall olmInstall = new OlmInstall();
        olmInstall.setApproval("Manual");
        olmInstall.setStartingCsv(olmInstall.getOperatorName() + "." + startingVersion);
        olmInstall.createManual();

        // Approve install plan created for older version
        KubeUtils.waitForInstallPlan(olmInstall.getNamespace(), olmInstall.getOperatorName() + "." + startingVersion);
        InstallPlan ip = ResourceManager.getClient().getNonApprovedInstallPlan(olmInstall.getNamespace(), olmInstall.getOperatorName() + "." + startingVersion);
        ResourceManager.getClient().approveInstallPlan(olmInstall.getNamespace(), ip.getMetadata().getName());
        // Wait for old version readiness
        DeploymentUtils.waitForDeploymentReady(olmInstall.getNamespace(), olmInstall.getDeploymentName());

        // Make snapshot of current operator
        Map<String, String> operatorSnapshot = DeploymentUtils.depSnapshot(olmInstall.getNamespace(), olmInstall.getDeploymentName());

        // Deploy DSC
        deployDsc(DS_PROJECT_NAME);
        deployNotebook(ntbNamespace, ntbName);

        LabelSelector lblSelector = new LabelSelectorBuilder()
                .withMatchLabels(Map.of("app", ntbName))
                .build();

        PodUtils.waitForPodsReady(ntbNamespace, lblSelector, 1, true, () -> { });

        LOGGER.info("Upgrade to next available version in OLM catalog");
        // Approve upgrade to newer version
        KubeUtils.waitForInstallPlan(olmInstall.getNamespace(), olmInstall.getCsvName());
        ip = ResourceManager.getClient().getNonApprovedInstallPlan(olmInstall.getNamespace(), olmInstall.getCsvName());
        ResourceManager.getClient().approveInstallPlan(olmInstall.getNamespace(), ip.getMetadata().getName());
        // Wait for operator RU
        DeploymentUtils.waitTillDepHasRolled(olmInstall.getNamespace(), olmInstall.getDeploymentName(), operatorSnapshot);

        // Wait for pod stability for Dashboard
        LabelSelector labelSelector = ResourceManager.getClient().getDeployment(OdhConstants.CONTROLLERS_NAMESPACE, OdhConstants.DASHBOARD_CONTROLLER).getSpec().getSelector();
        PodUtils.verifyThatPodsAreStable(OdhConstants.CONTROLLERS_NAMESPACE, labelSelector);

        // Verify that NTB pods are stable
        PodUtils.waitForPodsReady(ntbNamespace, lblSelector, 1, true, () -> { });
        // Check logs in operator pod
        UpgradeUtils.deploymentLogIsErrorEmpty(olmInstall.getNamespace(), olmInstall.getDeploymentName());
    }
}
