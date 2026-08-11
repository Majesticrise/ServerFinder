<p align="center">
  <img src="https://img.shields.io/badge/Java-21+-blue.svg" alt="Java">
  <img src="https://img.shields.io/badge/License-LGPL--2.1-green.svg" alt="License">
  <img src="https://img.shields.io/badge/Minecraft-1.8--1.20-orange.svg" alt="Minecraft">
  <img src="https://img.shields.io/badge/Platform-Linux%20%7C%20Windows-lightgrey.svg" alt="Platform">
  <img src="https://img.shields.io/badge/CI-GitHub%20Actions-brightgreen" alt="CI">
</p>

## ✦ Title & Introduction / 标题与引言

| English / 英文 | 中文 / Chinese |
|----------------|---------------|
| **Minecraft Server Scanner · An Elegant Distributed Probing Engine** | **Minecraft 服务器扫描器 · 优雅的分布式探测引擎** |
| A high‑performance, self‑adaptive scanner for discovering Minecraft Java Edition servers. | 一款高性能、自适应并发的 Minecraft Java 版服务器发现工具。 |
| Supports SOCKS5 proxy pools, intelligent concurrency tuning, result deduplication, and persistent storage – designed for large‑scale public Internet reconnaissance. | 支持 SOCKS5 代理池、智能调速、结果去重与持久化，专为大规模公网探测而设计。 |

---
## 📑 Table of Contents / 目录

- [Features / 功能](#-features--功能)
- [Architecture Overview / 架构概览](#-architecture-overview--架构概览)
- [Quick Start / 快速开始](#-quick-start--快速开始)
- [Configuration Details / 配置详解](#-configuration-details--配置详解)
- [IP Generation / IP 生成引擎](#-ip-generation--ip-生成引擎)
- [Adaptive Concurrency Algorithm / 自适应并发算法](#-adaptive-concurrency-algorithm--自适应并发算法)
- [Proxy Pool Mechanics / 代理池机制](#-proxy-pool-mechanics--代理池机制)
- [Output & Deduplication / 输出与去重](#-output--deduplication--输出与去重)
- [Important Notes / 注意事项](#-important-notes--注意事项)
- [Customisation & Extensions / 扩展与定制](#-customisation--extensions--扩展与定制)
- [CI/CD Pipeline](#-cicd-pipeline--持续集成与交付)
- [License / 许可](#-license--许可)

---

## ✦ Features / 功能

| Feature / 功能 | Description / 描述 |
|----------------|-------------------|
| **Hybrid Adaptive Concurrency**<br>混合自适应并发 | Dynamically adjusts scanning concurrency based on discovery rate, from exponential coarse sweep to golden‑section fine tuning, automatically locking onto the optimal throughput.<br>基于发现速率动态调整扫描并发数，从指数粗扫到黄金分割细扫，自动锁定最优吞吐量。 |
| **SOCKS5 Proxy Pool**<br>SOCKS5 代理池 | Built‑in proxy fission and GitHub public pool integration, with automatic refill, deduplication, and invalidation – effectively conceals your real IP.<br>内置代理裂变与 GitHub 公共池，自动补货、去重与失效回收，有效隐藏真实 IP。 |
| **Multi‑Version Protocol Probing**<br>多协议版本探测 | Simultaneously attempts three mainstream protocol versions: 1.20.1 (763), 1.12.2 (340), and 1.8.9 (47), covering the vast majority of servers.<br>同时尝试 1.20.1、1.12.2、1.8.9 三个主流协议版本，兼容绝大多数服务器。 |
| **Smart Result Management**<br>智能结果管理 | Writes results to file in real time, periodically deduplicates by IP, and automatically cleans up the output file.<br>实时写入文件，定期基于 IP 去重（避免重复记录），支持输出文件自动整理。 |
| **Memory & Stability Safeguards**<br>内存与稳定性保障 | Memory water‑level monitoring with emergency throttling; network error and timeout statistics aid health assessment of the proxy pool.<br>内存水位监控，紧急降速；网络错误与超时统计，辅助判断代理池健康。 |
| **Dual Display Modes**<br>双模式展示 | Scrollable list mode and real‑time speed summary mode, giving you clear visibility into scanning progress.<br>滚动列表模式与实时速度摘要模式，清晰掌握扫描进度。 |
| **Ultra‑Fast IP Generation**<br>极速 IP 生成引擎 | Employs **hexadecimal bitmasks** and **ternary bitwise operations** for **O(1) constant‑time** public IP classification, eliminating string parsing overhead and enabling sub‑microsecond generation with zero intermediate allocations.<br>采用 **十六进制位掩码** 与 **三目位运算** 实现 **O(1) 常数时间** 的公网 IP 分类判定，彻底消除字符串解析开销，实现亚微秒级生成与零中间状态分配。 |
---

## ✦ Architecture Overview / 架构概览

| Component / 组件 | Responsibility (EN) / 职责（英文） | Responsibility (CN) / 职责（中文） |
|------------------|-----------------------------------|-----------------------------------|
| **ScanOrchestrator**<br>扫描调度器 | Core scheduler that manages adaptive concurrency and task lifecycles, orchestrates the entire scanning process. | 核心调度器，管理自适应并发与任务生命周期，协调整个扫描流程。 |
| **ScanWorker**<br>工作线程 | A virtual thread per IP probe; performs port checking and Minecraft ping, either through a proxy or directly. | 每个虚拟线程执行一个 IP 的探测任务，进行端口检查与 Minecraft 握手，支持代理或直连。 |
| ProxyManager代理管理器 | Maintains the proxy pool, auto‑crawls new proxies via fission, pulls pre‑validated lists from GitHub, and schedules periodic refill tasks via its internal scheduler. | 维护代理池，通过裂变自动爬取新代理，从 GitHub 拉取预验证列表，并通过内部调度器执行定期补货任务。 |
| **ResultConsumer**<br>结果消费者 | Consumes scan results from the queue, writes them to the output file, and performs periodic IP‑based deduplication with read‑write lock protection. | 从队列中消费扫描结果，写入输出文件，并定期执行基于 IP 的去重（使用读写锁保护）。 |
| **NetworkMonitor**<br>网络监控器 | Tracks errors and timeouts within a sliding time window, providing real‑time feedback for proxy pool health assessment. | 统计滑动时间窗口内的错误与超时，为代理池健康提供实时反馈。 |
| **AdaptiveSemaphore**<br>自适应信号量 | A dynamically adjustable semaphore that controls the number of concurrent tasks, enabling smooth concurrency changes. | 动态可调的信号量，控制并发任务数，支持并发量的平滑调整。 |
| **IpGenerator**<br>IP 生成器 | Implements a **pure‑integer classification pipeline** – constructs 32‑bit IPs from random octets, evaluates against **pre‑compiled hexadecimal constants** via bitwise predicates, and returns the string representation **only upon successful validation**. The entire hot path operates on the stack with **zero heap allocations**, making it a **GC‑neutral** component that sustains **millions of iterations per second** without introducing observable latency variance. | 实现 **纯整数分类流水线** – 由随机字节直接构造 32 位 IP，通过 **预编译十六进制常量** 与位运算谓词进行判定，**仅在验证通过后**才返回字符串表示。整个热路径完全在栈上运行，**零堆内存分配**，是一个 **GC 中立** 的组件，可维持 **每秒数百万次迭代** 且不引入可观测的延迟波动。 |
| **MinecraftPinger**<br>Minecraft 探针 | Performs the Minecraft server handshake across multiple protocol versions (1.20.1, 1.12.2, 1.8.9) to verify server presence. | 跨多个协议版本（1.20.1、1.12.2、1.8.9）执行 Minecraft 服务器握手，验证服务器存在性。 |
| **PortChecker**<br>端口检查器 | Tests TCP port availability using a standard socket connection, recording timeouts and network errors for monitoring. | 使用标准 Socket 连接测试 TCP 端口可用性，记录超时与网络错误以供监控。 |
| **Config**<br>配置对象 | Holds all configurable parameters including scanning, adaptive tuning, and deduplication settings, serialized to/from JSON. | 持有所有可配置参数，包括扫描、自适应调优与去重设置，支持 JSON 序列化与反序列化。 |
| **Main**<br>主程序 | Entry point that orchestrates configuration loading, interactive setup, and lifecycle management of all components. | 程序入口，负责配置加载、交互式参数设置以及所有组件的生命周期管理。 |
## ✦ Configuration Details / 配置详解

Key fields in `scanner_config.json`:  
`scanner_config.json` 核心字段：

| Field / 字段 | Description / 说明 |
|---------------|-------------------|
| `total` | Total IPs to scan; `-1` means continuous scanning / 扫描 IP 总数，`-1` 表示持续扫描 |
| `concurrency` | Initial concurrency level / 初始并发数 |
| `timeout` | TCP connection timeout (seconds) / TCP 连接超时（秒） |
| `port` | Target port / 目标端口 |
| `useProxy` | Enable SOCKS5 proxy pool / 是否使用代理池 |
| `adaptive` | Enable adaptive concurrency / 是否启用自适应并发 |
| `enableFileDedup` | Periodically deduplicate output file by IP / 是否定期对输出文件去重（基于 IP） |
| `cachedBestConcurrency` | Best concurrency from previous runs (auto‑saved) / 历史最优并发（自动保存） |

For a complete list, refer to the comments in `Config.java`.  
完整参数请参考 `Config.java` 中的注释。


## ✦ IP Generation / IP 生成引擎

The `IpGenerator` module is a masterpiece of **low‑overhead systems design**:

`IpGenerator` 模块是 **低开销系统设计** 的典范之作：

- **Hexadecimal Bitmask Classification** – Uses pre‑computed `0xFF000000`, `0xFFFF0000`, `0xFFF00000`, and `0xF0000000` masks to evaluate IP ranges against 15 reserved blocks in a single branch chain.  
  **十六进制位掩码分类** – 使用预计算的 `0xFF000000`、`0xFFFF0000`、`0xFFF00000` 与 `0xF0000000` 掩码，在单一分支链中完成对 15 个保留地址段的判定。

- **Ternary Bitwise Operations** – Employs `(ip & MASK) == CONSTANT` predicates with short‑circuit evaluation, achieving **O(1) constant‑time** classification with no loops or hash lookups.  
  **三目位运算** – 采用 `(ip & MASK) == CONSTANT` 谓词与短路求值，实现 **O(1) 常数时间** 分类，无循环、无哈希查找。

- **Zero‑Allocation Random Generation** – Directly constructs the 32‑bit integer from four random octets, defers string conversion only after successful validation, eliminating GC pressure from discarded private IP strings.  
  **零分配随机生成** – 直接由四个随机字节构造 32 位整数，仅在验证通过后才进行字符串转换，彻底消除私有 IP 字符串废弃带来的 GC 压力。

- **Sub‑Microsecond Latency** – The entire generation + validation pipeline completes in **< 1 µs** on modern hardware, enabling **millions of IPs per second** at full concurrency.  
  **亚微秒级延迟** – 完整的生成 + 验证流水线在现代硬件上完成时间 **< 1 微秒**，全并发下可实现 **每秒数百万 IP** 的生成速度。

> 🚀 This design transforms what is typically a hot‑path bottleneck into a near‑free operation, allowing the scanner to saturate network bandwidth rather than CPU.  
> 🚀 这一设计将通常的热路径瓶颈转化为近乎零开销的操作，使扫描器能够饱和网络带宽而非 CPU。

## ✦ Adaptive Concurrency Algorithm / 自适应并发算法

1. **Exponential Coarse Sweep / 指数下降粗扫**  
   Starts from the maximum concurrency, decreases by `expFactor` each step, tests about 12 points, and selects two best candidates.  
   从最大并发开始，以 `expFactor` 衰减，测试 12 个点，选出两个最优候选。

2. **Golden‑Section Fine Tuning / 黄金分割细扫**  
   Constructs an interval around the two best points and uses golden‑section search (up to 25 iterations) to pinpoint the optimal concurrency.  
   在最优两点附近构建区间，用黄金分割法迭代 25 次，精确定位最佳并发。

3. **Lock & Validate / 锁定与验证**  
   Locks onto the best concurrency, then performs a three‑point validation every 10 minutes; if performance degrades, it restarts the search.  
   锁定最佳并发，每 10 分钟用三点采样验证，若性能下降则重新触发搜索。

The evaluation metric is `throughput × discovery rate`; if no servers are found, a penalty is applied to discourage idle high concurrency.  
测试指标 = `吞吐量 × 发现速率`，无发现时给予惩罚，避免高并发空转。

## ✦ Proxy Pool Mechanics / 代理池机制

- **Initial Fill / 初始填充**: Fetches SOCKS5 proxies from `proxy.scdn.io` and a public GitHub repository.  
  从 `proxy.scdn.io` 和 GitHub 公共仓库获取 SOCKS5 代理。

- **Fission Refill / 裂变补货**: When the pool size drops below a threshold, it uses existing proxies to request fresh ones from the API, creating a fission effect.  
  当池中代理数低于阈值时，使用现有代理向 API 请求新代理，形成裂变。

- **Lazy Validation / 惰性验证**: Proxies are validated only when used; valid ones are returned to the pool, invalid ones are discarded.  
  代理仅在实际使用中验证，有效则归还，无效自动丢弃。

- **Capacity Limit / 容量上限**: Pool size is capped at 1500 to prevent memory bloat.  
  池大小上限 1500，避免内存膨胀。

## ✦ Output & Deduplication / 输出与去重

- Discovered servers are appended to `found_servers.txt` in `IP:port` format.  
  发现的服务器以 `IP:端口` 格式追加至 `found_servers.txt`。

- Every 10 minutes (configurable), the file is deduplicated by IP – only the first occurrence is kept.  
  每 10 分钟（可配置）自动去重，同一 IP 只保留首次记录。

- On program exit, the current best concurrency is saved, allowing faster convergence on the next start.  
  程序退出时自动保存当前最优并发值，下次启动可快速收敛。

## ✦ Quick Start / 快速开始

### Requirements / 环境要求
- **Java 21+** (virtual thread support / 虚拟线程支持)
- Recommended: 2 GB+ RAM, outbound bandwidth ≥ 10 Mbps  
  建议 2GB 以上内存，公网出口带宽 ≥ 10 Mbps


## ✦ Important Notes / 注意事项

> [!CAUTION]
> **Legal Use / 合法使用**：Only scan IP ranges you own or have explicit permission to probe. Unauthorised scanning may violate local laws and terms of service.  
> **请仅扫描您拥有授权或属于公开范围的 IP 段。未经许可的扫描可能违反当地法律。**

> [!WARNING]
> **Network Load / 网络负载**：High concurrency may trigger rate‑limiting on the target network or proxy providers; adjust concurrency and timeout accordingly.  
> **高并发可能触发目标网络或代理商的流量限制，建议合理设置并发与超时。**

> [!NOTE]
> **Proxy Quality / 代理质量**：Free proxy pools vary in stability and anonymity; for production use, consider private proxy services.  
> **免费代理池的稳定性和匿名性参差不齐，生产环境建议使用私有代理服务。**

> [!NOTE]
> **Memory / 内存占用**：Both queues and proxy pool are capped, but significantly increasing concurrency may still consume more memory – tune `maxConcurrency` based on your hardware.  
> **队列与代理池均设有上限，但大幅调高并发时仍可能消耗较多内存，请根据机器配置调整 `maxConcurrency` 等参数。**

---

## ✦ CI/CD Pipeline / 持续集成与交付

This project leverages **GitHub Actions** for automated builds, testing, and releases.  
本项目使用 **GitHub Actions** 实现自动化构建、测试与发布。

| Workflow / 工作流 | Trigger / 触发条件 | Purpose / 目的 |
|-------------------|-------------------|----------------|
| **PR Validation** | Pull Request to `main` | Compile, test, and validate code quality / 编译、测试与代码质量验证 |
| **Release Build** | Push to `main` or tag push | Build JAR and attach to release / 构建 JAR 并关联至 Release |


## ✦ Customisation & Extensions / 扩展与定制

- **Add a protocol version / 增加协议版本**: Append a new protocol number to `MinecraftPinger.PROTOCOL_VERSIONS`.  
  在 `MinecraftPinger.PROTOCOL_VERSIONS` 中添加新的协议号。

- **Change proxy sources / 更换代理源**: Modify `API_URL` and `GITHUB_PROXY_URL` in `ProxyManager`.  
  修改 `ProxyManager` 中的 `API_URL` 和 `GITHUB_PROXY_URL`。

- **Alter deduplication logic / 调整去重策略**: Modify `ResultConsumer.deduplicateFile()` to change the deduplication key (currently IP).  
  修改 `ResultConsumer.deduplicateFile()` 中的去重键（当前为 IP）。

## ✦ License / 许可

This project is licensed under the **GNU Lesser General Public License v2.1** – see the [LICENSE](LICENSE) file for details.  
本项目采用 **GNU Lesser General Public License v2.1** 协议 – 详情请见 [LICENSE](LICENSE) 文件。

---

**Elegantly explore every corner of the blocky universe.** 🚀  
**优雅地探索方块世界的每一处角落。** 🚀