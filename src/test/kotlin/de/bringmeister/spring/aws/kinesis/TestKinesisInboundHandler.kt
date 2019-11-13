package de.bringmeister.spring.aws.kinesis;

internal class TestKinesisInboundHandler : KinesisInboundHandler<Any, Any> {
    override val stream = "test"
    override fun handleRecord(record: Record<Any, Any>, context: KinesisInboundHandler.ExecutionContext) {}

    override fun dataType() = Any::class.java
    override fun metaType() = Any::class.java

    class TestExecutionContext : KinesisInboundHandler.ExecutionContext {
        override val sequenceNumber: String = "any"
    }
}
