/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.processor.internals;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.streams.errors.StreamsException;
import org.easymock.EasyMock;
import org.junit.Test;

import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static org.apache.kafka.common.utils.Utils.mkSet;
import static org.apache.kafka.streams.processor.internals.ClientUtils.consumerRecordSizeInBytes;
import static org.apache.kafka.streams.processor.internals.ClientUtils.fetchCommittedOffsets;
import static org.apache.kafka.streams.processor.internals.ClientUtils.fetchEndOffsets;
import static org.apache.kafka.streams.processor.internals.ClientUtils.producerRecordSizeInBytes;

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ClientUtilsTest {

    // consumer and producer records use utf8 encoding for topic name, header keys, etc
    private static final String TOPIC = "topic";
    private static final int TOPIC_BYTES = 5;

    private static final byte[] KEY = "key".getBytes();
    private static final int KEY_BYTES = 3;

    private static final byte[] VALUE = "value".getBytes();
    private static final int VALUE_BYTES = 5;

    private static final Headers HEADERS = new RecordHeaders(asList(
        new RecordHeader("h1", "headerVal1".getBytes()),   // 2 + 10 --> 12 bytes
        new RecordHeader("h2", "headerVal2".getBytes())
    ));    // 2 + 10 --> 12 bytes
    private static final int HEADERS_BYTES = 24;

    private static final int RECORD_METADATA_BYTES =
        8 + // timestamp
        8 + // offset
        4;  // partition

    // 57 bytes
    private static final long SIZE_IN_BYTES =
        KEY_BYTES +
        VALUE_BYTES +
        TOPIC_BYTES +
        HEADERS_BYTES +
        RECORD_METADATA_BYTES;

    private static final long TOMBSTONE_SIZE_IN_BYTES =
        KEY_BYTES +
        TOPIC_BYTES +
        HEADERS_BYTES +
        RECORD_METADATA_BYTES;

    private static final Set<TopicPartition> PARTITIONS = mkSet(
        new TopicPartition(TOPIC, 1),
        new TopicPartition(TOPIC, 2)
    );

    @Test
    public void fetchCommittedOffsetsShouldRethrowKafkaExceptionAsStreamsException() {
        final Consumer<byte[], byte[]> consumer = EasyMock.createMock(Consumer.class);
        expect(consumer.committed(PARTITIONS)).andThrow(new KafkaException());
        replay(consumer);
        assertThrows(StreamsException.class, () -> fetchCommittedOffsets(PARTITIONS, consumer));
    }

    @Test
    public void fetchCommittedOffsetsShouldRethrowTimeoutException() {
        final Consumer<byte[], byte[]> consumer = EasyMock.createMock(Consumer.class);
        expect(consumer.committed(PARTITIONS)).andThrow(new TimeoutException());
        replay(consumer);
        assertThrows(TimeoutException.class, () -> fetchCommittedOffsets(PARTITIONS, consumer));
    }

    @Test
    public void fetchCommittedOffsetsShouldReturnEmptyMapIfPartitionsAreEmpty() {
        final Consumer<byte[], byte[]> consumer = EasyMock.createMock(Consumer.class);
        assertTrue(fetchCommittedOffsets(emptySet(), consumer).isEmpty());
    }

    @Test
    public void fetchEndOffsetsShouldReturnEmptyMapIfPartitionsAreEmpty() {
        final Admin adminClient = EasyMock.createMock(AdminClient.class);
        assertTrue(fetchEndOffsets(emptySet(), adminClient).isEmpty());
    }

    @Test
    public void fetchEndOffsetsShouldRethrowRuntimeExceptionAsStreamsException() throws Exception {
        final Admin adminClient = EasyMock.createMock(AdminClient.class);
        final ListOffsetsResult result = EasyMock.createNiceMock(ListOffsetsResult.class);
        final KafkaFuture<Map<TopicPartition, ListOffsetsResultInfo>> allFuture = EasyMock.createMock(KafkaFuture.class);

        EasyMock.expect(adminClient.listOffsets(EasyMock.anyObject())).andStubReturn(result);
        EasyMock.expect(result.all()).andStubReturn(allFuture);
        EasyMock.expect(allFuture.get()).andThrow(new RuntimeException());
        replay(adminClient, result, allFuture);

        assertThrows(StreamsException.class, () -> fetchEndOffsets(PARTITIONS, adminClient));
        verify(adminClient);
    }

    @Test
    public void fetchEndOffsetsShouldRethrowInterruptedExceptionAsStreamsException() throws Exception {
        final Admin adminClient = EasyMock.createMock(AdminClient.class);
        final ListOffsetsResult result = EasyMock.createNiceMock(ListOffsetsResult.class);
        final KafkaFuture<Map<TopicPartition, ListOffsetsResultInfo>> allFuture = EasyMock.createMock(KafkaFuture.class);

        EasyMock.expect(adminClient.listOffsets(EasyMock.anyObject())).andStubReturn(result);
        EasyMock.expect(result.all()).andStubReturn(allFuture);
        EasyMock.expect(allFuture.get()).andThrow(new InterruptedException());
        replay(adminClient, result, allFuture);

        assertThrows(StreamsException.class, () -> fetchEndOffsets(PARTITIONS, adminClient));
        verify(adminClient);
    }

    @Test
    public void fetchEndOffsetsShouldRethrowExecutionExceptionAsStreamsException() throws Exception {
        final Admin adminClient = EasyMock.createMock(AdminClient.class);
        final ListOffsetsResult result = EasyMock.createNiceMock(ListOffsetsResult.class);
        final KafkaFuture<Map<TopicPartition, ListOffsetsResultInfo>> allFuture = EasyMock.createMock(KafkaFuture.class);

        EasyMock.expect(adminClient.listOffsets(EasyMock.anyObject())).andStubReturn(result);
        EasyMock.expect(result.all()).andStubReturn(allFuture);
        EasyMock.expect(allFuture.get()).andThrow(new ExecutionException(new RuntimeException()));
        replay(adminClient, result, allFuture);

        assertThrows(StreamsException.class, () -> fetchEndOffsets(PARTITIONS, adminClient));
        verify(adminClient);
    }
    
    @Test
    public void shouldComputeSizeInBytesForConsumerRecord() {
        final ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
            TOPIC,
            1,
            0L,
            0L,
            TimestampType.CREATE_TIME,
            KEY_BYTES,
            VALUE_BYTES,
            KEY,
            VALUE,
            HEADERS,
            Optional.empty()
        );

        assertThat(consumerRecordSizeInBytes(record), equalTo(SIZE_IN_BYTES));
    }

    @Test
    public void shouldComputeSizeInBytesForProducerRecord() {
        final ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(
            TOPIC,
            1,
            0L,
            KEY,
            VALUE,
            HEADERS
        );
        assertThat(producerRecordSizeInBytes(record), equalTo(SIZE_IN_BYTES));
    }

    @Test
    public void shouldComputeSizeInBytesForConsumerRecordWithNullValue() {
        final ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
            TOPIC,
            1,
            0,
            0L,
            TimestampType.CREATE_TIME,
            KEY_BYTES,
            0,
            KEY,
            null,
            HEADERS,
            Optional.empty()
        );
        assertThat(consumerRecordSizeInBytes(record), equalTo(TOMBSTONE_SIZE_IN_BYTES));
    }

    @Test
    public void shouldComputeSizeInBytesForProducerRecordWithNullValue() {
        final ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(
            TOPIC,
            1,
            0L,
            KEY,
            null,
            HEADERS
        );
        assertThat(producerRecordSizeInBytes(record), equalTo(TOMBSTONE_SIZE_IN_BYTES));
    }
}
