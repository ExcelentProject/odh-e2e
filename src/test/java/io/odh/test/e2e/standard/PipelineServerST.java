/*
 * Copyright Skodjob authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.odh.test.e2e.standard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.openshift.client.OpenShiftClient;
import io.odh.test.OdhAnnotationsLabels;
import io.odh.test.TestConstants;
import io.odh.test.TestUtils;
import io.odh.test.framework.listeners.ResourceManagerDeleteHandler;
import io.odh.test.framework.manager.ResourceManager;
import io.odh.test.platform.httpClient.MultipartFormDataBodyPublisher;
import io.opendatahub.datasciencepipelinesapplications.v1alpha1.DataSciencePipelinesApplicationBuilder;
import io.opendatahub.datasciencepipelinesapplications.v1alpha1.datasciencepipelinesapplicationspec.ApiServer;
import lombok.SneakyThrows;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.odh.test.TestUtils.DEFAULT_TIMEOUT_DURATION;
import static io.odh.test.TestUtils.DEFAULT_TIMEOUT_UNIT;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Q2: how to deal with jsons from kf pipeline?
 * fabric8 uses jackson; either use ad hoc, or generate client from openapi/swagger
 * rest-assured can read json fields using string paths (example in its readme)
 *
 * try graalpython and simply use python client from java :P
 * https://kf-pipelines.readthedocs.io/en/latest/source/kfp.client.html
 */

@ExtendWith(ResourceManagerDeleteHandler.class)
public class PipelineServerST extends StandardAbstract {
    private static final Logger logger = LoggerFactory.getLogger(PipelineServerST.class);

    private final ResourceManager resourceManager = ResourceManager.getInstance();
    private final KubernetesClient client = ResourceManager.getKubeClient().getClient();

    /// ODS-2206 - Verify user can create and run a data science pipeline in DS Project
    /// ODS-2226 - Verify user can delete components of data science pipeline from DS Pipelines page
    /// https://issues.redhat.com/browse/RHODS-5133
    @Test
    void verifyUserCanCreateRunAndDeleteADSPipelineFromDSProject() throws IOException {
        OpenShiftClient ocClient = (OpenShiftClient) client;

        String PIPELINE_TEST_NAME = "pipeline-test-name";
        String PIPELINE_TEST_DESC = "pipeline-test-desc";
        String PRJ_TITLE = "pipeline-test";
        String PIPELINE_TEST_FILEPATH = "/home/jdanek/repos/odh-e2e/src/test/resources/pipelines/iris_pipeline_compiled.yaml";
        String PIPELINE_TEST_RUN_BASENAME = "pipeline-test-run-basename";

        final String secretName = "secret-name";

        // create project
        Namespace ns = new NamespaceBuilder()
            .withNewMetadata()
            .withName(PRJ_TITLE)
            .addToLabels(OdhAnnotationsLabels.LABEL_DASHBOARD, "true")
            .addToAnnotations(OdhAnnotationsLabels.ANNO_SERVICE_MESH, "false")
            .endMetadata()
            .build();
        ResourceManager.getInstance().createResourceWithWait(ns);

        // create minio secret
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                    .withName(secretName)
                    .addToLabels("opendatahub.io/dashboard", "true")
                    .withNamespace(PRJ_TITLE)
                .endMetadata()
                .addToStringData("AWS_ACCESS_KEY_ID", "KEY007")
                .addToStringData("AWS_S3_BUCKET", "HolyGrail")
                .addToStringData("AWS_SECRET_ACCESS_KEY", "gimmeAccessPlz")
                .withType("Opaque")
                .build();
        ResourceManager.getInstance().createResourceWithWait(secret);

        // configure pipeline server (with minio, not AWS bucket)
        var dspa = new DataSciencePipelinesApplicationBuilder()
                .withNewMetadata()
                    .withName("pipelines-definition")
                    .withNamespace(PRJ_TITLE)
                .endMetadata()
                .withNewSpec()
                    .withNewApiServer()
                        .withApplyTektonCustomResource(true)
                        .withArchiveLogs(false)
                        .withAutoUpdatePipelineDefaultVersion(true)
                        .withCollectMetrics(true)
                        .withDbConfigConMaxLifetimeSec(120L)
                        .withDeploy(true)
                        .withEnableOauth(true)
                        .withEnableSamplePipeline(false)
                        .withInjectDefaultScript(true)
                        .withStripEOF(true)
                        .withTerminateStatus(ApiServer.TerminateStatus.CANCELLED)
                        .withTrackArtifacts(true)
                    .endApiServer()
                    .withNewDatabase()
                        .withDisableHealthCheck(false)
                        .withNewMariaDB()
                            .withDeploy(true)
                            .withPipelineDBName("mlpipeline")
                            .withNewPvcSize("10Gi")
                            .withUsername("mlpipeline")
                        .endMariaDB()
                    .endDatabase()
                    .withNewMlmd()
                        .withDeploy(false)
                    .endMlmd()
                    .withNewObjectStorage()
                        .withDisableHealthCheck(false)
                        // todo: ods-ci uses aws, I think minio is more appropriate here
                        .withNewMinio()
                            .withDeploy(true)
                            .withImage("quay.io/minio/minio")
                            .withNewPvcSize("1Gi")
                            .withBucket("HollyGrail")
                            .withNewS3CredentialsSecret()
                                .withAccessKey("AWS_ACCESS_KEY_ID")
                                .withSecretKey("AWS_SECRET_ACCESS_KEY")
                                .withSecretName(secretName)
                            .endMinioS3CredentialsSecret()
                        .endMinio()
                    .endObjectStorage()
                    .withNewPersistenceAgent()
                        .withDeploy(true)
                        .withNumWorkers(2L)
                    .endPersistenceAgent()
                    .withNewScheduledWorkflow()
                        .withCronScheduleTimezone("UTC")
                        .withDeploy(true)
                    .endScheduledWorkflow()
                .endSpec()
                .build();
        ResourceManager.getInstance().createResourceWithWait(dspa);

        // wait for pipeline api server to come up
        var endpoints = client.endpoints().inNamespace(PRJ_TITLE).withName("ds-pipeline-pipelines-definition");
        waitForEndpoints(endpoints);

        // connect to the api server we just created, route not available unless I enable oauth
        var route = ocClient.routes()
                .inNamespace(PRJ_TITLE).withName("ds-pipeline-pipelines-definition");

        // TODO actually I don't know how to do oauth, so lets forward a port
        var svc = client.services().inNamespace(PRJ_TITLE).withName("ds-pipeline-pipelines-definition");
        try (var portForward = svc.portForward(8888, 0)) {
            var kfpv1Client = new KFPv1Client("http://localhost:%d".formatted(portForward.getLocalPort()));

            var importedPipeline = kfpv1Client.importPipeline(PIPELINE_TEST_NAME, PIPELINE_TEST_DESC, PRJ_TITLE, PIPELINE_TEST_FILEPATH);

            List<KFPv1Client.Pipeline> pipelines = kfpv1Client.listPipelines(PRJ_TITLE);
            assertThat(pipelines.stream().map(p -> p.name).collect(Collectors.toList()), Matchers.contains(PIPELINE_TEST_NAME));

            var pipelineRun = kfpv1Client.runPipeline(PIPELINE_TEST_RUN_BASENAME, importedPipeline.id, "Immediate");

            kfpv1Client.waitForPipelineRun(pipelineRun.id);

            var status = kfpv1Client.getPipelineRunStatus();
            assertThat(status.stream().filter((r) -> r.id.equals(pipelineRun.id)).map((r) -> r.status).findFirst().get(), Matchers.is("Succeeded"));

//        Pipeline Run Should Be Listed    name=${PIPELINE_TEST_RUN_BASENAME}
//    ...    pipeline_name=${PIPELINE_TEST_NAME}

//        Verify Pipeline Run Deployment Is Successful    project_title=${PRJ_TITLE}
//    ...    workflow_name=${workflow_name}

            kfpv1Client.deletePipelineRun();
            kfpv1Client.deletePipeline();
            kfpv1Client.deletePipelineServer();
        }
    }

    private static void waitForEndpoints(Resource<Endpoints> endpoints) {
        TestUtils.waitFor("pipelines svc to come up", TestConstants.GLOBAL_POLL_INTERVAL_SHORT, TestConstants.GLOBAL_TIMEOUT, () -> {
            try {
                var endpointset = endpoints.get();
                if (endpointset == null) {
                    return false;
                }
                var subsets = endpointset.getSubsets();
                if (subsets.isEmpty()) {
                    return false;
                }
                for (var subset : subsets) {
                    return !subset.getAddresses().isEmpty();
                }
            } catch (KubernetesClientException e) {
                if (e.getCode() == 404) {
                    return false;
                }
                throw e;
            }
            return false;
        });
    }
}

class KFPv1Client {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String baseUrl;

    public KFPv1Client(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @SneakyThrows
    Pipeline importPipeline(String name, String description, String project, String filePath) {
        MultipartFormDataBodyPublisher requestBody = new MultipartFormDataBodyPublisher()
                .addFile("uploadfile", Path.of(filePath), "application/yaml");

        HttpRequest createPipelineRequest = HttpRequest.newBuilder()
                .uri(new URI(baseUrl + "/apis/v1beta1/pipelines/upload?name=%s&description=%s".formatted(name, description)))
                .header("Content-Type", requestBody.contentType())
                .POST(requestBody)
                .timeout(Duration.of(DEFAULT_TIMEOUT_DURATION, DEFAULT_TIMEOUT_UNIT.toChronoUnit()))
                .build();
        var responseCreate = httpClient.send(createPipelineRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(responseCreate.body(), responseCreate.statusCode(), Matchers.is(200));

        return new ObjectMapper().readValue(responseCreate.body(), Pipeline.class);
    }

    @SneakyThrows
    public List<Pipeline> listPipelines(String prjTitle) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/apis/v1beta1/pipelines"))
                .GET()
                .timeout(Duration.of(DEFAULT_TIMEOUT_DURATION, DEFAULT_TIMEOUT_UNIT.toChronoUnit()))
                .build();

        var reply = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(reply.statusCode(), 200, reply.body());

        var json = objectMapper.readValue(reply.body(), PipelineResponse.class);
        List<Pipeline> pipelines = json.pipelines;

        return pipelines;
    }

    @SneakyThrows
    public PipelineRun runPipeline(String pipelineTestRunBasename, String pipelineId, String immediate) {
        Assertions.assertEquals(immediate, "Immediate");

        var pipelineRun = new PipelineRun();
        pipelineRun.name = pipelineTestRunBasename;
        pipelineRun.pipeline_spec = new PipelineSpec();
        pipelineRun.pipeline_spec.pipeline_id = pipelineId;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/apis/v1beta1/runs"))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(pipelineRun)))
                .timeout(Duration.of(DEFAULT_TIMEOUT_DURATION, DEFAULT_TIMEOUT_UNIT.toChronoUnit()))
                .build();
        HttpResponse<String> reply = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        Assertions.assertEquals(reply.statusCode(), 200, reply.body());
        return objectMapper.readValue(reply.body(), ApiRunDetail.class).run;
    }

    @SneakyThrows
    public List<PipelineRun> getPipelineRunStatus() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/apis/v1beta1/runs"))
                .GET()
                .timeout(Duration.of(DEFAULT_TIMEOUT_DURATION, DEFAULT_TIMEOUT_UNIT.toChronoUnit()))
                .build();
        HttpResponse<String> reply = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        Assertions.assertEquals(reply.statusCode(), 200, reply.body());
        return objectMapper.readValue(reply.body(), ApiListRunsResponse.class).runs;
    }

    @SneakyThrows
    public PipelineRun waitForPipelineRun(String pipelineRunId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/apis/v1beta1/runs/" + pipelineRunId))
                .GET()
                .timeout(Duration.of(DEFAULT_TIMEOUT_DURATION, DEFAULT_TIMEOUT_UNIT.toChronoUnit()))
                .build();

        AtomicReference<PipelineRun> run = new AtomicReference<>();
        TestUtils.waitFor("pipelineRun to complete", 5000, 10 * 60 * 1000, () -> {
            HttpResponse<String> reply = null;
            try {
                reply = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                Assertions.assertEquals(reply.statusCode(), 200, reply.body());
                run.set(objectMapper.readValue(reply.body(), ApiRunDetail.class).run);
                var status = run.get().status;
                if (status.equals("Failed")) { // todo possible statuses?
                    throw new AssertionError("Pipeline run failed: " + status.toString() + run.get().error);
                }
                // "Running"
                return status.equals("Succeeded");
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        return run.get();
    }

    public void deletePipelineRun() {

    }

    public void deletePipeline() {
    }

    public void deletePipelineServer() {
    }

    /// helpers for reading json responses
    /// there should be openapi spec, so this can be generated

    static class PipelineResponse {
        public List<Pipeline> pipelines;
        public int total_size;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Pipeline {
        public String id;
        public String name;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ApiRunDetail {
        public PipelineRun run;
    }

    static class ApiListRunsResponse {
        public List<PipelineRun> runs;
        public int total_size;
        public String next_page_token;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PipelineRun {
        public String id;
        public String name;
        public PipelineSpec pipeline_spec;

        public String created_at;
        public String scheduled_at;
        public String finished_at;
        public String status;
        public String error;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PipelineSpec {
        public String pipeline_id;
        public String pipeline_name;
    }
}
