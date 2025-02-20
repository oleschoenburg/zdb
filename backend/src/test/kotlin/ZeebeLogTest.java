import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.streamprocessor.TypedRecordImpl;
import io.camunda.zeebe.util.FileUtil;
import io.zeebe.containers.ZeebeContainer;
import io.zell.zdb.ZeebePaths;
import io.zell.zdb.log.ApplicationRecord;
import io.zell.zdb.log.LogContentReader;
import io.zell.zdb.log.LogSearch;
import io.zell.zdb.log.LogStatus;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public class ZeebeLogTest {

  private static File tempDir = new File("/tmp/", "data-" + ThreadLocalRandom.current().nextLong());
  private static final BpmnModelInstance PROCESS =
      Bpmn.createExecutableProcess("process")
          .startEvent()
          .parallelGateway("gw")
          .serviceTask("task")
          .zeebeJobType("type")
          .endEvent()
          .moveToLastGateway()
          .serviceTask("incidentTask")
          .zeebeInputExpression("=foo", "bar")
          .zeebeJobType("type")
          .endEvent()
          .done();
  private static final BpmnModelInstance SIMPLE_PROCESS =
      Bpmn.createExecutableProcess("simple")
          .startEvent()
          .endEvent()
          .done();
  private static final String CONTAINER_PATH = "/usr/local/zeebe/data/";
  @Container
  public static ZeebeContainer zeebeContainer = new ZeebeContainer()
      /* run the container with the current user, in order to access the data and delete it later */
      .withCreateContainerCmdModifier(cmd -> cmd.withUser(TestUtils.getRunAsUser()))
      .withFileSystemBind(tempDir.getPath(), CONTAINER_PATH, BindMode.READ_WRITE);
  private static CountDownLatch jobLatch;
  private static final AtomicLong jobKey = new AtomicLong();

  static {
    tempDir.mkdirs();
  }

  @BeforeAll
  public static void setup() throws Exception {
    final ZeebeClient client =
        ZeebeClient.newClientBuilder()
            .gatewayAddress(zeebeContainer.getExternalGatewayAddress())
            .usePlaintext()
            .build();

    client.newDeployCommand()
        .addProcessModel(PROCESS, "process.bpmn")
        .addProcessModel(SIMPLE_PROCESS, "simple.bpmn")
        .send()
        .join();

    client
        .newCreateInstanceCommand()
        .bpmnProcessId("process")
        .latestVersion()
        .variables(Map.of("var1", "1", "var2", "12", "var3", "123"))
        .send()
        .join();

    client.newPublishMessageCommand().messageName("msg").correlationKey("123")
        .timeToLive(Duration.ofSeconds(1)).send().join();
    client.newPublishMessageCommand().messageName("msg12").correlationKey("123")
        .timeToLive(Duration.ofHours(1)).send().join();

    // Small hack to ensure we reached the task and job of the previous PI
    // If the process instance we start after, ended we can be sure that
    // we reached also the wait-state in the other PI.
    client
        .newCreateInstanceCommand()
        .bpmnProcessId("simple")
        .latestVersion()
        .withResult()
        .send()
        .join();

    var responseJobKey = 0L;
    do {
        final var activateJobsResponse = client.newActivateJobsCommand().jobType("type")
            .maxJobsToActivate(1).send()
            .join();
        if (activateJobsResponse != null && !activateJobsResponse.getJobs().isEmpty()) {
          responseJobKey = activateJobsResponse.getJobs().get(0).getKey();
        }
    } while (responseJobKey <= 0);

    client.close();
  }

  @AfterAll
  public static void cleanup() throws Exception {
    FileUtil.deleteFolderIfExists(tempDir.toPath());
  }

  @Test
  public void shouldReadStatusFromLog() {
    // given
    final var logPath = ZeebePaths.Companion.getLogPath(tempDir, "1");
    var logStatus = new LogStatus(logPath);

    // when
    final var status = logStatus.status();

    // then
    assertThat(status.getHighestIndex()).isEqualTo(24);
    assertThat(status.getScannedEntries()).isEqualTo(24);
    assertThat(status.getHighestTerm()).isEqualTo(1);
    assertThat(status.getHighestRecordPosition()).isEqualTo(60);

    assertThat(status.getLowestIndex()).isEqualTo(1);
    assertThat(status.getLowestRecordPosition()).isEqualTo(1);

    assertThat(status.getMinEntrySizeBytes()).isNotZero();
    assertThat(status.getMinEntrySizeBytes()).isLessThan(status.getMaxEntrySizeBytes());

    assertThat(status.getMaxEntrySizeBytes()).isNotZero();
    assertThat(status.getMaxEntrySizeBytes()).isGreaterThan(status.getMinEntrySizeBytes());

    assertThat(status.getAvgEntrySizeBytes()).isNotZero();
    assertThat(status.getAvgEntrySizeBytes()).isGreaterThan(status.getMinEntrySizeBytes());
    assertThat(status.getAvgEntrySizeBytes()).isLessThan(status.getMaxEntrySizeBytes());

    assertThat(status.toString())
        .contains("lowestRecordPosition")
        .contains("highestRecordPosition")
        .contains("highestTerm")
        .contains("highestIndex")
        .contains("lowestIndex")
        .contains("scannedEntries")
        .contains("maxEntrySizeBytes")
        .contains("minEntrySizeBytes")
        .contains("avgEntrySizeBytes");
  }


  @Test
  public void shouldReturnDefaultsWhenReadStatusFromNonExistingLog() {
    // given
    final var logPath = ZeebePaths.Companion.getLogPath(new File("/tmp/doesntExist"), "1");
    var logStatus = new LogStatus(logPath);

    // when
    final var status = logStatus.status();

    // then
    assertThat(status.getHighestIndex()).isEqualTo(Long.MIN_VALUE);
    assertThat(status.getScannedEntries()).isZero();
    assertThat(status.getHighestTerm()).isEqualTo(Long.MIN_VALUE);
    assertThat(status.getHighestRecordPosition()).isEqualTo(Long.MIN_VALUE);
    assertThat(status.getLowestIndex()).isEqualTo(Long.MAX_VALUE);
    assertThat(status.getLowestRecordPosition()).isEqualTo(Long.MAX_VALUE);
    assertThat(status.getMinEntrySizeBytes()).isEqualTo(Integer.MAX_VALUE);
    assertThat(status.getMaxEntrySizeBytes()).isEqualTo(Integer.MIN_VALUE);
    assertThat(status.getAvgEntrySizeBytes()).isZero();

    assertThat(status).hasToString("{}");
  }


  @Test
  public void shouldBuildLogContent() throws JsonProcessingException {
    // given
    final var logPath = ZeebePaths.Companion.getLogPath(tempDir, "1");
    var logContentReader = new LogContentReader(logPath);

    // when
    final var content = logContentReader.content();

    // then
    assertThat(content.getRecords()).hasSize(24);

    final var objectMapper = new ObjectMapper();
    final var jsonNode = objectMapper.readTree(content.toString());
    assertThat(jsonNode).isNotNull(); // is valid json
  }

  @Test
  public void shouldReturnLogContentAsDotFile() throws JsonProcessingException {
    // given
    final var logPath = ZeebePaths.Companion.getLogPath(tempDir, "1");
    var logContentReader = new LogContentReader(logPath);
    final var content = logContentReader.content();

    // when
    final var dotFileContent = content.asDotFile();

    // then
    assertThat(dotFileContent).startsWith("digraph log {").endsWith("}");
  }

  @Test
  public void shouldContainNoDuplicatesInLogContent() throws JsonProcessingException {
    // given
    final var logPath = ZeebePaths.Companion.getLogPath(tempDir, "1");
    var logContentReader = new LogContentReader(logPath);

    // when
    final var content = logContentReader.content();

    // then
    // validate that records are not duplicated in LogContent
    assertThat(content.getRecords())
        .filteredOn(ApplicationRecord.class::isInstance)
        .asInstanceOf(InstanceOfAssertFactories.list(ApplicationRecord.class))
        .flatExtracting(ApplicationRecord::getEntries)
        .extracting(TypedRecordImpl::getPosition)
        .doesNotHaveDuplicates();
  }

  @Test
  public void shouldSearchPositionInLog() {
    // given
    final var logPath = ZeebePaths.Companion.getLogPath(tempDir, "1");
    var logSearch = new LogSearch(logPath);
    final var position = 1;

    // when
    final Record<?> record = logSearch.searchPosition(position);

    // then
    assertThat(record).isNotNull();
    assertThat(record.getPosition()).isEqualTo(position);
  }

  @Test
  public void shouldReturnNullOnNegPosition() {
    // given
    final var logPath = ZeebePaths.Companion.getLogPath(tempDir, "1");
    var logSearch = new LogSearch(logPath);

    // when
    final Record<?> record = logSearch.searchPosition(-1);

    // then
    assertThat(record).isNull();
  }

  @Test
  public void shouldReturnNullOnToBigPosition() {
    // given
    final var logPath = ZeebePaths.Companion.getLogPath(tempDir, "1");
    var logSearch = new LogSearch(logPath);

    // when
    final Record<?> record = logSearch.searchPosition(Long.MAX_VALUE);

    // then
    assertThat(record).isNull();
  }

  @Test
  public void shouldSearchIndexInLog() {
    // given
    final var logPath = ZeebePaths.Companion.getLogPath(tempDir, "1");
    var logSearch = new LogSearch(logPath);
    final var index = 7;

    // when
    final var logContent = logSearch.searchIndex(index);

    // then
    assertThat(logContent).isNotNull();
    assertThat(logContent.getRecords()).hasSize(1);
  }

  @Test
  public void shouldNotReturnDuplicatesWhenSearchForIndexInLog() {
    // given
    final var logPath = ZeebePaths.Companion.getLogPath(tempDir, "1");
    var logSearch = new LogSearch(logPath);
    final var index = 7;

    // when
    final var logContent = logSearch.searchIndex(index);

    // then
    // validate that records are not duplicated in LogContent
    assertThat(logContent.getRecords())
        .filteredOn(ApplicationRecord.class::isInstance)
        .asInstanceOf(InstanceOfAssertFactories.list(ApplicationRecord.class))
        .flatExtracting(ApplicationRecord::getEntries)
        .extracting(TypedRecordImpl::getPosition)
        .doesNotHaveDuplicates();
  }

  @Test
  public void shouldReturnNullOnNegIndex() {
    // given
    final var logPath = ZeebePaths.Companion.getLogPath(tempDir, "1");
    var logSearch = new LogSearch(logPath);

    // when
    final var logContent = logSearch.searchIndex(-1);

    // then
    assertThat(logContent).isNull();
  }

  @Test
  public void shouldReturnNullOnToBigIndex() {
    // given
    final var logPath = ZeebePaths.Companion.getLogPath(tempDir, "1");
    var logSearch = new LogSearch(logPath);

    // when
    final var logContent = logSearch.searchIndex(Long.MAX_VALUE);

    // then
    assertThat(logContent).isNull();
  }
}
