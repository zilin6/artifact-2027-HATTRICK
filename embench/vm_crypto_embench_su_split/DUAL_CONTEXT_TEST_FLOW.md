# `vm_crypto_embench_su_split` 当前测试流程与后续双 U 改造说明

## 1. 当前版本的目标

当前版本的测试目标是：

- 用严格隔离的两态执行流，验证前端地址加密切换下的预测器共享问题。
- 保证：
  - `S-mode` 和 `U-mode` 不共享运行时代码/数据副本。
  - 只有 `M-mode` 负责调度、切换和最终打印。
  - trap 入口和返回处的自定义 CSR 开关顺序严格受控。
- 现阶段已经跑通 `st`，并且在仿真里通过了“中途 ecall 切换上下文”的版本。

## 2. 当前版本的软件结构

### 2.1 运行上下文

当前版本维护两份被 `M-mode` 调度的上下文：

- `U-mode` 加密虚拟地址上下文
  - 使用 `vm_enc_root_page_table`
  - 使用当前的加密运行时映像
  - trapframe 保存在 `vm_su_u_trapframe`
- `S-mode` 明文虚拟地址上下文
  - 使用 `s_plain_root_page_table`
  - 使用完整拷贝出来的明文运行时映像、栈和运行时 BSS
  - trapframe 保存在 `vm_su_s_trapframe`

### 2.2 关键文件

- `embench-iot/config/riscv32/boards/ri5cyverilator/start.S`
  - 启动路径
  - 全局 trap handler
  - trapframe 保存/恢复
  - `M-mode` 到 `U/S-mode` 的切换入口
- `embench-iot/config/riscv32/boards/ri5cyverilator/boardsupport.c`
  - 页表初始化
  - 明文副本构造
  - trap 之后的 `M-mode` 调度逻辑
- `embench-iot/src/st/libst.c`
  - `st` 主测试体
  - 测试中途插入的模式切换 `ecall`
- `embench-iot/support/support.h`
  - 自定义 `ecall` ID 定义

## 3. 当前版本的启动流程

### 3.1 `M-mode` 启动

`_start` 目前会做这些事：

1. 清零寄存器，建立 `gp/tp/sp`
2. 建立专用 `M-mode trap stack`，写入 `mscratch`
3. 安装全局 `trap_entry`
4. 初始化板级环境和 benchmark
5. 调用 `vm_prepare_s_plain_runtime(tp, sp)`，准备明文 `S-mode` 副本
6. 继续执行现有 `U-mode` 加密页表构造和加密初始化复制流程
7. 切换到第一个 `U-mode` 上下文执行 `embench_cryptoexec_main`

### 3.2 `S-mode` 副本准备

`vm_prepare_s_plain_runtime()` 当前会：

- 从当前运行时物理镜像完整拷贝一份代码到 `S_PLAIN_IMAGE_PA_BASE`
- 拷贝当前栈到 `S_PLAIN_STACK_PA_BASE`
- 拷贝 runtime BSS 到 `S_PLAIN_RUNTIME_BSS_PA_BASE`
- 单独构造一份普通页表 `s_plain_root_page_table`
- 为这份明文上下文预填 `vm_su_s_trapframe`

这里的关键点是：

- 明文上下文的代码、栈、BSS 都是独立副本。
- 它不复用 `U-mode` 页表构造函数。

## 4. 当前版本的 trap 和切换规则

### 4.1 全局 trap 入口硬规则

`trap_entry` 入口的顺序当前是严格固定的：

1. **先** `csrwi/csrw` 关闭所有 custom crypto CSR
2. **再** 切到 `mscratch` 指向的 `M-mode trap stack`
3. **再** 保存完整 trapframe

这个顺序不能改。原因是：

- 一旦先动栈、先存寄存器，再关 custom CSR，就可能让 trap 保存路径本身落在错误的加密/解密语义下。

### 4.2 `VM_CUSTOM_ECALL_ID`

这是“纯 roundtrip ecall”：

- 用于验证 trap 保存/恢复与 CSR 开关顺序本身
- 不做模式切换
- 返回原上下文继续执行

其返回路径要求：

1. 先恢复 trapframe
2. 最后一步才重新打开用户态 custom CSR
3. 然后 `mret`

即：

- `trapframe restore`
- `csrwi DATA_CONTROL_CSR, ...`
- `csrwi FETCH_CONTROL_CSR, ...`
- `mret`

### 4.3 `VM_MODE_SWITCH_ECALL_ID`

这是“中途切换上下文”的专用 ecall：

- `U-mode` 触发时：
  - 保存 `vm_su_u_trapframe`
  - `mepc += 4`
  - 切换去 `S-mode`
- `S-mode` 触发时：
  - 保存 `vm_su_s_trapframe`
  - `mepc += 4`
  - 切回 `U-mode`

这个 ID 必须和 `VM_CUSTOM_ECALL_ID` 保持分离，不能复用。

## 5. 当前 `st` 测试点

`st` 当前在 `benchmark_body()` 中间插入了一次内联汇编 `ecall`：

- 不是函数调用形式
- 只触发一次
- 使用 `VM_MODE_SWITCH_ECALL_ID`

作用是：

- 让正在运行的第一个上下文执行到中间时陷入 `M-mode`
- 由 `M-mode` 调度另一个上下文继续跑同一份测试

## 6. 当前版本已经验证过的点

### 6.1 仿真可跑通

当前版本已经完成：

- 软件重新构建成功
- RTL 重新构建成功
- `st` 在 SmallBoomV3 仿真下跑通

已知通过的命令：

- `source /path/to/chipyard/env.sh && ./build.sh`
- `source /path/to/chipyard/env.sh && python3 ./run_single_smallboomv3_bench_cycles.py --skip-build --timeout-cycles 5000000 --log-path run-logs-su-split/st-dual-trapframe-mode-switch.full.log st`

结果要点：

- `STATUS=success`
- `BENCH_CYCLES=9962`
- `TOTAL_SIM_CYCLES=848066`

### 6.2 RAS 观察结论

基于现有 frontend/RAS 日志，当前可得结论是：

- `RAS` 在不同特权级之间看起来是共享的，没有在 mode switch 时被硬件自动清空。
- 日志里可以看到来自不同模式地址形态的 `RAS write`。
- 但在当前这版通过测试中，还**没有**观察到“`S-mode` 污染 `U-mode` 返回地址预测，最终导致错误返回”的实际失败。

这个结论只能说明：

- “当前测试形态下没有触发错误”，不能说明“硬件一定不存在共享问题”。

## 7. 当前版本最容易改坏的地方

### 7.1 trapframe 槽位和汇编顺序

`boardsupport.c` 中 trapframe 槽位定义必须与 `start.S` 的保存/恢复顺序严格一致：

- `SP=2`
- `GP=3`
- `TP=4`
- `MEPC=32`
- `MSTATUS=35`

只要 C 和汇编有一个地方偏了，恢复回来就会出现：

- 返回地址错
- 栈错
- `gp/tp` 错
- `mstatus` 错

### 7.2 `mepc += 4` 不能漏

对于 `ecall` 路径，保存到目标 trapframe 前必须把 `mepc` 前移 4 字节。

否则恢复后会重复执行同一条 `ecall`，直接卡死或来回切换。

### 7.3 custom CSR 的顺序不能改

当前硬规则是：

- trap 入口第一件事：关闭 custom CSR
- 用户态返回最后一件事：打开 custom CSR

如果把“打开 custom CSR”放到恢复 trapframe 之前，就可能导致：

- 恢复栈/返回地址时已经处于错误的数据/地址加密语义
- 进一步破坏用户态继续执行

### 7.4 `mscratch` 不能再指回运行时栈

之前已经踩过一次：

- 如果 `M-mode trap stack` 和用户运行时栈物理重叠，trap 过程中会把用户栈覆盖掉。

现在必须保持：

- `mscratch` 始终指向专用 `M-mode trap stack`

### 7.5 不要混淆两类 ecall

必须保持：

- `VM_CUSTOM_ECALL_ID`：纯 roundtrip，不切模式
- `VM_MODE_SWITCH_ECALL_ID`：保存上下文并切到另一侧
- `VM_EXIT_ECALL_ID`：benchmark 完成后的退出/汇总

这三条路径不能混用。

## 8. 下一步改造目标：从 `U+S` 改成 `U+U`

后续不再让第二个测试体跑在 `S-mode`，而是改成：

- 两个都跑在 `U-mode`
- 两个都走“加密虚拟地址 + 加密初始化复制”的用户进程启动流程
- 但它们必须拥有**不同的 key 上下文**

目标是验证：

- 在两个不同 `U-mode` 进程之间切换前端地址加密 key 时
- 共享前端/BTB/RAS 等预测状态是否会带来错误行为

## 9. 下一步改造要求

### 9.1 两块全局 key 存储区

需要新增两块全局空间：

- 进程 1 key 存储区
- 进程 2 key 存储区

每个进程都需要保存：

- `pointer_key`：256 bits
- `data_key`：256 bits

这里按你的要求，文档先按“每个 key 256 bits”记录；实际落地时会再核对当前硬件软件接口一次，确保和现有 `gen_key/load_key/store_key` 指令语义一致。

### 9.2 第一个 `U-mode` 进程初始化

现有用户进程启动流程要改成：

1. 先调用 `gen_key`
2. 为进程 1 生成 `pointer_key` 和 `data_key`
3. 用 `store_key` 把这组 key 保存到“进程 1 全局 key 空间”
4. 然后继续执行现有代码：
   - 构造加密页表
   - 加密初始化复制
   - 进入第一个 `U-mode` 进程

### 9.3 第二个上下文初始化

当前 `S-mode` 初始化逻辑要整体改造成“第二个 `U-mode` 进程初始化”，差异点是：

1. 也先调用 `gen_key`
2. 生成与进程 1 不同的一组 `data_key/pointer_key`
3. 用 `store_key` 保存到“进程 2 全局 key 空间”
4. 然后执行与第一个 `U-mode` 进程同类的初始化：
   - 构造自己的加密页表
   - 做自己的加密初始化复制
   - 准备自己的 trapframe

要求是：

- 两个进程都走用户态加密流程
- 不能再依赖当前 `S-mode` 明文页表/明文镜像路径

### 9.4 调度时显式切 key

无论是首次进入进程 1，还是 trap 中进程切换，都必须：

1. 根据目标进程选择对应 key 存储区
2. 用 `load_key` 把目标进程的 `data_key` 和 `pointer_key` 重新装入硬件
3. 再切换 `satp`、trapframe 和 custom CSR

也就是说，后续全局 trap handler 在“切换到另一个进程运行”时，除了切：

- trapframe
- `satp`
- `mstatus`
- custom CSR

还必须显式切：

- `data_key`
- `pointer_key`

这一步不能省略。否则即使两个 `U-mode` 进程页表分离，也仍然会共享同一套硬件 key 上下文，测试意义就不成立。

## 10. 后续实现时的额外风险点

### 10.1 key 与地址空间必须一一对应

后续改成双 `U-mode` 后，必须把这些东西当成一个不可拆分的上下文：

- `satp`
- `mstatus`
- `gp/tp/sp`
- trapframe
- custom CSR 开关状态
- `data_key`
- `pointer_key`

其中任何一项漏切，都会形成“地址空间 A + key B”的错配。

### 10.2 key 切换顺序不能破坏 trap 硬规则

trap 入口“先关 custom CSR 再保存 trapframe”的顺序仍然必须保持。

新增 `load_key` 之后，也不能把它随意插到会破坏这个顺序的位置。特别是：

- 不能在 trapframe 尚未稳定保存前修改用户进程 key 上下文
- 不能在用户态 trapframe 尚未恢复完成前过早打开用户态 custom CSR

### 10.3 第二个 `U-mode` 不能偷用第一个 `U-mode` 的页表池/镜像副本

如果后续只是“逻辑上第二个进程”，但实际上：

- 页表复用
- 代码/数据副本复用
- key 单独切换

那就无法判断问题来自：

- 预测器共享
- 还是地址空间/加密状态未真正隔离

因此第二个 `U-mode` 必须有自己完整的：

- 运行时镜像副本
- 栈副本
- 运行时 BSS 副本
- 页表
- trapframe
- key 存储区

## 11. 建议的实现顺序

建议按下面顺序做，便于定位问题：

1. 先把本文档对应的当前实现状态固化
2. 新增“进程 1 / 进程 2 key 存储区”与 `gen_key/store_key/load_key` 封装
3. 把当前 `S-mode` 明文副本初始化替换成“第二个 `U-mode` 加密副本初始化”
4. 把 trap 调度路径从 `U<->S` 改成 `U1<->U2`
5. 在首次进入和每次切换时显式 `load_key`
6. 重新跑 `st`
7. 再根据日志分析 RAS/BTB 是否出现跨进程污染

