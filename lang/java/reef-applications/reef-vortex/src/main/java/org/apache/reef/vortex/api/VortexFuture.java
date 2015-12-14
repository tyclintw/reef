/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.reef.vortex.api;

import org.apache.reef.annotations.Unstable;
import org.apache.reef.annotations.audience.Private;
import org.apache.reef.util.Optional;
import org.apache.reef.vortex.driver.VortexMaster;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The interface between user code and submitted task.
 */
@Unstable
public final class VortexFuture<TOutput> implements Future<TOutput> {
  private static final Logger LOG = Logger.getLogger(VortexFuture.class.getName());

  // userResult starts out as null. If not null => variable is set and tasklet returned.
  // Otherwise tasklet has not completed.
  private Optional<TOutput> userResult = null;
  private Exception userException;
  private AtomicBoolean cancelled = new AtomicBoolean(false);
  private final CountDownLatch countDownLatch = new CountDownLatch(1);
  private final FutureCallback<TOutput> callbackHandler;
  private final Executor executor;
  private final VortexMaster vortexMaster;
  private final int taskletId;

  /**
   * Creates a {@link VortexFuture}.
   */
  @Private
  public VortexFuture(final Executor executor, final VortexMaster vortexMaster, final int taskletId) {
    this(executor, vortexMaster, taskletId, null);
  }

  /**
   * Creates a {@link VortexFuture} with a callback.
   */
  @Private
  public VortexFuture(final Executor executor,
                      final VortexMaster vortexMaster,
                      final int taskletId,
                      final FutureCallback<TOutput> callbackHandler) {
    this.executor = executor;
    this.vortexMaster = vortexMaster;
    this.taskletId = taskletId;
    this.callbackHandler = callbackHandler;
  }

  /**
   * Sends a cancel signal and blocks and waits until the task is cancelled, completed, or failed.
   * @return true if task did not start or was cancelled, false if task failed or completed
   */
  @Override
  public boolean cancel(final boolean mayInterruptIfRunning) {
    try {
      return cancel(mayInterruptIfRunning, Optional.<Long>empty(), Optional.<TimeUnit>empty());
    } catch (final TimeoutException e) {
      // This should never happen.
      LOG.log(Level.WARNING, "Received a TimeoutException in VortexFuture.cancel(). Should not have occurred.");
      return false;
    }
  }

  /**
   * Sends a cancel signal and blocks and waits until the task is cancelled, completed, or failed, or
   * if the timeout has expired.
   * @return true if task did not start or was cancelled, false if task failed or completed
   */
  public boolean cancel(final boolean mayInterruptIfRunning, final long timeout, final TimeUnit unit)
      throws TimeoutException {
    return cancel(mayInterruptIfRunning, Optional.of(timeout), Optional.of(unit));
  }

  private boolean cancel(final boolean mayInterruptIfRunning,
                         final Optional<Long> timeout,
                         final Optional<TimeUnit> unit) throws TimeoutException {
    if (isDone()) {
      return isCancelled();
    }

    vortexMaster.cancelTasklet(mayInterruptIfRunning, taskletId);

    try {
      if (timeout.isPresent() && unit.isPresent()) {
        if (!countDownLatch.await(timeout.get(), unit.get())) {
          throw new TimeoutException();
        }
      } else {
        countDownLatch.await();
      }
    } catch (InterruptedException e) {
      e.printStackTrace();
      return false;
    }

    return isCancelled();
  }

  /**
   * @return true if the task is cancelled, false if not.
   */
  @Override
  public boolean isCancelled() {
    return cancelled.get();
  }

  /**
   * @return true it the task completed, false if not.
   */
  @Override
  public boolean isDone() {
    return countDownLatch.getCount() == 0;
  }

  /**
   * Infinitely wait for the result of the task.
   * @throws InterruptedException if the thread is interrupted.
   * @throws ExecutionException if the Tasklet execution failed to complete.
   * @throws CancellationException if the Tasklet was cancelled.
   */
  @Override
  public TOutput get() throws InterruptedException, ExecutionException, CancellationException {
    countDownLatch.await();
    if (userResult != null) {
      return userResult.get();
    } else {
      assert this.cancelled.get() || userException != null;
      if (userException != null) {
        throw new ExecutionException(userException);
      }

      throw new CancellationException("Tasklet was cancelled.");
    }
  }

  /**
   * Wait a certain period of time for the result of the task.
   * @throws TimeoutException if the timeout provided hits before the Tasklet is done.
   * @throws InterruptedException if the thread is interrupted.
   * @throws ExecutionException if the Tasklet execution failed to complete.
   * @throws CancellationException if the Tasklet was cancelled.
   */
  @Override
  public TOutput get(final long timeout, final TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException, CancellationException {
    if (!countDownLatch.await(timeout, unit)) {
      throw new TimeoutException();
    }

    return get();
  }

  /**
   * Called by VortexMaster to let the user know that the task completed.
   */
  public void completed(final TOutput result) {
    this.userResult = Optional.ofNullable(result);
    if (callbackHandler != null) {
      executor.execute(new Runnable() {
        @Override
        public void run() {
          callbackHandler.onSuccess(userResult.get());
        }
      });
    }
    this.countDownLatch.countDown();
  }

  /**
   * Called by VortexMaster to let the user know that the task threw an exception.
   */
  @Private
  public void threwException(final Exception exception) {
    this.userException = exception;
    if (callbackHandler != null) {
      executor.execute(new Runnable() {
        @Override
        public void run() {
          callbackHandler.onFailure(exception);
        }
      });
    }
    this.countDownLatch.countDown();
  }

  /**
   * Called by VortexMaster to let the user know that the task was cancelled.
   */
  @Private
  public void cancelled() {
    this.cancelled.set(true);
    if (callbackHandler != null) {
      executor.execute(new Runnable() {
        @Override
        public void run() {
          callbackHandler.onFailure(new InterruptedException("VortexFuture has been cancelled on request."));
        }
      });
    }
    this.countDownLatch.countDown();
  }
}
