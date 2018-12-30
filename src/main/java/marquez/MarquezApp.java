package marquez;

import io.dropwizard.Application;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.flyway.FlywayBundle;
import io.dropwizard.flyway.FlywayFactory;
import io.dropwizard.jdbi3.JdbiFactory;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import marquez.api.resources.DatasetResource;
import marquez.api.resources.HealthResource;
import marquez.api.resources.JobResource;
import marquez.api.resources.NamespaceResource;
import marquez.api.resources.PingResource;
import marquez.core.mappers.ResourceExceptionMapper;
import marquez.core.services.JobService;
import marquez.core.services.NamespaceService;
import marquez.dao.JobDAO;
import marquez.dao.JobRunDAO;
import marquez.dao.JobVersionDAO;
import marquez.dao.NamespaceDAO;
import marquez.dao.RunArgsDAO;
import marquez.db.DatasetDao;
import marquez.service.DatasetService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

@Slf4j
public class MarquezApp extends Application<MarquezConfig> {
  private static final String APP_NAME = "MarquezApp";
  private static final String POSTGRES_DB = "postgresql";
  private static final boolean ERROR_ON_UNDEFINED = false;

  public static void main(String[] args) throws Exception {
    new MarquezApp().run(args);
  }

  @Override
  public String getName() {
    return APP_NAME;
  }

  @Override
  public void initialize(@NonNull Bootstrap<MarquezConfig> bootstrap) {
    // Enable variable substitution with environment variables.
    bootstrap.setConfigurationSourceProvider(
        new SubstitutingSourceProvider(
            bootstrap.getConfigurationSourceProvider(),
            new EnvironmentVariableSubstitutor(ERROR_ON_UNDEFINED)));

    // Enable Flyway for database migrations.
    bootstrap.addBundle(
        new FlywayBundle<MarquezConfig>() {
          @Override
          public DataSourceFactory getDataSourceFactory(@NonNull MarquezConfig config) {
            return config.getDataSourceFactory();
          }

          @Override
          public FlywayFactory getFlywayFactory(@NonNull MarquezConfig config) {
            return config.getFlywayFactory();
          }
        });
  }

  @Override
  public void run(@NonNull MarquezConfig config, @NonNull Environment env) {
    migrateDbOrError(config);
    registerResources(config, env);
  }

  private void migrateDbOrError(@NonNull MarquezConfig config) {
    final Flyway flyway = new Flyway();
    final DataSourceFactory database = config.getDataSourceFactory();
    flyway.setDataSource(database.getUrl(), database.getUser(), database.getPassword());
    // Attempt to perform a database migration. An exception is thrown on failed migration attempts
    // requiring we handle the throwable and apply a repair on the database to fix any
    // issues before terminating.
    try {
      flyway.migrate();
    } catch (FlywayException e) {
      log.error("Failed to apply migration to database.", e.getMessage());
      log.info("Repairing failed database migration...", e.getMessage());
      flyway.repair();

      log.info("Successfully repaired database, stopping app...");
      // The throwable is not propagating up the stack.
      onFatalError(); // Signal app termination.
    }
  }

  private void registerResources(@NonNull MarquezConfig config, @NonNull Environment env) {
    final JdbiFactory factory = new JdbiFactory();
    final Jdbi jdbi =
        factory
            .build(env, config.getDataSourceFactory(), POSTGRES_DB)
            .installPlugin(new SqlObjectPlugin())
            .installPlugin(new PostgresPlugin());

    final NamespaceDAO namespaceDao = jdbi.onDemand(NamespaceDAO.class);
    final DatasetDao datasetDao = jdbi.onDemand(DatasetDao.class);

    final NamespaceService namespaceService = new NamespaceService(namespaceDao);

    env.jersey().register(new PingResource());
    env.jersey().register(new HealthResource());
    env.jersey().register(new NamespaceResource(namespaceService));
    env.jersey().register(new DatasetResource(namespaceService, new DatasetService(datasetDao)));

    final JobDAO jobDAO = jdbi.onDemand(JobDAO.class);
    final JobVersionDAO jobVersionDAO = jdbi.onDemand(JobVersionDAO.class);
    final JobRunDAO jobRunDAO = jdbi.onDemand(JobRunDAO.class);
    final RunArgsDAO runArgsDAO = jdbi.onDemand(RunArgsDAO.class);

    final JobService jobService = new JobService(jobDAO, jobVersionDAO, jobRunDAO, runArgsDAO);
    env.jersey().register(new JobResource(namespaceService, jobService));

    env.jersey().register(new ResourceExceptionMapper());
  }
}