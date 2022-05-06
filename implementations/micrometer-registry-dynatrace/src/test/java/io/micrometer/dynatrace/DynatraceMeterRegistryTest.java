/*
 * Copyright 2017 VMware, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.dynatrace;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.config.validate.ValidationException;
import io.micrometer.core.ipc.http.HttpSender;
import io.micrometer.core.util.internal.logging.MockLogger;
import io.micrometer.core.util.internal.logging.MockLoggerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.micrometer.core.util.internal.logging.InternalLogLevel.DEBUG;
import static io.micrometer.core.util.internal.logging.InternalLogLevel.ERROR;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DynatraceMeterRegistry}.
 *
 * @author Johnny Lim
 */
class DynatraceMeterRegistryTest {

    private static final MockLoggerFactory FACTORY = new MockLoggerFactory();

    private static final MockLogger LOGGER = FACTORY.getLogger(DynatraceMeterRegistry.class);

    private static final Double GAUGE_VALUE = 1.0;

    private final DynatraceConfig config = new DynatraceConfig() {
        @Override
        public String get(String key) {
            return null;
        }

        @Override
        public String uri() {
            return "http://localhost";
        }

        @Override
        public String deviceId() {
            return "deviceId";
        }

        @Override
        public String apiToken() {
            return "apiToken";
        }
    };

    private final MockClock clock = new MockClock();

    private final HttpSender httpClient = request -> new HttpSender.Response(200, null);

    private final DynatraceMeterRegistry meterRegistry = FACTORY.injectLogger(() -> createRegistry(httpClient));

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        LOGGER.clear();
    }

    @Test
    void constructorWhenUriIsMissingShouldThrowValidationException() {
        assertThatThrownBy(() -> new DynatraceMeterRegistry(new DynatraceConfig() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public String deviceId() {
                return "deviceId";
            }

            @Override
            public String apiToken() {
                return "apiToken";
            }
        }, Clock.SYSTEM)).isExactlyInstanceOf(ValidationException.class);
    }

    @Test
    void constructorWhenDeviceIdIsMissingShouldThrowValidationException() {
        assertThatThrownBy(() -> new DynatraceMeterRegistry(new DynatraceConfig() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public String uri() {
                return "uri";
            }

            @Override
            public String apiToken() {
                return "apiToken";
            }
        }, Clock.SYSTEM)).isExactlyInstanceOf(ValidationException.class);
    }

    @Test
    void constructorWhenApiTokenIsMissingShouldThrowValidationException() {
        assertThatThrownBy(() -> new DynatraceMeterRegistry(new DynatraceConfig() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public String uri() {
                return "uri";
            }

            @Override
            public String deviceId() {
                return "deviceId";
            }
        }, Clock.SYSTEM)).isExactlyInstanceOf(ValidationException.class);
    }

    @Test
    void putCustomMetricOnSuccessShouldAddMetricIdToCreatedCustomMetrics()
            throws NoSuchFieldException, IllegalAccessException {
        Field createdCustomMetricsField = DynatraceMeterRegistry.class.getDeclaredField("createdCustomMetrics");
        createdCustomMetricsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> createdCustomMetrics = (Set<String>) createdCustomMetricsField.get(meterRegistry);
        assertThat(createdCustomMetrics).isEmpty();

        DynatraceMetricDefinition customMetric = new DynatraceMetricDefinition("metricId", null, null, null,
                new String[] { "type" }, null);
        meterRegistry.putCustomMetric(customMetric);
        assertThat(createdCustomMetrics).containsExactly("metricId");
    }

    @Test
    void writeMeterWithGauge() {
        meterRegistry.gauge("my.gauge", GAUGE_VALUE);
        Gauge gauge = meterRegistry.find("my.gauge").gauge();
        assertThat(meterRegistry.writeMeter(gauge)).hasSize(1);
    }

    @Test
    void writeMeterWithGaugeShouldDropNanValue() {
        meterRegistry.gauge("my.gauge", Double.NaN);
        Gauge gauge = meterRegistry.find("my.gauge").gauge();
        assertThat(meterRegistry.writeMeter(gauge)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void writeMeterWithGaugeWhenChangingFiniteToNaNShouldWork() {
        AtomicBoolean first = new AtomicBoolean(true);
        meterRegistry.gauge("my.gauge", first, (b) -> b.getAndSet(false) ? GAUGE_VALUE : Double.NaN);
        Gauge gauge = meterRegistry.find("my.gauge").gauge();
        Stream<DynatraceMeterRegistry.DynatraceCustomMetric> stream = meterRegistry.writeMeter(gauge);
        List<DynatraceMeterRegistry.DynatraceCustomMetric> metrics = stream.collect(Collectors.toList());
        assertThat(metrics).hasSize(1);
        DynatraceMeterRegistry.DynatraceCustomMetric metric = metrics.get(0);
        DynatraceTimeSeries timeSeries = metric.getTimeSeries();
        try {
            Map<String, Object> map = mapper.readValue(timeSeries.asJson(), Map.class);
            List<List<Number>> dataPoints = (List<List<Number>>) map.get("dataPoints");
            assertThat(dataPoints.get(0).get(1).doubleValue()).isEqualTo(GAUGE_VALUE);
        }
        catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void writeMeterWithGaugeShouldDropInfiniteValues() {
        meterRegistry.gauge("my.gauge", Double.POSITIVE_INFINITY);
        Gauge gauge = meterRegistry.find("my.gauge").gauge();
        assertThat(meterRegistry.writeMeter(gauge)).isEmpty();

        meterRegistry.gauge("my.gauge", Double.NEGATIVE_INFINITY);
        gauge = meterRegistry.find("my.gauge").gauge();
        assertThat(meterRegistry.writeMeter(gauge)).isEmpty();
    }

    @Test
    void writeMeterWithTimeGauge() {
        AtomicReference<Double> obj = new AtomicReference<>(GAUGE_VALUE);
        meterRegistry.more().timeGauge("my.timeGauge", Tags.empty(), obj, TimeUnit.SECONDS, AtomicReference::get);
        TimeGauge timeGauge = meterRegistry.find("my.timeGauge").timeGauge();
        assertThat(meterRegistry.writeMeter(timeGauge)).hasSize(1);
    }

    @Test
    void writeMeterWithTimeGaugeShouldDropNanValue() {
        AtomicReference<Double> obj = new AtomicReference<>(Double.NaN);
        meterRegistry.more().timeGauge("my.timeGauge", Tags.empty(), obj, TimeUnit.SECONDS, AtomicReference::get);
        TimeGauge timeGauge = meterRegistry.find("my.timeGauge").timeGauge();
        assertThat(meterRegistry.writeMeter(timeGauge)).isEmpty();
    }

    @Test
    void writeMeterWithTimeGaugeShouldDropInfiniteValues() {
        AtomicReference<Double> obj = new AtomicReference<>(Double.POSITIVE_INFINITY);
        meterRegistry.more().timeGauge("my.timeGauge", Tags.empty(), obj, TimeUnit.SECONDS, AtomicReference::get);
        TimeGauge timeGauge = meterRegistry.find("my.timeGauge").timeGauge();
        assertThat(meterRegistry.writeMeter(timeGauge)).isEmpty();

        obj = new AtomicReference<>(Double.NEGATIVE_INFINITY);
        meterRegistry.more().timeGauge("my.timeGauge", Tags.empty(), obj, TimeUnit.SECONDS, AtomicReference::get);
        timeGauge = meterRegistry.find("my.timeGauge").timeGauge();
        assertThat(meterRegistry.writeMeter(timeGauge)).isEmpty();
    }

    @Test
    void writeCustomMetrics() {
        meterRegistry.gauge("my.gauge", GAUGE_VALUE);
        Gauge gauge = meterRegistry.find("my.gauge").gauge();
        Stream<DynatraceMeterRegistry.DynatraceCustomMetric> series = meterRegistry.writeMeter(gauge);
        List<DynatraceTimeSeries> timeSeries = series.map(DynatraceMeterRegistry.DynatraceCustomMetric::getTimeSeries)
                .collect(Collectors.toList());
        List<DynatraceBatchedPayload> entries = meterRegistry.createPostMessages("my.type", null, timeSeries);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).metricCount).isEqualTo(1);
        assertThat(isValidJson(entries.get(0).payload)).isEqualTo(true);
    }

    @Test
    void whenAllTsTooLargeEmptyMessageListReturned() {
        List<DynatraceBatchedPayload> messages = meterRegistry.createPostMessages("my.type", null,
                Collections.singletonList(createTimeSeriesWithDimensions(10_000)));
        assertThat(messages).isEmpty();
    }

    @Test
    void splitsWhenExactlyExceedingMaxByComma() {
        // comma needs to be considered when there is more than one time series
        List<DynatraceBatchedPayload> messages = meterRegistry.createPostMessages("my.type", "my.group",
                // Max bytes: 15330 (excluding header/footer, 15360 with header/footer)
                Arrays.asList(createTimeSeriesWithDimensions(750), // 14861 bytes
                        createTimeSeriesWithDimensions(23, "asdfg"), // 469 bytes
                                                                     // (overflows due to
                                                                     // comma)
                        createTimeSeriesWithDimensions(750), // 14861 bytes
                        createTimeSeriesWithDimensions(22, "asd") // 468 bytes + comma
                ));
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).metricCount).isEqualTo(1);
        assertThat(messages.get(1).metricCount).isEqualTo(1);
        assertThat(messages.get(2).metricCount).isEqualTo(2);
        assertThat(messages.get(2).payload.getBytes(UTF_8).length).isEqualTo(15360);
        assertThat(messages.stream().map(message -> message.payload).allMatch(this::isValidJson)).isTrue();
    }

    @Test
    void countsPreviousAndNextComma() {
        List<DynatraceBatchedPayload> messages = meterRegistry.createPostMessages("my.type", null,
                // Max bytes: 15330 (excluding header/footer, 15360 with header/footer)
                Arrays.asList(createTimeSeriesWithDimensions(750), // 14861 bytes
                        createTimeSeriesWithDimensions(10, "asdf"), // 234 bytes + comma
                        createTimeSeriesWithDimensions(10, "asdf") // 234 bytes + comma =
                                                                   // 15331 bytes
                                                                   // (overflow)
                ));
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).metricCount).isEqualTo(2);
        assertThat(messages.get(1).metricCount).isEqualTo(1);
        assertThat(messages.stream().map(message -> message.payload).allMatch(this::isValidJson)).isTrue();
    }

    @Test
    void writeMeterWhenCustomMeterHasOnlyNonFiniteValuesShouldNotBeWritten() {
        Measurement measurement1 = new Measurement(() -> Double.POSITIVE_INFINITY, Statistic.VALUE);
        Measurement measurement2 = new Measurement(() -> Double.NEGATIVE_INFINITY, Statistic.VALUE);
        Measurement measurement3 = new Measurement(() -> Double.NaN, Statistic.VALUE);
        List<Measurement> measurements = Arrays.asList(measurement1, measurement2, measurement3);
        Meter meter = Meter.builder("my.meter", Meter.Type.GAUGE, measurements).register(this.meterRegistry);
        assertThat(meterRegistry.writeMeter(meter)).isEmpty();
    }

    @Test
    void writeMeterWhenCustomMeterHasMixedFiniteAndNonFiniteValuesShouldSkipOnlyNonFiniteValues() {
        Measurement measurement1 = new Measurement(() -> Double.POSITIVE_INFINITY, Statistic.VALUE);
        Measurement measurement2 = new Measurement(() -> Double.NEGATIVE_INFINITY, Statistic.VALUE);
        Measurement measurement3 = new Measurement(() -> Double.NaN, Statistic.VALUE);
        Measurement measurement4 = new Measurement(() -> 1d, Statistic.VALUE);
        Measurement measurement5 = new Measurement(() -> 2d, Statistic.VALUE);
        List<Measurement> measurements = Arrays.asList(measurement1, measurement2, measurement3, measurement4,
                measurement5);
        Meter meter = Meter.builder("my.meter", Meter.Type.GAUGE, measurements).register(this.meterRegistry);
        assertThat(meterRegistry.writeMeter(meter)).hasSize(2);
    }

    @Test
    void writeSummary() {
        DistributionSummary summary = DistributionSummary.builder("my.distribution.summary").register(meterRegistry);

        summary.record(1d);
        summary.record(2d);
        summary.record(3d);

        clock.add(config.step());

        List<String> metrics = meterRegistry.writeSummary(summary).map((metric) -> metric.getTimeSeries().asJson())
                .collect(Collectors.toList());

        assertThat(metrics).containsExactlyInAnyOrder(
                "{\"timeseriesId\":\"custom:my.distribution.summary.sum\",\"dataPoints\":[[60001,6]]}",
                "{\"timeseriesId\":\"custom:my.distribution.summary.count\",\"dataPoints\":[[60001,3]]}",
                "{\"timeseriesId\":\"custom:my.distribution.summary.avg\",\"dataPoints\":[[60001,2]]}",
                "{\"timeseriesId\":\"custom:my.distribution.summary.max\",\"dataPoints\":[[60001,3]]}");
    }

    @Test
    void failOnPutShouldHaveProperLogging() {
        HttpSender httpClient = request -> new HttpSender.Response(500, "simulated");
        DynatraceMeterRegistry meterRegistry = FACTORY.injectLogger(() -> createRegistry(httpClient));

        meterRegistry.gauge("my.gauge", GAUGE_VALUE);
        meterRegistry.publish();
        Gauge gauge = meterRegistry.find("my.gauge").gauge();
        assertThat(meterRegistry.writeMeter(gauge)).hasSize(1);

        assertThat(LOGGER.getLogEvents()).hasSize(1);
        assertThat(LOGGER.getLogEvents().get(0).getLevel()).isSameAs(ERROR);
        assertThat(LOGGER.getLogEvents().get(0).getMessage()).isEqualTo(
                "failed to create custom metric custom:my.gauge in Dynatrace: Error Code=500, Response Body=simulated");
        assertThat(LOGGER.getLogEvents().get(0).getCause()).isNull();
    }

    @Test
    void failOnPostShouldHaveProperLogging() throws Throwable {
        HttpSender httpClient = mock(HttpSender.class);
        HttpSender.Request.Builder builder = HttpSender.Request.build("https://test", httpClient);
        when(httpClient.put(isA(String.class))).thenReturn(builder);
        when(httpClient.post(isA(String.class))).thenReturn(builder);
        when(httpClient.send(isA(HttpSender.Request.class))).thenReturn(new HttpSender.Response(200, null),
                new HttpSender.Response(500, "simulated"));

        DynatraceMeterRegistry meterRegistry = FACTORY.injectLogger(() -> createRegistry(httpClient));

        meterRegistry.gauge("my.gauge", GAUGE_VALUE);
        meterRegistry.publish();
        Gauge gauge = meterRegistry.find("my.gauge").gauge();
        assertThat(meterRegistry.writeMeter(gauge)).hasSize(1);

        assertThat(LOGGER.getLogEvents()).hasSize(3);
        assertThat(LOGGER.getLogEvents().get(0).getLevel()).isSameAs(DEBUG);
        assertThat(LOGGER.getLogEvents().get(0).getMessage())
                .isEqualTo("created custom:my.gauge as custom metric in Dynatrace");
        assertThat(LOGGER.getLogEvents().get(0).getCause()).isNull();

        assertThat(LOGGER.getLogEvents().get(1).getLevel()).isSameAs(ERROR);
        assertThat(LOGGER.getLogEvents().get(1).getMessage())
                .isEqualTo("failed to send metrics to Dynatrace: Error Code=500, Response Body=simulated");
        assertThat(LOGGER.getLogEvents().get(1).getCause()).isNull();
    }

    private DynatraceMeterRegistry createRegistry(HttpSender httpClient) {
        return DynatraceMeterRegistry.builder(config).clock(clock).httpClient(httpClient).build();
    }

    private DynatraceTimeSeries createTimeSeriesWithDimensions(int numberOfDimensions) {
        return createTimeSeriesWithDimensions(numberOfDimensions, "some.metric");
    }

    private DynatraceTimeSeries createTimeSeriesWithDimensions(int numberOfDimensions, String metricId) {
        return new DynatraceTimeSeries(metricId, System.currentTimeMillis(), 1.23,
                createDimensionsMap(numberOfDimensions));
    }

    private Map<String, String> createDimensionsMap(int numberOfDimensions) {
        Map<String, String> map = new HashMap<>();
        IntStream.range(0, numberOfDimensions).forEach(i -> map.put("key" + i, "value" + i));
        return map;
    }

    private boolean isValidJson(String json) {
        try {
            mapper.readTree(json);
            return true;
        }
        catch (Exception e) {
            return false;
        }
    }

}
