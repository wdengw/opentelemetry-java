/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.jaeger.proto.api_v2.Collector;
import io.opentelemetry.exporter.jaeger.proto.api_v2.CollectorServiceGrpc;
import io.opentelemetry.extension.trace.propagation.AwsXrayPropagator;
import io.opentelemetry.extension.trace.propagation.B3Propagator;
import io.opentelemetry.extension.trace.propagation.JaegerPropagator;
import io.opentelemetry.extension.trace.propagation.OtTracerPropagator;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.MetricsServiceGrpc;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@SuppressWarnings("InterruptedExceptionSwallowed")
class FullConfigTest {

  private static final BlockingQueue<Collector.PostSpansRequest> jaegerRequests =
      new LinkedBlockingDeque<>();
  private static final BlockingQueue<ExportTraceServiceRequest> otlpTraceRequests =
      new LinkedBlockingDeque<>();
  private static final BlockingQueue<ExportMetricsServiceRequest> otlpMetricsRequests =
      new LinkedBlockingDeque<>();
  private static final BlockingQueue<String> zipkinJsonRequests = new LinkedBlockingDeque<>();

  @RegisterExtension
  public static final ServerExtension server =
      new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
          sb.service(
              GrpcService.builder()
                  // OTLP spans
                  .addService(
                      new TraceServiceGrpc.TraceServiceImplBase() {
                        @Override
                        public void export(
                            ExportTraceServiceRequest request,
                            StreamObserver<ExportTraceServiceResponse> responseObserver) {
                          try {
                            RequestHeaders headers =
                                ServiceRequestContext.current().request().headers();
                            assertThat(headers.get("cat")).isEqualTo("meow");
                            assertThat(headers.get("dog")).isEqualTo("bark");
                          } catch (Throwable t) {
                            responseObserver.onError(t);
                            return;
                          }
                          otlpTraceRequests.add(request);
                          responseObserver.onNext(ExportTraceServiceResponse.getDefaultInstance());
                          responseObserver.onCompleted();
                        }
                      })
                  // OTLP metrics
                  .addService(
                      new MetricsServiceGrpc.MetricsServiceImplBase() {
                        @Override
                        public void export(
                            ExportMetricsServiceRequest request,
                            StreamObserver<ExportMetricsServiceResponse> responseObserver) {
                          try {
                            RequestHeaders headers =
                                ServiceRequestContext.current().request().headers();
                            assertThat(headers.get("cat")).isEqualTo("meow");
                            assertThat(headers.get("dog")).isEqualTo("bark");
                          } catch (Throwable t) {
                            responseObserver.onError(t);
                            return;
                          }
                          if (request.getResourceMetricsCount() > 0) {
                            otlpMetricsRequests.add(request);
                          }
                          responseObserver.onNext(
                              ExportMetricsServiceResponse.getDefaultInstance());
                          responseObserver.onCompleted();
                        }
                      })
                  // Jaeger
                  .addService(
                      new CollectorServiceGrpc.CollectorServiceImplBase() {
                        @Override
                        public void postSpans(
                            Collector.PostSpansRequest request,
                            StreamObserver<Collector.PostSpansResponse> responseObserver) {
                          jaegerRequests.add(request);
                          responseObserver.onNext(Collector.PostSpansResponse.getDefaultInstance());
                          responseObserver.onCompleted();
                        }
                      })
                  .useBlockingTaskExecutor(true)
                  .build());

          // Zipkin
          sb.service(
              "/api/v2/spans",
              (ctx, req) ->
                  HttpResponse.from(
                      req.aggregate()
                          .thenApply(
                              aggRes -> {
                                zipkinJsonRequests.add(aggRes.contentUtf8());
                                return HttpResponse.of(HttpStatus.OK);
                              })));
        }
      };

  @BeforeEach
  void setUp() {
    otlpTraceRequests.clear();
    otlpMetricsRequests.clear();
    jaegerRequests.clear();
    zipkinJsonRequests.clear();
  }

  @Test
  void configures() throws Exception {
    String endpoint = "localhost:" + server.httpPort();
    System.setProperty("otel.exporter.otlp.endpoint", endpoint);
    System.setProperty("otel.exporter.otlp.insecure", "true");
    System.setProperty("otel.exporter.otlp.timeout", "10000");

    System.setProperty("otel.exporter.jaeger.endpoint", endpoint);

    System.setProperty("otel.exporter.zipkin.endpoint", "http://" + endpoint + "/api/v2/spans");
    OpenTelemetrySdkAutoConfiguration.initialize();
    Map<String, String> headers = new HashMap<>();
    GlobalOpenTelemetry.get()
        .getPropagators()
        .getTextMapPropagator()
        .inject(
            Context.root()
                .with(
                    Span.wrap(
                        SpanContext.create(
                            TraceId.fromLongs(1, 1),
                            SpanId.fromLong(2),
                            TraceFlags.getDefault(),
                            TraceState.builder().set("cat", "meow").build())))
                .with(Baggage.builder().put("airplane", "luggage").build()),
            headers,
            Map::put);
    List<String> keys = new ArrayList<>();
    keys.addAll(W3CTraceContextPropagator.getInstance().fields());
    keys.addAll(W3CBaggagePropagator.getInstance().fields());
    keys.addAll(B3Propagator.getInstance().fields());
    keys.addAll(JaegerPropagator.getInstance().fields());
    // Legacy baggage format.
    keys.add("uberctx-airplane");
    keys.addAll(OtTracerPropagator.getInstance().fields());
    keys.addAll(AwsXrayPropagator.getInstance().fields());
    assertThat(headers).containsOnlyKeys(keys);

    GlobalOpenTelemetry.get()
        .getTracer("test")
        .spanBuilder("test")
        .startSpan()
        .setAttribute("cat", "meow")
        .setAttribute("dog", "bark")
        .end();

    await()
        .untilAsserted(
            () -> {
              assertThat(jaegerRequests).hasSize(1);
              assertThat(otlpTraceRequests).hasSize(1);
              assertThat(zipkinJsonRequests).hasSize(1);

              // Not well defined how many metric exports would have happened by now, check that
              // any
              // did. The metrics will be BatchSpanProcessor metrics.
              assertThat(otlpMetricsRequests).isNotEmpty();
            });

    ExportTraceServiceRequest traceRequest = otlpTraceRequests.take();
    assertThat(traceRequest.getResourceSpans(0).getResource().getAttributesList())
        .contains(
            KeyValue.newBuilder()
                .setKey("service.name")
                .setValue(AnyValue.newBuilder().setStringValue("test").build())
                .build(),
            KeyValue.newBuilder()
                .setKey("cat")
                .setValue(AnyValue.newBuilder().setStringValue("meow").build())
                .build());
    io.opentelemetry.proto.trace.v1.Span span =
        traceRequest.getResourceSpans(0).getInstrumentationLibrarySpans(0).getSpans(0);
    // Dog dropped by attribute limit.
    assertThat(span.getAttributesList())
        .containsExactlyInAnyOrder(
            KeyValue.newBuilder()
                .setKey("configured")
                .setValue(AnyValue.newBuilder().setBoolValue(true).build())
                .build(),
            KeyValue.newBuilder()
                .setKey("cat")
                .setValue(AnyValue.newBuilder().setStringValue("meow").build())
                .build());
  }
}
