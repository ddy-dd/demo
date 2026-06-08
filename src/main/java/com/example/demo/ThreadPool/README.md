# 自定义线程池设计原理详解

## 📋 目录
1. [为什么需要线程池](#1-为什么需要线程池)
2. [核心参数设计](#2-核心参数设计)
3. [任务提交流程](#3-任务提交流程)
4. [关键设计决策](#4-关键设计决策)
5. [与JDK线程池对比](#5-与jdk线程池对比)
6. [最佳实践](#6-最佳实践)

---

## 1. 为什么需要线程池

### 1.1 不使用线程池的问题

```java
// ❌ 错误做法：每次请求都创建新线程
for (int i = 0; i < 1000; i++) {
    new Thread(() -> {
        // 处理任务
    }).start();
}
```

**问题：**
- **资源消耗大**：每个线程占用约1MB栈空间，1000个线程 = 1GB内存
- **频繁创建销毁**：线程创建/销毁开销大（系统调用、内存分配）
- **缺乏控制**：无法限制并发数，可能导致系统崩溃
- **上下文切换**：过多线程导致CPU时间浪费在切换上

### 1.2 线程池的优势

✅ **资源复用**：线程执行完任务后不销毁，继续执行下一个任务  
✅ **控制并发**：限制最大线程数，保护系统资源  
✅ **任务管理**：通过队列缓冲任务，平滑流量峰值  
✅ **统一管理**：监控、统计、优雅关闭  

---

## 2. 核心参数设计

### 2.1 参数说明

```java
CustomThreadPool pool = new CustomThreadPool(
    2,          // corePoolSize: 核心线程数
    4,          // maximumPoolSize: 最大线程数
    60000,      // keepAliveTime: 非核心线程空闲存活时间(ms)
    queue,      // workQueue: 工作队列
    factory,    // threadFactory: 线程工厂
    handler     // rejectedHandler: 拒绝策略
);
```

### 2.2 参数选择原则

#### **corePoolSize（核心线程数）**

| 场景 | 推荐值 | 原因 |
|------|--------|------|
| CPU密集型 | CPU核数 + 1 | 充分利用CPU，减少上下文切换 |
| IO密集型 | CPU核数 × 2 | IO等待时CPU空闲，可增加线程 |
| 混合型 | 根据 profiling 调整 | 实际测试确定最优值 |

**示例计算：**
```
服务器：8核CPU
- CPU密集任务：corePoolSize = 8 + 1 = 9
- IO密集任务：corePoolSize = 8 × 2 = 16
```

#### **maximumPoolSize（最大线程数）**

**设计考虑：**
- 应对突发流量
- 不能设置过大（避免资源耗尽）
- 通常 = corePoolSize × 2 ~ 3

**示例：**
```java
corePoolSize = 10
maximumPoolSize = 20  // 允许短期翻倍应对峰值
```

#### **workQueue（工作队列）**

**常见选择：**

1. **LinkedBlockingQueue（无界队列）**
   ```java
   new LinkedBlockingQueue<>()  // 容量 Integer.MAX_VALUE
   ```
   - ⚠️ 风险：任务堆积可能导致OOM
   - ✅ 适用：任务量可控的场景

2. **ArrayBlockingQueue（有界队列）**
   ```java
   new ArrayBlockingQueue<>(100)  // 固定容量100
   ```
   - ✅ 推荐：防止内存溢出
   - ⚠️ 注意：队列满后触发拒绝策略

3. **SynchronousQueue（直接交接）**
   ```java
   new SynchronousQueue<>()  // 不存储，直接传递给线程
   ```
   - ✅ 适用：高吞吐、低延迟场景
   - ⚠️ 要求：maximumPoolSize必须足够大

#### **keepAliveTime（存活时间）**

**作用：** 非核心线程空闲多久后被回收

**建议值：**
- 短生命周期应用：60秒
- 长生命周期应用：5-10分钟
- 高频任务：0（不回收）

---

## 3. 任务提交流程

### 3.1 完整流程图

```
提交任务 execute(task)
    ↓
┌─────────────────────────────┐
│ 当前线程数 < corePoolSize?  │
└─────────────────────────────┘
    ↓ Yes              ↓ No
 创建核心线程      ┌──────────────────┐
                  │ 放入工作队列      │
                  │ workQueue.offer() │
                  └──────────────────┘
                       ↓ Success  ↓ Failed
                   等待执行    ┌──────────────────────┐
                              │ 线程数 < maxPoolSize?  │
                              └──────────────────────┘
                                   ↓ Yes       ↓ No
                               创建非核心   执行拒绝策略
                               线程执行
```

### 3.2 代码实现解析

```java
public void execute(Runnable task) {
    mainLock.lock();
    try {
        int currentSize = workers.size();
        
        // 第一步：核心线程未满，创建核心线程
        if (currentSize < corePoolSize) {
            if (addWorker(task, true)) {
                return;  // 成功，直接返回
            }
        }
        
        // 第二步：尝试入队
        if (workQueue.offer(task)) {
            return;  // 入队成功，等待执行
        }
        
        // 第三步：创建非核心线程
        if (currentSize < maximumPoolSize) {
            if (addWorker(task, false)) {
                return;
            }
        }
        
        // 第四步：拒绝策略
        rejectedHandler.reject(task, this);
        
    } finally {
        mainLock.unlock();
    }
}
```

**为什么按这个顺序？**

1. **优先使用核心线程** → 避免频繁创建销毁线程
2. **其次入队缓冲** → 削峰填谷，平滑负载
3. **再创建非核心线程** → 应对突发流量
4. **最后拒绝** → 保护系统不被压垮

---

## 4. 关键设计决策

### 4.1 为什么用 ReentrantLock？

```java
private final ReentrantLock mainLock = new ReentrantLock();
```

**对比 synchronized：**

| 特性 | ReentrantLock | synchronized |
|------|---------------|--------------|
| 可中断 | ✅ lockInterruptibly() | ❌ |
| 超时获取 | ✅ tryLock(timeout) | ❌ |
| 公平锁 | ✅ 可选 | ❌ |
| 多条件等待 | ✅ 多个Condition | ❌ |

**在线程池中的优势：**
- 可以在等待锁时被中断（shutdown时）
- 更细粒度的控制

### 4.2 为什么 Worker 实现 Runnable？

```java
private class Worker implements Runnable {
    public void run() {
        while (task != null || (task = getTask()) != null) {
            task.run();
        }
    }
}
```

**设计思路：**
- Worker 本身是一个任务，被 Thread 执行
- run() 方法中循环从队列取任务执行
- 实现线程复用（一个线程执行多个任务）

### 4.3 核心线程 vs 非核心线程的区别

```java
private Runnable getTask() {
    if (!isCore && keepAliveTime > 0) {
        // 非核心线程：超时退出
        task = workQueue.poll(keepAliveTime, MILLISECONDS);
    } else {
        // 核心线程：一直等待
        task = workQueue.take();
    }
}
```

**区别：**
- **核心线程**：`take()` 阻塞等待，永不超时
- **非核心线程**：`poll(timeout)` 超时后返回null，线程退出

**为什么这样设计？**
- 核心线程保证基本处理能力
- 非核心线程应对峰值，空闲时回收节省资源

### 4.4 四种拒绝策略

#### 1️⃣ AbortPolicy（默认）
```java
throw new RejectedExecutionException(...);
```
- **行为**：抛出异常
- **适用**：不能丢失任务的场景
- **优点**：立即发现问题

#### 2️⃣ CallerRunsPolicy
```java
task.run();  // 由提交任务的线程执行
```
- **行为**：降级处理，由调用者执行
- **适用**：允许降级的场景
- **优点**：不丢失任务，自动限流

#### 3️⃣ DiscardPolicy
```java
// 什么都不做
```
- **行为**：静默丢弃
- **适用**：任务可丢失（如日志、监控数据）
- **优点**：性能最高

#### 4️⃣ DiscardOldestPolicy
```java
workQueue.poll();  // 丢弃最老的任务
execute(task);     // 重新提交新任务
```
- **行为**：丢弃旧任务，保留新任务
- **适用**：新任务价值更高的场景
- **优点**：优先处理最新数据

---

## 5. 与JDK线程池对比

### 5.1 对应关系

| 自定义实现 | JDK实现 | 说明 |
|-----------|---------|------|
| CustomThreadPool | ThreadPoolExecutor | 核心类 |
| Worker | ThreadPoolExecutor.Worker | 工作线程 |
| ThreadFactory | java.util.concurrent.ThreadFactory | 线程工厂 |
| RejectedExecutionHandler | java.util.concurrent.RejectedExecutionHandler | 拒绝策略 |

### 5.2 简化了什么？

**JDK ThreadPoolExecutor 的复杂性：**
- ❌ 支持定时任务（ScheduledThreadPoolExecutor）
- ❌ 复杂的线程状态管理（RUNNING, SHUTDOWN, STOP...）
- ❌ 钩子方法（beforeExecute, afterExecute）
- ❌ 动态调整线程数（setCorePoolSize等）

**我们的实现聚焦核心：**
- ✅ 清晰的三步提交流程
- ✅ 简单的线程生命周期
- ✅ 易于理解的代码结构
- ✅ 适合学习和二次开发

### 5.3 生产环境建议

**学习/轻量场景** → 使用我们的 CustomThreadPool  
**生产环境** → 使用 JDK 的 ThreadPoolExecutor 或 Spring 的 TaskExecutor

**原因：**
- JDK经过多年优化和测试
- 更多功能和边界情况处理
- 更好的性能和稳定性

---

## 6. 最佳实践

### 6.1 参数配置示例

#### 场景1：Web服务器（IO密集）
```java
int cpuCores = Runtime.getRuntime().availableProcessors();
CustomThreadPool pool = new CustomThreadPool(
    cpuCores * 2,                    // 核心线程：16（8核CPU）
    cpuCores * 4,                    // 最大线程：32
    60000,                           // 存活时间：60秒
    new ArrayBlockingQueue<>(100),   // 有界队列：100
    new ThreadPool.CallerRunsPolicy() // 降级策略
);
```

#### 场景2：数据处理（CPU密集）
```java
CustomThreadPool pool = new CustomThreadPool(
    cpuCores + 1,                    // 核心线程：9
    cpuCores + 1,                    // 最大线程：9（不需要弹性）
    0,                               // 不回收线程
    new LinkedBlockingQueue<>(1000), // 较大队列
    new ThreadPool.AbortPolicy()     // 失败快速报错
);
```

### 6.2 必须遵守的规则

#### ✅ 正确做法

```java
// 1. 始终使用 try-finally 确保任务安全
pool.execute(() -> {
    try {
        // 业务逻辑
    } catch (Exception e) {
        log.error("Task failed", e);
    }
});

// 2. 应用关闭时优雅停机
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    pool.shutdown();
    try {
        if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
            pool.shutdownNow();
        }
    } catch (InterruptedException e) {
        pool.shutdownNow();
    }
}));

// 3. 监控线程池状态
ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
monitor.scheduleAtFixedRate(() -> {
    System.out.println("Pool size: " + pool.getPoolSize());
    System.out.println("Active: " + pool.getActiveCount());
    System.out.println("Completed: " + pool.getCompletedTaskCount());
}, 0, 10, TimeUnit.SECONDS);
```

#### ❌ 错误做法

```java
// 错误1：使用无界队列 + 大量任务 → OOM
new CustomThreadPool(2, 4, 60000, new LinkedBlockingQueue<>());
// 如果任务产生速度 > 消费速度，队列无限增长

// 错误2：忘记异常处理 → 静默失败
pool.execute(() -> {
    doSomething();  // 如果抛异常，没人知道
});

// 错误3：核心线程数设置过大
new CustomThreadPool(100, 200, ...);  
// 100个线程会消耗大量内存和CPU时间片

// 错误4：不关闭线程池 → 资源泄漏
// 程序结束时必须调用 shutdown()
```

### 6.3 常见问题排查

#### 问题1：任务执行慢

**可能原因：**
- 核心线程数太小
- 队列太长，任务等待时间长

**解决方案：**
```java
// 增加核心线程数
corePoolSize = cpuCores * 2

// 或使用更快的队列
new SynchronousQueue<>()  // 无缓冲，直接执行
```

#### 问题2：频繁触发拒绝策略

**可能原因：**
- 队列容量太小
- 最大线程数不够

**解决方案：**
```java
// 增大队列
new ArrayBlockingQueue<>(1000)

// 或增大最大线程数
maximumPoolSize = corePoolSize * 3

// 或使用 CallerRunsPolicy 自动限流
new CustomThreadPool.CallerRunsPolicy()
```

#### 问题3：内存泄漏

**可能原因：**
- 任务持有大对象引用
- 线程池未正确关闭

**解决方案：**
```java
// 确保任务完成后释放引用
pool.execute(() -> {
    try {
        processData();
    } finally {
        largeObject = null;  // 手动释放
    }
});

// 应用关闭时清理
pool.shutdown();
```

---

## 7. 扩展阅读

### 相关源码位置
- JDK ThreadPoolExecutor: `java.util.concurrent.ThreadPoolExecutor`
- 工作队列: `java.util.concurrent.BlockingQueue`
- 原子类: `java.util.concurrent.atomic.AtomicInteger`

### 推荐书籍
- 《Java并发编程实战》- Brian Goetz
- 《Java并发编程的艺术》- 方腾飞

### 调试技巧
```java
// 1. 打印线程信息
System.out.println(Thread.currentThread().getName());

// 2. 查看线程堆栈
jstack <pid>

// 3. 监控工具
VisualVM, JConsole, Arthas
```

---

## 总结

**线程池的核心思想：**
1. **复用**：线程执行完任务不销毁，继续执行下一个
2. **控制**：限制并发数，保护系统资源
3. **缓冲**：队列平滑流量峰值
4. **隔离**：不同业务使用不同线程池

**设计要点：**
- 合理设置核心线程数和最大线程数
- 选择合适的队列类型和容量
- 根据业务特点选择拒绝策略
- 做好异常处理和资源清理

**记住：**
> 线程池不是越大越好，而是合适最好。
> 监控和调整比初始配置更重要。
