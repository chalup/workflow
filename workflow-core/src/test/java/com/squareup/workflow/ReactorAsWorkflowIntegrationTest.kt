/*
 * Copyright 2017 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.workflow

import com.squareup.workflow.TestState.FirstState
import com.squareup.workflow.TestState.SecondState
import kotlinx.coroutines.experimental.CancellationException
import kotlinx.coroutines.experimental.CompletableDeferred
import kotlinx.coroutines.experimental.CoroutineExceptionHandler
import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.Dispatchers.Unconfined
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.suspendCancellableCoroutine
import org.junit.Test
import kotlin.coroutines.experimental.EmptyCoroutineContext
import kotlin.coroutines.experimental.suspendCoroutine
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ReactorAsWorkflowIntegrationTest {

  private open class MockReactor : Reactor<TestState, Nothing, String> {
    override suspend fun onReact(
      state: TestState,
      events: ReceiveChannel<Nothing>,
      workflows: WorkflowPool
    ): Reaction<TestState, String> = suspendCancellableCoroutine {
      // Suspend forever.
    }
  }

  private lateinit var stateSub: ReceiveChannel<TestState>
  private lateinit var resultSub: Deferred<String>

  private var reactor: Reactor<TestState, Nothing, String> = MockReactor()
  private lateinit var workflow: Workflow<TestState, Nothing, String>
  private val uncaughtExceptions = mutableListOf<Throwable>()
  private val workflowContext = CoroutineExceptionHandler { _, e -> uncaughtExceptions += e }

  private fun start(input: String) {
    workflow = reactor.startRootWorkflow(FirstState(input), workflowContext)
        .apply {
          subscribeToState(this)
          subscribeToResult(this)
        }
  }

  private fun subscribeToState(workflow: Workflow<TestState, *, *>) {
    stateSub = workflow.openSubscriptionToState()
  }

  private fun subscribeToResult(workflow: Workflow<*, *, String>) {
    resultSub = GlobalScope.async(Unconfined) { workflow.await() }
  }

  @Test fun startNew_initialState_afterStartNew() {
    start("hello")

    assertEquals(FirstState("hello"), stateSub.poll())
    assertTrue(stateSub.isEmpty)
    assertFalse(stateSub.isClosedForReceive)
    assertFalse(resultSub.isCompleted)
  }

  @Test fun startFromState() {
    val workflow = reactor.startRootWorkflow(SecondState("hello"))

    subscribeToState(workflow)
    subscribeToResult(workflow)

    assertEquals(SecondState("hello"), stateSub.poll())
    assertTrue(stateSub.isEmpty)
    assertFalse(stateSub.isClosedForReceive)
    assertFalse(resultSub.isCompleted)
  }

  @Test fun stateCompletesWhenAbandoned() {
    val workflow = reactor.startRootWorkflow(SecondState("hello"))

    subscribeToState(workflow)
    workflow.cancel()

    assertFailsWith<CancellationException> { stateSub.poll() }
    assertTrue(stateSub.isClosedForReceive)
  }

  @Test fun stateStaysCompletedForLateSubscribersWhenAbandoned() {
    val workflow = reactor.startRootWorkflow(SecondState("hello"))
    workflow.cancel()

    subscribeToState(workflow)
    assertTrue(stateSub.isClosedForReceive)
  }

  @Test fun resultCompletesWhenAbandoned() {
    val workflow = reactor.startRootWorkflow(SecondState("hello"))
    subscribeToResult(workflow)
    workflow.cancel()
    assertTrue(resultSub.isCompletedExceptionally)
  }

  @Test fun singleStateChangeAndThenFinish() {
    reactor = object : MockReactor() {
      override suspend fun onReact(
        state: TestState,
        events: ReceiveChannel<Nothing>,
        workflows: WorkflowPool
      ): Reaction<TestState, String> {
        return when (state) {
          is FirstState -> EnterState(SecondState("${state.value} ${state.value}"))
          is SecondState -> FinishWith("all done")
        }
      }
    }

    start("hello")
    // We never see the values b/c the machine races through its states and finishes
    // the instant we start it.
    assertTrue(stateSub.isClosedForReceive)
    assertEquals("all done", resultSub.getCompleted())
  }

  @Test fun delayedStateChange() {
    val secondStateDeferred = CompletableDeferred<TestState>()

    reactor = object : MockReactor() {
      override suspend fun onReact(
        state: TestState,
        events: ReceiveChannel<Nothing>,
        workflows: WorkflowPool
      ): Reaction<TestState, String> {
        return when (state) {
          is FirstState -> EnterState(secondStateDeferred.await())
          is SecondState -> FinishWith("all done")
        }
      }
    }

    start("hello")

    assertEquals(FirstState("hello"), stateSub.poll())
    assertTrue(stateSub.isEmpty)
    assertFalse(resultSub.isCompleted)

    secondStateDeferred.complete(SecondState("foo"))
    assertTrue(stateSub.isClosedForReceive)
    assertEquals("all done", resultSub.getCompleted())
  }

  @Test fun whenReactThrowsImmediately() {
    reactor = object : MockReactor() {
      override suspend fun onReact(
        state: TestState,
        events: ReceiveChannel<Nothing>,
        workflows: WorkflowPool
      ): Reaction<TestState, String> {
        throw RuntimeException("((angery))")
      }
    }

    start("foo")

    try {
      stateSub.poll()
      fail("Expected exception")
    } catch (e: ReactorException) {
      assertTrue("threw RuntimeException: ((angery))" in e.message)
    }

    try {
      resultSub.getCompleted()
      fail("Expected exception")
    } catch (e: ReactorException) {
      assertTrue("threw RuntimeException: ((angery))" in e.message)
    }
  }

  @Test fun whenReactThrowsAfterSuspending() {
    reactor = object : MockReactor() {
      override suspend fun onReact(
        state: TestState,
        events: ReceiveChannel<Nothing>,
        workflows: WorkflowPool
      ): Reaction<TestState, String> {
        suspendCoroutine<Nothing> { continuation ->
          continuation.resumeWithException(RuntimeException("((angery))"))
        }
      }
    }

    start("foo")

    try {
      stateSub.poll()
      fail("Expected exception")
    } catch (e: ReactorException) {
      assertTrue("threw RuntimeException: ((angery))" in e.message)
    }

    try {
      resultSub.getCompleted()
      fail("Expected exception")
    } catch (e: ReactorException) {
      assertTrue("threw RuntimeException: ((angery))" in e.message)
    }
  }

  @Test fun reactIsCancelled_onAbandonment() {
    var invoked = false
    var cancelled = false
    reactor = object : MockReactor() {
      override suspend fun onReact(
        state: TestState,
        events: ReceiveChannel<Nothing>,
        workflows: WorkflowPool
      ): Reaction<TestState, String> {
        invoked = true
        return suspendCancellableCoroutine { continuation ->
          continuation.invokeOnCancellation { cancelled = true }
        }
      }
    }

    start("foo")

    assertTrue(invoked)
    assertFalse(cancelled)

    workflow.cancel()

    assertTrue(invoked)
    assertTrue(cancelled)
  }

  @Test fun exceptionIsPropagated_whenStateSubscriberThrowsFromSecondOnNext_asynchronously() {
    val trigger = CompletableDeferred<Unit>()
    reactor = object : MockReactor() {
      override suspend fun onReact(
        state: TestState,
        events: ReceiveChannel<Nothing>,
        workflows: WorkflowPool
      ): Reaction<TestState, String> =
        when (state) {
          is FirstState -> {
            trigger.await()
            EnterState(SecondState(""))
          }
          is SecondState -> super.onReact(state, events, workflows)
        }
    }

    start("foo")
    stateSub.cancel()

    GlobalScope.launch(Unconfined) {
      workflow.openSubscriptionToState()
          .consumeEach { /* No-op */ }
    }

    val uncaughtExceptions = mutableListOf<Throwable>()
    GlobalScope.launch(Unconfined + CoroutineExceptionHandler { _, throwable ->
      uncaughtExceptions += throwable
    }) {
      workflow.openSubscriptionToState()
          .consumeEach { state ->
            if (state is SecondState) throw RuntimeException("fail")
          }
    }

    assertTrue(uncaughtExceptions.isEmpty())

    trigger.complete(Unit)

    assertEquals(1, uncaughtExceptions.size)
    assertTrue {
      uncaughtExceptions[0] is RuntimeException &&
          uncaughtExceptions[0].message == "fail"
    }
  }

  @Test fun acceptsEventsBeforeSubscriptions() {
    val reactor = object : Reactor<FirstState, String, String> {
      override suspend fun onReact(
        state: FirstState,
        events: ReceiveChannel<String>,
        workflows: WorkflowPool
      ): Reaction<FirstState, String> {
        val event = events.receive()
        return EnterState(FirstState(event))
      }
    }

    // Unused, but just to be certain it doesn't get gc'd on us. Silly, I know.
    val workflow = reactor.startRootWorkflow(FirstState("Hello"))
    workflow.sendEvent("Fnord")
    // No crash, no bug!
  }

  @Test fun buffersEvents_whenSelectNotCalled() {
    val proceedToSecondState = CompletableDeferred<Unit>()
    val reactor = object : Reactor<TestState, String, String> {
      override suspend fun onReact(
        state: TestState,
        events: ReceiveChannel<String>,
        workflows: WorkflowPool
      ): Reaction<TestState, String> = when (state) {
        is FirstState -> {
          proceedToSecondState.await()
          EnterState(SecondState(""))
        }
        is SecondState -> FinishWith(events.receive())
      }
    }
    val workflow = reactor.startRootWorkflow(FirstState("Hello"))
    subscribeToResult(workflow)

    // This event should get buffered…
    workflow.sendEvent("Fnord")

    // …but not accepted yet.
    assertFalse(resultSub.isCompleted)

    // This triggers the events.select, which should finish the workflow.
    proceedToSecondState.complete(Unit)
    assertEquals("Fnord", resultSub.getCompleted())
  }

  @Test fun buffersEvents_whenReentrant() {
    lateinit var workflow: Workflow<TestState, String, String>
    val reactor = object : Reactor<TestState, String, String> {
      override suspend fun onReact(
        state: TestState,
        events: ReceiveChannel<String>,
        workflows: WorkflowPool
      ): Reaction<TestState, String> = when (state) {
        is FirstState -> {
          val event = events.receive()
          workflow.sendEvent("i heard you like events")
          EnterState(SecondState(event))
        }
        is SecondState -> FinishWith(events.receive())
      }
    }
    workflow = reactor.startRootWorkflow(FirstState(""))
    subscribeToState(workflow)
    subscribeToResult(workflow)

    assertEquals(FirstState(""), stateSub.poll())
    assertFalse(resultSub.isCompleted)

    workflow.sendEvent("foo")
    assertTrue(stateSub.isClosedForReceive)
    assertEquals("i heard you like events", resultSub.getCompleted())
  }

  @Test fun stateReceiveOrNull_returnsNull_onFinishImmediately() {
    reactor = object : MockReactor() {
      override suspend fun onReact(
        state: TestState,
        events: ReceiveChannel<Nothing>,
        workflows: WorkflowPool
      ): Reaction<TestState, String> = FinishWith("done")
    }
    start("foo")

    runBlocking {
      assertEquals(null, stateSub.receiveOrNull())
    }
  }

  @Test fun stateReceiveOrNull_returnsNull_onFinishLater() {
    val trigger = CompletableDeferred<Unit>()
    reactor = object : MockReactor() {
      override suspend fun onReact(
        state: TestState,
        events: ReceiveChannel<Nothing>,
        workflows: WorkflowPool
      ): Reaction<TestState, String> {
        trigger.await()
        return FinishWith("done")
      }
    }
    start("foo")
    trigger.complete(Unit)

    runBlocking {
      assertEquals(null, stateSub.receiveOrNull())
    }
  }

  @Test fun handleUncaughtException_fallsBackToThreadHandler() {
    val thread = Thread.currentThread()
    lateinit var uncaughtThread: Thread
    lateinit var uncaughtException: Throwable
    val originalHandler = thread.uncaughtExceptionHandler
    thread.setUncaughtExceptionHandler { t, e ->
      uncaughtThread = t
      uncaughtException = e
    }

    try {
      val scope = CoroutineScope(EmptyCoroutineContext)
      scope.handleUncaughtException(RuntimeException("i escaped"))
    } finally {
      thread.uncaughtExceptionHandler = originalHandler
    }

    assertEquals(thread, uncaughtThread)
    assertEquals("i escaped", uncaughtException.message)
  }
}

private sealed class TestState {
  data class FirstState(val value: String) : TestState()
  data class SecondState(val value: String) : TestState()
}
