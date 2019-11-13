package de.bringmeister.spring.aws.kinesis

import com.amazonaws.services.kinesis.clientlibrary.exceptions.KinesisClientLibNonRetryableException
import com.amazonaws.services.kinesis.clientlibrary.exceptions.KinesisClientLibRetryableException
import com.amazonaws.services.kinesis.clientlibrary.exceptions.ThrottlingException
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.ShutdownReason
import com.amazonaws.services.kinesis.clientlibrary.types.ProcessRecordsInput
import com.amazonaws.services.kinesis.clientlibrary.types.ShutdownInput
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.argumentCaptor
import com.nhaarman.mockito_kotlin.doAnswer
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.doThrow
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.times
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.verifyNoMoreInteractions
import com.nhaarman.mockito_kotlin.verifyZeroInteractions
import com.nhaarman.mockito_kotlin.whenever
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.Test
import org.springframework.context.ApplicationEventPublisher
import java.nio.ByteBuffer
import java.time.Duration
import java.util.UUID
import com.amazonaws.services.kinesis.model.Record as KinesisRecord

class AwsKinesisRecordProcessorTest {

    data class ExampleEvent(val data: ExampleData, val metadata: ExampleMetadata)
    data class ExampleData(val value: String)
    data class ExampleMetadata(val hash: String)

    class ExampleException : RuntimeException()

    val checkpointMaxRetries = 2
    val checkpointingBackoff = Duration.ofMillis(1)
    val exampleConfig =
        RecordProcessorConfiguration(checkpointing = CheckpointingConfiguration(maxRetries = checkpointMaxRetries, backoff = checkpointingBackoff))

    val mockHandler = mock<KinesisInboundHandler<ExampleData, ExampleMetadata>> {
        on { dataType() } doReturn ExampleData::class.java
        on { metaType() } doReturn ExampleMetadata::class.java
        on { stream } doReturn "any-stream"
    }

    val mockCheckpointer = mock<IRecordProcessorCheckpointer> {}
    val mockEventPublisher = mock<ApplicationEventPublisher> {}
    val objectMapper = ObjectMapper().registerModule(KotlinModule())
    val recordDeserializer = ObjectMapperRecordDeserializerFactory(objectMapper).deserializerFor(mockHandler)

    fun recordProcessor(configuration: RecordProcessorConfiguration = exampleConfig): AwsKinesisRecordProcessor<ExampleData, ExampleMetadata> {
        return AwsKinesisRecordProcessor(recordDeserializer, configuration, mockHandler, mockEventPublisher)
    }

    val events = listOf(
        ExampleEvent(ExampleData("first"), ExampleMetadata("8b04d5e3775d298e78455efc5ca404d5")),
        ExampleEvent(ExampleData("second"), ExampleMetadata("a9f0e61a137d86aa9db53465e0801612"))
    )
    val eventsAsJson = events.map(this::json)

    /* ---- Happy Path ---- */
    @Test
    fun `should invoke event handler for every record of a batch`() {
        val records = recordBatch(eventsAsJson)

        recordProcessor().processRecords(records)

        verifyAllEventsAreProcessed()
        verify(mockCheckpointer).checkpoint()
    }

    /* ---- Initialization ---- */
    @Test
    fun `should publish event on initialization`() {
        recordProcessor().initialize(mock { on { shardId } doReturn "any" })

        val captor = argumentCaptor<WorkerInitializedEvent>()
        verify(mockEventPublisher).publishEvent(captor.capture())
        assertThat(captor.firstValue).isInstanceOf(WorkerInitializedEvent::class.java)
    }

    /* ---- Checkpointing - complete batch ---- */
    @Test
    fun `should checkpoint batch after all records of that batch are processed`() {
        val records = recordBatch(eventsAsJson)
        val config = RecordProcessorConfiguration(checkpointing = CheckpointingConfiguration(checkpointMaxRetries, checkpointingBackoff, CheckpointingStrategy.BATCH))

        recordProcessor(config).processRecords(records)

        verify(mockCheckpointer).checkpoint()
        verifyNoMoreInteractions(mockCheckpointer)
    }

    @Test
    fun `should not checkpoint batch if a record can't be processed`() {
        val records = recordBatch(eventsAsJson)

        whenever(mockHandler.handleRecord(any(), any()))
            .then { } // process 1st record successful
            .doThrow(ExampleException::class) // fail on 2nd record
            .then { } // process 3rd record successful

        Assertions.catchThrowable {
            val config = RecordProcessorConfiguration(
                checkpointing = CheckpointingConfiguration(checkpointMaxRetries, checkpointingBackoff, CheckpointingStrategy.BATCH)
            )
            recordProcessor(config).processRecords(records)
        }
        verifyZeroInteractions(mockCheckpointer)
    }

    /* ---- Checkpointing - single records ---- */
    @Test
    fun `should checkpoint after every record when checkpointing strategy is set to 'RECORD'`() {
        val records = recordBatch(eventsAsJson)
        val config = RecordProcessorConfiguration(checkpointing = CheckpointingConfiguration(checkpointMaxRetries, checkpointingBackoff, CheckpointingStrategy.RECORD))

        recordProcessor(config).processRecords(records)

        for (record in records.records) {
            verify(mockCheckpointer).checkpoint(record)
        }
        verifyNoMoreInteractions(mockCheckpointer)
    }

    @Test
    fun `should checkpoint only successful processed records when checkpointing strategy is set to 'RECORD'`() {
        val records = recordBatch(eventsAsJson)

        whenever(mockHandler.handleRecord(any(), any()))
            .then { } // process 1st record successful
            .doThrow(ExampleException::class) // fail on 2nd record
            .then { } // process 3rd record successful

        Assertions.catchThrowable {
            val config = RecordProcessorConfiguration(
                checkpointing = CheckpointingConfiguration(checkpointMaxRetries, checkpointingBackoff, CheckpointingStrategy.RECORD)
            )
            recordProcessor(config).processRecords(records)
        }
        verify(mockCheckpointer).checkpoint(records.records[0]) // checkpoint only successful processed record
        verifyNoMoreInteractions(mockCheckpointer)
    }

    @Test
    fun `should use 'BATCH' checkpointing strategy as default`() {
        val config = RecordProcessorConfiguration(CheckpointingConfiguration(checkpointMaxRetries, checkpointingBackoff))

        assertThat(config.checkpointing.strategy).isEqualTo(CheckpointingStrategy.BATCH)
    }

    /* ---- Deserialization failures ---- */
    @Test
    fun `should skip invalid records when deserialization fails and process remaining records`() {
        val records = recordBatch(eventsAsJson.toMutableList().apply { add(1, "{foobar}") }) // insert one invalid record between two valid ones

        recordProcessor().processRecords(records)

        verifyAllEventsAreProcessed()
    }

    @Test
    fun `should invoke deserializationErrorHandler when deserialization fails`() {
        val records = recordBatch(listOf("{foobar}"))

        recordProcessor().processRecords(records)

        verify(mockHandler).handleDeserializationError(any(), any(), any())
    }

    class AnyKinesisRetryableException : KinesisClientLibRetryableException("any")

    @Test
    fun `should retry batch checkpointing on KinesisClientLibRetryableException`() {
        val records = recordBatch(eventsAsJson)

        whenever(mockCheckpointer.checkpoint())
            .doThrow(AnyKinesisRetryableException::class)
            .then { } // stop throwing

        val config = RecordProcessorConfiguration(checkpointing = CheckpointingConfiguration(checkpointMaxRetries, checkpointingBackoff, CheckpointingStrategy.BATCH))
        recordProcessor(config).processRecords(records)

        verify(mockCheckpointer, times(2)).checkpoint()
    }

    @Test
    fun `should retry record checkpointing on KinesisClientLibRetryableException`() {
        val records = recordBatch(eventsAsJson)

        whenever(mockCheckpointer.checkpoint(any<com.amazonaws.services.kinesis.model.Record>()))
            .doThrow(AnyKinesisRetryableException::class)
            .then { } // stop throwing

        val config = RecordProcessorConfiguration(checkpointing = CheckpointingConfiguration(checkpointMaxRetries, checkpointingBackoff, CheckpointingStrategy.RECORD))
        recordProcessor(config).processRecords(records)

        verify(mockCheckpointer, times(2)).checkpoint(records.records[0]) // retry
        verify(mockCheckpointer).checkpoint(records.records[1])
    }

    class AnyKinesisNonRetryableException : KinesisClientLibNonRetryableException("any")

    @Test
    fun `should not retry batch checkpointing on KinesisClientLibNonRetryableException`() {
        val records = recordBatch(eventsAsJson)

        whenever(mockCheckpointer.checkpoint())
            .doAnswer { throw AnyKinesisNonRetryableException() }
            .then { } // stop throwing

        val config = RecordProcessorConfiguration(checkpointing = CheckpointingConfiguration(checkpointMaxRetries, checkpointingBackoff, CheckpointingStrategy.BATCH))
        recordProcessor(config).processRecords(records)

        verify(mockCheckpointer).checkpoint() // verify checkpoint is called only once
    }

    @Test
    fun `should not retry record checkpointing on KinesisClientLibNonRetryableException`() {
        val records = recordBatch(eventsAsJson)

        whenever(mockCheckpointer.checkpoint(any<com.amazonaws.services.kinesis.model.Record>()))
            .doAnswer { throw AnyKinesisNonRetryableException() }
            .then { } // stop throwing

        val config = RecordProcessorConfiguration(checkpointing = CheckpointingConfiguration(checkpointMaxRetries, checkpointingBackoff, CheckpointingStrategy.RECORD))
        recordProcessor(config).processRecords(records)

        verify(mockCheckpointer).checkpoint(records.records[0])
        verify(mockCheckpointer).checkpoint(records.records[1])
    }

    @Test
    fun `should not bubble exception when checkpointing on ThrottlingException runs out of retries`() {
        val records = recordBatch(eventsAsJson)
        whenever(mockCheckpointer.checkpoint()).doThrow(ThrottlingException::class)

        assertThatCode { recordProcessor().processRecords(records) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `should checkpoint on resharding`() {
        val shutdownInput = mock<ShutdownInput> {
            on { shutdownReason } doReturn ShutdownReason.TERMINATE // re-sharding
            on { checkpointer } doReturn mockCheckpointer
        }

        recordProcessor().shutdown(shutdownInput)

        verify(mockCheckpointer).checkpoint()
    }

    private fun verifyAllEventsAreProcessed() {
        for (i in 0 until events.size) {
            val recordCaptor = argumentCaptor<Record<ExampleData, ExampleMetadata>>()
            val contextCaptor = argumentCaptor<KinesisInboundHandler.ExecutionContext>()
            verify(mockHandler, times(events.size)).handleRecord(recordCaptor.capture(), contextCaptor.capture())

            assertThat(recordCaptor.allValues[i].data).isEqualTo(events[i].data)
            assertThat(recordCaptor.allValues[i].metadata).isEqualTo(events[i].metadata)
        }
    }

    private fun json(event: ExampleEvent) = objectMapper.writeValueAsString(event)

    fun recordBatch(recordData: List<String>): ProcessRecordsInput {
        val records = recordData.map { record -> KinesisRecord().withSequenceNumber(UUID.randomUUID().toString()).withData(ByteBuffer.wrap(record.toByteArray())) }
        return ProcessRecordsInput()
            .withRecords(records)
            .withCheckpointer(mockCheckpointer)
    }
}
