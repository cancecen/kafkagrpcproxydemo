package server.modules;

import com.be_hase.grpc.micrometer.GrpcMetricsConfigure;
import com.be_hase.grpc.micrometer.MicrometerServerInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dagger.Module;
import dagger.Provides;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.micrometer.core.instrument.Metrics;
import java.nio.file.Path;
import java.nio.file.Paths;
import server.KafkaProxyServiceImpl;
import server.discovery.ClusterEndpoints;
import server.discovery.EndpointDiscoverer;
import server.discovery.ZookeeperEndpointDiscoverer;
import server.interceptors.ClientIdInterceptor;
import server.kafkautils.ClientPool;
import server.kafkautils.KafkaClientFactory;

@Module
public class KafkaProxyModule {

  @Provides
  public static EndpointDiscoverer provideFileBasedEndpointDiscoverer() {
    final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
    final Path bootstrapPath = Paths.get("target/resources/cluster-coordinates").toAbsolutePath();
    final String bootstrapFile = "cluster-coordinates.yaml";
    try {
      final ClusterEndpoints initialEndpoints =
          objectMapper.readValue(
              Paths.get(bootstrapPath.toString(), bootstrapFile).toFile(), ClusterEndpoints.class);
      return new ZookeeperEndpointDiscoverer("localhost:2181", initialEndpoints);
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Provides
  public static ClientPool provideClientPool(
      final EndpointDiscoverer endpointDiscoverer, final KafkaClientFactory kafkaClientFactory) {
    return new ClientPool(endpointDiscoverer, kafkaClientFactory);
  }

  @Provides
  public static GrpcMetricsConfigure provideGrpcMetricsConfigure() {
    return GrpcMetricsConfigure.create()
        .withLatencyTimerConfigure(
            builder -> {
              builder.publishPercentiles(0.5, 0.75, 0.95, 0.99);
            });
  }

  @Provides
  public static MicrometerServerInterceptor provideMicrometerServerInterceptor(
      final GrpcMetricsConfigure configure) {
    return new MicrometerServerInterceptor(Metrics.globalRegistry, configure);
  }

  @Provides
  public static ClientIdInterceptor provideClientIdInterceptor() {
    return new ClientIdInterceptor();
  }

  @Provides
  public static KafkaProxyServiceImpl provideKafkaProxyServiceImp(final ClientPool clientPool) {
    return new KafkaProxyServiceImpl(clientPool);
  }

  @Provides
  public static KafkaClientFactory kafkaClientFactory() {
    return new KafkaClientFactory();
  }

  @Provides
  public static Server provideServer(
      final ClientIdInterceptor clientIdInterceptor,
      final MicrometerServerInterceptor micrometerServerInterceptor,
      final KafkaProxyServiceImpl kafkaProxyServiceImpl) {
    return ServerBuilder.forPort(9999)
        .addService(
            ServerInterceptors.intercept(
                kafkaProxyServiceImpl, clientIdInterceptor, micrometerServerInterceptor))
        .build();
  }
}
