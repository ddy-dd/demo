package com.example.demo.ThreadPool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 自定义线程池实现
 * 
 * 设计原理：
 * 1. 核心线程数(corePoolSize)：线程池维持的最小线程数，即使空闲也不会被回收
 * 2. 最大线程数(maximumPoolSize)：线程池允许创建的最大线程数
 * 3. 工作队列(workQueue)：存放待执行任务的阻塞队列
 * 4. 拒绝策略(rejectedHandler)：当任务无法被执行时的处理方式
 * 
 * 任务提交流程：
 * - 当前线程数 < corePoolSize → 创建新核心线程执行
 * - 当前线程数 >= corePoolSize → 放入工作队列
 * - 队列已满 && 线程数 < maximumPoolSize → 创建非核心线程执行
 * - 队列已满 && 线程数 >= maximumPoolSize → 执行拒绝策略
 */
public class CustomThreadPool {
    
    private final int corePoolSize;
    private final int maximumPoolSize;
    private final long keepAliveTime; // 非核心线程空闲存活时间(毫秒)
    private final BlockingQueue<Runnable> workQueue;
    private final ThreadFactory threadFactory;
    private final RejectedExecutionHandler rejectedHandler;
    
    private volatile List<Worker> workers;
    private final ReentrantLock mainLock = new ReentrantLock();
    private volatile boolean isShutdown = false;
    
    private final AtomicInteger completedTaskCount = new AtomicInteger(0);
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final AtomicInteger threadIdGenerator = new AtomicInteger(0);
    
    /**
     * 构造函数 - 使用默认拒绝策略和线程工厂
     */
    public CustomThreadPool(int corePoolSize, 
                           int maximumPoolSize,
                           long keepAliveTime,
                           BlockingQueue<Runnable> workQueue) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, workQueue, 
             new DefaultThreadFactory(), new AbortPolicy());
    }
    
    /**
     * 完整构造函数
     */
    public CustomThreadPool(int corePoolSize,
                           int maximumPoolSize,
                           long keepAliveTime,
                           BlockingQueue<Runnable> workQueue,
                           ThreadFactory threadFactory,
                           RejectedExecutionHandler rejectedHandler) {
        if (corePoolSize < 0 || maximumPoolSize <= 0 || 
            maximumPoolSize < corePoolSize || keepAliveTime < 0) {
            throw new IllegalArgumentException("Invalid pool parameters");
        }
        
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.workQueue = workQueue;
        this.threadFactory = threadFactory;
        this.rejectedHandler = rejectedHandler;
        this.workers = new ArrayList<>();
    }
    
    /**
     * 提交任务到线程池
     * 
     * @param task 待执行的任务
     * @throws RejectedExecutionException 任务被拒绝时抛出
     */
    public void execute(Runnable task) {
        if (task == null) {
            throw new NullPointerException("Task cannot be null");
        }
        
        if (isShutdown) {
            throw new IllegalStateException("Thread pool is shutdown");
        }
        
        mainLock.lock();
        try {
            int currentSize = workers.size();
            
            // 第一步：如果当前线程数小于核心线程数，创建新核心线程
            if (currentSize < corePoolSize) {
                if (addWorker(task, true)) {
                    return;
                }
                currentSize = workers.size();
            }
            
            // 第二步：尝试将任务放入工作队列
            if (workQueue.offer(task)) {
                return;
            }
            
            // 第三步：队列已满，尝试创建非核心线程
            if (currentSize < maximumPoolSize) {
                if (addWorker(task, false)) {
                    return;
                }
                currentSize = workers.size();
            }
            
            // 第四步：所有资源耗尽，执行拒绝策略
            rejectedHandler.reject(task, this);
            
        } finally {
            mainLock.unlock();
        }
    }
    
    /**
     * 添加工作线程
     * 
     * @param firstTask 第一个要执行的任务（可能为null）
     * @param isCore 是否为核心线程
     * @return 是否成功添加
     */
    private boolean addWorker(Runnable firstTask, boolean isCore) {
        int currentSize = workers.size();
        
        // 检查线程数限制
        if (currentSize >= (isCore ? corePoolSize : maximumPoolSize)) {
            return false;
        }
        
        Worker worker = new Worker(firstTask, isCore);
        Thread thread = threadFactory.newThread(worker);
        
        if (thread == null) {
            return false;
        }
        
        worker.thread = thread;
        workers.add(worker);
        thread.start();
        
        return true;
    }
    
    /**
     * 关闭线程池
     * 不再接受新任务，但会执行完已提交的任务
     */
    public void shutdown() {
        mainLock.lock();
        try {
            isShutdown = true;
            for (Worker worker : workers) {
                worker.interruptIfIdle();
            }
        } finally {
            mainLock.unlock();
        }
    }
    
    /**
     * 立即关闭线程池
     * 中断所有正在执行的任务
     */
    public void shutdownNow() {
        mainLock.lock();
        try {
            isShutdown = true;
            for (Worker worker : workers) {
                worker.interrupt();
            }
            workQueue.clear();
        } finally {
            mainLock.unlock();
        }
    }
    
    /**
     * 判断线程池是否已关闭
     */
    public boolean isShutdown() {
        return isShutdown;
    }
    
    /**
     * 获取已完成任务数
     */
    public int getCompletedTaskCount() {
        return completedTaskCount.get();
    }
    
    /**
     * 获取活跃线程数
     */
    public int getActiveCount() {
        return activeCount.get();
    }
    
    /**
     * 获取线程池大小
     */
    public int getPoolSize() {
        mainLock.lock();
        try {
            return workers.size();
        } finally {
            mainLock.unlock();
        }
    }
    
    /**
     * 工作线程内部类
     * 每个Worker包装一个线程，负责从队列中获取任务并执行
     */
    private class Worker implements Runnable {
        private Thread thread;
        private Runnable firstTask;
        private final boolean isCore;
        private volatile long lastActiveTime;
        
        Worker(Runnable firstTask, boolean isCore) {
            this.firstTask = firstTask;
            this.isCore = isCore;
            this.lastActiveTime = System.currentTimeMillis();
        }
        
        @Override
        public void run() {
            try {
                // 执行第一个任务（如果有）
                Runnable task = firstTask;
                firstTask = null;
                
                while (task != null || (task = getTask()) != null) {
                    activeCount.incrementAndGet();
                    lastActiveTime = System.currentTimeMillis();
                    
                    try {
                        task.run();
                        completedTaskCount.incrementAndGet();
                    } catch (RuntimeException e) {
                        // 记录异常但不中断线程
                        System.err.println("Task execution failed: " + e.getMessage());
                    } finally {
                        activeCount.decrementAndGet();
                        task = null;
                    }
                }
                
            } finally {
                // 线程退出，从workers列表中移除
                workerDone(this);
            }
        }
        
        /**
         * 从工作队列获取任务
         * 核心线程会一直等待，非核心线程有时间限制
         */
        private Runnable getTask() {
            while (true) {
                try {
                    Runnable task;
                    
                    // 非核心线程使用带超时的poll，超时后退出
                    if (!isCore && keepAliveTime > 0) {
                        task = workQueue.poll(keepAliveTime, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } else {
                        // 核心线程使用阻塞的take
                        task = workQueue.take();
                    }
                    
                    if (task != null) {
                        return task;
                    }
                    
                    // 非核心线程超时，准备退出
                    if (!isCore) {
                        return null;
                    }
                    
                } catch (InterruptedException e) {
                    // 线程被中断，准备退出
                    return null;
                }
            }
        }
        
        /**
         * 如果线程空闲则中断
         */
        void interruptIfIdle() {
            if (thread != null) {
                thread.interrupt();
            }
        }
        
        /**
         * 强制中断线程
         */
        void interrupt() {
            if (thread != null) {
                thread.interrupt();
            }
        }
    }
    
    /**
     * 工作线程完成后的清理工作
     */
    private void workerDone(Worker worker) {
        mainLock.lock();
        try {
            workers.remove(worker);
            
            // 如果线程池未关闭且还有任务，补充新线程
            if (!isShutdown && !workQueue.isEmpty() && workers.size() < corePoolSize) {
                addWorker(null, true);
            }
        } finally {
            mainLock.unlock();
        }
    }
    
    // ==================== 内部接口和实现类 ====================
    
    /**
     * 线程工厂接口
     */
    public interface ThreadFactory {
        Thread newThread(Runnable r);
    }
    
    /**
     * 默认线程工厂
     */
    public static class DefaultThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        
        DefaultThreadFactory() {
            this.namePrefix = "custom-pool-thread-";
        }
        
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            t.setDaemon(false); // 非守护线程
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    }
    
    /**
     * 拒绝策略接口
     */
    public interface RejectedExecutionHandler {
        void reject(Runnable task, CustomThreadPool executor);
    }
    
    /**
     * 中止策略 - 抛出异常（默认策略）
     */
    public static class AbortPolicy implements RejectedExecutionHandler {
        @Override
        public void reject(Runnable task, CustomThreadPool executor) {
            throw new RejectedExecutionException(
                "Task " + task.toString() + " rejected from " + executor.toString()
            );
        }
    }
    
    /**
     * 调用者运行策略 - 由提交任务的线程执行
     */
    public static class CallerRunsPolicy implements RejectedExecutionHandler {
        @Override
        public void reject(Runnable task, CustomThreadPool executor) {
            if (!executor.isShutdown()) {
                task.run();
            }
        }
    }
    
    /**
     * 丢弃策略 - 直接丢弃任务
     */
    public static class DiscardPolicy implements RejectedExecutionHandler {
        @Override
        public void reject(Runnable task, CustomThreadPool executor) {
            // 什么都不做，静默丢弃
        }
    }
    
    /**
     * 丢弃最老任务策略 - 丢弃队列中最老的任务，然后重试提交
     */
    public static class DiscardOldestPolicy implements RejectedExecutionHandler {
        @Override
        public void reject(Runnable task, CustomThreadPool executor) {
            if (!executor.isShutdown()) {
                executor.workQueue.poll(); // 丢弃队首任务
                executor.execute(task); // 重新提交
            }
        }
    }
    
    /**
     * 拒绝执行异常
     */
    public static class RejectedExecutionException extends RuntimeException {
        public RejectedExecutionException(String message) {
            super(message);
        }
    }
    
    @Override
    public String toString() {
        return String.format("CustomThreadPool[pool size = %d, active threads = %d, completed tasks = %d]",
                getPoolSize(), getActiveCount(), getCompletedTaskCount());
    }
}
