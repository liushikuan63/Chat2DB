# Community 导入、导出与任务操作

本指南描述当前功能栈的实现，不代表已经发布到主分支的版本。Community 使用本地数据源、任务存储和 JCEF 桌面桥接；网页运行与桌面运行共用服务端任务。请先在专用测试数据库中验证文件和映射，再操作业务数据。

## 当前能力

| 入口 | 文件与选项 | 预览和执行边界 |
| --- | --- | --- |
| 单表导入 | CSV、XLS、XLSX | 页面提供有界数据预览、列映射、必填列校验；CSV 可设置编码、分隔符、引号、跳过行、NULL 文本和 ABORT/SKIP |
| 单表 JSON 导入 | JSON | 当前新任务弹窗没有 JSON 映射预览；执行拒绝规范化后冲突的不同原始键，例如 `Name` 与 `name`，保留冲突前已提交的成功前缀 |
| SQL 文件导入 | SQL | 选择匹配的第三方导出工具配置，按后端支持的方言执行；不提供逐数据行恢复承诺 |
| 数据库／schema 多表导入 | 每个文件均为 CSV | 文件名与表名唯一匹配时自动选择目标；每个文件独立预览、CSV 选项和映射，全部预检通过后才能提交；目标或选项改变会失效旧预览 |
| 数据导出 | CSV、XLS、XLSX、JSON、SQL | 网页通过任务产物下载；桌面选择本地目录。CSV 产物明确使用 UTF-8，含逗号、引号和换行的字段由 CSV writer 转义 |
| 取消 | 排队或可取消的执行中任务 | 排队任务终止并清理资源；执行中任务先收到取消请求，实际停止后才成为 `CANCELLED`；已提交的数据保留 |
| 暂停 | 尚未开放安全暂停 | 能力接口返回 `pauseSupported=false`、`SAFE_PAUSE_UNSUPPORTED`。取消不能替代暂停 |
| 恢复 | 启动恢复后有持久 checkpoint 的任务 | 服务端只接受 `PENDING` 且有 spec/checkpoint 的任务；界面在 `PENDING + RESUMING` 时显示继续操作。不能仅按扩展名判断所有任务都可恢复 |

插件还会限制入口能力。例如 H2 在当前数据库能力判断中不开放 UI `IMPORT_EXPORT`；本机 H2 导入执行测试通过不能用来声称 H2 页面入口可用。

## 单表操作

1. 在支持导入的数据源中，右键目标表并选择导入数据。上传 CSV/XLS/XLSX 后等待预览。
2. 检查目标列、预览值和必填列。未映射的必填列、重复目标映射、失效或失败的预览都会阻止提交。
3. 预览失败可点击重试，保留成功上传的 `fileId`。改变格式会重新选择文件；改变解析选项会重新预览。重试不会自动循环。
4. 使用常规模式先验证数据。ABORT 在错误处停止；SKIP 跳过允许范围内的错误行。两者都不承诺撤销之前提交的批次。
5. 提交后以任务终态和数据库内容确认结果。进度 100% 不能替代目标行数、值和任务状态检查。

新任务弹窗的简化 CSV 选项与 rich mapped API 是不同入口。后者还支持表头位置、数据行范围、无表头以及日期、数值和空值格式。多表逐文件设置复用 rich CSV 选项。Excel rich API 的执行与预览共用 reader；当前新单表任务 UI 尚未暴露所有 rich Excel 选项。

## 多表操作与重试

数据库或 schema 的多表导入依次检查文件与目标、依赖、验证和执行设置。每个文件预览完成后检查映射；可以跳过源列，并为没有源值的目标列选择 DEFAULT 或 NULL。无法填充的非空必填列仍会阻止提交。

目标列表失败可以重试，已经成功加载的 schema 结果保留。上传失败可以单独重试失败文件，已经成功上传的文件不重复上传。删除文件、变更目标或关闭弹窗后，迟到的上传结果会释放，过期预览不会覆盖新的选择。多表预览使用现有 typed mapped preview API；任务提交仍使用统一任务 API 和 `clientSubmissionId`。

外键或用户声明的逻辑依赖决定父子表顺序。高级 staging、循环策略、序列、索引及统计功能受具体数据库插件限制。不要把普通分片写入、多表 staging 和数据库原生 `LOAD DATA` 当成相同的性能路径。

## 模式、准入与限额

`STANDARD` 是常规模式；`ULTRA_FAST` 是请求极速模式。后端会根据文件完整探测、格式、关系和资源进行准入判断，可能禁止或降级。界面展示请求模式、实际模式、阻断／降级原因、判断依据和处理建议。确认无强关联只是必要输入，不会覆盖后端拒绝。

当前产品策略是单表最多 50 MiB、多表最多 1 GiB/文件、10 GiB 总量和 1000 文件。实际可选值取产品策略、staging 配置和 HTTP multipart 限额的更小值，来自 `GET /api/rdb/import_preview/capabilities`。网页还预留 multipart 表单封装预算；桌面本地文件绕过 HTTP multipart，仍遵守 staging 和产品策略。

界面单位是二进制 MiB（1 MiB = 1,048,576 字节）。能力查询失败时，每个文件和合计均采用 50 MiB 保守回退，并提供重试；这个回退无法保证小于未知的自定义服务端限制。查询返回零表示该路径不可用，不能把零解释为无限。限额刷新收紧后，现有全部选择和提交参数都会重新校验。总量是选择预算与配置上限，不是当前剩余空间预留；并发任务仍可能被服务端最终拒绝。

## 取消与响应丢失

任务中心的取消操作会先读取控制能力，再提交取消请求。提交或恢复保护阶段可能拒绝取消；请求成功包装不能解释为取消完成。`cancellationRequested=true` 且 `status=RUNNING` 表示正在停止，需要等待实际终态。

接口均沿用 `DataResult`：

```text
GET  /api/tasks/control?taskId=123
POST /api/tasks/cancel  {"taskId":123}
```

控制状态包含 `status`、`cancelSupported`、`cancellationRequested`、`reason` 和暂停能力。其他用户／组织的任务与未知任务沿用未找到行为。导入提交的网络响应丢失时，客户端保留同一次提交身份，并通过同一个 `clientSubmissionId` 重试；不要自行生成新的身份再次写入。后端把该身份与请求指纹绑定，不能复用同一个身份提交不同内容。

## 可复用验收

使用 Java 17、Maven 3.8+、项目 Yarn 锁文件及兼容的 Node 版本。所有示例从仓库根目录执行，Maven 测试明确启用。

真实 MySQL、PostgreSQL、MariaDB 集成门禁会创建动态 loopback 容器，运行后只清理记录 ID 且 owner label 匹配的 fixture：

```powershell
python -X utf8 script/test/run-import-it.py
```

脚本的 required 模式缺少 fixture 时失败，不把跳过当通过。GitHub workflow `import-task-integration.yml` 同时执行报告和清理逻辑测试，并上传实际 Surefire 报告。提供方 Actions 未真正产生当前 head 对应的 run/job 时，不能声称 CI 已通过。

浏览器脚本需要自行启动属于当前 checkout 的 Community 后端和前端。使用一个全新测试 home，例如仓库外的 `c2db-browser-fixture/home`；先用 `script/security/init-community-encryption-key.sh <测试home>/.config/chat2db-community/encryption.key` 初始化密钥，不输出密钥。后端设置：

```text
-Duser.home=<测试home>
-Dchat2db.community.encryption-key-file=<测试home>/.config/chat2db-community/encryption.key
-Dchat2db.runtime.mode=community
-Dchat2db.network.status=OFFLINE
-Dchat2db.gui=false
-Dserver.address=127.0.0.1
-Dserver.port=10825
-Dspring.profiles.active=dev
```

沿 README 后端 build/run 命令设置 jar 与 `loader.path`；`finalName` 只改变文件名。预先把已取得的 MySQL JDBC jar 放入测试 home 的 `.chat2db-community/jdbc-lib/mysql-ui-fixture-8.3.0.jar`，或给 fixture script 传入对应 `--driver-name`。不要使用个人 home 或已有业务数据源。启动 `UMI_ENV=community` 前端 8889，实查监听地址；旧 Umi 版本的 `HOST` 展示值不能代替实际 listener 验证。

```powershell
python -X utf8 script/test/prepare-import-browser-fixture.py
$env:C2D_BROWSER_FIXTURE = 'target/import-browser-evidence/fixture.json'
# 沿用已有 Playwright；如未位于项目模块搜索路径，显式设置绝对模块路径。
$env:C2D_PLAYWRIGHT_MODULE = '<已有Playwright模块目录>'
node script/test/import-task-browser-e2e.cjs
python -X utf8 script/test/prepare-import-browser-fixture.py --cleanup target/import-browser-evidence/fixture.json
```

准备脚本只创建具名 `c2d_ui_fixture_20261013` 数据库与 `C2D_UI_*` 表，密码只在内存、进程环境和本地加密数据源 API 中使用，不写 fixture receipt。浏览器脚本只重置 receipt 指向的 loopback fixture 表，并核验数据源 alias、URL 与端口。它目前覆盖必填映射阻止提交、真实单表导入、任务成功与 100%、CSV 导出和浏览器下载的逐字段比对；未覆盖的多表、坏行、响应丢失和取消浏览器用例不能由此脚本冒充通过。

2026-10-13 Docker 恢复后，已在隔离 home、loopback MySQL fixture 与 headless 浏览器中完成单表上传／映射／提交，以及修复后的 CSV 导出下载逐字段比对：导入任务 `1791898860246999`、导出任务 `1791898862727999`，下载文件 81 字节、3 行，中文、emoji、字段内换行和小数均一致。日志最终输出 `SINGLE_IMPORT_EXPORT_BROWSER_PASSED`；收尾仅移除该测试容器并停止该轮启动的服务。详细执行证据见 [vibecoding.md](vibecoding.md) 的「真实 MySQL + 浏览器单表导入/导出闭环」。多表、坏行、响应丢失和取消浏览器用例仍未执行；Web 验收也不代替 JCEF native handler 或安装包验收。

## 本机 H2 staging 性能基线

可显式运行的 opt-in 测试（不属于默认 `*Test` 门禁）：

```powershell
mvn -B -f chat2db-community-server/pom.xml -pl :chat2db-community-domain-core -am `
  '-Dmaven.test.skip=false' '-DskipTests=false' '-Dtest=ImportPerformanceBaselineIT' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmaven.test.failure.ignore=false' package
```

结果写入 owning module `target/performance-baseline/h2-staging.json`。固定 seed 20261013，UTF-8 CSV 50,000 行、738,905 字节，SHA256 `cc8f164157816a77ffa2da83b97f9c84aa12cde838eb2ad3880abf1eb186fe5d`。每组 1 次预热、3 次测量；每次实际检查目标 count、sum、生产 checksum、连接关闭与 staging 表清理。rehearsal 均验证目标零行。

2026-10-13，Windows、Java 17.0.12、H2 2.1.214，最终 scoped CSV typed-binding 与 SKIP 重放取消修复后的源码已复测。下表为新接续聊天在修复依赖后的 fn10 实际 checkout 上再次运行的有效采样；其 domain/core 与整合分支对应源码一致。JUnit 为 1 个测试、0 失败／错误／跳过，包含 3 轮预热与 9 轮测量。原始 JSON 与 XML 已归档到本地 `.superpowers/sdd/vibecoding/review-reports/h2-baseline/`，没有根据旧日志重建采样：

| 生产 StagingManifestImporter 场景 | 每轮处理行数 | 3 次耗时（ms） |
| --- | ---: | --- |
| STANDARD staging 提交 | 50,000 | 472.441 / 301.843 / 253.201 |
| 全量 rollback rehearsal | 50,000 | 309.712 / 402.236 / 370.623 |
| 5% rollback rehearsal | 2,500 | 51.730 / 48.378 / 46.516 |

每轮 importer 打开一条连接，实际 sessions 从 1 到 2 再回到 1。JSON 保留吞吐、全部预热和测量样本、每 5 ms 采样的 JVM heap 峰值代理；该值不是进程 RSS 或精确分配峰值。固定顺序可能受 JIT、GC 和缓存影响。

计时包含 importer 执行、报告写入及连接清理，排除文件生成、分片预处理、验收查询、上传、队列和完整 TaskExecutor。50,000 行低于默认 100,000 行／64 MiB 准入阈值，direct fixture 也没有 opaque staged identity；实际评估不允许 ULTRA_FAST。本次没有改变门禁或伪造身份，没有 ULTRA_FAST 执行样本。以上数据只说明本机 H2 staging 路径；普通 CSV、MySQL/PostgreSQL/MariaDB 及符合准入的极速路径仍需各自基线，不据此作跨数据库性能承诺或无证据优化。

## 核心事务与取消审查补充（2026-10-14）

## 导入文件生命周期补充（2026-10-14）

文件归属按完整 UUID 识别，允许其已有合法大小写形式；顶层 SQL 导入和 tableSources 的持久任务引用不会因大小写不同在 claim 宽限期后被误判为孤儿。resolve 只接受 staged 路径中的普通文件，不跟随最终文件符号链接；release 删除链接本身，保留链接目标。

当前文件生命周期 26 个测试、完整 owning gate 2260 项，均零失败／错误／跳过。新增测试实际验证 File 存储重建、大写归属、H2 存储关闭重开后的活跃 SQL 源保留及 SUCCESS 终态回收、不同 UUID 的孤儿回收、真实符号链接拒绝与目标保留。24 小时未 claim 清理、7 天孤儿宽限期、活跃／可恢复任务保留、查询失败保守保留、配额和 claim/release 并发规则继续生效。

这些是资源生命周期与持久存储验证，不代替异步导入进程重启、浏览器或真实 SQL Server 验收。父目录链接、检查后文件替换、hard link/junction 等本地文件系统边界没有据此宣称全面覆盖。

### 核心事务与取消审查

批次执行返回后、提交前重新检查取消；在该边界已经观察到取消时，回滚当前自管理块并恢复连接状态，保留此前已提交的块。真实 H2 回归覆盖“执行成功、关闭通知时取消”的窗口，原块间取消测试依据实际 commit 事件判断，不依赖取消检查的调用次数。

SQL Server 插件提供同一连接的 `SELECT @@TRANCOUNT` 只读探针，覆盖命名保存点回滚和嵌套事务内层 COMMIT；探针设置 5 秒超时、登记可取消 Statement，读取失败则终止导入。其他插件通过兼容的 default SPI 方法沿用通用脚本状态；JDBC autoCommit=false 保留调用者事务。方言 SQL 保留在 SQL Server 插件，参见 [Microsoft 事务计数契约](https://learn.microsoft.com/en-us/sql/t-sql/functions/trancount-transact-sql)。

生产 SQLImporter/SQL Server GO parser 的三个回归使用驱动形态代理和真实 H2 存储，属于合同验证，没有真实 SQL Server 引擎、探针驱动取消／超时的运行证据。新核心候选真实 PostgreSQL 八项验收属于上游 PR #2969 的对应源码树，不能代替所有整合路径。整合分支当前 full owning gate 为 2255 项、零失败／错误／跳过。原有显式事务失败后的连接池归还边界将在任务恢复和浏览器取消验收中继续验证。

### 脚本取消与恢复的真实边界（2026-10-14）

隔离 MySQL 的显式 BEGIN 脚本取消复现了任务已 CANCELLED、连接47仍在 innodb_trx 的泄漏。普通脚本初始 JDBC autoCommit=true 时，失败／取消／Error 会先从当前 ConnectInfo 脱离该借用连接再关闭，避免脏事务回池；初始 autoCommit=false 的 caller 保留自身事务决定，exporter profile 继续其既有清理路径。四个真实 H2 回归验证取消、执行失败、caller 事务与已提交前缀。整合 owning package2264/0/0/0，修复后的真实 MySQL 取消任务1791934067591999 终态为 CANCELLED，未提交行0，原连接与事务均消失。close 失败物理会话消失、成功脚本遗漏 COMMIT 和其他真实引擎尚不据此宣称覆盖。

真实后端强杀／重启验证：STANDARD+SKIP 的任务1791933145770999 在已提交500行后中断，重启恢复为 PENDING/RESUMING，同一任务最终1500行且字段一致；各阶段重复提交均返回原ID，重复 resume 被拒绝。但默认 STANDARD/ABORT 在已提交622行、本地 checkpoint500时中断，恢复重放导致1062主键错误。仅本地 checkpoint 不能消除跨持久化提交窗口，不能将前一个成功推广为默认模式可靠恢复。用户已授权目标数据库工具管理回执表；该历史首红保留。其修复后的真实重启和无唯一键验收见下方数据库回执章节。

当前整合源码的浏览器上传→预览→KEY映射ID→导入结果和真实CSV下载已重新跑通：任务1791933196875999、导出1791933199157999，中文、emoji、字段内换行、小数逐字段一致。后续在 H2 与 File 两种任务存储模式下完成了真实响应丢失重试和取消／结果验证，见下方最新验收。

### Unfinished task scripts and procedural blocks (2026-10-14)

A task that borrowed an auto-commit connection must finish its script transaction and restore auto-commit before reporting success. An unfinished BEGIN or SET autocommit=0 script fails with IMPORT_SQL_TRANSACTION_OPEN and discards its borrowed connection; an initially caller-owned transaction retains its caller's decision. MySQL checks the actual session autocommit flag before insert batches, preserving SQL-controlled rollback even when JDBC caches a different flag. COMMIT alone does not restore a disabled MySQL session mode. The read-only probe has a five-second timeout and participates in task cancellation.

BEGIN/END procedural blocks are distinct from transaction control: Oracle anonymous PL/SQL blocks and SQL Server TRY blocks do not alter the generic transaction tracker. The production Oracle parser has a focused contract regression; it is not a real Oracle engine run. Six real-H2 script cases, ten transaction/parser cases and three MySQL probe contracts pass without skips. Fresh isolated MySQL BEGIN and SET-autocommit EOF cases fail, leave zero target rows and no active fixture transaction; the Chinese task error is verified. Earlier committed chunks remain committed.

## 数据库回执恢复与完整流程验收（2026-10-14）

普通单表 STANDARD 的 CSV、XLS、XLSX、JSON 导入支持数据库回执恢复。服务器生成恢复 UUID，并将完整源文件 SHA256、目标命名空间、格式／映射／转换选项绑定到源回执；该 UUID 不进入客户端提交指纹。任务重启使用持久化的原身份和任务 ID，重新读取源文件，以数据库回执判断每一行是否需要写入。一个批次尚未更新本地进度时，已提交行也不会被重写；目标无需唯一键。

工具在目标命名空间创建 `__chat2db_import_receipts_v1`。每行先在同一数据库事务中占用唯一 `(run_key,row_no)` 回执，再写目标数据，最后提交。已导入和已拒绝两种 outcome 均持久化；源／行内容改变会拒绝复用。提交回复丢失时丢弃该连接，将任务保留为 PENDING/RESUMING，保留错误码、源文件及 checkpoint，允许以新连接读取回执再恢复。用户退出也保留这个未知结果；确认前不能将其标记为安全取消。

回执使用独立任务连接，建表不会提交用户 SQL 控制台已有事务。目标需允许创建／读写回执表；已有同名表必须通过列、类型、可空性、默认版本标记和联合主键校验，否则在目标写入前失败。MySQL 系列仅准入 InnoDB；每行持有目标及回执的元数据锁并重查引擎。H2 仅准入原生 CACHED/MEMORY 表。PostgreSQL 仅准入永久 logged 普通 heap，每行持有关系锁并检查目标和回执的持久性；UNLOGGED、临时、foreign、view 和分区父表不宣称该恢复能力。

不支持回执的目标仍保留普通导入，日志明确提示无法安全重启恢复，新任务不发布可重放检查点。旧标准单表任务的本地检查点没有数据库提交证明，启动后以 IMPORT_LEGACY_RECOVERY_UNSAFE 明确失败，不能直接重放已提交行。ULTRA_FAST、多表／manifest、staging、rehearsal／延迟事务路径继续原协议，本次没有扩展或宣称它们具备此回执保证。应用回退或数据库管理员主动删表／改数据／迁移数据库，需要另行处理这些持久化状态。

回执默认永久保留，保护活动、未知提交、可恢复任务和提交重试。应用不会自动 DROP 回执表、清除回执或删除目标数据；回执空间随处理行数增长。它与应用提交键保留期是不同状态：显式启用提交键过期后再次提交会建立新的任务／恢复 UUID。数据库清理应依据已确认的终态和提交键保留要求另行安排，不在导入期间删除恢复资料。暂存源仍沿用已验证的文件归属及终态清理策略。

File 任务存储取消时，中断标志可能使 FileChannel 无法落盘终态。TaskRunner 在完成锁内清除中断，持久化结果并清理资源后恢复标志。回归分别控制“先中断后进入 catch”和“先取消 token、后中断”时序，真实 File 存储重开后均为 CANCELLED，资源只清理一次。提交阶段和未知结果恢复阶段的保护保持。

### 实际验证

| 路径 | 已运行结果 |
| --- | --- |
| 完整 owning core/web 与依赖 package | 追加 H2／旧 journal 兼容回归后的新基线2371测试，0失败／错误／跳过；显式覆盖 Surefire include 和 failure-ignore 默认值 |
| 真实 H2 回执与四格式 | Store9；CSV/JSON/XLS/XLSX4；CSV/XLSX SKIP2，包含转换拒绝和 SQL 约束拒绝，丢回复后重放保持相同拒绝行及原因 |
| 真实 PostgreSQL16 回执 | 5 项无跳过：无键重放、未知 commit、REJECTED、UNLOGGED／分区及非持久回执拒绝、相邻 schema 同名回执表隔离；自有容器已清理 |
| 默认 STANDARD、H2 任务存储、MySQL 无唯一键 | task1791940585713999，本地500／数据库706后强杀；原 ID 恢复到1500行，ID／备注／金额正确，各阶段提交重试同 ID，冲突键拒绝 |
| 默认检查点频率、File 任务存储、MySQL 无唯一键 | task1791942298951999，本地0／数据库669后强杀，原 ID 恢复到精确1500行；终态清理源文件 |
| 浏览器预览→映射→成功／下载 | 必填 KEY→ID 映射约束、真实任务、中文／emoji／字段内换行／小数及 CSV 下载逐值校验通过 |
| 浏览器真实响应丢失与取消 | 先真实提交再丢弃响应，同一表单重试同 ID，无键表仅3行；H2 模式取消31行，最终 File 模式取消28行，终态 UI、无继续写入和源清理通过 |

浏览器回归保留截图、trace、请求和数据库结果；初次定位／等待失败和 File 终态首红均保留。页面异常为0，既有 React findDOMNode 弃用警告及故意注入的 ERR_FAILED 分别记录。没有把 fixture、静态 parser／driver 合同或单纯 package 当作真实 Oracle、SQL Server、MariaDB、ULTRA 或原生安装包验收。

可复跑的专用入口：

```powershell
python -X utf8 script/test/run-import-receipt-postgres-it.py
# 先用已有 prepare-import-browser-fixture.py 创建明确的 loopback 测试数据源，
# C2D_BROWSER_FIXTURE 指向该 fixture；C2D_BROWSER_OUTPUT 必须为新证据目录。
# C2D_PLAYWRIGHT_MODULE 可复用已安装的项目 Playwright，不全局安装或抢前台。
node script/test/import-task-browser-e2e.cjs
node script/test/import-task-lifecycle-browser-e2e.cjs
```

### H2 检查点与旧 journal 兼容修复

任务存储自己的 H2 文件数据库使用 `WRITE_DELAY=0`，使已确认的 checkpoint 不依赖延迟日志刷盘。回归在隔离子 JVM 中保存 checkpoint 后立即 `halt(0)`，父进程重开数据库验证已保存水位；原实现读到0条，修复后读到1条、500行。该配置只作用于任务存储，沿用原数据库路径和事务边界，不修改导入目标库。H2 参数说明见[官方 WRITE_DELAY 文档](https://h2database.com/html/commands.html#set_write_delay)。本测试验证进程骤停后的恢复，不代表断电或磁盘硬件故障验收。

旧／直接导入的 journal 恢复探测分别读取 journal 和任务存储。当 checkpoint 查询异常时，保留已通过源文件身份校验的正水位；没有这样的水位则以 `IMPORT_FAILED` 停止，避免静默从0写入。正常新任务在读取成功且没有检查点时仍从0开始。该兼容修复不能替代数据库回执对未知提交的判定，回执路径继续按原协议读取数据库证明。

当前源码的两项首红已重现并修复：journal有效但checkpoint不可读时原实现产生101条重复拒绝；两处都无可读水位时原实现仍写目标。定向106项测试通过，包含真实H2/File存储、journal、并行回放及回执四格式/SKIP；并行生命周期夹具补齐正常 checkpoint 读写后，完整 owning package 新基线为2371项，0失败／错误／跳过。隔离QA旧基线2267不作为当前源码验收。
