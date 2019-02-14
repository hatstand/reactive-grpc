/*
 *  Copyright (c) 2017, salesforce.com, inc.
 *  All rights reserved.
 *  Licensed under the BSD 3-Clause license.
 *  For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.reactivegrpc.common;

import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.CallStreamObserver;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import static com.google.common.base.Preconditions.checkNotNull;

// TODO: FIX ME ACCORDING TO THE LATEST CHANGES PLEEESE!
/**
 * ReactivePublisherBackpressureOnReadyHandler bridges the manual flow control idioms of Reactive Streams and gRPC. This
 * class takes messages off of a {@link org.reactivestreams.Publisher} and feeds them into a {@link CallStreamObserver}
 * while respecting backpressure. This class is the inverse of {@link AbstractStreamObserverAndPublisher}.
 * <p>
 * When a gRPC publisher's transport wants more data to transmit, the {@link CallStreamObserver}'s onReady handler is
 * signaled. This handler must keep transmitting messages until {@link CallStreamObserver#isReady()} ceases to be true.
 * <p>
 * When a {@link org.reactivestreams.Publisher} is subscribed to by a {@link Subscriber}, the
 * {@code Publisher} hands the {@code Subscriber} a {@link Subscription}. When the {@code Subscriber}
 * wants more messages from the {@code Publisher}, the {@code Subscriber} calls {@link Subscription#request(long)}.
 * <p>
 * To bridge the two idioms: when gRPC wants more messages, the {@code onReadyHandler} is called and {@link #run()}
 * calls the {@code Subscription}'s {@code request()} method, asking the {@code Publisher} to produce another message.
 * Since this class is also registered as the {@code Publisher}'s {@code Subscriber}, the {@link #onNext(Object)}
 * method is called. {@code onNext()} passes the message to gRPC's {@link CallStreamObserver#onNext(Object)} method,
 * and then calls {@code request()} again if {@link CallStreamObserver#isReady()} is true. The loop of
 * request->pass->check is repeated until {@code isReady()} returns false, indicating that the outbound transmit buffer
 * is full and that backpressure must be applied.
 *
 * @param <T>
 */
public abstract class AbstractSubscriberAndProducer<T> implements Subscriber<T>, Runnable {

    /** Indicates the fusion has not happened yet. */
    private static final int NOT_FUSED = -1;
    /** Indicates the QueueSubscription can't support the requested mode. */
    private static final int NONE = 0;
    /** Indicates the QueueSubscription can perform sync-fusion. */
    private static final int SYNC = 1;
    /** Indicates the QueueSubscription can perform only async-fusion. */
    private static final int ASYNC = 2;
    /** Indicates the QueueSubscription should decide what fusion it performs (input only). */
    private static final int ANY = 3;
    /**
     * Indicates that the queue will be drained from another thread
     * thus any queue-exit computation may be invalid at that point.
     * <p>
     * For example, an {@code asyncSource.map().publishOn().subscribe()} sequence where {@code asyncSource}
     * is async-fuseable: publishOn may fuse the whole sequence into a single Queue. That in turn
     * could invoke the mapper function from its {@code poll()} method from another thread,
     * whereas the unfused sequence would have invoked the mapper on the previous thread.
     * If such mapper invocation is costly, it would escape its thread boundary this way.
     */
    private static final int THREAD_BARRIER = 4;

    private static final Subscription CANCELLED_SUBSCRIPTION = new CancelledQueueSubscription();

    private Throwable throwable;
    private boolean   done;

    private boolean isRequested;

    private int sourceMode = NOT_FUSED;

    private volatile Subscription subscription;
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<AbstractSubscriberAndProducer, Subscription> SUBSCRIPTION =
        AtomicReferenceFieldUpdater.newUpdater(AbstractSubscriberAndProducer.class, Subscription.class, "subscription");

    protected volatile CallStreamObserver<T> downstream;
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<AbstractSubscriberAndProducer, CallStreamObserver> DOWNSTREAM =
        AtomicReferenceFieldUpdater.newUpdater(AbstractSubscriberAndProducer.class, CallStreamObserver.class, "downstream");

    private volatile int wip;
    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<AbstractSubscriberAndProducer> WIP =
            AtomicIntegerFieldUpdater.newUpdater(AbstractSubscriberAndProducer.class, "wip");

    public void subscribe(final CallStreamObserver<T> downstream) {
        checkNotNull(downstream);

        if (this.downstream == null && DOWNSTREAM.compareAndSet(this, null, downstream)) {
            downstream.setOnReadyHandler(this);
        }
    }

    @Override
    public void run() {
        Subscription s = this.subscription;
        if (s != null && s != CANCELLED_SUBSCRIPTION) {
            drain();
        }
    }

    public void cancel() {
        Subscription s = SUBSCRIPTION.getAndSet(this, CANCELLED_SUBSCRIPTION);
        if (s != CANCELLED_SUBSCRIPTION) {
            s.cancel();

            if (WIP.getAndIncrement(this) == 0) {
                if (s instanceof Queue) {
                    ((Queue) s).clear();
                }
            }
        }
    }

    public boolean isCanceled() {
        return subscription == CANCELLED_SUBSCRIPTION;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        checkNotNull(subscription);

        subscription = fuse(subscription);

        if (this.subscription == null && SUBSCRIPTION.compareAndSet(this, null, subscription)) {

            drain();

            return;
        }

        subscription.cancel();
    }

    @Override
    public void onNext(T t) {
        if (t == null && sourceMode == ASYNC || sourceMode == NOT_FUSED) {
            drain();
            return;
        }

        if (!isCanceled()) {
            checkNotNull(t);

            final CallStreamObserver<T> a = downstream;

            try {
                a.onNext(t);
                isRequested = false;
                drain();
            } catch (Throwable throwable) {
                cancel();
                a.onError(prepareError(throwable));
            }
        }
    }

    @Override
    public void onError(Throwable t) {
        if (!isCanceled()) {
            checkNotNull(t);

            done = true;
            throwable = t;

            drain();
        }
    }

    @Override
    public void onComplete() {
        if (!isCanceled()) {
            done = true;

            drain();
        }
    }

    protected abstract Subscription fuse(Subscription subscription);

    void drain() {
        if (WIP.getAndIncrement(this) != 0) {
            return;
        }

        int mode = sourceMode;

        int missed = 1;
        final CallStreamObserver<? super T> a = downstream;


        if (mode == NOT_FUSED) {
            final Subscription s = subscription;

            if (s instanceof FusionModeAwareSubscription) {
                mode = ((FusionModeAwareSubscription) s).mode();

                if (mode == SYNC) {
                    done = true;
                } else {
                    s.request(1);
                }
            } else {
                mode = NONE;
            }

            sourceMode = mode;
        }


        for (;;) {
            if (a != null) {
                if (mode == SYNC) {
                    drainSync();
                } else if (mode == ASYNC) {
                    drainAsync();
                } else {
                    drainRegular();
                }

                return;
            }

            missed = WIP.addAndGet(this, -missed);
            if (missed == 0) {
                break;
            }
        }
    }

    void drainSync() {
        int missed = 1;

        final CallStreamObserver<? super T> a = downstream;
        @SuppressWarnings("unchecked")
        final Queue<T> q = (Queue<T>) subscription;

        for (;;) {

            while (a.isReady()) {
                T v;

                try {
                    v = q.poll();
                } catch (Throwable ex) {
                    try {
                        a.onError(prepareError(ex));
                    } catch (Throwable ignore) { }
                    return;
                }

                if (isCanceled()) {
                    q.clear();
                    return;
                }
                if (v == null) {
                    try {
                        a.onCompleted();
                    } catch (Throwable ignore) { }
                    return;
                }

                try {
                    a.onNext(v);
                } catch (Throwable ex) {
                    try {
                        a.onError(prepareError(ex));
                    } catch (Throwable ignore) { }
                    return;
                }
            }

            if (isCanceled()) {
                q.clear();
                return;
            }

            if (q.isEmpty()) {
                try {
                    a.onCompleted();
                } catch (Throwable ignore) { }
                return;
            }

            int w = wip;
            if (missed == w) {
                missed = WIP.addAndGet(this, -missed);
                if (missed == 0) {
                    break;
                }
            } else {
                missed = w;
            }
        }
    }

    void drainAsync() {
        int missed = 1;

        final Subscription s = subscription;
        final CallStreamObserver<? super T> a = downstream;
        @SuppressWarnings("unchecked")
        final Queue<T> q = (Queue<T>) subscription;

        long sent = 0;

        for (;;) {

            while (a.isReady()) {
                boolean d = done;
                T v;

                try {
                    v = q.poll();
                } catch (Throwable ex) {
                    s.cancel();
                    q.clear();

                    try {
                        a.onError(prepareError(ex));
                    } catch (Throwable ignore) { }

                    return;
                }

                boolean empty = v == null;

                if (checkTerminated(d, empty, a, q)) {
                    return;
                }

                if (empty) {
                    break;
                }

                try {
                    a.onNext(v);
                } catch (Throwable ex) {
                    throwable = ex;
                    done = true;
                    try {
                        a.onError(prepareError(ex));
                    } catch (Throwable ignore) { }
                    return;
                }

                sent++;
            }

            if (checkTerminated(done, q.isEmpty(), a, q)) {
                return;
            }

            int w = wip;
            if (missed == w) {
                if (sent > 0) {
                    s.request(sent);
                }
                missed = WIP.addAndGet(this, -missed);
                if (missed == 0) {
                    break;
                }
                sent = 0;
            } else {
                missed = w;
            }
        }
    }

    void drainRegular() {
        int missed = 1;
        final CallStreamObserver<? super T> a = downstream;

        for (;;) {

            if (done) {
                Throwable t = throwable;

                if (t != null) {
                    try {
                        a.onError(prepareError(t));
                    } catch (Throwable ignore) { }
                } else {
                    try {
                        a.onCompleted();
                    } catch (Throwable ignore) { }
                }

                return;
            } else {
                if (a.isReady() && !isRequested) {
                    isRequested = true;
                    subscription.request(1);
                }
            }

            int w = wip;
            if (missed == w) {
                missed = WIP.addAndGet(this, -missed);
                if (missed == 0) {
                    break;
                }
            } else {
                missed = w;
            }
        }
    }

    boolean checkTerminated(boolean d, boolean empty, CallStreamObserver<?> a, Queue<T> q) {
        if (isCanceled()) {
            q.clear();
            return true;
        }

        if (d) {
            Throwable t = throwable;
            if (t != null) {
                q.clear();
                try {
                    a.onError(prepareError(t));
                } catch (Throwable ignore) { }
                return true;
            } else if (empty) {
                try {
                    a.onCompleted();
                } catch (Throwable ignore) { }
                return true;
            }
        }

        return false;
    }

    private static Throwable prepareError(Throwable throwable) {
        if (throwable instanceof StatusException || throwable instanceof StatusRuntimeException) {
            return throwable;
        } else {
            return Status.fromThrowable(throwable).asException();
        }
    }

    /**
     * Implementation of Cancelled Queue Subscription which is used as a marker of
     * cancelled {@link AbstractSubscriberAndProducer} instance.
     */
    private static class CancelledQueueSubscription implements Subscription, Queue {

        static final String NOT_SUPPORTED_MESSAGE = "Although CancelledQueueSubscription implements Queue it is" +
            " purely internal and only guarantees support for poll/clear/size/isEmpty." +
            " Instances shouldn't be used/exposed as Queue outside of RxGrpc operators.";

        @Override
        public void cancel() {
            // deliberately no op
        }

        @Override
        public void request(long n) {
            // deliberately no op
        }

        @Override
        public Object poll() {
            return null;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public void clear() {
            // deliberately no op
        }

        @Override
        public boolean offer(Object t) {
            throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
        }

        @Override
        public int size() {
            throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
        }


        @Override
        public Object peek() {
            throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
        }

        @Override
        public boolean add(Object t) {
            throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
        }

        @Override
        public Object remove() {
            throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
        }

        @Override
        public Object element() {
            throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
        }

        @Override
        public boolean contains(Object o) {
            throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
        }

        @Override
        public Iterator iterator() {
            throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
        }

        @Override
        public Object[] toArray() {
            throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
        }

        @Override
        public Object[] toArray(Object[] a) {
            throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
        }

        @Override
        public boolean remove(Object o) {
            throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
        }

        @Override
        public boolean containsAll(Collection c) {
            throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
        }

        @Override
        public boolean addAll(Collection c) {
            throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
        }

        @Override
        public boolean removeAll(Collection c) {
            throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
        }

        @Override
        public boolean retainAll(Collection c) {
            throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
        }
    }
}
