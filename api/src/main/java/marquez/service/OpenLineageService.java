package marquez.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.PushGateway;
import java.io.IOException;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import marquez.common.Utils;
import marquez.common.models.DatasetName;
import marquez.common.models.DatasetVersionId;
import marquez.common.models.JobName;
import marquez.common.models.JobVersionId;
import marquez.common.models.NamespaceName;
import marquez.common.models.RunId;
import marquez.common.models.RunState;
import marquez.db.DatasetVersionDao;
import marquez.db.OpenLineageDao;
import marquez.db.models.ExtendedDatasetVersionRow;
import marquez.db.models.ExtendedRunRow;
import marquez.db.models.RunArgsRow;
import marquez.db.models.RunRow;
import marquez.db.models.UpdateLineageRow;
import marquez.service.RunTransitionListener.JobInputUpdate;
import marquez.service.RunTransitionListener.JobOutputUpdate;
import marquez.service.RunTransitionListener.RunInput;
import marquez.service.RunTransitionListener.RunOutput;
import marquez.service.models.LineageEvent;
import marquez.service.models.LineageEvent.Dataset;
import marquez.service.models.RunMeta;

@Slf4j
public class OpenLineageService {
  private final OpenLineageDao openLineageDao;
  private final RunService runService;
  private final DatasetVersionDao datasetVersionDao;
  private final ObjectMapper mapper = Utils.newObjectMapper();
  private final PushGateway prometheus;

  public OpenLineageService(
      OpenLineageDao openLineageDao, RunService runService, DatasetVersionDao datasetVersionDao) {
    this.openLineageDao = openLineageDao;
    this.runService = runService;
    this.datasetVersionDao = datasetVersionDao;
    this.prometheus = new PushGateway("localhost:9091");
  }

  public CompletableFuture<Void> createAsync(LineageEvent event) {
    CompletableFuture marquez =
        CompletableFuture.supplyAsync(() -> openLineageDao.updateMarquezModel(event, mapper))
            .thenAccept(
                (update) -> {
                  if (event.getEventType() != null) {
                    buildJobInputUpdate(update).ifPresent(runService::notify);
                    buildJobOutputUpdate(update).ifPresent(runService::notify);
                  }
                  if (event.getEventType() != null &&
                      openLineageDao.getRunState(event.getEventType()) == RunState.COMPLETED) {
                    Optional<ExtendedRunRow> latestRun =
                        openLineageDao.createRunDao().findLatestRunForJob(event.getJob().getName(), event.getJob().getNamespace());
                    CollectorRegistry registry = new CollectorRegistry();

                    if (latestRun.get().getStartedAt().isPresent()) {
                      Gauge metric = Gauge.build()
                          .name("job_duration_ms")
                          .help("job duration ms")
                          .labelNames("name")
                          .register(registry);

                      long milli = ChronoUnit.MILLIS.between(latestRun.get().getStartedAt().get(),
                          latestRun.get().getEndedAt().get());
                      metric.labels(String.format("%s_%s", event.getJob().getNamespace(),
                          event.getJob().getName()).replaceAll("[-.]", ""))
                          .inc(milli);
                    }

                    //get previous run
                    List<ExtendedRunRow> top2 =
                        openLineageDao.createRunDao().findLast2Runs(event.getJob().getName(), event.getJob().getNamespace());
                    if (top2.size() > 1) {
                      if (top2.get(0).getLocation() != null && !top2.get(0).getLocation().equalsIgnoreCase(top2.get(1).getLocation())) {
                        Counter metric = Counter.build()
                            .name("job_version_change")
                            .help("job version change")
                            .labelNames("name")
                            .register(registry);
                        metric.labels(
                                String.format("%s_%s", event.getJob().getNamespace(),
                                    event.getJob().getName()).replaceAll("[-.]", ""))
                            .inc(1);
                      }
                    }
                    if (event.getOutputs() != null) {
                      for (Dataset dataset: event.getOutputs()) {
                        if (dataset.getFacets() != null && dataset.getFacets().getAdditionalFacets().containsKey("datasetQualityFacet")) {
                          List<Map<String, Object>> metrics = (List<Map<String, Object>>)dataset.getFacets().getAdditionalFacets().get("datasetQualityFacet");
                          for (Map<String, Object> metric : metrics) {
                            for (Map.Entry<String, Object>entry : metric.entrySet()) {
                              Map map = (Map) entry.getValue();
                              Gauge gauge = Gauge.build()
                                  .name(entry.getKey())
                                  .labelNames("name")
                                  .help(" metric ")
                                  .register(registry);
                              gauge.labels(
                                      String.format("%s_%s", dataset.getNamespace(),
                                          dataset.getName()).replaceAll("[-.]", ""))
                                  .inc((Integer) map.get("sum"));
                            }
                          }
                        }
                      }
                    }
                    try {
                      prometheus.push(registry, "dataQualityFacet");
                    } catch (IOException e) {
                      log.error("err", e);
                      e.printStackTrace();
                    }

                  }
                });

    CompletableFuture openLineage =
        CompletableFuture.runAsync(
            () ->
                openLineageDao.createLineageEvent(
                    event.getEventType() == null ? "" : event.getEventType(),
                    event.getEventTime().withZoneSameInstant(ZoneId.of("UTC")).toInstant(),
                    event.getRun().getRunId(),
                    event.getJob().getName(),
                    event.getJob().getNamespace(),
                    openLineageDao.createJsonArray(event, mapper),
                    event.getProducer()));

    return CompletableFuture.allOf(marquez, openLineage);
  }

  private Optional<JobOutputUpdate> buildJobOutputUpdate(UpdateLineageRow record) {
    RunId runId = RunId.of(record.getRun().getUuid());
    return buildJobOutput(runId, buildJobVersionId(record), record);
  }

  private Optional<JobInputUpdate> buildJobInputUpdate(UpdateLineageRow record) {
    RunId runId = RunId.of(record.getRun().getUuid());
    return buildJobInput(
        record.getRun(), record.getRunArgs(), buildJobVersionId(record), runId, record);
  }

  public JobVersionId buildJobVersionId(UpdateLineageRow record) {
    if (record.getJobVersionBag() != null) {
      return JobVersionId.builder()
          .versionUuid(record.getJobVersionBag().getJobVersionRow().getUuid())
          .namespace(NamespaceName.of(record.getNamespace().getName()))
          .name(JobName.of(record.getJob().getName()))
          .build();
    }
    return null;
  }

  Optional<JobOutputUpdate> buildJobOutput(
      RunId runId, JobVersionId jobVersionId, UpdateLineageRow record) {
    // We query for all datasets since they can come in slowly over time
    List<ExtendedDatasetVersionRow> datasets =
        datasetVersionDao.findByRunId(record.getRun().getUuid());

    // Do not trigger a JobOutput event if there are no new datasets
    if (datasets.isEmpty() && record.getOutputs().isEmpty()) {
      return Optional.empty();
    }

    List<RunOutput> runOutputs =
        datasets.stream()
            .map(this::buildDatasetVersionId)
            .map(RunOutput::new)
            .collect(Collectors.toList());

    return Optional.of(
        new JobOutputUpdate(
            runId,
            jobVersionId,
            JobName.of(record.getRun().getJobName()),
            NamespaceName.of(record.getRun().getNamespaceName()),
            runOutputs));
  }

  Optional<JobInputUpdate> buildJobInput(
      RunRow run,
      RunArgsRow runArgsRow,
      JobVersionId jobVersionId,
      RunId runId,
      UpdateLineageRow record) {
    // We query for all datasets since they can come in slowly over time
    List<ExtendedDatasetVersionRow> datasets =
        datasetVersionDao.findInputsByRunId(record.getRun().getUuid());
    // Do not trigger a JobInput event if there are no new datasets
    if (datasets.isEmpty() || record.getInputs().isEmpty()) {
      return Optional.empty();
    }

    Map<String, String> runArgs;
    try {
      runArgs = Utils.fromJson(runArgsRow.getArgs(), new TypeReference<Map<String, String>>() {});
    } catch (Exception e) {
      runArgs = new HashMap<>();
    }

    List<RunInput> runInputs =
        datasets.stream()
            .map(this::buildDatasetVersionId)
            .map(RunInput::new)
            .collect(Collectors.toList());

    return Optional.of(
        new JobInputUpdate(
            runId,
            RunMeta.builder()
                .id(RunId.of(run.getUuid()))
                .nominalStartTime(run.getNominalStartTime().orElse(null))
                .nominalEndTime(run.getNominalEndTime().orElse(null))
                .args(runArgs)
                .build(),
            jobVersionId,
            JobName.of(run.getJobName()),
            NamespaceName.of(run.getNamespaceName()),
            runInputs));
  }

  private DatasetVersionId buildDatasetVersionId(ExtendedDatasetVersionRow ds) {
    return DatasetVersionId.builder()
        .versionUuid(ds.getVersion())
        .namespace(NamespaceName.of(ds.getNamespaceName()))
        .name(DatasetName.of(ds.getDatasetName()))
        .build();
  }
}
