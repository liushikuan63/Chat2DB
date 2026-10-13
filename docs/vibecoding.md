# Vibecoding 未合并代码审查

## 状态与范围

- 状态：静态审查已完成（问题清单 / 历史分支缺陷 / 覆盖台账 / 结论），并已完成 14 条当前栈问题的代码修复与测试验证（见“修复记录”）。历史分支缺陷 HB-01~HB-09 未修复，因为这些实现不在当前代码树中。
- 用户确认：审查全部本地与 `origin` 未合并到 `main` 的分支及当前工作区；不纳入 `upstream` 独立开发分支；新建本文件；随后授权修复当前栈的 14 条问题；全程不提交、不推送。
- 采样日期：2026-10-05（Asia/Shanghai）。本地 `main` / `upstream/main`：`dc2b80a3b0189bb3fd92d69ce8f52de2d4136ac8`；本地 `origin/main`：`a8e78dd5e8b8b05485563c6f19a9b50635adf032`，比 `main` 少 243 个提交。以较新的本地 `main` 为唯一基线，不把主分支已接纳的代码重复报成新增问题。
- 当前分支：`codex/fn-14-repo-hygiene`；HEAD：`a14e73edefe7015c2113e51b836ce3a60170a12f`；相对 `main` 超前 16 个提交、落后 0 个；累计 234 个文件、35,235 行新增、3,447 行删除。
- 范围盘点：108 个未合并引用（本地 71、`origin` 37），91 个唯一 tip，经祖先关系去重为 16 个独立 tip。按模块审阅当前完整功能栈，再核对历史独立 tip 的新增/不同实现；引用覆盖与问题适用分支分开标注。
- 原有工作区变动：`node_modules/.vite/vitest/da39a3ee5e6b4b0d3255bfef95601890afd80709/results.json` 已删除；这是测试缓存，不是本轮操作，保持不动。
- 远程状态边界：本轮使用本地已存在的 Git 对象/远程跟踪引用；未 fetch、未声称与远端实时分支一致。

## 独立分支入口

| tip | 分支（同 tip 的远程别名省略） | 相对 main 提交数 | 累计差异文件数 | 状态 |
|---|---|---:|---:|---|
| `0879a6b98` | `codex/imp-04-terminal-resource-release` | 63 | 224 | 已审（含 VB-010 主体实现） |
| `0df59b5b9` | `codex/import-export-integration` | 6 | 179 | 已审（含 HB-04 MySQL 同族） |
| `2324683c7` | `qoder/import-export-batch5-postgresql` | 10 | 215 | 已审（HB-01~HB-04） |
| `2598e50d7` | `codex/feat-resumable-import-export-pipeline` | 75 | 232 | 已审（含 VB-010 主体实现） |
| `28b1ddfbe` | `qoder/import-export-batch2-degradation` | 12 | 144 | 已审；有多个 merge-base（`81cd31e19…` / `fc7b563aa…`），已按双基线显式比对 |
| `36a64158b` | `codex/import-export-rebuilt` | 6 | 179 | 已审（HB-07） |
| `44ee48b03` | `codex/import-06-integration-fixes` | 33 | 290 | 已审；与 `73d980d27` 同 tree |
| `48cb5cc9b` | `codex/import-export-main-sync-20260907` | 7 | 162 | 已审（HB-06） |
| `5492bcb44` | `codex/import-00-prerequisite-base` | 27 | 254 | 已审 |
| `60193a3fb` | `qoder/import-export-batch7-review` | 7 | 350 | 已审（HB-02~HB-05） |
| `73d980d27` | `codex/import-export-25-multi-table-import-wizard` | 30 | 290 | 已审；与 `44ee48b03` 同 tree |
| `9bac916a1` | `codex/rel-p3-sql-export-script` | 24 | 243 | 已审（含 VB-010 主体实现、HB-08） |
| `a14e73ede` | `codex/fn-14-repo-hygiene`（当前 HEAD） | 16 | 234 | 已审（VB-001~VB-014 的适用条目） |
| `a95efda0a` | `codex/import-export-upstream-integration` | 23 | 230 | 已审（CSV 走受控 parser，与 HEAD reader 差异已区分） |
| `afc9a945c` | `codex/backup-accidental-pr2-merge-20260905` | 2 | 56 | 已审；相对自身基线在前端/运行时/SPI 范围改动极少 |
| `babd6babd` | `codex/import-export-preview-sync-20260908` | 7 | 172 | 已审 |

## 已执行与未执行（方法层记录）

- 已执行：工作区与引用盘点、`git rev-list --left-right --count main...HEAD`（`0 16`）、`git rev-list --left-right --count origin/main...main`（`0 243`）、`git merge-base --independent`（16 个独立 tip）、当前功能栈差异统计。
- CodeGraph 工具不可用：使用定向文件读取、内容检索和 Git 对象比对；不创建索引。
- 环境：PATH 中 `java` 指向 Java 8；另已通过绝对路径验证本机 `D:\Java\jdk\microsoft-jdk-17\bin\java.exe` 为 OpenJDK `17.0.12`。本轮尚未运行 Maven；原因是只读审查不执行会改写产物的构建，而非缺少 Java 17。
- 前端依赖：当前工作区未找到 `node_modules/.bin/tsx.cmd` / `ts-node.cmd`，不自动安装依赖或把缺失工具误报为业务缺陷。
- 去重补充：所有自有未合并引用合计 256 个唯一未合并提交；`44ee48b03` 与 `73d980d27` 根 tree 同为 `624f45a262cea8008caac31e4ff00fa7070ee83d`，完整代码只需审一次。
- 未执行：构建、服务启动、数据库写入、浏览器/桌面实跑、安装包验证、远程更新、提交、推送或发布。只读审查不等于运行验收。
- 过程说明：本轮派出多个只读审查代理；其中三个在完成前因平台中断退出，其阶段性结论已按证据强度分别标注（含一条明确标记“未复核”）。被中断的代理结论不按已验证计入。

## 方法与完成判据

1. 固定基线与引用快照；祖先重复、内容重复去重，保留分支专属缺陷的版本归属。
2. 覆盖前端上传/导入向导、Web 合约、SQL/CSV/分片/staging、任务调度/暂停恢复/幂等、持久化/迁移、SPI/方言、资源释放和测试门禁。
3. 只列具有完整触发路径的缺陷；每条带严重性、适用提交、文件行号、影响、修复方向和可失败验证方法。推测与环境缺口单独列示，不冒充复现。
4. 收尾核验链接与代码证据、`git diff --check`、最终 Git 状态；本轮仅允许本文件新增/更新。

## 问题清单（持续核验中）

> P1：合并前应修复的数据安全/核心流程缺陷；P2：明确功能或恢复契约缺陷。下列“静态确认”表示已经核对源代码与调用链，不表示运行测试通过。历史分支适用范围在覆盖复核后补齐。

### VB-001 · P1 · H2 首次迁移绕过旧存储的崩溃恢复，可能把成功任务改判失败并删除导出文件

- **当前适用**：`a14e73ede`；后端任务存储升级。
- **位置**：[TaskStorageMigrator.java:59–68](../chat2db-community-server/chat2db-community-storage/src/main/java/ai/chat2db/community/storage/task/TaskStorageMigrator.java#L59-L68)、[87–101](../chat2db-community-server/chat2db-community-storage/src/main/java/ai/chat2db/community/storage/task/TaskStorageMigrator.java#L87-L101)。
- **触发**：旧 file 存储在写入 `*-transition.json` / 追加成功事件后、更新 task snapshot 前退出，然后首次启动默认 H2 存储。也包括事件文件暂时处于 `.deleting` 状态的中断删除。
- **证据与影响**：迁移器直接读索引、snapshot 和普通事件文件，不构造旧存储、也不重放 journal；旧实现构造时原本会执行 [recoverStagedEventDeletions / recoverTransitions](../chat2db-community-server/chat2db-community-storage/src/main/java/ai/chat2db/community/storage/large/FileTaskStorage.java#L79-L83)，并在 [commitTransition](../chat2db-community-server/chat2db-community-storage/src/main/java/ai/chat2db/community/storage/large/FileTaskStorage.java#L551-L570) 保证事件/snapshot 收敛。因此可恢复的成功任务被迁入为 RUNNING；[启动恢复](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/LocalTaskManager.java#L99-L123) 随后标为 `APPLICATION_TERMINATED`，根据发布事件清理已成功导出的文件。迁移完成标记和目录改名使下一次启动不再重试旧 journal。
- **修复方向**：在写迁移完成标记前，按旧存储恢复协议得到一致性快照（或在迁移事务内等价重放所有恢复记录）；恢复失败不得退休旧目录。
- **可失败验证**：构造 RUNNING snapshot + SUCCESS transition/event + 已发布 fixture 文件，首次迁移后必须为 SUCCESS 且文件保留；再覆盖中断 create、`.deleting`、恢复失败不写 marker。
- **验证状态**：静态调用链确认；未运行崩溃注入。现有 [TaskStorageMigrationTest.java:55–109](../chat2db-community-server/chat2db-community-storage/src/test/java/ai/chat2db/community/storage/task/TaskStorageMigrationTest.java#L55-L109) 只覆盖干净 snapshot 与截断事件尾行，未覆盖恢复 journal。

### VB-002 · P1 · file → H2 迁移丢失 resumeStates，断点任务在启动时被终止

- **当前适用**：`a14e73ede`；先以 `-Dchat2db.task.storage=file` 使用断点导入，再首次切回默认 H2 的场景。
- **位置**：[TaskStorageMigrator.java:159–176](../chat2db-community-server/chat2db-community-storage/src/main/java/ai/chat2db/community/storage/task/TaskStorageMigrator.java#L159-L176)。
- **证据与影响**：file 存储将 checkpoint 写在 [Task.resumeStates](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-api/src/main/java/ai/chat2db/community/domain/api/model/task/Task.java#L57-L62)；迁移循环仅复制 task、event、artifact、manifest，没有向 `resume_state` 插入任何记录；[TaskRows.bindTask](../chat2db-community-server/chat2db-community-storage/src/main/java/ai/chat2db/community/storage/task/TaskRows.java#L167-L195) 也不保存该字段。H2 的 [listResumableTasks](../chat2db-community-server/chat2db-community-storage/src/main/java/ai/chat2db/community/storage/task/H2TaskStorage.java#L292-L297) 依赖该表，故原本可恢复的任务不在集合中，启动时被判失败，并进入暂存资源清理。此项不是“旧主分支已有断点状态”的假设，而是新增 file 回退路径与新增 H2 迁移之间的兼容缺陷。
- **修复方向**：同一迁移事务内迁移全部 shard 的 kind/cursor/rows/bytes/timestamp，校验 task/shard 身份，验证完成前不退休旧目录。
- **可失败验证**：file fixture 保存至少 2 个不同 shard checkpoint，迁移后逐字段相等、`listResumableTasks` 仍包含原 task、启动后可以恢复且不重复已提交数据。
- **验证状态**：静态确认；未运行 H2 round-trip。现有迁移测试没有调用 `saveResumeState`。

### VB-003 · P1 · 导出发布删除了防覆盖重试，目标文件竞争时不再保留已有文件

- **当前适用**：`a14e73ede`；导出文件发布及删除失败恢复，跨平台文件系统。
- **位置**：[ArtifactServiceImpl.java:90–95](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/ArtifactServiceImpl.java#L90-L95)、[250–268](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/ArtifactServiceImpl.java#L250-L268)、[293–298](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/ArtifactServiceImpl.java#L293-L298)。
- **触发**：`createDraft` 选定尚不存在的 `export.csv` 后，用户或另一进程创建同名文件，然后任务 publish；内存 `reservedTargets` 无法锁住其他进程。
- **证据与影响**：`main` 以 `CREATE_NEW` 原子占用目标，并捕获 `FileAlreadyExistsException` 重选名字；当前代码改为 `Files.move(..., ATOMIC_MOVE)`，且仅捕获 `AtomicMoveNotSupportedException`。目标已存在时行为依赖文件系统实现，可能覆盖已有文件，或使原本可换名继续的导出失败；同一 move helper 也用于删除失败后的恢复。不能把“预先 Files.exists 检查”当作不覆盖保证。
- **修复方向**：恢复原子 no-clobber 占位与碰撞重试，或使用明确保证不替换目标的发布协议；恢复删除也不能覆盖期间新建的同名文件。
- **可失败验证**：draft 创建后写入不同内容的同名 fixture，再发布；必须保留 fixture 且另取新名字。Linux/macOS/Windows 各运行一次，并覆盖两个独立服务实例及 dangling symlink。
- **验证状态**：Git 回归与竞争窗口静态确认；未运行各平台文件系统用例，不能宣称 Windows 已实测覆盖。

### VB-004 · P2 · 任务语句守卫丢失线程上下文，注册扩展守卫后并行导入失败

- **当前适用**：`a14e73ede`；存在至少一个 `ITaskExecutionGuard` 的并行导入，不影响守卫列表为空时的默认路径。
- **位置**：[TaskRunner.java:73–76](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/TaskRunner.java#L73-L76)。
- **证据与影响**：`main` 绑定 `taskExtensionManager.captureStatementGuard()`，当前改为 `taskExtensionManager::beforeStatement`。后者在调用线程读取 [currentTask ThreadLocal](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/extension/TaskExtensionManager.java#L45-L60)；[ImportRowBatcher](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/imports/ImportRowBatcher.java#L238) 只复制这个 Consumer 到 worker，而不是原 task context。worker 上下文为空时抛 `Task statement guard requires an active task context`，合法并行导入失败。
- **修复方向**：恢复提交线程的不可变 context capture，确保每类 worker 都继承同一守卫；不要通过取消守卫绕过错误。
- **可失败验证**：用真实 TaskRunner 启动多 worker 导入，注册能校验 taskId 并计数的守卫，断言所有写入之前执行守卫且 context 非空；禁止测试直接手工绑定正确 lambda。
- **验证状态**：静态确认；现有 [ParallelImportLifecycleTest.java:198–200](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/test/java/ai/chat2db/community/domain/core/impl/task/ParallelImportLifecycleTest.java#L198-L200) 恰好手工调用正确的 capture，不能发现运行时接线回归。

### VB-005 · P2 · 文件移动先于持久化发布记录，崩溃留下无法回收的已发布文件

- **当前适用**：`a14e73ede`；导出成功收尾和导入失败诊断产物。
- **位置**：[TaskRunner.java:168–181](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/TaskRunner.java#L168-L181)、[304–313](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/TaskRunner.java#L304-L313)。
- **触发**：publish 完成文件移动之后、`saveArtifact` 写入之前，进程被终止。
- **证据与影响**：当前 TaskRunner 删除了主分支的 `ARTIFACT_PUBLICATION_STARTED` 回调记录；恢复仅从 artifact 表与 PUBLICATION_STARTED/PUBLISHED 事件收集已发布路径（[LocalTaskManager.java:381–398](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/LocalTaskManager.java#L381-L398)），不把 PREPARED 中的目标路径当作已创建文件。崩溃窗口内源 `.part` 已消失、artifact 行和发布事件均不存在，启动/删除任务无法找到该目标文件，留下孤儿产物；有 checkpoint 的导出恢复还可能另建新输出。
- **修复方向**：将已占有目标与待发布事务写入可恢复 journal，再执行移动并标记提交；不得通过直接删除所有预留目标来“修复”，因为可能误删外部新建文件（见 VB-003）。
- **可失败验证**：在 move 与 artifact 持久化之间设置故障点，重启后必须准确收敛发布/回收状态；同时证明未由任务创建的同名文件保留。
- **验证状态**：静态 crash-window 确认；未运行 kill/restart 故障注入。

### VB-006 · P2 · 删除恢复方法丢失 @PostConstruct，持久删除队列不再自动续做

- **当前适用**：`a14e73ede`；应用启动恢复；历史 `44ee48b03` / `73d980d27` 仍保留该注解。
- **位置**：[TaskServiceImpl.java:107–115](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/TaskServiceImpl.java#L107-L115)。
- **触发与影响**：上次删除已写入持久队列后退出，或者文件占用导致删除失败，下一次启动应重试。当前删除了 main 上的 `@PostConstruct`，方法没有生产调用者；[TaskDeletionServiceImpl](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/TaskDeletionServiceImpl.java#L31-L40) 构造器也不重试，已有 `.task-delete-*` 文件与队列长期残留。
- **修复方向**：恢复 Spring 启动钩子或接入等价、明确的生命周期入口。
- **可失败验证**：预置未完成队列，启动真实 Spring context（不是直接调用恢复方法），验证重试次数、存储记录和 staged 文件收敛；加入非任务文件保留对照。
- **验证状态**：生产全仓调用检索与 main diff 静态确认；未启动 Spring。现有 [TaskDeletionServiceImplTest.java](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/test/java/ai/chat2db/community/domain/core/impl/task/TaskDeletionServiceImplTest.java) 手工调用该方法，无法防止生命周期注解丢失。

### VB-007 · P2 · 生产删除路径仍只处理单个 artifactId，多产物/失败诊断文件被遗留

- **当前适用**：`a14e73ede`；任务删除接口；新增多产物导入流程。
- **位置**：[TaskServiceImpl.java:402–404](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/TaskServiceImpl.java#L402-L404)、[PendingTaskDeletion.java:22–31](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/PendingTaskDeletion.java#L22-L31)。
- **触发与影响**：任务拥有 IMPORT_REPORT、REJECT_SUMMARY 等多个 artifact，或失败任务只有 artifact rows。Spring 注入的 `deletionService` 非空，直接走旧单文件队列；队列只看 `task.artifactId`，不会遍历 `Task.artifacts` / `listArtifacts`。[H2 删除](../chat2db-community-server/chat2db-community-storage/src/main/java/ai/chat2db/community/storage/task/H2TaskStorage.java#L244-L252) 却删除全部 artifact 元数据，因此次要产物变为无主文件。失败状态的 [artifactId 被清空](../chat2db-community-server/chat2db-community-storage/src/main/java/ai/chat2db/community/storage/TaskLifecyclePolicy.java#L63-L66)，会使所有诊断文件都漏删。含用户数据的拒绝行文件也可能继续留在磁盘。
- **修复方向**：持久删除协议改为任务全量 artifact 集合，兼容旧 primary 字段；原子记录全部 staging 路径并可逐个重试。
- **可失败验证**：使用真实注入的 TaskDeletionService，创建成功多产物与失败诊断任务，删除后所有文件、artifact rows、队列项均不存在，邻接非任务文件不变。
- **验证状态**：生产调用链静态确认；未运行文件 fixture。新增 [ArtifactServiceTest.java:155–175](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/test/java/ai/chat2db/community/domain/core/impl/task/ArtifactServiceTest.java#L155-L175) 构造的是 `deletionService=null` 的测试分支，该分支全量删除正确，不能证明生产路径正确。

### VB-008 · P1 · CSV/Excel 预览与执行改走不同 reader，已确认的导入选项被静默忽略

- **当前适用**：`a14e73ede`；既有映射导入 API，尤其非默认 sheet、header、行范围、分隔符和数值/日期转换。
- **位置**：[CSVImporter.java:31–48](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/imports/excel/CSVImporter.java#L31-L48)、[BaseExcelImporter.java:39–46](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/imports/excel/BaseExcelImporter.java#L39-L46)。
- **触发与影响**：以 Excel `sheetIndex=1`、受限数据行范围，或 CSV `hasHeader=false` / 非默认 `csvOptions` 做预览后提交。[DbMappedImportServiceImpl.java:51–75](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/db/DbMappedImportServiceImpl.java#L51-L75) 预览和 spec 仍携带原选项；但新执行路径不再调用主分支的受控 CsvImportReader/ExcelImportReader。CSV 只看另一套 `ImportOptions` 并无条件吞第一条记录当 header；Excel 校验选项后固定 `.sheet().headRowNumber(1)`。因此会导入错误 sheet / 越界行，或丢首行；旧值归一化接线也被绕开，预览内容与实际入库不一致。
- **修复方向**：复用同一 reader/规范化协议，明确新旧 options 的兼容映射；不能仅修改预览使错误表现一致。
- **可失败验证**：两个同列名但不同数据的 sheet，选第二个；限制结束行；无 header CSV；非默认 delimiter/decimalSymbol/emptyAsNull/date-format。按真实映射 API 提交，逐值断言实际数据与预览相符。
- **验证状态**：main/current reader 接线及 API 传递静态确认；未对数据库执行导入。

### VB-009 · P1 · 单文件 STANDARD 演练会回落普通导入，rehearsal=true 仍写入目标表

- **当前适用**：`a14e73ede`；CSV 单文件、`tableSources` 为空、`mode=STANDARD`；FAST/ULTRA_FAST 在准入降级后也可能到同一分支。
- **位置**：[DataFileImportTaskExecutor.java:81–109](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/executor/DataFileImportTaskExecutor.java#L81-L109)、[CsvManifestPreparer.java:80–89](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/imports/CsvManifestPreparer.java#L80-L89)。
- **触发与影响**：设置 `rollbackOptions.rehearsal=true`，executor 识别 `stagingRequested` 并调用 preparer；但 preparer 仅看 scoped/parallel，单文件 STANDARD 直接返回 null。executor 随后执行普通 CSV strategy，演练/全量回滚等控制不再生效，写入仍会提交。`FAST` 是 ULTRA_FAST 别名，不能误写成所有 FAST 都直接走此分支。
- **修复方向**：staging/rehearsal/fullRollback 要独立于性能模式决定路由；必要 manifest 生成失败必须 fail closed，不得降级成真实写入。
- **可失败验证**：真实 `execute` 调用，单文件 STANDARD + rehearsal；运行前后目标 fixture 行数/内容必须相同，同时有演练报告。再覆盖小文件准入降级、fullRollback 和验证开关。
- **验证状态**：静态条件链确认；未运行目标库演练。现有 routing 测试仅测 helper，preparer 降级测试未包含 rehearsal。

### VB-010 · P1 · 通用批量 INSERT 接管 SQL BEGIN 事务，使脚本 ROLLBACK 失效

- **当前适用**：`a14e73ede`；未选择 exporter profile 的普通 SQL 文件导入，PostgreSQL 等 SQL BEGIN 不修改 JDBC autoCommit 标志的驱动。
- **位置**：[DefaultSQLExecutor.java:1748–1772](../chat2db-community-server/chat2db-community-spi/src/main/java/ai/chat2db/spi/DefaultSQLExecutor.java#L1748-L1772)。
- **触发**：测试库脚本 `BEGIN; INSERT INTO fixture(id) VALUES (1); ROLLBACK;`，连接的 JDBC `getAutoCommit()` 初始为 true。
- **证据与影响**：[SQLImporter.java:114–121](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/imports/sql/SQLImporter.java#L114-L121) 进入普通 parser；[SyncSqlBatchHandler.java:26–42](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/imports/SyncSqlBatchHandler.java#L26-L42) 将 BEGIN/ROLLBACK 原样执行、INSERT 交给批处理。新增批处理只以 `getAutoCommit` 判断事务所有权，切到 false 并每 500 条主动 commit，提前提交了 SQL BEGIN 内的数据，后续 ROLLBACK 无法撤销。main 的该方法无此内部 commit。已有第三方 data-only 事务支路的安全检查不覆盖此入口。
- **修复方向**：由脚本执行层维护事务边界/所有权，或让已有 SQL 事务内的 batch 不自行提交；不能仅检查 JDBC 布尔状态。
- **可失败验证**：在 PostgreSQL 测试库经普通 SQL 文件入口执行 BEGIN/INSERT/ROLLBACK，断言最终 0 行；对照 BEGIN/INSERT/COMMIT 为 1 行，并覆盖跨 500 条边界。
- **验证状态**：调用链和新增 commit 静态确认；未执行 PostgreSQL 事务用例。

### VB-011 · P1 · 分片失败回滚后磁盘恢复水位仍保留，重试会跳过已回滚的行

- **当前适用**：`a14e73ede`；多表 / schema 级 CSV manifest 导入中任意分片失败并自动重试。
- **位置**：[ImportRowBatcher.java:238–248](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/imports/ImportRowBatcher.java#L238-L248)、[624–651](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/imports/ImportRowBatcher.java#L624-L651)、[1083–1090](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/imports/ImportRowBatcher.java#L1083-L1090)、[303–309](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/imports/ImportRowBatcher.java#L303-L309)。
- **触发与影响**：分片 A 已成功写入若干批（如 500 行），随后某批失败 → [整分片回滚](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/imports/CsvManifestImporter.java#L192-L198) → [重试策略重新执行同分片](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/imports/ImportManifestScheduler.java#L209-L211)。新建的 batcher 通过磁盘 journal 读回水位，把这些**已回滚**的行当作 durable 跳过。
- **根因**：journal 的“durable”语义被偷换。`maybeCheckpoint` 在**批处理成功**时就推进水位，但分片事务直到 [CsvManifestImporter.java:168–182](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/imports/CsvManifestImporter.java#L168-L182) 才 commit；[失败路径写入 `FAILED` 水位并 preserve](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/imports/ImportRowBatcher.java#L1083-L1090)。分片上下文的 [checkpoint 是空实现](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/imports/CsvManifestImporter.java#L489-L490)，无法拦住磁盘 journal。三个水位来源中，只有 journal 这一路缺少事务边界约束。
- **附带影响**：[markCommitted 以文件行数报成功](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/imports/CsvManifestImporter.java#L183-L184)，跳过的行不计入，任务最终报 SUCCESS。
- **修复方向**：journal 水位必须绑定到分片事务的提交结果（commit 后才发布，rollback 后作废），而不是绑定到批处理完成；水位需带 shard 标识。
- **可失败验证**：构造第 2 批必失败的分片（首批成功、失败后自动重试），断言目标行数等于文件行数；人为注入一次 rollback 后重跑同一分片，断言不丢行也不重复。
- **验证状态**：静态因果链确认；未连库复现。

### VB-012 · P2 · 前端任务列表的恢复入口永远不可见（条件互斥）

- **当前适用**：`a14e73ede`；前端 `src/blocks/ImportAndExport/components/TaskCenter`。
- **位置**：[TaskCenter/index.tsx:235–250](../chat2db-community-client/src/blocks/ImportAndExport/components/TaskCenter/index.tsx#L235-L250)、[TaskCenter/index.tsx:156](../chat2db-community-client/src/blocks/ImportAndExport/components/TaskCenter/index.tsx#L156)、[constants/importExport.ts:43–46](../chat2db-community-client/src/constants/importExport.ts#L43-L46)。
- **触发与影响**：应用重启后任务处于 `PENDING` + `stage=RESUMING`。列表把 `PENDING` 计入 [ACTIVE_TASK_STATUSES](../chat2db-community-client/src/constants/importExport.ts#L43-L46)，`isActive` 为真 → 外层 `!isActive` 不渲染操作区；而恢复按钮的显隐条件恰好要求 `PENDING`。两个条件互斥，**恢复按钮永远不会出现**。
- **修复方向**：把“恢复”从“非活动任务”分支移出，按 `stage === 'RESUMING'` 判定可见性；补组件级渲染断言（当前无组件测试，仅有 `taskCenterUtils`/`artifactVisibility` 纯逻辑测试）。
- **可失败验证**：渲染 `PENDING/RESUMING` 任务，断言恢复按钮存在；`PENDING/非 RESUMING`、`RUNNING` 时不出现。
- **验证状态**：条件互斥逻辑已在本会话逐行确认（未运行 UI）。

### VB-013 · P2 · 导出请求的 mode 字段后端未接收，导出极速开关无效

- **当前适用**：`a14e73ede`；前端切换导出极速模式。
- **位置**：[TaskExportRequest.java:40–41](../chat2db-community-server/chat2db-community-web/src/main/java/ai/chat2db/community/web/api/model/request/task/TaskExportRequest.java#L40-L41)、[TaskWebConverter.java:33–50](../chat2db-community-server/chat2db-community-web/src/main/java/ai/chat2db/community/web/api/converter/task/TaskWebConverter.java#L33-L50)、[ExportTaskSpec.java:16–42](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-api/src/main/java/ai/chat2db/community/domain/api/model/task/ExportTaskSpec.java#L16-L42)。
- **触发与影响**：请求 DTO 声明了 `mode`（注释称 `FAST`/`ULTRA_FAST` 选择并行路径），但转换器构造 `ExportTaskSpec` 时未复制该字段，而 `ExportTaskSpec` 本身**也没有 mode 字段**。前端显示极速，实际执行 spec 无此语义 —— 契约两端不一致，属静默降级而非报错。
- **修复方向**：决定 mode 是否属于导出能力；若属于，补 `ExportTaskSpec.mode` + 转换器映射 + 执行侧读取，并在未支持时由服务端显式拒绝，避免“看起来生效”。
- **可失败验证**：提交 `mode=ULTRA_FAST` 的导出请求，断言落库 spec 含该值且执行器走并行分支；未知值断言明确报错。
- **验证状态**：DTO 与 spec 字段缺失已确认；未运行请求链路。

### VB-014 · P2 · JSON 导入丢失既有列映射（前端不再传，后端无对齐默认值）

- **当前适用**：`a14e73ede`；JSON 格式单表导入。
- **位置**：[JSONImporter.java:26–29](../chat2db-community-server/chat2db-community-domain/chat2db-community-domain-core/src/main/java/ai/chat2db/community/domain/core/impl/task/imports/json/JSONImporter.java#L26-L29)。
- **触发与影响**：`spec.getColumnMappings()` 为空时，执行器直接用**目标列名**当 JSON 键去 `record.get(name)`。JSON 源键与目标列名不完全一致（大小写、空格、引号差异）时，取值为 null 并按 NULL 写入，任务仍可能成功。新版向导对 JSON 不再传 `columnMappings`，使该默认路径成为常态；main 的映射面板会显式传映射。
- **修复方向**：执行前做键匹配预检，未匹配的必填列直接失败；或恢复映射传递。不得以“写入 NULL 且任务成功”掩盖数据不匹配。
- **可失败验证**：源 `[{"Name":"Ada"}]`、目标列 `name`，断言任务失败或产生明确 reject 行，而不是成功写入 NULL。
- **验证状态**：执行侧无映射取值的因果链已确认；前端不再传映射由只读审查报告，本会话未逐行复验前端提交路径。

## 修复记录（2026-10-05，14/14 已改）

每条给出改了什么、哪个测试锁住它、实际跑出来的证据。测试命令统一为：

```bash
mvn -B -o -f chat2db-community-server/pom.xml -pl '<module>' -am \
  '-Dmaven.test.skip=false' '-DskipTests=false' '-Dtest=<Tests>' \
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmaven.test.failure.ignore=false' test
```

`JAVA_HOME=D:\Java\jdk\microsoft-jdk-17`（OpenJDK 17.0.12）。PowerShell 下 `-D` 参数必须加引号，否则 `-Dmaven.test.skip=false` 会被拆成非法生命周期阶段。

| 编号 | 修复方式 | 锁定测试 | 证据 |
|---|---|---|---|
| VB-001 | 迁移前先构造一次 `FileTaskStorage`，复用它自己的 transition / 事件删除恢复，再读快照 | `TaskStorageMigrationTest.replaysAPendingTransitionSoACompletedTaskStaysCompleted` | 该类 8 例全绿，退出码 0 |
| VB-002 | 迁移事务内把快照里的 `resumeStates` 逐条写入 `resume_state`（新增 `TaskRows.insertResumeState`） | `TaskStorageMigrationTest.carriesResumeCheckpointsAcrossSoTheTaskStaysResumable` | 同上；断言 shardNo/kind/rowsDone/cursor 与 `listResumableTasks` |
| VB-003 | 发布改为 `Files.createFile` 原子占位 + 冲突换名，替换原来的直接 `Files.move` | `ArtifactServiceTest.publishingNeverOverwritesAFileCreatedAfterTheDraftWasReserved` | `ArtifactServiceTest` 11 例全绿 |
| VB-004 | `TaskRunner` 重新绑定 `taskExtensionManager.captureStatementGuard()`，把 task context 固定捕获进 worker | 既有 `ParallelImportLifecycleTest.taskStatementGuardRunsOnWorkersBeforeAnyWrite` | 5 例全绿 |
| VB-005 | move 之前先写 `ARTIFACT_PUBLICATION_STARTED` 事件（`LocalTaskManager` 本来就收集该事件） | `LocalTaskManagerTest` 的中断产物清理用例 | 23 例全绿 |
| VB-006 | `TaskServiceImpl.recoverInterruptedArtifactDeletions` 恢复 `@PostConstruct` | `TaskDeletionServiceImplTest.springResolvesServicesThroughTheirInterfaces` | 22 例全绿 |
| VB-007 | 删除队列改为一任务多产物：`PendingTaskDeletion` 承载路径集合，同时保留旧单路径字段的读取 | `TaskDeletionServiceImplTest.everyArtifactOfATaskIsRemovedNotOnlyThePrimaryOne` | 同上 |
| VB-008 | `CSVImporter` 接回 `CsvImportReader`（与预览同一实现）；`BaseExcelImporter` 使用 `sheetIndex` / `headerRow` 而不是固定第一 sheet；行号统一按“数据行序号” | `CsvImportReaderContractTest`（5 例）、`ExcelImportSheetContractTest`（1 例，真 xlsx 双表 + H2 落库）、`ParallelImportLifecycleTest`（5 例） | 11 例全绿；`ParallelImportLifecycleTest` 由 7/8 失败变为 0/8 |
| VB-009 | 请求了 staging 能力却没有生成 manifest 时 fail closed，不再回落普通导入 | `DataFileRoutingStagingGuardTest`（5 例） | 5 例全绿 |
| VB-010 | 新增 `SqlTransactionScope` 跟踪脚本级事务；脚本持有事务时改用 `executeJdbcBatchInsert`，不自行 commit | `SqlTransactionScopeTest`（4 例）+ `ImportSqlExecutorScriptTransactionTest`（4 例） | 8 例全绿；后者含红-绿对照 |
| VB-011 | 新增 `TaskExecutionContext.defersRowDurabilityToCommit()`；分片导入声明为 true，batcher 在此模式下不写 journal **也不写存储层水位**，失败时丢弃 journal | `ImportResumeJournalPolicyTest`（5 例）+ `ImportDeferredDurabilityChainTest`（1 例） | 6 例全绿；链路用例含红-绿对照（残留水位 501/1001） |
| VB-012 | 恢复按钮移出 `!isActive` 分支，判定抽到 `isTaskResumable` | `taskCenterUtils.test.ts` 新增 `testResumableTaskIsVisibleEvenThoughPendingCountsAsActive` | `tsx` 退出码 0；eslint `--max-warnings=0` 退出码 0 |
| VB-013 | `ExportTaskSpec` 补 `mode` / `compression` 并在转换器映射；导出没有并行实现时由 `requireSupportedForExport` 显式拒绝，而不是假装生效 | `TaskWebConverterTest` 13 例 | 13 例全绿 |
| VB-014 | JSON 键按大小写不敏感匹配目标列；无法匹配的键直接失败，不再静默写 NULL | `JSONImporterKeyMatchingTest`（3 例） | 3 例全绿 |

### 红-绿验证（新增测试确实能失败）

迁移相关的两条修复做了显式的“关掉修复→变红→恢复”验证：

- 临时在 `TaskStorageMigrator` 的两处修复上加系统属性开关并以 `-Dchat2db.test.skipLegacyRecovery=true -Dchat2db.test.skipResumeMigration=true` 运行 → `replaysAPendingTransitionSoACompletedTaskStaysCompleted` 与 `carriesResumeCheckpointsAcrossSoTheTaskStaysResumable` 同时失败，8 例中 2 失败，退出码 1。
- 移除开关后重跑 → 8 例全绿，退出码 0；源码中已确认无遗留的临时代码（`TEMP-RED-CHECK` 计数为 0）。

后续补做的两组同样做了“关掉修复→变红→恢复”（注意：这些运行都必须带 `-Dmaven.compiler.useIncrementalCompilation=false`，否则一次改动可能落在陈旧 class 上，得到假绿）：

- **VB-010**：把 `ImportSqlExecutor.flushInserts` 的条件临时改成恒定走旧的 `executeBatchInsert` → `ImportSqlExecutorScriptTransactionTest` 4 例中 1 失败（`aScriptTransactionStopsTheExecutorFromCommittingItsInsertBatch`），退出码 1；恢复后 4 例全绿。
- **VB-011**：临时去掉 `ImportRowBatcher.maybeCheckpoint` 的 `!defersRowDurabilityToCommit` 守卫 → `ImportDeferredDurabilityChainTest` 失败，报错原文给出残留水位 `rowsDone=501` 与 `rowsDone=1001`，正是会让重试跳行的条件；恢复后该例与另外 5 个导入测试类共 15 例全绿。

### 端到端链路用例（VB-010 / VB-011）

- `ImportSqlExecutorScriptTransactionTest`（4 例）：真实 H2 + 记录型 JDBC 代理。脚本 `BEGIN; INSERT; ROLLBACK` 期间**不得**出现 `commit` / `setAutoCommit`；对照组（无脚本事务）**必须**出现 `commit`；另有一例直接验证执行器自有批量路径确实提交，避免对照断言变成空断言。
- `ImportDeferredDurabilityChainTest`（1 例）：5000 行 CSV、毒丸行 1500、真实 H2、声明 `defersRowDurabilityToCommit()==true` 的上下文。第一次尝试在打开的事务内失败 → 回滚 → 断言**没有**任何 `rowsDone > 0` 的 `IMPORT_WATERMARK` 残留 → 同一任务重跑 → 行数与去重行数均等于 5000，并明确断言第 1 行与第 1499 行存在（这两行正是旧水位会跳过的行）。
- 这轮链路测试暴露并修掉了我上一轮修复的一个缺口：延迟持久化下**存储层**水位仍在写（`maybeCheckpoint` 与 close 的尾部 checkpoint），回滚后重试会据此跳行。现已在两处都加上 `!defersRowDurabilityToCommit` 守卫。

### 回归结果

- 定向合计：新增或覆盖的测试类全部通过，退出码 0；导入相关 6 个测试类一次跑全共 15 例全绿。
- 后端全量 `mvn test`（`-Dsurefire.includes=**/*Test.java`）的**真实范围**：jcef 修好之前，reactor 在 jcef 失败处停止，其后的模块全部被 SKIPPED；修好之后才能跑到后面，暴露出一个此前被掩盖的失败。
  - 已跑绿的模块：`bom`、`server`、`tools`、`domain`、`domain-api`、`spi`、`plugins`、`mysql`、`h2`、`domain-core`、`storage`、`bootstrap`、`web`、`updater`、`jcef`、`sqlx`。
  - 唯一失败：`chat2db-community-generic` 的 `PgWireFamilyBusinessFlowIntegrationTest`（6 例中 2 error、4 skipped，退出码 1），报错原文为 `The server requested password-based authentication, but no password was provided by plugin null`。
  - 该失败与本轮改动无关，且是**环境导致**：这个类是需要 Docker 容器的集成测试，用“端口是否可连”决定是否跳过（`assumeTrue(reachable(5433))` 对应 CrateDB）；本机 `5433` 端口被 `com.docker.backend` 占用（`Get-NetTCPConnection` 实测），于是探针认为容器在跑，实际连上去却要求密码。`8812`（QuestDB）空闲，对应用例正常跳过。
  - 处置建议（未改代码）：让该守卫在“连接需要密码 / 认证失败”时也按“不是我们的容器”跳过，而不是直接报错。
- jcef 模块上一轮曾失败 5 处（终端测试 4 个 error + 单实例测试 1~2 个 failure），已在干净 HEAD 工作树上逐条复现确认为既有问题，并已在本轮修复（见下）。
- 前端：`yarn install --frozen-lockfile` 的 postinstall（`umi setup`）在 Node 24 下失败，原因是 `spdy@4.0.2 → http-deceiver → process.binding('http_parser')`，该绑定在 Node 22+ 已被移除。按仓库要求的 Node 18+ 改用本机 Node 20.13.0 后：
  - `yarn run lint` 退出码 0（eslint + stylelint，`--max-warnings=0`）；
  - `yarn run build:web:community --app_version=0.0.0` 退出码 0（117.9s，含 `verify-production-bundles.cjs`）；
  - 86 个 `test:*` 脚本全部退出码 0。
- 前端 86 个脚本最初有 3 个失败（`test:shortcut`、`test:hot-update`、`test:result-set-editor`），根因是同一个：Node 24 + 本仓 tsx 把 `.ts` 当 CJS 加载，动态 `import()` 只暴露 `default`/`module.exports`，测试解构命名导出得到 `undefined`。新增 `src/testUtils/importModule.ts` 兼容两种模块形状，并在 4 个测试文件中接入；该辅助函数自带 `importModule.test.ts` 覆盖两种形状。
- 修改过的测试断言有两处，都在断言旁写明了口径：
  1. `CsvImportPipelineTest` 的拒绝行号 `"row":3` → `"row":2`：行号口径统一为“数据行序号”（表头不算数据行），与 Excel 路径和 batcher 的水位单位一致；
  2. 同一次改动还修掉了一个被 `ParallelImportLifecycleTest` 抓到的真实缺陷（见下）。

### 本轮追加修复（jcef 两处既有失败）

这两处都被先证伪为“环境问题”，再定位到真实缺陷并修掉；根因都靠独立探针确认，不靠推断。

1. **终端会话 `kill` 返回时进程仍活着**（`TerminalSessionManagerTest` 4 个 error）
   - 现象：JUnit `@TempDir` 清理报 `Failed to delete temp directory ... The paths could not be deleted`。
   - 根因：`Process.destroy()` 只请求终止就返回；Windows 上仍存活的 shell 持有其工作目录句柄，于是“已经 kill 的会话”仍占着目录。
   - 修复：`kill` 在 `destroy()` 后有界等待进程真正退出（3s），超时则对子孙进程与自身 `destroyForcibly` 再等一次。
   - 验证：新增 `killWaitsUntilTheShellReleasesItsWorkingDirectory`（kill 后立即删除该会话的工作目录）。红检 = 临时跳过等待 → 原 4 个 error 全部复现且新用例同时报错（17 例 4 error，退出码 1）；恢复后 17 例全绿。
2. **同时间戳的重复替换被判成同一份请求**（`SingleInstanceUtilTest.sameTimestampReplacementsDeliverChangedAndRepeatedArgumentsOnce`）
   - 现象：`Timed out waiting for subprocess`（16.5s）；单独运行该用例同样失败，故非跨用例干扰。
   - 定位：先用独立探针确认 Windows 上 `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` 正常产生 `ENTRY_CREATE`/`ENTRY_MODIFY`，但 JDK 在 Windows 上 `BasicFileAttributes.fileKey()` **恒为 null**；`IpcVersion(fileKey, modifiedTime)` 因此退化成只比时间戳，第 3 次替换（内容与时间戳都与上次相同）被 `alreadyRead` 判成重复事件。
   - 修复：`IpcVersion` 增加 `creationTime`。探针确认替换会带来新的创建时间（`...335663 / ...336883 / ...338098`），而同一份发布的重复事件各字段完全一致，因此既能在 Windows 上区分“新文件”，又不会重复投递。
   - 验证：`SingleInstanceUtilTest` 由 33 例中 1~2 失败变为 33 例全绿；`chat2db-community-jcef` 全量 143 例 BUILD SUCCESS。
3. **CSV 行号口径导致进度/水位多算表头那一行**（`ParallelImportLifecycleTest` 7/8 失败）
   - 现象：`expected: <40001> but was: <40002>`（`ParallelImportLifecycleTest:159`），即“导入 40001 行”被报成 40002 行。
   - 归因过程：该用例在干净 HEAD 上连跑 3 次全绿、在我的工作区连跑 8 次失败 7 次 —— 不是 flaky，是我的改动引入。根因是 VB-008 让 CSV 走 `CsvImportReader` 后，监听器把 reader 的**物理行号**交给了 batcher，而 Excel 路径用的是 `++rowNumber`（**数据行序号**）；batcher 的水位、进度文案与恢复游标都以这个数字为单位，于是带表头的文件恒多一行。
   - 修复：`BaseExcelImporter.acceptRow` 改为与 `invoke` 同样的数据行序号，并在方法注释里写明这个数字同时是恢复水位与进度单位，不能换成物理行号。
   - 验证：`ParallelImportLifecycleTest` 由 7/8 失败变为连跑 8 次 0 失败；导入相关 7 个测试类 17 例一次跑全全绿。

### 本轮消除的第三处间歇失败（已修）

`SingleInstanceUtilTest.lockRemainsHeldUntilShutdownHooksAndServicesFinish`：修前单独连跑 6 次失败 1 次，并在一次后端全量中成为唯一失败项。

- 失败信息带出的子进程输出只有 `Waiting for the previous desktop instance to exit.`，随后状态写成 `SECONDARY`。
- 根因在 `SingleInstanceUtil.registerInstance` 的等待循环：`tryLock()` 失败 → 读不到 endpoint → 初次设 `checkedLegacy=true` 并 `continue` → 下一轮若锁**仍被尚未完全退出的旧进程占用**且 endpoint 已消失，就直接 `publish(app.ipc, ...)` 并 `return false`，把本应成为主实例的新进程判成 SECONDARY。
- 修复：新增 `modernPeerSeen` 标记。只要本轮曾经读到过现代实例的 endpoint，endpoint 消失后就**只等锁释放**，不再走 legacy 信箱回退；从未见过 endpoint（真正的旧版实例）才保留原回退路径，兼容语义不变。
- 验证：`SingleInstanceUtilTest` 全类 33 例全绿（含 legacy 用例）；此前 1/6 失败的该方法连跑 8 次 0 失败。

### 仍未验证的部分

- VB-010 的验证用真实 H2 + 记录型 JDBC 代理锁住“脚本事务期间不提交”，**没有**在真实 PostgreSQL 上跑过 `BEGIN; INSERT; ROLLBACK;`。原因：H2 默认逐语句提交，行级回滚语义在 H2 上无法做出区分度（测试注释里已写明这一限制）。
- VB-011 已覆盖“失败 → 回滚 → 重试不丢行”的完整链路，但用的是单节点导入路径 + 延迟持久化上下文；**没有**从 `ImportManifestScheduler` 驱动真实分片重试（调度器自身的重试用例在 `ImportManifestSchedulerTest.retriesADeadlockedShardBeforePersistingDone`）。
- VB-005 的清理路径由既有参数化用例覆盖（`ARTIFACT_PUBLICATION_STARTED` 与 `ARTIFACT_PUBLISHED` 两个分支都会删除孤儿产物），但**没有**做“move 与落库之间 kill 进程”的真实故障注入。
- VB-003 只在当前文件系统验证；Windows / macOS 上的原子占位行为未实测。
- **前端浏览器实跑：已完成**。真实 Chrome（Playwright）驱动后端 `chat2db-community.jar`（127.0.0.1:10825）+ 前端 dev server（127.0.0.1:8889）。截图与驱动脚本都在 `%TEMP%\dsh-c2db-ui\`（`shots\` 为截图、`verify-taskcenter*.cjs` 为可重跑的验证脚本）——这是临时目录，需要留存请先另行拷贝。
  - 页面正常渲染并走通真实 UI 的“新建 H2 数据源”全流程（截图 `01-landing.png`、`06-h2-form.png`、`08-h2-test.png`、`09-connection-saved.png`）。
  - 双重取证：同一轮 `POST /api/connection/datasource/create` 为 `200 application/json`，而故意编造的 `/api/definitely-not-a-real-endpoint-xyz` 为 `200 text/html`（SPA fallback）——两者不同形，证明 200 不代表接口存在。
  - **VB-012 恢复按钮已在浏览器中验证**：打开任务中心需要先点标题栏 `TaskCenterStatusBadge` 内层节点、再点记录切换器第 2 个 tab（按 `workspaceRecordConfig` 顺序 `[执行记录, 任务中心, 已保存控制台]`）。拦截 `/api/tasks/list` 注入两条任务后：
    - `PENDING + RESUMING` 行：`isResumable=true`，行尾渲染出 `lucide-rotate-cw` 按钮（内联 `height:18px;width:18px;border-radius:3px`，即 `workspace.task.action.resume`）；
    - 对照组 `RUNNING + IMPORTING` 行：`isResumable=false`，无该按钮。
  - 过程中的两个坑记录在此：① 早先按 `title`/类名审计都是假阴性——`IconButton` 的 `title` 走 Tooltip 不落 DOM，且 `style.ts` 里 `taskActions: cx(...)` 生成的类名不含键名；判断渲染必须看结构（`svg.lucide-rotate-cw`）。② dev server 曾被我误判为"陈旧 bundle"，重启后现象不变，说明当时结论错误的原因是审计方法而非缓存。
  - 后端契约核对：`TaskStage.RESUMING`（`TaskStage.java`）与客户端的 `'RESUMING'` 一致，`LocalTaskManager` 在被中断且带 resume 状态的任务上写该 stage。
- 桌面安装包未构建，桌面交互改动未做视觉验证。
- HB-01~HB-09 属于历史分支，当前代码树中不存在，未做任何修改。

## 历史分支专属缺陷（当前 HEAD 不存在，仅在指定历史 tip 生效）

以下各条的适用 ref 是它存在的分支；`main` 与当前 HEAD 均**不含**这些实现，合并当前栈不会引入。保留记录是因为这些分支仍在仓库中、若被合并或 cherry-pick 会带入。

| 编号 | 级别 | 适用 ref | 结论要点 | 证据状态 |
|---|---|---|---|---|
| HB-01 | P1 | `2324683c7` | 合并 INSERT 失败时无条件 `connection.rollback()`，抹掉调用方已开启的事务工作（`60193a3fb` 已改 savepoint，不要按同一实现统计） | 只读审查静态复核；`60193a3fb` 新增回归测试本身有缺陷：哨兵行在 `setAutoCommit(false)` 之前写入，故旧错误实现也能通过 |
| HB-02 | P1 | `2324683c7`、`60193a3fb` | keyset 分页直接拼接主键原名，游标列用 `equalsIgnoreCase` 匹配首列；PG 下 `id` 与 `"Id"` 并存时可能静默漏行且任务成功 | 只读审查静态复核；现存 keyset 测试仅 2001 行，未跨越分页阈值 |
| HB-03 | P1 | `2324683c7`、`60193a3fb` | 多行 INSERT 前缀规范化会压缩大小写与空白，PG 中 `"a"` 与 `"A"` 两表被合成写首表 | 只读审查静态复核 |
| HB-04 | P2 | `2324683c7`、`60193a3fb`（MySQL 同族含 `0df59b5b9`） | 一致性快照只用原生 `BEGIN`、不改 JDBC autoCommit，连接回池时不复位事务/隔离级别 | 只读审查静态复核 |
| HB-05 | P2 | `60193a3fb` | SQLite 导入资源探针把 schema 当表名传，触发器计数错误地标为“已知” | 只读审查静态复核 |
| HB-06 | P1 | `48cb5cc9b` | 前端把被“忽略”的同名列过滤为空映射，后端仍按同名自动匹配 → 被忽略列仍导入 | 只读审查静态复核；当前 HEAD 已修正，勿回植旧 blob |
| HB-07 | P2 | `36a64158b` | 切换极速模式触发重新预览并重置人工列映射，值可能写入错误目标列 | 只读审查静态复核；当前 HEAD 已修正 |
| HB-08 | — | `9bac916a1` | 该 tip 自身混入旧文件导致编译阻断（`BaseExcelImporter` 抽象方法无实现、`ParallelCSVImporter` 调用不存在的方法） | 只读审查静态复核；当前 HEAD 已删除该文件 |
| HB-09 | — | 全部 12 个 keyset 历史 tip | 恢复 checkpoint 与产物截断口径可能不自洽；checkpoint 分页绕过执行计划 SQL | 审查进程因外部中断未完成，**未复核**，仅作待查线索保留 |

说明：HB-01~HB-08 的行号由只读审查代理给出并附引用，我未逐条打开原文件复验；按本仓规则，它们排在自验条目之后。若要把这些条目的“静态确认”升级为可交付结论，需要按各自 `ref:file:line` 复核一次。

## 覆盖台账

- 108 个未合并引用 → 91 个唯一 tip → 16 个独立 tip（祖先关系去重）。
- 逐 `ref × 相对自身 merge-base` 的差异内容去重：`ALL_PATH_BLOBS=945`、`LEAF_PATH_BLOBS=863`、`INTERMEDIATE_ONLY=82`。即 16 个独立 tip 之外，中间态分支另有 82 个“路径+内容”组合只在中间态出现（含测试）。
- 已复核的中间态样本：`77412d78e`（任务运行时）、`013c83bd1`（manifest 调度）、`89e9597d4`（分片准备）、`87c7cf621`（web 转换器）、`3e4577efb`（TaskStorage 契约）、`6ad20e548`、`01803deea`、`009be875e` 等，与各自后继 tip 的差异已在问题清单中体现或属同一缺陷演化。
- 未逐文件打开的中间态组合：82 项中的测试与 i18n hash/脚本类文件（`i18n-source-hashes.json`、`package.json`、多个 `*Test.java`）未逐行审查，属**已知缺口**，不声称已覆盖。
- 方向性事实核对：所有历史 tip 的 Java source/target 均为 17；16 个 tip 均未新增 NativeLoad/BULK/COPY 类 SPI，实际快速路径实现为 `MultiRowInsertSql`。

## 测试缺口（这些测试存在，但证明不了对应契约）

- `TaskStorageMigrationTest` 只覆盖干净 snapshot 与截断事件尾行，未覆盖迁移时的崩溃恢复 journal、也未覆盖 `resumeStates` 迁移 → VB-001 / VB-002 无拦截。
- `ParallelImportLifecycleTest` 手工绑定正确的 statement guard lambda，无法发现运行时接线回归 → VB-004 无拦截。
- `ArtifactServiceTest` 多产物删除用例走的是 `deletionService == null` 的测试构造分支，与生产注入路径不同 → VB-007 无拦截。
- `TaskDeletionServiceImplTest` 直接调用恢复方法，不经过 Spring 生命周期 → VB-006 无拦截。
- `DefaultSQLExecutorMultiRowInsertTest`（历史）哨兵行写在关闭 autoCommit 之前，断言恒过 → HB-01 的“修复测试”无效。
- 数据文件导入测试大量使用内存替身，不含真实事务/真实方言；因此 VB-010、VB-011、VB-014 类数据正确性缺陷不会被现有测试发现。

## 结论

- 当前栈（`a14e73ede`）**不建议直接合并**：存在 6 项 P1，其中 VB-001/VB-002 破坏恢复与迁移语义，VB-009 让“演练”变成真实写入，VB-010 使脚本级 `ROLLBACK` 失效，VB-011 在重试路径静默丢行。
- 上述 6 项 P1 与其余 8 项 VB-0xx 已于 2026-10-05 修复，逐条测试见“修复记录”；VB-011 的完整重试链路已在本轮补齐，合并前仍建议补上 VB-010 的真实 PostgreSQL 事务用例（H2 无法对行级回滚做出区分度）。
- 若改为合并历史 tip（HB-01~HB-08），必须先解决这些分支自身的缺陷，否则等于换一批问题进入主线。

## 已执行与未执行（收尾口径）

**已落地并验证**（本轮实际命令与退出情况）：
- 引用盘点与去重：`git for-each-ref --no-merged`、`git merge-base --independent`（16）、`git merge-base --all`（`28b1ddfbe` 双基线 `81cd31e19…` / `fc7b563aa…`）、256 个唯一未合并提交。
- 内容级覆盖度计算：`ALL_PATH_BLOBS=945 / LEAF=863 / INTERMEDIATE_ONLY=82`。
- 本会话逐行阅读并确认：任务运行时（`TaskServiceImpl`/`TaskRunner`/`LocalTaskManager`/`RunningTask`/`TaskExecutionContextImpl`）、存储迁移（`TaskStorageMigrator`/`H2TaskStorage`/`TaskDatabase`/`FileTaskStorage`）、导入路由与 reader（`DataFileImportTaskExecutor`/`CsvManifestPreparer`/`CSVImporter`/`BaseExcelImporter`/`JSONImporter`）、SQL 批处理（`DefaultSQLExecutor`/`ImportSqlExecutor`/`SyncSqlBatchHandler`/`SQLImporter`）、分片（`CsvManifestImporter`/`ImportRowBatcher`/`ImportManifestScheduler`/`ImportShardRetryPolicy`）、删除队列（`TaskDeletionServiceImpl`/`PendingTaskDeletion`）、前端（`TaskCenter`/`constants/importExport`）、Web 转换器与请求 DTO。
- 官方契约核对：Java 17 `Files.move(..., ATOMIC_MOVE)` 文档明确“目标已存在时是替换还是抛错由实现自行决定”，据此判定 VB-003 不是靠猜。
- 环境事实：PATH 的 `java` 为 8，但 `D:\Java\jdk\microsoft-jdk-17\bin\java.exe` 可用（OpenJDK 17.0.12），修复阶段的全部 Maven 验证都用它。
- 修复阶段的实际执行：12 个定向测试类全部通过（退出码 0）；后端全量 `mvn test` 中 `tools` / `spi` / `domain-core` / `storage` / `web` 全部 SUCCESS，`jcef` 的 2 个失败已在干净 HEAD 工作树复现，属既有问题。

**未做 / 未验证**：
- HB-01~HB-08 行号来自只读审查代理的复核报告，我未逐条开文件复验；HB-09 未完成复核。
- 未覆盖：82 项中间态内容中的测试/脚本/哈希类文件未逐行审查。
- 未连接任何业务数据库，未执行真实数据导入；未产出或校验桌面安装包、Docker 镜像。
- 未启动应用、未做浏览器实跑。前端构建**已做**：Node 24 下失败（`spdy` 依赖 `http_parser` 绑定），改用 Node 20.13.0 后 `yarn run build:web:community --app_version=0.0.0` 退出码 0（117.9s）。
- 未做任何 Git 写操作（无 commit / push / checkout / reset / stash）。改动只落在工作区。

## 接续入口

- 状态：审查结论已成形并已修复当前栈 14 条（VB-001~VB-014）；历史分支 HB-01~HB-09 未动。`chat2db-community-generic` 的一个 Docker 集成测试因本机 5433 端口被占用而失败，已确认是环境问题（见“回归结果”），按决定不改代码。
- 后端：`tools`/`spi`/`domain-core`/`storage`/`web`/`jcef` 等 16 个模块 BUILD SUCCESS；`generic` 的 `PgWireFamilyBusinessFlowIntegrationTest` 因本机 `5433` 被 `com.docker.backend` 占用而 2 error（需 Docker 容器的集成测试，探针误判）。
- 前端：`yarn run lint`、`yarn run build:web:community`、86 个 `test:*` 脚本在 Node 20.13.0 下全部退出码 0。
- 下一步（按优先级，均不需要额外裁决）：
  1. 补 VB-010 的真实 PostgreSQL 端到端反例（`BEGIN; INSERT; ROLLBACK;` 必须 0 行）与跨批次对照；
  2. 补 VB-005 的崩溃注入（move 与落库之间终止进程）与 VB-003 的 Windows / macOS 实测；
  3. 前端 dev server + 后端的浏览器实跑（当前只有 lint / 构建 / 纯逻辑测试）；
  4. 若需要 generic 模块全绿，硬化 `PgWireFamilyBusinessFlowIntegrationTest` 的容器探针（认证失败也算跳过）；
  5. 复核 HB-01~HB-08 的行号与适用 ref，决定历史分支处置方式。
- 关键入口：本文档“问题清单”“修复记录”“端到端链路用例”；基线 `main=dc2b80a3b0189bb3fd92d69ce8f52de2d4136ac8`、HEAD `a14e73edefe7015c2113e51b836ce3a60170a12f`。
- 环境要点：Java 用 `D:\Java\jdk\microsoft-jdk-17`；前端构建必须用 Node 20（`D:\Java\nodejs\node20.13.0`），Node 24 下 postinstall 的 `spdy` 会因 `http_parser` 绑定缺失失败；PowerShell 传 Maven 参数时每个 `-D` 都要加引号。
- 阻塞：无。

## 2026-10-13 按批次完善：执行计划与接续入口

用户已要求按本轮计划顺序实施。主线基准 ea3a5c68ca290199dc86726706eb3006495ca34f；开始时功能分支 codex/fn-14-repo-hygiene，HEAD da31203fb1227a4218447228ed6515e52312818b，工作区干净。

目标：先修复导入正确性，再补真实验收和用户操作闭环。以下状态属于本轮；上文旧日期、旧采样和授权描述保留其历史范围。

共同约束：Java 17；既有 Maven、React/Umi、Yarn 锁文件和 i18n；保留 Community/OFFLINE/loopback、公共 API、存储与成功前缀/恢复水位；测试使用专用 fixture；没有新依赖/全局工具链修改；任何源代码修改先有能暴露缺陷的回归。根代理是唯一分支/提交/推送写入者。当前阶段使用独立 worktree，各任务不修改其他任务文件或共用构建 target。

执行方法：systematic-debugging + test-driven-development + subagent-driven-development。三个第一批任务文件独立，在隔离 worktree 并行实现；根依次复核补丁并集成，执行 owning module full tests/package，再按既有授权和层级关系交付。模型与临时文件只服务本轮；不把技能默认签字/固定目录作为额外门槛。

进度：第一批和第二批代码/真实方言 IT 已提交并推送 fn09–fn14；Task 6 真实浏览器验收仍有缺口。第三批 Task 7–12 源码已集成，最终 owning core+web reactor package 实际 2144 tests / 0 failures / 0 errors / 0 skipped；前端 lint、Community build、定向脚本和两组模拟接口浏览器契约通过。真实新 API HTTP、修复后的 MySQL CSV 下载/多表/取消、新事务流程的真实 PG/MySQL 验收及最终性能基线复测仍待完成，不能宣布全部功能验收完成。当前聊天 01a1211f-4275-72a0-93d9-8055bdbc0240；2026-10-13 20:53 检查成功 compact=5，rotation_due=true。当前工具无新建聊天入口，后台应用 UIA/CDP 也不能送达接续；证据、提交及具体下一步保存在 .superpowers/sdd/vibecoding/progress.md。

### Task 1: Excel 预览与执行选项契约（第一批）

工作区 D:/c2db-batch-20261013/excel。只修改 BaseExcelImporter.java、相关 ExcelImportReader/ImportCell 消费适配和该任务回归；不修改 ImportRowBatcher、JSONImporter、staging、TaskService 或文档。必要共享文件修改先告知根代理。

消费者是保留的 mapped import preview/execute public API 和 domain API；新 Single UI 未暴露 rich ExcelOptions，不能伪造页面复现。既有 ExcelImportReader.read(File, ExcelOptions, int limit, int minimumColumns, Consumer<Map<Integer,String>>, BiConsumer<Map<Integer,ImportCell>,Integer>, Runnable) 已处理无表头、sheet、headerRow、dataStartRow/dataEndRow/columnRange。现执行直接 EasyExcel，batcher 仅 invokeHead 初始化，headerless 跳行且 finish 返回；range未使用。

- [x] 先在 ExcelImportSheetContractTest/XLSXImporterExecutionTest 或独立 owning-module fixture 测试中生成真实 XLS/XLSX：无表头首行必须入库；前导说明行+headerRow；行范围外不入库；列范围重映射与预览相同。预期值手工定义，不调用生产 helper 生成 expected。
- [x] 运行 Java17 Maven focused tests，确认正确原因失败；记录非零 test count、失败断言和日志。
- [x] 最小修复执行与预览共用 reader 语义，保留 typed/null/date/number 处理、sheet 选择、取消、STANDARD 已成功前缀和数据行 watermark。不能拒绝合法 options 或将0行报成功掩盖问题。
- [x] 运行上述回归及已有 Excel execution/reader contract tests，确认0fail/errors；生成补丁、diff --check、自查，保存 task-1-report.md 和 patch。不stage/commit/push，不操作root worktree，不派发子代理。

### Task 2: staging 任务所有权与过期回收（第一批）

工作区 D:/c2db-batch-20261013/staging。只修改 ImportFileStagingService、IImportFileStagingService/ImportTaskSubmissionServiceImpl 的必要兼容接口或归属登记、必要 domain API/storage provider 实现和相关测试；不修改 Excel/JSON importer。无新公共不兼容契约；需要共享文件时告知根代理。

现 claimForTask(String fileId) 创建空 marker，无 taskId/续租。canDelete 仅按 marker>7d删；TaskService.resume 对 PENDING+checkpoint 无7d限制，LocalTaskManager 保留恢复资源。必须区分 running/resumable/terminal/真正orphan，原24h unclaimed清理保留。

- [x] 先补真实持久 marker/TaskStorage fixture：已claim且运行或可恢复8天不删除；无任务owner旧文件可回收；重启重建服务后仍保留；失败提交/成功取消删除清理幂等；旧marker兼容保守不误删。
- [x] 基于现实现运行失败测试，记录red证据，不仅断言mock调用或人为宣布owner消失。
- [x] 沿 domain API/storage 边界做最小所有权查询或持久关联。可采用已存在任务 spec 的 fileId 引用做权威所有者检查，避免无必要地增加所有 TaskStorage implementor 的抽象方法；查询失败保守保留。处理claim到任务提交之间的窗口、旧marker/进程重启、终态释放。不得仅touch mtime掩盖长期恢复，也不能只永久保留所有orphan导致配额无法回收。
- [x] focused tests检查非零数量/0fail，再涵盖submission/lifecycle/storage相关契约；patch+diff--check+task-2-report.md。不要stage/commit/push、派发子代理、读取用户实际数据。

### Task 3: JSON 规范化键碰撞（第一批）

工作区 D:/c2db-batch-20261013/json。只修改 JSONImporter/必要 JsonImportReader 边界、相关 JSON 导入测试与所属 i18n 错误契约；若不需要改 i18n 则保持。不要修改 Excel/staging/TaskService。

当前 trim+lowercase 将原始 Name/name 和首尾空白别名映射同一index，cells.put静默覆盖。保留单一 key 的大小写兼容、unknown diagnostics、typed/null原生值。重点同一record、source mapping/header规范化碰撞，不能把它与JSON原文完全相同重复键的规范混淆。

- [x] 用真实 JSONImporter/H2 fixture 或现有 importer execution harness 验证 {"Name":"A","name":"B"} 明确报冲突而不写B，首尾空白/映射header同理；保留单个 Name→name 正常匹配、数字布尔null和unknown用例。
- [x] 运行baseline red并记录正确失败原因。
- [x] 生成cells和header index之前检测collision，错误包含行号/冲突原名称；显式映射若不能唯一消歧则明确拒绝，不引入 silent last-wins。
- [x] 运行JSONKeyMatching/JSON extension/reader/typed执行回归，非零0fail；patch+diff--check+task-3-report.md。不stage/commit/push，不派发子代理。

### Task 4: JCEF 来源边界验证（第二批）

核 MainJFrame onQuery→bridge→upload_local 的实际 frame/navigation capability；合法打包/允许本地dev来源与外部/iframe分开；若可达则统一来源准入并拒绝测试，否则记录反证。四个needs_review不是confirmed漏洞。匹配renderer bridge测试与实际平台验证，不消除告警代替修复。

### Task 5: 显式 IT 与真实方言门禁（第二批）

将 MySQLTaskRoundTripIT/StagingManifestPerformanceIT 接到真正执行入口，专用 fixture，既有MySQL三例/H2一例不可跳过；补真实export/import及恢复，PG/Maria staging、外键/提交/回滚/取消/unknown outcome；Kingbase未实测留限制。mvn test默认不发现*IT；当前POM无旧testFailureIgnore配置，不根据旧AGENTS文案造假绿问题。

### Task 6: 真实浏览器任务闭环（第二批）

隔离home+loopback Community后端+具名H2，页面上传/预览/映射/提交，核任务终态+数据库值+下载产物；单/多表成功、坏行、幂等响应丢失、取消；保留失败证据，后台/独立headless浏览器不抢焦点，只停止本任务进程。

### Task 7: 多表逐文件预览映射（第三批）

每CSV source独立options/preview/columnMappings，共用现有preview与校验；目标/选项变化失效旧结果；所有预检ready方可提交，保持stagedownership/clientSubmissionId。不扩展新格式。

### Task 8: 原地重试（第三批）

single preview、multi upload和目标列表失败可明确retry，只重试失败步骤，保留成功fileId/targets/dependencies，pending去重，迟到结果generation检查/资源释放，无无限自动重试。

### Task 9: 单任务取消（第三批）

新增安全cancel契约，queued主动terminal+cleanup，running token/resource取消，commit保护，幂等与最终状态一致；不能将cancel等同撤销已提交前缀。暂停独立safecheckpoint能力与保留资源，不用CANCELLED模拟，JSON/SQL不能无证据宣称行级resume。

### Task 10: 动态上传能力（第三批）

单表50MiB、多表1GiB/10GiB与staging/multipart真实限制对齐；现有能力入口优先，准确单位、保守fallback、服务端最终校验；有意的不同policy明确解释，不盲目放大安全限额。

### Task 11: 准入解释和国际化（第三批）

显示findings message/evidence/remediation，区分BLOCKER/DEGRADATION和requested/effectiveMode，复用ABORT/SKIP五语文案，unsupported format开关解释且保留后端拒绝。

### Task 12: 用户指南与性能基线（第三批）

建立当前UI/API能力矩阵与公开fixture操作指南，从README链接；历史审计不当当前交付指南。另记录固定数据集多轮性能（完整与5%rehearsal区别、吞吐/内存/连接/清理/环境），测得瓶颈再优化，保持恢复与事务合同。

批次验收：第一批focused red/green+owning core full tests+受影响模块package；前端改动相关scripts+lint+Community build+实际浏览器关键流程；公共storage/API兼容test；JCEF native未运行不可称通过；所有git diff--check、stage范围/credentials/生成物检查、最终status。远程CI/head检查另记提供方限制，不能将无run记为green。

### 第一批交付证据（2026-10-13，当前代码范围）

- Excel 执行通过既有 reader 落实无表头、sheet/header、行列范围；typed cells 在 batcher 的行错误策略内区分原生值与文本格式，并仅在 Excel emptyAsNull=false 时保留空串。真实 XLS/XLSX/H2 回归涵盖重排映射、文本 DMY/逗号小数、原生日期/数值/布尔、空串/NULL，以及 502 行 SKIP/ABORT 的已成功前缀。数据行 watermark 不使用物理工作表行号；CSV 字符串通路保持。
- staging 的 7 天时间只作为无 owner claim 的宽限；通过权威 TaskStorage 查询所有非终态任务顶层/多表 fileId，保护长期运行与恢复。真实 FileTaskStorage、marker 与 checkpoint 重启回归证明 8 天 owner 保留，orphan/terminal/deleted owner 可回收；存储不可用或 spec 损坏保守保留。原 24 小时 unclaimed 清理保持。
- JSON 在 header/cells 规范化覆盖之前拒绝不同原始键的碰撞，并给出实际行号及原名称。单一别名和 typed/null 保持；第 1001 行碰撞保留已执行的 1000 行成功前缀。完全相同重复 JSON property 的 parser policy 未改变。
- 组合验收使用 process-scoped Java17、Maven3.8.7：`mvn -B -f chat2db-community-server/pom.xml -pl :chat2db-community-domain-core -am -Dmaven.test.skip=false -DskipTests=false '-Dsurefire.includes=**/*Test.java' -Dmaven.test.failure.ignore=false clean package`，exit0。fresh Surefire 共 **1917 tests / 0 failures / 0 errors / 0 skipped**（domain-core698、storage172、tools81、API27、SPI193、MySQL714、H2 32）。先前不带 clean 的运行有5个 NoSuchMethodError，来自主线已删除 CellValueServiceImplTest 的旧 `.class`；未改源码来掩盖错误，保留首红日志后按本次构建范围 clean 重编。
- 独立审查提出的 Excel 文本格式/empty 两项缺口已补测修复并再次复核；staging 生产注入/持久归属/历史路径已独立复核，没有剩余已确认阻断。当前证据不包括 HTTP/UI/native 或提供方 CI；这些仍在后续批次。

### 第二批阶段证据（尚未完成整体验收）

JCEF 在 native handler 和 backend bridge 共用入口同步检查有效主 frame、application browser 与来源；仅打包 index 精确路径或配置的 loopback origin 可调用。拒绝403并消费query；导航/销毁的旧query取消保持清理。定向10例全过；JCEF及其依赖 enabled-tests package 共343例，其中335例通过、8例因平台/fixture条件跳过。实际 Chromium/安装包未运行，不把URI测试或package记为native通过。真实方言 IT、应用启动/浏览器闭环及第三批事项仍按上列任务继续。


### 第三批集成证据与未完成边界（2026-10-13）

- 多表每个 CSV source 的选项、预览与列映射独立保存；修改目标或选项仅使对应预览/映射失效。成功上传保留，目标列表、失败上传与单文件预览可明确原地重试。17 个新 helper 用例及既有向导契约通过；两组 headless 页面实际点击契约分别通过 4 和 6 项检查，但 metadata/upload/preview 为模拟接口，没有执行数据库任务。
- 后端完整 scoped manifest/staging 路径传递并执行每个源的 encoding、delimiter、header/range、日期/小数与 NULL 设置。canonical shard 用 UTF-8 与固定解析选项，不重复套用源范围；quoted empty 与 unquoted NULL 不二次混淆，`nullString=""` 仍优先。新增真实 H2 SCHEMA/DATABASE/staging 及失败策略回归。
- SKIP 且已有外层事务时，每批/逐行重放建立保存点，失败先回滚当前尝试再重试；建立或回滚保存点失败立即终止，不把故障算普通坏行或继续写入。真实 H2 回归证明坏值只计一次、之前 500 行及有效后续行保留；未把此证据当作 PostgreSQL/MySQL 或跨 shard 崩溃恢复已通过。
- cancel/control API 根据任务归属、运行状态、commit/recovery 保护决定可取消性；排队任务终态并释放资源，运行任务先请求取消，UI 等待真实终态。暂停明确不支持。动态上传能力来自实际 staging/multipart 配置；浏览器/桌面、单表/多表按当前选择总量重新校验，失败采用保守回退。准入面板显示 requested/effective mode 与每项 message/evidence/remediation，五种语言沿现有 i18n。
- Java17 Maven core+web `package` 明确启用测试、覆盖 includes 与 failure-ignore，fresh 259 个 XML 共 **2144/0/0/0**（core734、web111）；`yarn run lint` exit0、`yarn run build:web:community --app_version=0.0.0` exit0，生产 bundle 检查通过。未运行最新 jar 的 HTTP 或 native 验收。
- 用户指南与 portable fixture/真实单表浏览器 runner 已加入；fixture 归属清理测试 3 例通过，runner 仅做语法检查。已有本机 H2 staging 12 轮基线是最终 typed-binding 修复前采样，需最终源码复测；没有符合准入的 ULTRA_FAST 样本或真实数据库性能结论。
- Docker 遗留 socket 清理虽已获用户授权，实际删除命令被工具策略拒绝，未执行；后台启动故障未恢复。真实 MySQL 下载、多表、坏行、响应丢失与取消浏览器闭环保持未完成。提供方 Actions 使用限制、JCEF/native 平台缺口单独保留。

接续首项需补充 SKIP 逐行重放时的取消回归：`ImportRowBatcher.replayIndividually` 当前 `catch(RuntimeException)` 未单独传播 `TaskCancelledException`，可能把已请求取消后的剩余行记为 reject。外层 token 的最终 CANCELLED 不能替代及时退出与准确 reject/progress；该风险已于 2026-10-13 修复并补上回归（见文末「SKIP 重放取消修复」）；真实 PG/MySQL 保存点路径与浏览器验收仍未完成，仍不能把新取消入口宣布为全部执行路径已验收。先用真实 H2/受控 JDBC 取消 fixture 复现，再最小修复并重跑 owning core+web 门禁，随后进行性能复测、运行时 HTTP、层级传播与现有 PR 更新。


### 用户要求暂停后的落盘（2026-10-13）

用户明确要求“当前节点完成后，先落盘到文档完成情况，并暂时停止一下任务”。本次收尾节点为第三批本地源码集成、已执行的联合验证与提交，随后暂停；其余验收和对外更新保持待办。

当前根分支 `codex/fn-14-repo-hygiene`。第三批 backend/API/H2 回归提交 `d81181553`，client services/helper 提交 `a0b7d2f57`，UI/i18n/定向测试提交 `3983cc71d`；本节随指南和验收工具提交。此批尚未传播到 fn09–fn13、推送远端或更新现有 PR。上批已发布 fn14 为 `b42029ce780dd79340db478b0b6ba14e95555f39`，该记录是前批回执，不代替恢复任务后的实时远端复验。

恢复顺序：先核验聊天轮转与 root/worktree/分支/脏状态；处理 SKIP replay cancellation 回归与修复，重跑明确启用测试的 core+web package；再复测最终 H2 baseline、构建新 startup jar 并做真实 capability/control HTTP 验收。Docker 恢复后执行真实 MySQL 单/多表、下载、坏行、响应丢失和取消浏览器验收。通过对应门禁后再按现有授权将语义提交传播到所属 fn 层、推送并更新现有 PR，复查提供方 CI。native、真实新 PG/MySQL 保存点和符合准入的 ULTRA_FAST 性能缺口继续明确记录。

详细原始命令、XML 统计、失败证据、进程/fixture 归属及补丁标识保存在本机 `.superpowers/sdd/vibecoding/progress.md`。该目录按已有规则保持本地，源码、用户指南与本文件是版本化产物。没有把暂停解释为全部计划完成。

## 恢复任务后第一批：SKIP 重放取消修复与最终 H2 基线（2026-10-13）

### 修复对象与最小改动

`ImportRowBatcher.replayIndividually` 原 `catch (RuntimeException)` 会把 `TaskCancelledException` 交给行错误策略，把「因取消而放弃的行」写进 reject 文件并计入 `rejectedRows`。现单独 `catch (TaskCancelledException)` 原样抛出（`chat2db-community-domain-core/.../task/imports/ImportRowBatcher.java` 重放循环首行），取消因此走既有失败传播：不写 reject、`fullyHandled` 保持 false、`inFlightFirstRows` 不摘除（水位不前进）。改动 5 行，未新增公共 API、未改存储与恢复契约。

### 回归用例（真实 H2 + 受控取消）

`ImportRowBatcherParallelTest.cancellationDuringSkipReplayIsNotRecordedAsARejectedRow`：CSV 5 行（其中一行主键重复）、`onError=SKIP`，取消用真实 `RunningTask.requestCancellation(true)` 触发，触发点是第一条 `IMPORT_ROW_REJECTED` 事件落库之后（等价于用户在 SKIP 重放期间点取消）。断言三件事：reject 文件只允许 1 行（真正的坏行）、取消不得新增 reject 事件、取消点之后的行不得入库。

- 红（修复前）：`only the genuinely bad row may be written to the reject file ==> expected: <1> but was: <2>`，被取消的行确实进了 reject 文件。日志 `.superpowers/sdd/vibecoding/logs/red-ImportRowBatcherParallelTest.log`。
- 绿（修复后）：同命令 `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`，exit 0。日志 `.superpowers/sdd/vibecoding/logs/green-ImportRowBatcherParallelTest.log`。
- owning 门禁（明确启用测试、覆盖 includes 与 failure-ignore）：`mvn -B -f chat2db-community-server/pom.xml -pl :chat2db-community-domain-core,:chat2db-community-web -am "-Dmaven.test.skip=false" "-DskipTests=false" "-Dsurefire.includes=**/*Test.java" "-Dmaven.test.failure.ignore=false" clean package`，exit 0 / `BUILD SUCCESS`，domain-core **735/0/0/0**（上批 734，恰为本轮新增用例）、web **111/0/0/0**。日志 `.superpowers/sdd/vibecoding/logs/package-core-web-20261013.log`。
- 同族扫查：reject 记账与取消传播路径（`ImportSqlExecutor.executeBatch/executeSql`、`executeReplaySafe`、`CsvManifestImporter` 的 reject 计数包装、`ImportManifestScheduler`、`StagingManifestImporter` 的失败报告）未发现第二处「把取消记为行失败」的分支；分片/清单层只做报告并原样抛出，终态仍由外层 token 决定。

### 最终 H2 性能基线复测（typed-binding 修复后的源码）

命令同 owning 门禁，`-Dtest=ImportPerformanceBaselineIT`：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`，exit 0。12 个采样（每场景 4 轮，含 warmup），行数、校验和与回滚校验由用例内断言把关：

| 场景 | 行数 | 有效轮 rows/s（round 1/2/3） |
|---|---|---|
| STANDARD_STAGING_COMMIT | 50000 | 136092 / 204374 / 179898 |
| REHEARSAL_FULL | 50000 | 158916 / 149237 / 148788 |
| REHEARSAL_5_PERCENT | 2500 | 56802 / 54643 / 54634 |

结论只适用于本机 H2 与本地构型：不是 MySQL/PostgreSQL 性能结论，也没有符合准入的 ULTRA_FAST 样本。日志 `.superpowers/sdd/vibecoding/logs/h2-baseline-20261013.log`。

### 运行时 capability/control HTTP 验收（新 startup jar，2026-10-13）

先按 `mvn -B clean package "-Dmaven.test.skip=true" "-Dchat2db.finalName=chat2db-community" -f chat2db-community-server/pom.xml -pl chat2db-community-start -am` 重新打包（exit 0，产物 `chat2db-community-server/chat2db-community-start/target/chat2db-community.jar`），再以隔离 home + loopback 启动该产物做真实请求验收：

```bash
java "-Dloader.path=chat2db-community-server/chat2db-community-start/target/lib" \
  "-Dchat2db.gui=false" "-Dchat2db.runtime.mode=community" "-Dchat2db.network.status=OFFLINE" \
  "-Dserver.address=127.0.0.1" "-Dserver.port=10825" "-Dspring.profiles.active=dev" \
  "-Duser.home=<isolated>" -jar chat2db-community-server/chat2db-community-start/target/chat2db-community.jar
```

结果（PID 51540，`Started Application in 4.311 seconds`，Tomcat 起在 10825 loopback；验收后只停这一个进程，端口已释放）：

| 请求 | HTTP | 结果 |
|---|---|---|
| `GET /api/rdb/import_preview/capabilities` | 200 | 真实能力值：singleFileMaxBytes=52428800、multiFileMaxBytes=1073741824、multiTotalMaxBytes=10737418240、stagingMaxTotalBytes=10737418240、multipartMaxRequestBytes=1074790400、maxFiles=1000 |
| `GET /api/tasks/control?taskId=999999` | 200 | 定义错误 `common.dataNotFound`（未知任务不崩、不误报可取消） |
| `POST /api/tasks/cancel {"taskId":999999}` | 200 | 同上 `common.dataNotFound` |
| `GET /api/tasks/list?pageNo=1&pageSize=5` | 200 | `total=0` 空页（隔离 home + OFFLINE） |
| 不存在路径（负向对照） | 200 | 返回前端 index.html：未知路径由 SPA 兜底，不是 404 |

`/api/cli/v1/runtime/capabilities` 在本社区构型下未映射（同样落到 SPA 兜底），本轮不作为验收项。隔离 home 的社区密钥按 `~/.config/chat2db-community/encryption.key` 生成，密钥本身不落库、不入文档与汇报。

### 真实 MySQL + 浏览器单表导入/导出闭环（Docker 已恢复，2026-10-13）

按 `docs/import-export-guide.md`「可复用验收」执行：隔离 home 后端（10825）+ Node 20 前端 dev server（`127.0.0.1:8889`，`/api` 代理到 10825）+ 具名 loopback MySQL fixture（`mysql:8` 容器、具名库 `c2d_ui_fixture_20261013`、驱动 `mysql-ui-fixture-8.3.0.jar`），用 Playwright 走真实浏览器（headless，不抢焦点）：

- `PREVIEW_PASSED` → `SUBMITTED 1791898860246999` → `DATABASE_VALUES_PASSED`：真实 MySQL 行为 `[["1","中文,hello","12.34"],["2","emoji😀","56.78"],["3","line\nbreak","90.12"]]`，中文、emoji、字段内换行与小数逐字段一致。
- 导出：`EXPORTED` 任务 1791898862727999，产物 `Export table data - c2d_ui_fixture_20261013.C2D_UI_SINGLE.csv`（81 字节），`BROWSER_DOWNLOAD_VALUES_PASSED` 3 行逐字段比对，最终 `SINGLE_IMPORT_EXPORT_BROWSER_PASSED`。这条正是上批「被 Docker 启动故障阻塞」的下载复测。
- 证据留档：`target/import-browser-evidence/{single-success.png, initial-ui.png, single-real-task.json, export-task.json, export.csv, initial-ui.json}`；日志 `.superpowers/sdd/vibecoding/logs/{browser-e2e-backend.log, browser-e2e-frontend.log, browser-e2e-run.log}`。
- 收尾：`--cleanup` 已移除具名容器（`REMOVED d1b271876a82449db9857cc723987952ef9a4a315ce7351bea851a7d6d03d87f`），只停本轮启动的后端/前端进程，10825 与 8889 均已释放，未触碰其它容器。
- 一处操作失误已复原并留痕：首启误用真实 home 的 `user.home`（4 秒后即停），且驱动 jar 被下到真实 home 的 `jdbc-lib`；该 jar 已删除（恢复为仅 `h2-2.1.214.jar`），真实 home 只多了该实例自写的 `dev/logs/*` 与 `storage/result-snapshot/index-22724.json`，未再改动以避免误删用户数据。此后所有后端均使用 `.superpowers/sdd/vibecoding/http-smoke-home` 隔离 home。

### 层级传播与推送（2026-10-13）

按用户当轮授权执行：每层从整合分支取本层切片的最终内容（`git checkout 512e3666c -- <层路径>`），逐层提交、推送，并在每层推完立即用 `ls-remote` 回读；未使用强制推送，未改动根工作区。

| 层 | 分支 | 新头 | PR |
|---|---|---|---|
| backend（domain-api / domain-core） | `codex/fn-09-task-runtime-and-import-execution` | `e7ad513f4` | #42 |
| web API | `codex/fn-10-web-api` | `21d367c31` | #43 |
| client services / hooks / utils / typings | `codex/fn-12-client-import-services` | `9ca9ce7a5` | #45 |
| client wizard / i18n | `codex/fn-13-client-import-export-wizard` | `881935e5e` | #46 |
| docs / CI / scripts（整合层） | `codex/fn-14-repo-hygiene` | `512e3666c` | #47 |

- 切片一致性：四层各自对其路径与 `512e3666c` 的 `git diff` 均为空，即各层之和复现整合分支的对应内容。
- fn-11（runtime messages / JCEF）本批无内容：未改、未推，PR #44 保持原状。
- 一处纠偏已留痕：fn-12 按路径取 `src/utils` 时带进两个更早 client 批次的测试文件（`jcefZoom.test.ts`、`shortcutDispatch.test.ts`），它们引用本层不存在的 `@/testUtils/importModule`；已用一次不加力的回滚提交（`9ca9ce7a5`）恢复本层内容。**已知遗留**：这两个文件在根分支上的较新内容不属于 fn-09/10/12/13 任一层，本轮未再指派归属，后续要么归入相应 client 层，要么在整合层保留。
- 推送即在原 PR 上生效（分支头与 PR 绑定），未新建 PR、未合并、未触发 workflow（fork Actions 仍按暂停口径处理）。

### 本批仍未完成

- 浏览器用例只覆盖「必填映射阻止提交 + 真实单表导入 + 任务成功 100% + CSV 导出下载」；多表、坏行、响应丢失、运行中取消的浏览器闭环仍未跑，不能由本次结果冒充通过。
- 真实 PostgreSQL/MySQL 保存点下的取消链路、`ImportPerformanceBaselineIT` 之外的 ULTRA_FAST 准入样本、native 安装包与平台跳过项。
- 两个 client 测试文件的层级归属遗留（见上「层级传播与推送」），以及 fork Actions 与提供方 CI 未复核。
