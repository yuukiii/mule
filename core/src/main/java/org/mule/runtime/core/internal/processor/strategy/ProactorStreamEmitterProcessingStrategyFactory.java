/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.processor.strategy;

import static java.lang.Integer.MAX_VALUE;
import static java.lang.Math.max;
import static java.lang.Runtime.getRuntime;
import static java.lang.System.currentTimeMillis;
import static java.lang.Thread.currentThread;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.disposeIfNeeded;
import static org.mule.runtime.core.api.processor.ReactiveProcessor.ProcessingType.BLOCKING;
import static org.mule.runtime.core.api.processor.ReactiveProcessor.ProcessingType.CPU_INTENSIVE;
import static org.mule.runtime.core.internal.context.thread.notification.ThreadNotificationLogger.THREAD_NOTIFICATION_LOGGER_CONTEXT_KEY;
import static org.slf4j.LoggerFactory.getLogger;
import static reactor.core.publisher.Flux.from;
import static reactor.core.publisher.Mono.subscriberContext;
import static reactor.core.scheduler.Schedulers.fromExecutorService;

import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.lifecycle.Disposable;
import org.mule.runtime.api.scheduler.Scheduler;
import org.mule.runtime.api.scheduler.SchedulerService;
import org.mule.runtime.api.util.concurrent.Latch;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.construct.FlowConstruct;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.processor.ReactiveProcessor;
import org.mule.runtime.core.api.processor.ReactiveProcessor.ProcessingType;
import org.mule.runtime.core.api.processor.Sink;
import org.mule.runtime.core.api.processor.strategy.ProcessingStrategy;
import org.mule.runtime.core.internal.context.thread.notification.ThreadLoggingExecutorServiceDecorator;
import org.mule.runtime.core.internal.processor.strategy.AbstractProcessingStrategy.ReactorSink;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;

import java.util.function.Supplier;

import cn.danielw.fop.ObjectFactory;
import cn.danielw.fop.ObjectPool;
import cn.danielw.fop.PoolConfig;
import cn.danielw.fop.Poolable;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Creates {@link ReactorProcessingStrategyFactory.ReactorProcessingStrategy} instance that implements the proactor pattern by
 * de-multiplexing incoming events onto a multiple emitter using the {@link SchedulerService#cpuLightScheduler()} to process these
 * events from each emitter. In contrast to the {@link ReactorStreamProcessingStrategy} the proactor pattern treats
 * {@link ProcessingType#CPU_INTENSIVE} and {@link ProcessingType#BLOCKING} processors differently and schedules there execution
 * on dedicated {@link SchedulerService#cpuIntensiveScheduler()} and {@link SchedulerService#ioScheduler()} ()} schedulers.
 * <p/>
 * This processing strategy is not suitable for transactional flows and will fail if used with an active transaction.
 *
 * @since 4.2.0
 */
public class ProactorStreamEmitterProcessingStrategyFactory extends ReactorStreamProcessingStrategyFactory {

  @Override
  public ProcessingStrategy create(MuleContext muleContext, String schedulersNamePrefix) {
    return new ProactorStreamEmitterProcessingStrategy(getRingBufferSchedulerSupplier(muleContext, schedulersNamePrefix),
                                                       getBufferSize(),
                                                       getSubscriberCount(),
                                                       getWaitStrategy(),
                                                       getCpuLightSchedulerSupplier(muleContext, schedulersNamePrefix),
                                                       () -> muleContext.getSchedulerService()
                                                           .ioScheduler(muleContext.getSchedulerBaseConfig()
                                                               .withName(schedulersNamePrefix + "." + BLOCKING.name())),
                                                       () -> muleContext.getSchedulerService()
                                                           .cpuIntensiveScheduler(muleContext.getSchedulerBaseConfig()
                                                               .withName(schedulersNamePrefix + "." + CPU_INTENSIVE.name())),
                                                       resolveParallelism(),
                                                       getMaxConcurrency(),
                                                       isMaxConcurrencyEagerCheck(),
                                                       muleContext.getConfiguration().isThreadLoggingEnabled());
  }

  @Override
  protected int resolveParallelism() {
    return Integer.max(CORES, getMaxConcurrency());
  }

  @Override
  public Class<? extends ProcessingStrategy> getProcessingStrategyType() {
    return ProactorStreamEmitterProcessingStrategy.class;
  }

  static class ProactorStreamEmitterProcessingStrategy extends ProactorStreamProcessingStrategy {

    private static Logger LOGGER = getLogger(ProactorStreamEmitterProcessingStrategy.class);

    private final boolean isThreadLoggingEnabled;

    public ProactorStreamEmitterProcessingStrategy(Supplier<Scheduler> ringBufferSchedulerSupplier,
                                                   int bufferSize,
                                                   int subscriberCount,
                                                   String waitStrategy,
                                                   Supplier<Scheduler> cpuLightSchedulerSupplier,
                                                   Supplier<Scheduler> blockingSchedulerSupplier,
                                                   Supplier<Scheduler> cpuIntensiveSchedulerSupplier,
                                                   int parallelism,
                                                   int maxConcurrency, boolean maxConcurrencyEagerCheck,
                                                   boolean isThreadLoggingEnabled)

    {
      super(ringBufferSchedulerSupplier, bufferSize, subscriberCount, waitStrategy, cpuLightSchedulerSupplier,
            blockingSchedulerSupplier, cpuIntensiveSchedulerSupplier, parallelism, maxConcurrency,
            maxConcurrencyEagerCheck);
      this.isThreadLoggingEnabled = isThreadLoggingEnabled;
    }

    public ProactorStreamEmitterProcessingStrategy(Supplier<Scheduler> ringBufferSchedulerSupplier,
                                                   int bufferSize,
                                                   int subscriberCount,
                                                   String waitStrategy,
                                                   Supplier<Scheduler> cpuLightSchedulerSupplier,
                                                   Supplier<Scheduler> blockingSchedulerSupplier,
                                                   Supplier<Scheduler> cpuIntensiveSchedulerSupplier,
                                                   int parallelism,
                                                   int maxConcurrency, boolean maxConcurrencyEagerCheck)

    {
      this(ringBufferSchedulerSupplier, bufferSize, subscriberCount, waitStrategy, cpuLightSchedulerSupplier,
           blockingSchedulerSupplier, cpuIntensiveSchedulerSupplier, parallelism, maxConcurrency,
           maxConcurrencyEagerCheck, false);
    }

    @Override
    public Sink createSink(FlowConstruct flowConstruct, ReactiveProcessor function) {
      int concurrency = maxConcurrency < getRuntime().availableProcessors() ? maxConcurrency : getRuntime().availableProcessors();

      PoolConfig config = new PoolConfig()
          .setPartitionSize(concurrency)
          .setMaxSize(1)
          .setMinSize(1)
          .setMaxIdleMilliseconds(MAX_VALUE)
          .setScavengeIntervalMilliseconds(0);

      final long shutdownTimeout = flowConstruct.getMuleContext().getConfiguration().getShutdownTimeout();
      ObjectPool<ReactorSink<CoreEvent>> policySinkPool = new ObjectPool<>(config, new ObjectFactory<ReactorSink<CoreEvent>>() {

        @Override
        public ReactorSink<CoreEvent> create() {
          Latch completionLatch = new Latch();
          EmitterProcessor<CoreEvent> processor = EmitterProcessor.create(bufferSize / concurrency);
          processor
              .transform(function)
              .subscribe(null, e -> completionLatch.release(), () -> completionLatch.release());

          return new ProactorSinkWrapper<>(new DefaultReactorSink<>(processor.sink(), () -> {
            long start = currentTimeMillis();
            try {
              if (!completionLatch.await(max(start - currentTimeMillis() + shutdownTimeout, 0l), MILLISECONDS)) {
                LOGGER.warn("Subscribers of ProcessingStrategy for flow '{}' not completed in {} ms", flowConstruct.getName(),
                            shutdownTimeout);
              }
            } catch (InterruptedException e) {
              currentThread().interrupt();
              throw new MuleRuntimeException(e);
            }
          }, createOnEventConsumer(), bufferSize));
        }

        @Override
        public void destroy(ReactorSink<CoreEvent> t) {
          disposeIfNeeded(t, LOGGER);
        }

        @Override
        public boolean validate(ReactorSink<CoreEvent> t) {
          return !t.isCancelled();
        }
      });

      return new PooledReactorSink(policySinkPool);
    }

    @Override
    public ReactiveProcessor onPipeline(ReactiveProcessor pipeline) {
      reactor.core.scheduler.Scheduler scheduler = fromExecutorService(decorateScheduler(getCpuLightScheduler()));
      return publisher -> from(publisher).publishOn(scheduler).transform(pipeline);
    }

    @Override
    protected Flux<CoreEvent> scheduleProcessor(ReactiveProcessor processor, Scheduler processorScheduler,
                                                Publisher<CoreEvent> eventPub) {
      if (isThreadLoggingEnabled) {
        return from(eventPub)
            .flatMap(e -> subscriberContext()
                .flatMap(ctx -> Mono.just(e).transform(processor)
                    .subscribeOn(fromExecutorService(new ThreadLoggingExecutorServiceDecorator(ctx
                        .getOrEmpty(THREAD_NOTIFICATION_LOGGER_CONTEXT_KEY), decorateScheduler(processorScheduler),
                                                                                               e.getContext().getId())))));
      } else {
        return from(eventPub)
            .publishOn(fromExecutorService(decorateScheduler(processorScheduler)))
            .transform(processor)
            .subscribeOn(fromExecutorService(decorateScheduler(processorScheduler)));
      }
    }

  }

  static class PooledReactorSink implements Sink, Disposable {

    private final ObjectPool<ReactorSink<CoreEvent>> policySinkPool;

    public PooledReactorSink(ObjectPool<ReactorSink<CoreEvent>> policySinkPool) {
      this.policySinkPool = policySinkPool;
    }

    @Override
    public void accept(CoreEvent event) {
      try (Poolable<ReactorSink<CoreEvent>> borrowedSink = policySinkPool.borrowObject()) {
        borrowedSink.getObject().accept(event);
      }
    }

    @Override
    public boolean emit(CoreEvent event) {
      try (Poolable<ReactorSink<CoreEvent>> borrowedSink = policySinkPool.borrowObject()) {
        return borrowedSink.getObject().emit(event);
      }
    }

    @Override
    public void dispose() {
      try {
        policySinkPool.shutdown();
      } catch (InterruptedException e) {
        currentThread().interrupt();
      }
    }
  }

}
