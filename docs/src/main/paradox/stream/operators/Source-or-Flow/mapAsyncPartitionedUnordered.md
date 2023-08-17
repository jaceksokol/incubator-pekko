# mapAsyncPartitionedUnordered

Pass incoming elements to a partitioning function that returns a partition result for each element and then to
a processing function that returns a @scala[`Future`] @java[`CompletionStage`] result.

The resulting Source or Flow will not have ordered elements.

@ref[Asynchronous operators](../index.md#asynchronous-operators)

## Signature

@apidoc[Source.mapAsyncPartitionedUnordered](Source) { scala="#mapAsyncPartitioned[T,P](parallelism:Int,bufferSize:Int)(partitioner:Out=%3EP)(f:(Out,P)=%3Escala.concurrent.Future[T]):FlowOps.this.Repr[T]" java="#mapAsyncPartitioned(int,int,org.apache.pekko.japi.function.Function,org.apache.pekko.japi.function.Function2" }
@apidoc[Flow.mapAsyncPartitionedUnordered](Source) { scala="#mapAsyncPartitioned[T,P](parallelism:Int,bufferSize:Int)(partitioner:Out=%3EP)(f:(Out,P)=%3Escala.concurrent.Future[T]):FlowOps.this.Repr[T]" java="#mapAsyncPartitioned(int,int,org.apache.pekko.japi.function.Function,org.apache.pekko.japi.function.Function2" }

## Description

Like `mapAsync` but an intermediate partitioning stage is used.
