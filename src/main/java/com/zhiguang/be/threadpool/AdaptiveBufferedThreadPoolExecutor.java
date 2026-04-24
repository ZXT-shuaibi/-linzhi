package com.zhiguang.be.threadpool;

import com.sun.management.OperatingSystemMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 自适应缓冲线程池。
 * 在常规线程池的“核心线程 -> 队列 -> 最大线程 -> 拒绝”路径上增加缓冲阈值和自适应强制入队能力，
 * 让交易、RAG 这类突发异步任务在高峰期尽量少丢任务、少打满拒绝策略。
 */
public class AdaptiveBufferedThreadPoolExecutor extends AbstractExecutorService {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveBufferedThreadPoolExecutor.class);

    private static final int COUNT_BITS = Integer.SIZE - 3;
    private static final int CAPACITY = (1 << COUNT_BITS) - 1;

    private static final int RUNNING = -1 << COUNT_BITS;
    private static final int SHUTDOWN = 0 << COUNT_BITS;
    private static final int STOP = 1 << COUNT_BITS;
    private static final int TIDYING = 2 << COUNT_BITS;
    private static final int TERMINATED = 3 << COUNT_BITS;

    private static final boolean ONLY_ONE = true;

    private static int runStateOf(int c) {
        return c & ~CAPACITY;
    }

    private static int workerCountOf(int c) {
        return c & CAPACITY;
    }

    private static int ctlOf(int runState, int workerCount) {
        return runState | workerCount;
    }

    private static boolean isRunning(int c) {
        return c < SHUTDOWN;
    }

    private static boolean runStateLessThan(int c, int state) {
        return c < state;
    }

    private static boolean runStateAtLeast(int c, int state) {
        return c >= state;
    }

    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
    private final BlockingQueue<Runnable> workQueue;
    private final ReentrantLock mainLock = new ReentrantLock();
    private final Condition termination = mainLock.newCondition();
    private final HashSet<Worker> workers = new HashSet<Worker>();
    private final AtomicInteger threadLoad = new AtomicInteger(0);
    private final AtomicLong rejectedCount = new AtomicLong(0L);

    private volatile ThreadFactory threadFactory;
    private volatile AdaptiveRejectedExecutionHandler handler;
    private volatile long keepAliveTimeNanos;
    private volatile boolean allowCoreThreadTimeOut;
    private volatile int corePoolSize;
    private volatile int maximumPoolSize;

    private final double bufferDegree;
    private final boolean preventRejection;
    private final int threadLoadJudge;
    private final double cpuLoadJudge;
    private final long spinWaitMillis;
    private final long blockTimeoutMillis;
    private final int maxRetryAttempts;
    private final CpuLoadMonitor cpuLoadMonitor;

    private int largestPoolSize;
    private long completedTaskCount;

    /**
     * 构造自适应缓冲线程池。
     *
     * @param corePoolSize 核心线程数
     * @param maximumPoolSize 最大线程数
     * @param keepAliveTime 空闲线程保活时间
     * @param unit 保活时间单位
     * @param workQueue 工作队列
     * @param threadFactory 线程工厂
     * @param handler 拒绝策略
     * @param bufferDegree 队列缓冲阈值，0 到 1 之间
     * @param preventRejection 是否启用强制入队兜底
     * @param threadLoadJudge 线程负载阈值
     * @param cpuLoadJudge CPU 负载阈值
     * @param spinWaitMillis 空转重试初始等待时间
     * @param blockTimeoutMillis 阻塞等待超时时间
     * @param maxRetryAttempts 最大重试次数
     * @param monitorThreadPrefix 监控线程名前缀
     */
    public AdaptiveBufferedThreadPoolExecutor(
            int corePoolSize,
            int maximumPoolSize,
            long keepAliveTime,
            TimeUnit unit,
            BlockingQueue<Runnable> workQueue,
            ThreadFactory threadFactory,
            AdaptiveRejectedExecutionHandler handler,
            double bufferDegree,
            boolean preventRejection,
            int threadLoadJudge,
            double cpuLoadJudge,
            long spinWaitMillis,
            long blockTimeoutMillis,
            int maxRetryAttempts,
            String monitorThreadPrefix
    ) {
        if (corePoolSize < 0 || maximumPoolSize <= 0 || maximumPoolSize < corePoolSize || keepAliveTime < 0) {
            throw new IllegalArgumentException("线程池参数不合法");
        }
        if (unit == null || workQueue == null || threadFactory == null || handler == null) {
            throw new NullPointerException("线程池依赖不能为空");
        }

        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.keepAliveTimeNanos = unit.toNanos(keepAliveTime);
        this.workQueue = workQueue;
        this.threadFactory = threadFactory;
        this.handler = handler;
        this.bufferDegree = Math.max(0D, Math.min(bufferDegree, 1D));
        this.preventRejection = preventRejection;
        this.threadLoadJudge = Math.max(1, threadLoadJudge);
        this.cpuLoadJudge = Math.max(0D, Math.min(cpuLoadJudge, 1D));
        this.spinWaitMillis = Math.max(1L, spinWaitMillis);
        this.blockTimeoutMillis = Math.max(1L, blockTimeoutMillis);
        this.maxRetryAttempts = Math.max(1, maxRetryAttempts);
        this.cpuLoadMonitor = new CpuLoadMonitor(monitorThreadPrefix);
    }

    /**
     * 工作线程包装器。
     * 结构基本沿用 JDK 线程池的 Worker 模型，便于复用中断、加锁和退出逻辑。
     */
    private final class Worker extends AbstractQueuedSynchronizer implements Runnable {

        private static final long serialVersionUID = 1L;

        private final Thread thread;
        private Runnable firstTask;
        private volatile long completedTasks;

        Worker(Runnable firstTask) {
            setState(-1);
            this.firstTask = firstTask;
            this.thread = threadFactory.newThread(this);
        }

        @Override
        public void run() {
            runWorker(this);
        }

        @Override
        protected boolean isHeldExclusively() {
            return getState() != 0;
        }

        @Override
        protected boolean tryAcquire(int unused) {
            if (compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }

        @Override
        protected boolean tryRelease(int unused) {
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }

        void lock() {
            acquire(1);
        }

        boolean tryLock() {
            return tryAcquire(1);
        }

        void unlock() {
            release(1);
        }

        boolean isLocked() {
            return isHeldExclusively();
        }

        void interruptIfStarted() {
            Thread currentThread = thread;
            if (getState() >= 0 && currentThread != null && !currentThread.isInterrupted()) {
                try {
                    currentThread.interrupt();
                } catch (SecurityException ignore) {
                    // 保持与 JDK 线程池一致，忽略中断权限异常。
                }
            }
        }
    }

    @Override
    public void execute(Runnable command) {
        if (command == null) {
            throw new NullPointerException("任务不能为空");
        }

        int currentCtl = ctl.get();
        if (workerCountOf(currentCtl) < corePoolSize && addWorker(command, true)) {
            return;
        }

        currentCtl = ctl.get();
        if (shouldBufferToQueue() && isRunning(currentCtl) && workQueue.offer(command)) {
            int recheck = ctl.get();
            if (!isRunning(recheck) && remove(command)) {
                reject(command);
                return;
            }
            if (workerCountOf(recheck) == 0) {
                addWorker(null, false);
            }
            return;
        }

        if (addWorker(command, false)) {
            return;
        }

        if (!forceEnqueue(command, currentCtl)) {
            reject(command);
        }
    }

    /**
     * 判断当前是否优先走缓冲队列。
     */
    private boolean shouldBufferToQueue() {
        int queueSize = workQueue.size();
        int totalCapacity = queueSize + workQueue.remainingCapacity();
        if (totalCapacity <= 0) {
            return false;
        }
        double currentLoad = (double) queueSize / (double) totalCapacity;
        return currentLoad <= bufferDegree;
    }

    /**
     * 启用防拒绝能力后，在高峰期尝试继续接纳任务。
     * 根据线程负载和 CPU 负载决定是直接试探入队、阻塞等待还是空转重试。
     */
    private boolean forceEnqueue(Runnable command, int currentCtl) {
        if (!preventRejection || !isRunning(currentCtl)) {
            return false;
        }

        int currentLoad = threadLoad.incrementAndGet();
        try {
            double cpuLoad = cpuLoadMonitor.getCpuLoad();
            if (currentLoad > threadLoadJudge && cpuLoad > cpuLoadJudge) {
                return offerOnce(command);
            }
            if (currentLoad > threadLoadJudge) {
                return spinAndRetry(command);
            }
            return blockAndRetry(command);
        } finally {
            decrementThreadLoad();
        }
    }

    /**
     * 仅试探一次入队。
     */
    private boolean offerOnce(Runnable command) {
        if (!isRunning(ctl.get())) {
            return false;
        }
        if (!workQueue.offer(command)) {
            return false;
        }
        ensureWorkerForQueuedTasks();
        return true;
    }

    /**
     * 阻塞等待一定时间再尝试入队。
     */
    private boolean blockAndRetry(Runnable command) {
        if (!isRunning(ctl.get())) {
            return false;
        }
        try {
            boolean offered = workQueue.offer(command, blockTimeoutMillis, TimeUnit.MILLISECONDS);
            if (offered) {
                ensureWorkerForQueuedTasks();
            }
            return offered;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 使用指数退避进行空转重试。
     */
    private boolean spinAndRetry(Runnable command) {
        long currentWaitMillis = spinWaitMillis;
        for (int i = 0; i < maxRetryAttempts; i++) {
            if (!isRunning(ctl.get())) {
                return false;
            }

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(currentWaitMillis);
            while (System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }

            if (workQueue.offer(command)) {
                ensureWorkerForQueuedTasks();
                return true;
            }
            currentWaitMillis = Math.min(currentWaitMillis * 2L, 1000L);
        }
        return false;
    }

    /**
     * 确保队列中已有任务时至少有一个工作线程可用。
     */
    private void ensureWorkerForQueuedTasks() {
        int currentCtl = ctl.get();
        if (workerCountOf(currentCtl) == 0) {
            addWorker(null, false);
        }
    }

    /**
     * 拒绝任务并统计次数。
     */
    private void reject(Runnable command) {
        rejectedCount.incrementAndGet();
        handler.rejectedExecution(command, this);
    }

    private boolean addWorker(Runnable firstTask, boolean core) {
        retry:
        for (;;) {
            int currentCtl = ctl.get();
            int runState = runStateOf(currentCtl);

            if (runState >= SHUTDOWN && !(runState == SHUTDOWN && firstTask == null && !workQueue.isEmpty())) {
                return false;
            }

            for (;;) {
                int workerCount = workerCountOf(currentCtl);
                int limit = core ? corePoolSize : maximumPoolSize;
                if (workerCount >= CAPACITY || workerCount >= limit) {
                    return false;
                }
                if (compareAndIncrementWorkerCount(currentCtl)) {
                    break retry;
                }
                currentCtl = ctl.get();
                if (runStateOf(currentCtl) != runState) {
                    continue retry;
                }
            }
        }

        boolean workerStarted = false;
        boolean workerAdded = false;
        Worker worker = null;
        try {
            worker = new Worker(firstTask);
            Thread thread = worker.thread;
            if (thread != null) {
                final ReentrantLock mainLock = this.mainLock;
                mainLock.lock();
                try {
                    int runState = runStateOf(ctl.get());
                    if (runState < SHUTDOWN || (runState == SHUTDOWN && firstTask == null)) {
                        if (thread.isAlive()) {
                            throw new IllegalThreadStateException("工作线程已提前启动");
                        }
                        workers.add(worker);
                        int poolSize = workers.size();
                        if (poolSize > largestPoolSize) {
                            largestPoolSize = poolSize;
                        }
                        workerAdded = true;
                    }
                } finally {
                    mainLock.unlock();
                }
                if (workerAdded) {
                    thread.start();
                    workerStarted = true;
                }
            }
        } finally {
            if (!workerStarted) {
                addWorkerFailed(worker);
            }
        }
        return workerStarted;
    }

    private void addWorkerFailed(Worker worker) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            if (worker != null) {
                workers.remove(worker);
            }
            decrementWorkerCount();
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }

    private Runnable getTask() {
        boolean timedOut = false;
        for (;;) {
            int currentCtl = ctl.get();
            int runState = runStateOf(currentCtl);

            if (runState >= SHUTDOWN && (runState >= STOP || workQueue.isEmpty())) {
                decrementWorkerCount();
                return null;
            }

            int workerCount = workerCountOf(currentCtl);
            boolean timed = allowCoreThreadTimeOut || workerCount > corePoolSize;

            if ((workerCount > maximumPoolSize || (timed && timedOut)) && (workerCount > 1 || workQueue.isEmpty())) {
                if (compareAndDecrementWorkerCount(currentCtl)) {
                    return null;
                }
                continue;
            }

            try {
                Runnable task = timed
                        ? workQueue.poll(keepAliveTimeNanos, TimeUnit.NANOSECONDS)
                        : workQueue.take();
                if (task != null) {
                    return task;
                }
                timedOut = true;
            } catch (InterruptedException retry) {
                timedOut = false;
            }
        }
    }

    private void runWorker(Worker worker) {
        Thread currentThread = Thread.currentThread();
        Runnable task = worker.firstTask;
        worker.firstTask = null;
        worker.unlock();
        boolean completedAbruptly = true;

        try {
            while (task != null || (task = getTask()) != null) {
                worker.lock();
                if ((runStateAtLeast(ctl.get(), STOP)
                        || (Thread.interrupted() && runStateAtLeast(ctl.get(), STOP)))
                        && !currentThread.isInterrupted()) {
                    currentThread.interrupt();
                }

                try {
                    beforeExecute(currentThread, task);
                    Throwable thrown = null;
                    try {
                        task.run();
                    } catch (RuntimeException ex) {
                        thrown = ex;
                        throw ex;
                    } catch (Error ex) {
                        thrown = ex;
                        throw ex;
                    } catch (Throwable ex) {
                        thrown = ex;
                        throw new Error(ex);
                    } finally {
                        afterExecute(task, thrown);
                    }
                } finally {
                    task = null;
                    worker.completedTasks++;
                    worker.unlock();
                }
            }
            completedAbruptly = false;
        } finally {
            processWorkerExit(worker, completedAbruptly);
        }
    }

    private void processWorkerExit(Worker worker, boolean completedAbruptly) {
        if (completedAbruptly) {
            decrementWorkerCount();
        }

        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            completedTaskCount += worker.completedTasks;
            workers.remove(worker);
        } finally {
            mainLock.unlock();
        }

        tryTerminate();

        int currentCtl = ctl.get();
        if (runStateLessThan(currentCtl, STOP)) {
            if (!completedAbruptly) {
                int min = allowCoreThreadTimeOut ? 0 : corePoolSize;
                if (min == 0 && !workQueue.isEmpty()) {
                    min = 1;
                }
                if (workerCountOf(currentCtl) >= min) {
                    return;
                }
            }
            addWorker(null, false);
        }
    }

    private void advanceRunState(int targetState) {
        for (;;) {
            int currentCtl = ctl.get();
            if (runStateAtLeast(currentCtl, targetState)
                    || ctl.compareAndSet(currentCtl, ctlOf(targetState, workerCountOf(currentCtl)))) {
                break;
            }
        }
    }

    private void tryTerminate() {
        for (;;) {
            int currentCtl = ctl.get();
            if (isRunning(currentCtl)
                    || runStateAtLeast(currentCtl, TIDYING)
                    || (runStateOf(currentCtl) == SHUTDOWN && !workQueue.isEmpty())) {
                return;
            }

            if (workerCountOf(currentCtl) != 0) {
                interruptIdleWorkers(ONLY_ONE);
                return;
            }

            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                if (ctl.compareAndSet(currentCtl, ctlOf(TIDYING, 0))) {
                    try {
                        terminated();
                    } finally {
                        ctl.set(ctlOf(TERMINATED, 0));
                        termination.signalAll();
                    }
                    return;
                }
            } finally {
                mainLock.unlock();
            }
        }
    }

    private void interruptWorkers() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (Worker worker : workers) {
                worker.interruptIfStarted();
            }
        } finally {
            mainLock.unlock();
        }
    }

    private void interruptIdleWorkers() {
        interruptIdleWorkers(false);
    }

    private void interruptIdleWorkers(boolean onlyOne) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (Worker worker : workers) {
                Thread thread = worker.thread;
                if (!thread.isInterrupted() && worker.tryLock()) {
                    try {
                        thread.interrupt();
                    } catch (SecurityException ignore) {
                        // 保持线程池关闭逻辑稳定。
                    } finally {
                        worker.unlock();
                    }
                }
                if (onlyOne) {
                    break;
                }
            }
        } finally {
            mainLock.unlock();
        }
    }

    private boolean compareAndIncrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect + 1);
    }

    private boolean compareAndDecrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect - 1);
    }

    private void decrementWorkerCount() {
        do {
            // 自旋直到减计数成功。
        } while (!compareAndDecrementWorkerCount(ctl.get()));
    }

    private void decrementThreadLoad() {
        for (;;) {
            int current = threadLoad.get();
            if (current <= 0) {
                return;
            }
            if (threadLoad.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }

    @Override
    public void shutdown() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            advanceRunState(SHUTDOWN);
            interruptIdleWorkers();
            onShutdown();
        } finally {
            mainLock.unlock();
        }
        tryTerminate();
    }

    @Override
    public List<Runnable> shutdownNow() {
        List<Runnable> tasks;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            advanceRunState(STOP);
            interruptWorkers();
            tasks = drainQueue();
        } finally {
            mainLock.unlock();
        }
        cpuLoadMonitor.shutdown();
        tryTerminate();
        return tasks;
    }

    @Override
    public boolean isShutdown() {
        return !isRunning(ctl.get());
    }

    @Override
    public boolean isTerminated() {
        return runStateAtLeast(ctl.get(), TERMINATED);
    }

    /**
     * 返回线程池是否处于关闭中但尚未终止状态。
     */
    public boolean isTerminating() {
        int currentCtl = ctl.get();
        return !isRunning(currentCtl) && runStateLessThan(currentCtl, TERMINATED);
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (;;) {
                if (runStateAtLeast(ctl.get(), TERMINATED)) {
                    return true;
                }
                if (nanos <= 0L) {
                    return false;
                }
                nanos = termination.awaitNanos(nanos);
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 允许核心线程超时回收。
     */
    public void allowCoreThreadTimeOut(boolean value) {
        if (value && keepAliveTimeNanos <= 0L) {
            throw new IllegalArgumentException("核心线程开启超时前必须配置正数保活时间");
        }
        if (value != allowCoreThreadTimeOut) {
            allowCoreThreadTimeOut = value;
            if (value) {
                interruptIdleWorkers();
            }
        }
    }

    /**
     * 获取工作队列。
     */
    public BlockingQueue<Runnable> getQueue() {
        return workQueue;
    }

    /**
     * 从队列移除任务。
     */
    public boolean remove(Runnable task) {
        boolean removed = workQueue.remove(task);
        tryTerminate();
        return removed;
    }

    /**
     * 清理已经取消的 Future 任务。
     */
    public void purge() {
        final BlockingQueue<Runnable> currentQueue = workQueue;
        try {
            Iterator<Runnable> iterator = currentQueue.iterator();
            while (iterator.hasNext()) {
                Runnable runnable = iterator.next();
                if (runnable instanceof Future<?> && ((Future<?>) runnable).isCancelled()) {
                    iterator.remove();
                }
            }
        } catch (ConcurrentModificationException ex) {
            for (Object runnable : currentQueue.toArray()) {
                if (runnable instanceof Future<?> && ((Future<?>) runnable).isCancelled()) {
                    currentQueue.remove(runnable);
                }
            }
        }
        tryTerminate();
    }

    /**
     * 获取当前线程池中的工作线程数。
     */
    public int getPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            return runStateAtLeast(ctl.get(), TIDYING) ? 0 : workers.size();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 获取当前活跃线程数。
     */
    public int getActiveCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            int active = 0;
            for (Worker worker : workers) {
                if (worker.isLocked()) {
                    active++;
                }
            }
            return active;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 获取历史最大线程数。
     */
    public int getLargestPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            return largestPoolSize;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 获取累计任务总数。
     */
    public long getTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long count = completedTaskCount;
            for (Worker worker : workers) {
                count += worker.completedTasks;
                if (worker.isLocked()) {
                    count++;
                }
            }
            return count + workQueue.size();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 获取已完成任务总数。
     */
    public long getCompletedTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long count = completedTaskCount;
            for (Worker worker : workers) {
                count += worker.completedTasks;
            }
            return count;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 获取累计拒绝次数。
     */
    public long getRejectedCount() {
        return rejectedCount.get();
    }

    /**
     * 获取当前队列任务数。
     */
    public int getQueuedTaskCount() {
        return workQueue.size();
    }

    /**
     * 获取当前线程负载指标。
     */
    public int getThreadLoad() {
        return threadLoad.get();
    }

    /**
     * 获取最新 CPU 负载缓存值。
     */
    public double getCpuLoad() {
        return cpuLoadMonitor.getCpuLoad();
    }

    @Override
    public String toString() {
        long completed;
        int workersCount;
        int active;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            completed = completedTaskCount;
            active = 0;
            workersCount = workers.size();
            for (Worker worker : workers) {
                completed += worker.completedTasks;
                if (worker.isLocked()) {
                    active++;
                }
            }
        } finally {
            mainLock.unlock();
        }
        int currentCtl = ctl.get();
        String state = runStateLessThan(currentCtl, SHUTDOWN)
                ? "Running"
                : (runStateAtLeast(currentCtl, TERMINATED) ? "Terminated" : "Shutting down");
        return getClass().getSimpleName() + "[state=" + state
                + ", poolSize=" + workersCount
                + ", active=" + active
                + ", queued=" + workQueue.size()
                + ", completed=" + completed
                + ", rejected=" + rejectedCount.get()
                + "]";
    }

    /**
     * 关闭前钩子。
     */
    protected void onShutdown() {
        // 预留给子类扩展。
    }

    /**
     * 任务执行前钩子。
     */
    protected void beforeExecute(Thread thread, Runnable runnable) {
        // 预留给子类扩展。
    }

    /**
     * 任务执行后钩子。
     */
    protected void afterExecute(Runnable runnable, Throwable throwable) {
        // 预留给子类扩展。
    }

    /**
     * 线程池彻底终止后钩子。
     */
    protected void terminated() {
        cpuLoadMonitor.shutdown();
    }

    /**
     * 排空工作队列。
     */
    private List<Runnable> drainQueue() {
        ArrayList<Runnable> taskList = new ArrayList<Runnable>();
        workQueue.drainTo(taskList);
        if (!workQueue.isEmpty()) {
            for (Runnable runnable : workQueue.toArray(new Runnable[0])) {
                if (workQueue.remove(runnable)) {
                    taskList.add(runnable);
                }
            }
        }
        return taskList;
    }

    /**
     * 默认丢弃策略。
     * 仅记录日志，不再额外抛异常。
     */
    public static class DiscardPolicy implements AdaptiveRejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable task, AdaptiveBufferedThreadPoolExecutor executor) {
            log.warn("线程池任务被丢弃，executor={}", executor);
        }
    }

    /**
     * 调用方执行策略。
     * 比静默丢任务更适合业务系统，能在极端高峰下尽量保证任务不丢。
     */
    public static class CallerRunsPolicy implements AdaptiveRejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable task, AdaptiveBufferedThreadPoolExecutor executor) {
            if (!executor.isShutdown()) {
                task.run();
                return;
            }
            throw new RejectedExecutionException("线程池已关闭，无法继续执行任务");
        }
    }

    /**
     * 仅计数不执行策略。
     * 主要用于压测和实验观察。
     */
    public static class CountPolicy implements AdaptiveRejectedExecutionHandler {
        private final AtomicInteger count = new AtomicInteger(0);

        @Override
        public void rejectedExecution(Runnable task, AdaptiveBufferedThreadPoolExecutor executor) {
            count.incrementAndGet();
        }

        public int getCount() {
            return count.get();
        }
    }

    /**
     * CPU 负载监控器。
     * 使用 JDK 自带管理接口周期刷新系统 CPU 负载，避免为线程池模块额外引入第三方依赖。
     */
    static final class CpuLoadMonitor {

        private final OperatingSystemMXBean operatingSystemMXBean;
        private final ScheduledExecutorService scheduler;
        private volatile double cpuLoad = 0D;

        CpuLoadMonitor(String threadNamePrefix) {
            OperatingSystemMXBean bean = null;
            if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean) {
                bean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            }
            this.operatingSystemMXBean = bean;
            this.scheduler = Executors.newSingleThreadScheduledExecutor(
                    new NamedThreadFactory((threadNamePrefix == null ? "pool-monitor-" : threadNamePrefix) + "monitor-")
            );
            this.scheduler.scheduleAtFixedRate(this::refreshCpuLoad, 0L, 5L, TimeUnit.SECONDS);
        }

        /**
         * 刷新 CPU 负载缓存。
         */
        private void refreshCpuLoad() {
            if (operatingSystemMXBean == null) {
                cpuLoad = 0D;
                return;
            }
            try {
                double value = operatingSystemMXBean.getCpuLoad();
                if (!Double.isNaN(value) && value >= 0D) {
                    cpuLoad = Math.min(1D, value);
                }
            } catch (Exception ex) {
                cpuLoad = 0D;
            }
        }

        /**
         * 获取 CPU 负载。
         */
        double getCpuLoad() {
            return cpuLoad;
        }

        /**
         * 关闭监控线程。
         */
        void shutdown() {
            scheduler.shutdownNow();
        }
    }
}
