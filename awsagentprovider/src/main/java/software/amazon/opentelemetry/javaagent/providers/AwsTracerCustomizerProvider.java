/*
 * Copyright Amazon.com, Inc. or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.opentelemetry.javaagent.providers;

import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.contrib.awsxray.AlwaysRecordSampler;
import io.opentelemetry.contrib.awsxray.AttributePropagatingSpanProcessorBuilder;
import io.opentelemetry.contrib.awsxray.AwsMetricAttributesSpanExporterBuilder;
import io.opentelemetry.contrib.awsxray.AwsSpanMetricsProcessorBuilder;
import io.opentelemetry.contrib.awsxray.AwsXrayIdGenerator;
import io.opentelemetry.contrib.awsxray.ResourceHolder;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AwsTracerCustomizerProvider implements AutoConfigurationCustomizerProvider {
  private static final Logger logger =
      Logger.getLogger(AwsTracerCustomizerProvider.class.getName());

  static {
    if (System.getProperty("otel.aws.imds.endpointOverride") == null) {
      String overrideFromEnv = System.getenv("OTEL_AWS_IMDS_ENDPOINT_OVERRIDE");
      if (overrideFromEnv != null) {
        System.setProperty("otel.aws.imds.endpointOverride", overrideFromEnv);
      }
    }
  }

  @Override
  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    autoConfiguration.addSamplerCustomizer(this::customizeSampler);
    autoConfiguration.addTracerProviderCustomizer(this::customizeTracerProviderBuilder);
    autoConfiguration.addSpanExporterCustomizer(this::customizeSpanExporter);
  }

  private Sampler customizeSampler(Sampler sampler, ConfigProperties configProps) {
    Boolean isSmpEnabled = configProps.getBoolean("otel.smp.enabled");
    logger.log(Level.SEVERE, "isSmpEnabled: " + isSmpEnabled);
    if (Boolean.TRUE == isSmpEnabled) {
      return (AlwaysRecordSampler.create(sampler));
    } else {
      return sampler;
    }
  }

  private SdkTracerProviderBuilder customizeTracerProviderBuilder(
      SdkTracerProviderBuilder tracerProviderBuilder, ConfigProperties configProps) {
    tracerProviderBuilder.setIdGenerator(AwsXrayIdGenerator.getInstance());
    Boolean isSmpEnabled = configProps.getBoolean("otel.smp.enabled");
    logger.log(Level.SEVERE, "isSmpEnabled: " + isSmpEnabled);
    if (Boolean.TRUE == isSmpEnabled) {
      logger.log(Level.SEVERE, "add AttributePropagatingSpanProcessorBuilder");
      // Construct and set local and remote attributes span processor
      tracerProviderBuilder.addSpanProcessor(
          AttributePropagatingSpanProcessorBuilder.create().build());
      logger.log(Level.SEVERE, "configure meterProvider");
      // Construct meterProvider
      MetricExporter metricsExporter =
          OtlpGrpcMetricExporter.builder()
              .setEndpoint("http://otel-collector:4317")
              .setDefaultAggregationSelector(
                  instrumentType -> {
                    if (instrumentType == InstrumentType.HISTOGRAM) {
                      return Aggregation.base2ExponentialBucketHistogram();
                    }
                    return Aggregation.defaultAggregation();
                  })
              .setAggregationTemporalitySelector(AggregationTemporalitySelector.deltaPreferred())
              .build();
      MetricReader metricReader =
          PeriodicMetricReader.builder(metricsExporter).setInterval(Duration.ofSeconds(10)).build();
      MeterProvider meterProvider =
          SdkMeterProvider.builder()
              .setResource(ResourceHolder.getResource())
              .registerMetricReader(metricReader)
              .build();
      logger.log(Level.SEVERE, "add spanMetricsProcessor");
      // Construct and set span metrics processor
      SpanProcessor spanMetricsProcessor =
          AwsSpanMetricsProcessorBuilder.create(meterProvider, ResourceHolder.getResource())
              .build();
      tracerProviderBuilder.addSpanProcessor(spanMetricsProcessor);
    }
    return tracerProviderBuilder;
  }

  private SpanExporter customizeSpanExporter(
      SpanExporter spanExporter, ConfigProperties configProps) {
    Boolean isSmpEnabled = configProps.getBoolean("otel.smp.enabled");
    logger.log(Level.SEVERE, "isSmpEnabled: " + isSmpEnabled);
    if (Boolean.TRUE == isSmpEnabled) {
      return AwsMetricAttributesSpanExporterBuilder.create(
              spanExporter, ResourceHolder.getResource())
          .build();
    } else {
      return spanExporter;
    }
  }
}
