/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.policy;

import static com.google.common.collect.ImmutableMap.of;
import static java.util.Collections.singletonMap;
import static java.util.Optional.empty;
import static org.mule.runtime.core.internal.event.EventQuickCopy.quickCopy;
import static org.mule.runtime.core.privileged.processor.MessageProcessors.processToApply;
import static org.mule.runtime.core.privileged.processor.MessageProcessors.processWithChildContext;
import static reactor.core.publisher.Flux.from;
import static reactor.core.publisher.Mono.error;

import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.policy.OperationPolicyParametersTransformer;
import org.mule.runtime.core.api.policy.Policy;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.api.processor.ReactiveProcessor;
import org.mule.runtime.core.internal.exception.MessagingException;
import org.mule.runtime.core.internal.message.InternalEvent;

import org.reactivestreams.Publisher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/**
 * {@link OperationPolicy} created from a list of {@link Policy}.
 * <p>
 * Implements the template methods from {@link AbstractCompositePolicy} required to work with operation policies.
 *
 * @since 4.0
 */
public class CompositeOperationPolicy
    extends AbstractCompositePolicy<OperationPolicyParametersTransformer, OperationExecutionFunction> implements OperationPolicy {

  private static final String POLICY_OPERATION_NEXT_OPERATION_RESPONSE = "policy.operation.nextOperationResponse";
  public static final String POLICY_OPERATION_PARAMETERS_PROCESSOR = "policy.operation.parametersProcessor";
  public static final String POLICY_OPERATION_OPERATION_EXEC_FUNCTION = "policy.operation.operationExecutionFunction";

  public static final String POLICY_OPERATION_CALLER_SINK = "policy.operation.callerSink";

  private final FluxSink<CoreEvent> policySink;
  private final OperationPolicyProcessorFactory operationPolicyProcessorFactory;

  /**
   * Creates a new composite policy.
   * <p>
   * If a non-empty {@code operationPolicyParametersTransformer} is passed to this class, then it will be used to convert the flow
   * execution response parameters to a message with the content of such parameters in order to allow the pipeline after the
   * next-operation to modify the response. If an empty {@code operationPolicyParametersTransformer} is provided then the policy
   * won't be able to change the response parameters of the source and the original response parameters generated from the source
   * will be used.
   *
   * @param parameterizedPolicies list of {@link Policy} to chain together.
   * @param operationPolicyParametersTransformer transformer from the operation parameters to a message and vice versa.
   * @param operationPolicyProcessorFactory factory for creating each {@link OperationPolicy} from a {@link Policy}
   */
  public CompositeOperationPolicy(List<Policy> parameterizedPolicies,
                                  Optional<OperationPolicyParametersTransformer> operationPolicyParametersTransformer,
                                  OperationPolicyProcessorFactory operationPolicyProcessorFactory) {
    super(parameterizedPolicies, operationPolicyParametersTransformer);
    this.operationPolicyProcessorFactory = operationPolicyProcessorFactory;

    AtomicReference<FluxSink<CoreEvent>> sinkRef = new AtomicReference<>();

    Flux<CoreEvent> policyFlux = Flux.<CoreEvent>create(sink -> sinkRef.set(sink));

    if (operationPolicyParametersTransformer.isPresent()) {
      policyFlux = policyFlux
          .map(operationEventForPolicy -> {
            OperationParametersProcessor parametersProcessor =
                ((InternalEvent) operationEventForPolicy).getInternalParameter(POLICY_OPERATION_PARAMETERS_PROCESSOR);
            return CoreEvent.builder(operationEventForPolicy)
                .message(getParametersTransformer().get().fromParametersToMessage(parametersProcessor.getOperationParameters()))
                .build();
          });
    }

    policyFlux = policyFlux
        // .flatMap(event -> processWithChildContext(event, getExecutionProcessor()))
        .transform(getExecutionProcessor())
        .doOnNext(result -> {
          final MonoSink<CoreEvent> callerSink = ((InternalEvent) result).getInternalParameter(POLICY_OPERATION_CALLER_SINK);
          callerSink.success(result);
        })
        .doOnError(MessagingException.class, me -> {
          final MonoSink<CoreEvent> callerSink =
              ((InternalEvent) me.getEvent()).getInternalParameter(POLICY_OPERATION_CALLER_SINK);
          callerSink.error(me);
        })// .flatMap(event -> processWithChildContext(event, getExecutionProcessor()))
    ;

    policyFlux.subscribe(null, e -> e.printStackTrace(), () -> {
      new Exception().printStackTrace();
    });

    policySink = sinkRef.get();
  }

  @FunctionalInterface
  private interface OperationPolicyNextProcessor extends Processor {

    @Override
    default CoreEvent process(CoreEvent event) throws MuleException {
      return processToApply(event, this);
    }

    @Override
    Publisher<CoreEvent> apply(Publisher<CoreEvent> publisher);

  }

  /**
   * Stores the operation result so all the chains after the operation execution are executed with the actual operation result and
   * not a modified version from another policy.
   *
   * @param eventPub the event to execute the operation.
   */
  @Override
  protected Publisher<CoreEvent> applyNextOperation(Publisher<CoreEvent> eventPub) {
    return from(eventPub)
        .flatMap(event -> {
          Map<String, Object> parametersMap = new HashMap<>();
          try {
            OperationParametersProcessor parametersProcessor =
                ((InternalEvent) event).getInternalParameter(POLICY_OPERATION_PARAMETERS_PROCESSOR);
            parametersMap.putAll(parametersProcessor.getOperationParameters());
          } catch (Exception e) {
            return error(e);
          }
          if (getParametersTransformer().isPresent()) {
            parametersMap.putAll(getParametersTransformer().get().fromMessageToParameters(event.getMessage()));
          }

          OperationExecutionFunction operationExecutionFunction =
              ((InternalEvent) event).getInternalParameter(POLICY_OPERATION_OPERATION_EXEC_FUNCTION);
          return Mono.from(operationExecutionFunction.execute(parametersMap, event));
        })
        .map(response -> quickCopy(response, singletonMap(POLICY_OPERATION_NEXT_OPERATION_RESPONSE, response)));
  }

  /**
   * Always uses the stored result of {@code processNextOperation} so all the chains after the operation execution are executed
   * with the actual operation result and not a modified version from another policy.
   *
   * @param policy the policy to execute.
   * @param nextProcessor the processor to execute when the policy next-processor gets executed
   * @param eventPub the event to use to execute the policy chain.
   */
  @Override
  protected Publisher<CoreEvent> applyPolicy(Policy policy, ReactiveProcessor nextProcessor, Publisher<CoreEvent> eventPub) {
    Processor defaultOperationPolicy = operationPolicyProcessorFactory.createOperationPolicy(policy, nextProcessor);
    return from(eventPub)
        .transform(defaultOperationPolicy)
        .map(policyResponse -> {

          if (policy.getPolicyChain().isPropagateMessageTransformations()) {
            return quickCopy(policyResponse, singletonMap(POLICY_OPERATION_NEXT_OPERATION_RESPONSE, policyResponse));
          }

          final InternalEvent nextOperationResponse =
              ((InternalEvent) policyResponse).getInternalParameter(POLICY_OPERATION_NEXT_OPERATION_RESPONSE);
          return nextOperationResponse != null ? nextOperationResponse : policyResponse;
        })
        .cast(CoreEvent.class);
  }

  @Override
  public Publisher<CoreEvent> process(CoreEvent operationEvent, OperationExecutionFunction operationExecutionFunction,
                                      OperationParametersProcessor parametersProcessor) {
    return Mono.create(callerSink -> {
      final InternalEvent event = quickCopy(operationEvent, of(POLICY_OPERATION_PARAMETERS_PROCESSOR, parametersProcessor,
                                                               POLICY_OPERATION_OPERATION_EXEC_FUNCTION,
                                                               operationExecutionFunction,
                                                               POLICY_OPERATION_CALLER_SINK, callerSink));

      Mono.from(processWithChildContext(event, eventPub -> Mono.from(eventPub).doOnNext(e -> {
        policySink.next(e);
      }), empty()))
          // The response on the callerSink is created with the child context, so we have to go back to the parent.
          .map(result -> quickCopy(event.getContext(), result))
          .doOnError(MessagingException.class,
                     me -> me.setProcessedEvent(quickCopy(event.getContext(), me.getEvent())))
          .subscribe()

      // .doOnNext(result -> {
      // // final MonoSink<CoreEvent> callerSink = ((InternalEvent) result).getInternalParameter(POLICY_OPERATION_CALLER_SINK);
      // callerSink.success(result);
      // })
      // .doOnError(MessagingException.class, me -> {
      // // final MonoSink<CoreEvent> callerSink =
      // // ((InternalEvent) me.getEvent()).getInternalParameter(POLICY_OPERATION_CALLER_SINK);
      // callerSink.error(me);
      // })// .flatMap(event -> processWithChildContext(event, getExecutionProcessor()))
      ;
      // policySink.next(event);
    });
  }

}
