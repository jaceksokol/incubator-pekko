# mapAsyncPartitionedUnordered

Pass incoming elements to a partitioning function that returns a partition result for each element and then to
a processing function that returns a @scala[`Future`] @java[`CompletionStage`] result.

The resulting Source or Flow will not have ordered elements.

@ref[Asynchronous operators](../index.md#asynchronous-operators)

## Signature

@apidoc[Source.mapAsyncPartitionedUnordered](Source) { scala="#mapAsyncPartitioned[T,P](parallelism:Int)(partitioner:Out=%3EP)(f:(Out,P)=%3Escala.concurrent.Future[T]):FlowOps.this.Repr[T]" java="#mapAsyncPartitioned(int,org.apache.pekko.japi.function.Function,org.apache.pekko.japi.function.Function2" }
@apidoc[Flow.mapAsyncPartitionedUnordered](Source) { scala="#mapAsyncPartitioned[T,P](parallelism:Int)(partitioner:Out=%3EP)(f:(Out,P)=%3Escala.concurrent.Future[T]):FlowOps.this.Repr[T]" java="#mapAsyncPartitioned(int,org.apache.pekko.japi.function.Function,org.apache.pekko.japi.function.Function2" }

## Description

Like `mapAsync` but an intermediate partitioning stage is used.
Up to `parallelism` elements can be processed concurrently for a partition and pushed down the stream regardless of the
order of the partitions that triggered them. In other words, the order of the output elements will be preserved only within a partition.
For use cases where order does matter `mapAsyncPartitioned` can be used.