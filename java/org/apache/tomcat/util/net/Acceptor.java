/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.net;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.res.StringManager;

public class Acceptor<U> implements Runnable {

    private static final Log log = LogFactory.getLog(Acceptor.class);
    private static final StringManager sm = StringManager.getManager(Acceptor.class);

    private static final int INITIAL_ERROR_DELAY = 50;
    private static final int MAX_ERROR_DELAY = 1600;

    private final AbstractEndpoint<?,U> endpoint;
    private String threadName;
    /*
     * Tracked separately rather than using endpoint.isRunning() as calls to
     * endpoint.stop() and endpoint.start() in quick succession can cause the
     * acceptor to continue running when it should terminate.
     */
    private volatile boolean stopCalled = false;
    private final CountDownLatch stopLatch = new CountDownLatch(1);
    protected volatile AcceptorState state = AcceptorState.NEW;


    public Acceptor(AbstractEndpoint<?,U> endpoint) {
        this.endpoint = endpoint;
    }


    public final AcceptorState getState() {
        return state;
    }


    final void setThreadName(final String threadName) {
        this.threadName = threadName;
    }


    final String getThreadName() {
        return threadName;
    }


    @SuppressWarnings("deprecation")
    @Override
    /*
     run 方法是 `Acceptor` 类的核心逻辑，负责接收客户端连接并进行处理。它的主要功能包括：
        - 检查是否需要停止或暂停服务。
        - 接收客户端连接，并将连接传递给后续处理逻辑。
        - 处理异常情况，避免线程进入死循环或消耗过多 CPU 资源。
     */
    public void run() {

        int errorDelay = 0;
        long pauseStart = 0;

        try {
            // Loop until we receive a shutdown command
            // `stopCalled` 是一个标志位，用于指示是否需要停止线程。 `stopCalled`
            //  主循环会持续运行，直到收到停止信号 (`stopCalled = true`)
            while (!stopCalled) {

                // Loop if endpoint is paused.
                // There are two likely scenarios here.
                // The first scenario is that Tomcat is shutting down. In this
                // case - and particularly for the unit tests - we want to exit
                // this loop as quickly as possible. The second scenario is a
                // genuine pause of the connector. In this case we want to avoid
                // excessive CPU usage.
                // Therefore, we start with a tight loop but if there isn't a
                // rapid transition to stop then sleeps are introduced.
                // < 1ms       - tight loop
                // 1ms to 10ms - 1ms sleep
                // > 10ms      - 10ms sleep
                /*
                 - 如果 `endpoint.isPaused()` 返回 `true`，表示当前端点（Endpoint）处于暂停状态。
                 - 在暂停状态下，线程不会处理新的连接请求，而是进入一个低功耗的等待模式：
                     - : 紧循环（Tight Loop），快速检测状态变化。 **< 1ms**
                     - : **1ms ~ 10ms**: 使用 进行短时间休眠。 `Thread.sleep(1)`
                     - : 使用 进行长时间休眠。 **> 10ms**`Thread.sleep(10)`
                 */
                while (endpoint.isPaused() && !stopCalled) {

                    if (state != AcceptorState.PAUSED) {
                        // 单位是纳秒
                        pauseStart = System.nanoTime();
                        // Entered pause state
                        // 设置状态为暂停状态
                        state = AcceptorState.PAUSED;
                    }
                    // 如果大于 1ms，则进行休眠
                    if ((System.nanoTime() - pauseStart) > 1_000_000) {
                        // Paused for more than 1ms
                        // 休眠超过 1ms
                        try {
                            // 如果休眠时间大于 10ms，则进行长时间休眠
                            if ((System.nanoTime() - pauseStart) > 10_000_000) {
                                // 大于 10ms 休眠 10ms
                                Thread.sleep(10);
                            } else {
                                // 1-10ms，休眠 1ms
                                Thread.sleep(1);
                            }
                        } catch (InterruptedException e) {
                            // Ignore
                        }
                    }
                }

                if (stopCalled) {
                    break;
                }
                state = AcceptorState.RUNNING;

                try {
                    //if we have reached max connections, wait
                    // 如果达到了最大连接数，等待(NIO默认最大连接数是8192)
                    // 每次处理一个连接消耗一个 latch，达到 maxConnections 8192后进入等待队列
                    endpoint.countUpOrAwaitConnection();

                    // Endpoint might have been paused while waiting for latch
                    // If that is the case, don't accept new connections
                    // 如果 endpoint 处于暂停状态，则不处理新的连接请求
                    if (endpoint.isPaused()) {
                        continue;
                    }

                    U socket = null;
                    try {
                        // Accept the next incoming connection from the server
                        // socket
                        // 阻塞等待接收客户端连接，如果没有链接请求进来就阻塞在这里
                        socket = endpoint.serverSocketAccept();
                    } catch (Exception ioe) {
                        // We didn't get a socket
                        // 如果出现异常，释放一个信号量 latch
                        endpoint.countDownConnection();
                        if (endpoint.isRunning()) {
                            // Introduce delay if necessary
                            /*
                            - 当发生异常时（如连接数超限或文件描述符耗尽），通过 `handleExceptionWithDelay` 方法引入延迟，避免线程进入死循环。
                            - 延迟时间从 50ms 开始，每次翻倍，直到达到最大值（1.6 秒）。
                             */
                            errorDelay = handleExceptionWithDelay(errorDelay);
                            // re-throw
                            throw ioe;
                        } else {
                            break;
                        }
                    }
                    // Successful accept, reset the error delay
                    // 如果成功接收到了请求，重置延迟值
                    errorDelay = 0;

                    // Configure the socket
                    if (!stopCalled && !endpoint.isPaused()) {
                        // setSocketOptions() will hand the socket off to
                        // an appropriate processor if successful

                        // setSocketOptions() 将会将 socket 传递给合适的处理器
                        if (!endpoint.setSocketOptions(socket)) {
                            // 如果处理失败，则关闭socket
                            // socketWrapper.close()
                            endpoint.closeSocket(socket);
                        }
                    } else {
                        // 如果 endpoint 已暂停或者停止, socket.close
                        endpoint.destroySocket(socket);
                    }
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    String msg = sm.getString("endpoint.accept.fail");
                    // APR specific.
                    // Could push this down but not sure it is worth the trouble.
                    if (t instanceof org.apache.tomcat.jni.Error) {
                        org.apache.tomcat.jni.Error e = (org.apache.tomcat.jni.Error) t;
                        if (e.getError() == 233) {
                            // Not an error on HP-UX so log as a warning
                            // so it can be filtered out on that platform
                            // See bug 50273
                            log.warn(msg, t);
                        } else {
                            log.error(msg, t);
                        }
                    } else {
                            log.error(msg, t);
                    }
                }
            }
        } finally {
            // - 在线程结束时，调用 `stopLatch.countDown()` 通知其他线程该线程已停止。
            stopLatch.countDown();
        }
        // - 更新状态为 ，表示线程已终止。 `AcceptorState.ENDED`
        state = AcceptorState.ENDED;
    }


    /**
     * Signals the Acceptor to stop, waiting at most 10 seconds for the stop to
     * complete before returning. If the stop does not complete in that time a
     * warning will be logged.
     *
     * @deprecated This method will be removed in Tomcat 10.1.x onwards.
     *             Use {@link #stop(int)} instead.
     */
    @Deprecated
    public void stop() {
        stop(10);
    }


    /**
     * Signals the Acceptor to stop, optionally waiting for that stop process
     * to complete before returning. If a wait is requested and the stop does
     * not complete in that time a warning will be logged.
     *
     * @param waitSeconds The time to wait in seconds. Use a value less than
     *                    zero for no wait.
     */
    public void stop(int waitSeconds) {
        stopCalled = true;
        if (waitSeconds > 0) {
            try {
                if (!stopLatch.await(waitSeconds, TimeUnit.SECONDS)) {
                   log.warn(sm.getString("acceptor.stop.fail", getThreadName()));
                }
            } catch (InterruptedException e) {
                log.warn(sm.getString("acceptor.stop.interrupted", getThreadName()), e);
            }
        }
    }


    /**
     * Handles exceptions where a delay is required to prevent a Thread from
     * entering a tight loop which will consume CPU and may also trigger large
     * amounts of logging. For example, this can happen if the ulimit for open
     * files is reached.
     *
     * @param currentErrorDelay The current delay being applied on failure
     * @return  The delay to apply on the next failure
     */
    protected int handleExceptionWithDelay(int currentErrorDelay) {
        // Don't delay on first exception
        if (currentErrorDelay > 0) {
            try {
                Thread.sleep(currentErrorDelay);
            } catch (InterruptedException e) {
                // Ignore
            }
        }

        // On subsequent exceptions, start the delay at 50ms, doubling the delay
        // on every subsequent exception until the delay reaches 1.6 seconds.
        if (currentErrorDelay == 0) {
            return INITIAL_ERROR_DELAY;
        } else if (currentErrorDelay < MAX_ERROR_DELAY) {
            return currentErrorDelay * 2;
        } else {
            return MAX_ERROR_DELAY;
        }
    }


    public enum AcceptorState {
        NEW, RUNNING, PAUSED, ENDED
    }
}
