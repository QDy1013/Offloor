# Offloor「楼外」更新日志

> 本文件收纳历次版本迭代与实验分支变更记录。项目定位、系统架构、评测结果与使用说明见 [README.md](README.md)。

## v0.86（端侧 1.5B 非对称权限护栏、首页模块化瘦身与参赛架构/方案材料体系）

- **端侧小模型非对称权限护栏（解决同集评测暴露的 1.5B 迎合误报）**：
  1. [Engine.java](android/app/src/com/nopalou/Engine.java) 在 `Engine.AiVerdict` 新增 `fromLocalModel` 标记，并在 `applyAi` 的未出卡新建分支中拒绝端侧小模型凭空新建待办（仅允许整理弱标题与补全已有待确认卡片的原文有据字段；云端/较大模型不受此限）。
  2. [AiClient.java](android/app/src/com/nopalou/AiClient.java) 在本机模型分支调用 `parseVerdicts(..., true)` 标记来源；[AiJob.java](android/app/src/com/nopalou/AiJob.java) 在本机小模型模式下提前跳过 `cardId == null` 的未出卡队列条目，进一步节省端侧算力与电量；[AiResponseTest.java](android/test/AiResponseTest.java) 新增 3 项端侧非对称护栏回归断言。
  3. 经 [eval/run_llm_comparison.py](eval/run_llm_comparison.py) 同集复测（100 条无泄漏 OOD）：加护栏后 1.5B 端侧复核精确率由 **`88.9%` 恢复至 `97.9%`（+9.0pp）**，F1 由 **`92.3%` 升至 `95.9%`（+3.6pp）**，端侧调用条数再降 **`31.6%`**（`19 → 13` 条 / `7 → 5` 批，总 Token 较纯 1.5B 直抽节省 **`85.3%`**）。
- **首页模块化瘦身与死代码清零（面向软件工程与 AI 代码评测）**：
  1. 新增纯逻辑零 Android 依赖的 [HomeConflictState.java](android/app/src/com/nopalou/HomeConflictState.java)（133 行）与来源样式解析器 [HomeSourceStyle.java](android/app/src/com/nopalou/HomeSourceStyle.java)（136 行），并将二级菜单、通知栏历史扫描、存档状态与成绩/余额提醒弹窗整体迁入 [HomeOpsSheets.java](android/app/src/com/nopalou/HomeOpsSheets.java)。
  2. 清理 [MainActivity.java](android/app/src/com/nopalou/MainActivity.java) 中已废弃的 `kindName`/`dueLabel`/`dueColor` 零引用死方法，主活动行数从 `2,447` 行降至 **`2,030` 行（-17.0%）**；同步更新 [test_home_visual.cjs](eval/test_home_visual.cjs) 切片锚点，95+19+30 全量回归全绿。
- **软件工程架构与赛题二参赛材料齐备**：
  1. 新增 [系统架构与模块说明.md](docs/系统架构与模块说明.md)（含分层架构 Mermaid 图、模块职责与可复现验证矩阵）、[参赛方案书大纲_v0.86.md](docs/参赛方案书大纲_v0.86.md)（逐项对标赛题二官方评分与 5 条通用要求）及 [三分钟演示分镜脚本.md](docs/三分钟演示分镜脚本.md)。

---

## v0.85（两行极简分组卡片、移除撞车横幅、考试时长撞课判定与往周课表柔化）

- **首页轻薄分组两行列表与零跳动冲突呈现**：
  1. [MainActivity.java](android/app/src/com/nopalou/MainActivity.java) 将同日期分组事项收进同一张白底细描边圆角卡（`Ui.group` + 缩进细线分隔），收紧上下内边距与行距实现轻薄对称卡面，每行精简为「左标题 + 右对齐等宽时间」，第二行只保留简洁的「来源（群名/教务系统）· 关键行动点（考场/提交渠道/待核对）· 可选冲突」（不加来源小方块图标与发送者，完整信息收进点击后的详情底抽屉）。
  2. 彻底移除会顶开页面的顶部橙色 `clashBanner` 横幅，保留原版顶部日期问候、状态胶囊与概览统计行，存在冲突时仅在统计行末尾静默并入可点计数（如 `待确认 1 件 · 共 5 件未完成 · 1 处撞车 ›`），页面结构永不跳动；[HomeCardSheets.java](android/app/src/com/nopalou/HomeCardSheets.java) 在卡片详情的时间块内列出具体冲突；[test_home_visual.cjs](eval/test_home_visual.cjs) 同步锁定新极简契约。
- **往周课表统一柔化**：
  1. [TimetableActivity.java](android/app/src/com/nopalou/TimetableActivity.java) 修复仅判断 `isCurrentWeek` 导致翻到上周及更早教学周时已上过的课程反呈高亮的问题，现 `week < currentWeek` 的全部过往周课程块统一按 `52%` 透明度柔化。
- **考试持续时长区间重叠撞课判定**：
  1. [StudyPlanner.java](android/app/src/com/nopalou/StudyPlanner.java) 新增 `examDurationMinutes`（优先从原文或教务同步时段如 `18:30–20:30` 读取，否则默认 120 分钟），`findLessonClashes` 与 `firstFreeGap` 均按 `[examStart, examEnd)` 区间重叠判定与后续课程的冲突。
  2. [Engine.java](android/app/src/com/nopalou/Engine.java) 与 [CampusChanges.java](android/app/src/com/nopalou/CampusChanges.java) 在同步教务考试卡时透传起止时段 `clockRange`；[Step61Test.java](android/test/Step61Test.java) 新增考试区间重叠与显式 90 分钟/整点边界回归用例。

---

## v0.84（修复课表与考试成绩空态标题左偏对齐）

- **空态引导页标题居中修复**：[TimetableActivity.java](android/app/src/com/nopalou/TimetableActivity.java)（`把这一学期，装进口袋`）与 [AcademicActivity.java](android/app/src/com/nopalou/AcademicActivity.java)（`考试和成绩，一处看全`）空态标题在纵向 `LinearLayout` 中默认拉满宽且未设 `Gravity.CENTER` 导致偏左，现统一补齐居中对齐并统一两页空态左右留白与垂直重心。

---

## v0.83（校园余额格式统一、本机模型快捷复测与真机端侧实测）

- **校园账户总览金额格式统一**：[CampusSummary.java](android/app/src/com/nopalou/CampusSummary.java) 将 `network`（校园网）与 `card`（一卡通）数值余额统一经 `CampusBalance.money(...)` 格式化为 `¥XX.XX`（与宿舍电量保持一致），同步更新 [CampusAutoTest.java](android/test/CampusAutoTest.java) 与 [CampusScopeTest.java](android/test/CampusScopeTest.java)。
- **AI 识别页本机模型快捷复测**：[AiActivity.java](android/app/src/com/nopalou/AiActivity.java) 在「本机小模型」设置卡片内新增与「导入 .gguf」并列的**「测试模型」**按钮，并将加载/耗时结果同步回显在卡内状态框，无需重新走完四步向导即可直接复测。
- **真机实测与无隐私界面截图更新**：在 vivo V2505A（Android 16 · arm64-v8a）完成 `v0.83` 全链路与端侧 `Qwen2.5-1.5B-Instruct Q4_K_M`（1.04 GB）真机实测（冷加载试跑 `2.927s` / 温态 `2.357s`，PSS `251.4 MB → 1.79 GB → 256.8 MB` 即时释放，`0` 崩溃 / `0` ANR），并在 [README.md](README.md)、[方案书对照.md](docs/方案书对照.md)、[端侧小模型.md](docs/端侧小模型.md) 与 [演示与答辩要点.md](docs/演示与答辩要点.md) 中补齐实测数据与内置「离线演示」无隐私浅色/深色/详情截图。

---

## v0.82（双端分布外评测对齐、30 天过期清理、校园同步脱敏自检与课表自习空档直达）

- **APK Java 引擎与 Python 引擎规则对齐（分布外评测达平齐）**：
  1. [Engine.java](android/app/src/com/nopalou/Engine.java) 与 [jielong.py](engine/jielong.py) 同步补齐无「接龙」字头的连续编号名单识别（`>= 3` 条连续人名接龙），同步月日日期守卫（如 `3.23 22:00 前报名` 不误判为编号列表），并用任务动词守卫过滤编号作业/步骤清单。
  2. [Engine.java](android/app/src/com/nopalou/Engine.java) 与 [pipeline.py](engine/pipeline.py) 同步将星期/时段词加入 `REVISION_STOP`（防止「本周五班会取消」仅靠「周五」误撤同日体检回执待办），并补充指代性时间撤回回退（支持「周五那个不用交了」唯一匹配撤卡，多张冲突仍转待核对）。
  3. 100 条无泄漏分布外评测（`eval/strict_ood.jsonl`）上，APK Java 引擎召回由 `92.0%` 升至 **`94.0%`**、F1 由 `94.8%` 升至 **`95.9%`**、截止时间准确由 `87.5%` 升至 **`90.0%`**、接龙召回由 `80.0%` 升至 **`100.0%`**，与 Python 三级流水线完全持平；在 8,086 条合成留出集上，Java 截止时间准确由 `90.80%` 升至 **`93.25%`**，Python 截止时间准确由 `89.67%` 升至 **`92.40%`**（变更准确由 `74.47%` 升至 **`80.85%`**，双端接龙均 `100% / 100%`）。
- **隐私清理、校园同步脱敏自检与跨源学习闭环入口**：
  1. [Engine.java](android/app/src/com/nopalou/Engine.java) 新增 30 天过期数据清理 `pruneExpired(...)`，由 [EngineHolder.java](android/app/src/com/nopalou/EngineHolder.java) 在启动时自动清理超过 30 天的已完成卡片与已移除记录，**所有未完成待办即使过期也永不自动删除**。
  2. 新增纯 Java 脱敏自检工具 [CampusDiag.java](android/app/src/com/nopalou/CampusDiag.java)，并在 [CampusActivity.java](android/app/src/com/nopalou/CampusActivity.java) 右上角设置菜单增加「复制同步自检」，仅导出模块状态、字段标签计数与去除查询参数的路径，严格屏蔽学号、账号哈希、余额数字、姓名、宿舍、密码与临时令牌。
  3. [TimetableActivity.java](android/app/src/com/nopalou/TimetableActivity.java) 在本周课表页轻量恢复跨源学习空档提示条（复用 [StudyPlanner.java](android/app/src/com/nopalou/StudyPlanner.java) `findStudyGap`），不增加首页负担即可一键预填时段跳转**空教室查询**或**图书馆研修室预约**。
- **单测与文档对齐**：
  1. 新增 [Step65Test.java](android/test/Step65Test.java) 并扩展 [CampusDataTest.java](android/test/CampusDataTest.java) 与 [test_strict_benchmark.py](eval/test_strict_benchmark.py)，全量核心与适配器回归扩至 **95 组**；同步刷新 [README.md](README.md)、[方案书对照.md](docs/方案书对照.md) 与 [演示与答辩要点.md](docs/演示与答辩要点.md)。

---

## v0.81（修复校园网与一卡通余额同步、兜底及详情显示）

- **门户卡片同步时序与渐进保留**：
  1. 新增 [CampusPortalScan.java](android/app/src/com/nopalou/CampusPortalScan.java) 统一协调 [CampusSilentRefresh.java](android/app/src/com/nopalou/CampusSilentRefresh.java) 与 [CampusWebActivity.java](android/app/src/com/nopalou/CampusWebActivity.java) 的门户扫描：改以真正落地 `ecampus.nwpu.edu.cn` 后重新计时，在 `onPageStarted` 提前注入 `NwpuAuto.install()`，并先等待首页异步 `.hotdata-ul-li` 余额渲染再点击「应用中心」，避免因点击目录切页或登录超过 5 秒而直接丢失 `network`/`card` 余额。
- **一卡通统一认证兜底与多布局余额解析**：
  1. [CampusBatch.java](android/app/src/com/nopalou/CampusBatch.java) 与 [CampusWebActivity.java](android/app/src/com/nopalou/CampusWebActivity.java) 在缺少门户余额或一卡通首页返回 401/未登录态时自动复用 `ELECTRIC_SSO`（`/berserker-auth/cas/login/supwisdom`）进入一卡通首页，并放宽 SPA 落稳等待窗口。
  2. [nwpu-campus.js](android/app/assets/nwpu-campus.js) 与 [nwpu-sso.js](android/app/assets/nwpu-sso.js) 补充多行/无冒号余额卡片、金额在前标签在后、`4` 列键值对表格、Vant 列表、门户 `HotData`/`#app` Vue 状态兜底（隔离过渡/冻结余额与 `synjones-auth` 临时令牌），以及 `<input>`/`[onclick]` 统一认证按钮识别。
  3. [CampusServices.java](android/app/src/com/nopalou/CampusServices.java) 阻止持久化含 `synjones-auth` 的临时认证链接。
- **一卡通详情页与账户总览展示修复**：
  1. [CampusActivity.java](android/app/src/com/nopalou/CampusActivity.java) 修复详情页误判 `"ecard".equals(s.id)` 的问题（改为 `"card".equals(s.id)`），并在账户总览区分“已读到有效余额”与“仅同步到非余额字段”，统一适配 [CampusSummary.java](android/app/src/com/nopalou/CampusSummary.java) 与 [CampusChanges.java](android/app/src/com/nopalou/CampusChanges.java) 的余额字段标签。

---

## v0.80（界面极简重构、跨源课表上下文与端侧评测）

- **全套界面极简重构与图标重绘**：
  1. [Ui.java](android/app/src/com/nopalou/Ui.java)、[CourseUi.java](android/app/src/com/nopalou/CourseUi.java) 与应用资源主题统一切换为暖羊皮纸 `#FAF9F5` / 深墨 `#141413` + 素白卡片细描边 + 陶土赤陶 `#C96442`（深色 `#D97757`）单强调色与衬线标题层级；[AppNavigation.java](android/app/src/com/nopalou/AppNavigation.java) 改为贴底 `1px` 细线导航栏。
  2. [MainActivity.java](android/app/src/com/nopalou/MainActivity.java) 去除深墨概览方块、卡片冗余方框标签与常驻自习推荐卡片，改为可折叠搜索与 4 项分段筛选；「更多」菜单精简为 7 项核心入口，拆出 [HomeCardSheets.java](android/app/src/com/nopalou/HomeCardSheets.java) 与 [HomeOpsSheets.java](android/app/src/com/nopalou/HomeOpsSheets.java)。
  3. [TimetableActivity.java](android/app/src/com/nopalou/TimetableActivity.java)、[AcademicActivity.java](android/app/src/com/nopalou/AcademicActivity.java)、[CampusActivity.java](android/app/src/com/nopalou/CampusActivity.java)、[StudyRoomActivity.java](android/app/src/com/nopalou/StudyRoomActivity.java) 与 [CampusSyncBanner.java](android/app/src/com/nopalou/CampusSyncBanner.java) 全面轻量化，去除厚重渐变块、堆叠大按钮与粗同步圆环；重绘桌面自适应图标与通知小图标 `ic_notif`。
  4. [ShowcaseActivity.java](android/app/src/com/nopalou/ShowcaseActivity.java) 与 [ShowcaseScenario.java](android/app/src/com/nopalou/ShowcaseScenario.java) 移除构造的样例自习地点假交互。
- **跨源课表上下文、AI 线索与严格评测**：
  1. [StudyPlanner.java](android/app/src/com/nopalou/StudyPlanner.java)、[EngineHolder.java](android/app/src/com/nopalou/EngineHolder.java) 与 [Engine.java](android/app/src/com/nopalou/Engine.java) 保留静默课表课程同步（`setTimetableCourses` / `effectiveProfile`）与真实撞课/撞车提醒；[AiClient.java](android/app/src/com/nopalou/AiClient.java) 增加课表匹配线索 `matched_course`。
  2. 新增有状态序列评测（[run_sequence_eval.py](eval/run_sequence_eval.py)）、无泄漏分布外评测集（[strict_ood.jsonl](eval/strict_ood.jsonl)、[run_benchmark.py](eval/run_benchmark.py)）与端侧推理验证。

---

## v0.79 — v0.41（近期客户端演进）

- **v0.79** 回退 `v0.78` 视觉改版，恢复 `v0.77` 经典界面布局与全部稳定功能。
- **v0.77** UI 质感与视觉层次升级：1）在 [TimetableActivity.java](android/app/src/com/nopalou/TimetableActivity.java) 本周课表中自动柔化已结束课程块（52% 透明度）并为当前正在上的课程块增加主题色描边高亮；2）在 [CampusActivity.java](android/app/src/com/nopalou/CampusActivity.java) 账户总览三栏卡片中直接展示电费/校园网「余额偏低」橙色角标；3）在 [StudyRoomActivity.java](android/app/src/com/nopalou/StudyRoomActivity.java)「我的预约」顶部新增深墨渐变「当前使用中 / 下一场研修室预约」主卡；4）在 [CourseUi.java](android/app/src/com/nopalou/CourseUi.java) 全局启用等宽数字（`tnum`）对齐时间、绩点与金额，并为 [AcademicActivity.java](android/app/src/com/nopalou/AcademicActivity.java) 操作胶囊统一补全轻按微缩反馈。
- **v0.76** 实用细节与一键分享升级：1）在 [CampusActivity.java](android/app/src/com/nopalou/CampusActivity.java) 为校园卡详情页新增大字余额展示，并在空闲教室结果标题旁新增「复制列表」一键分享空闲教室清单；2）在 [StudyRoomActivity.java](android/app/src/com/nopalou/StudyRoomActivity.java) 研修室卡片上直接展示「今天还有 N 个可约时段」提示；3）在 [AcademicActivity.java](android/app/src/com/nopalou/AcademicActivity.java) 待考日程新增「复制全部待考」一键复制全部待考科目、时间与地点。
- **v0.75** 校车与研修室浏览效率升级：1）在 [CampusActivity.java](android/app/src/com/nopalou/CampusActivity.java) 实时校车区新增「未发车/可购班次与最近可购时间」摘要条，并在多条路线时提供横向路线快捷筛选胶囊；2）在 [StudyRoomActivity.java](android/app/src/com/nopalou/StudyRoomActivity.java) 全部房间视图中稳定优先展示「可预约」研修室，无需先翻越暂不可约房间。
- **v0.74** 教务与课表闭环打磨：1）在 [AcademicActivity.java](android/app/src/com/nopalou/AcademicActivity.java) 新增「全部展开 / 全部收起」学期切换、成绩总览卡「复制摘要」一键复制绩点与各学期走势，以及点击「下一场考试」主卡一键复制时间地点；2）在 [TimetableActivity.java](android/app/src/com/nopalou/TimetableActivity.java) 本周无课或当日课程结束后展示柔和提示胶囊（「今天没有课程安排 / 今日课程已全部结束」）。
- **v0.73** 校园服务余额与低余额预警视觉强化：在 [CampusActivity.java](android/app/src/com/nopalou/CampusActivity.java) 宿舍电费与校园网详情页中，复用 `CampusBalance` 与用户配置的提醒阈值，为电费低余额与校园网余额新增醒目大字金额展示与橙色预警胶囊，打开服务页即可一眼判断是否需要充值。
- **v0.72** 商业化高频交互打磨：1）在 [TimetableActivity.java](android/app/src/com/nopalou/TimetableActivity.java) 本周课表顶部新增实时「正在上课 · 还剩 X 分钟 / 下节课 HH:mm · 还有 X 分钟」状态胶囊，点击直达课程详情；2）在 [CampusActivity.java](android/app/src/com/nopalou/CampusActivity.java) 空闲教室查询面板新增「现在起 1 小时 / 现在起 2 小时」一键快捷时段预设并立即触发查询；3）在 [AcademicActivity.java](android/app/src/com/nopalou/AcademicActivity.java) 成绩单页新增跨学期实时课程名/类别搜索栏（就地局部刷新不抢焦点，匹配项自动附带学期标签）。
- **v0.71** 多日切页与空闲教室详情提速：1）将 [StudyRoomActivity.java](android/app/src/com/nopalou/StudyRoomActivity.java) 房间短时缓存升级为按日期键控的 7 天 LRU 缓存（60 秒有效，手动刷新/提交/取消/官方重连自动失效），在「今天 ↔ 明天 ↔ 后天」之间反复比对空位秒切不重新拉取；2）将 [CampusClassroomRefresh.java](android/app/src/com/nopalou/CampusClassroomRefresh.java) 单间教室当日节次占用轮询间隔由 `300ms/400ms` 缩短至 `120ms/150ms`，点开教室详情更快出结果。
- **v0.70** 系统级性能与稳定性优化：1）为 [CampusSilentRefresh.java](android/app/src/com/nopalou/CampusSilentRefresh.java) 与 [CampusBusRefresh.java](android/app/src/com/nopalou/CampusBusRefresh.java) 的隐藏后台 `WebView` 启用 `setBlockNetworkImage(true)`，跳过静默刷新与校车查询期间的无用图片下载，加快同步速度并省电省流量；2）优化 [TimetableActivity.java](android/app/src/com/nopalou/TimetableActivity.java) 课表网格重绘（预索引节次时间表消除循环内重复扫描，并隔离「现在」红线定时器 `nowLineTick`，避免误取消后台数据同步触发的 `reloadTask`）；3）在 [test_study_room.cjs](eval/test_study_room.cjs) 补充跳过冗余多类目逗号探测的回归断言。
- **v0.69** 优化校园校车实时查询体验：在 [CampusActivity.java](android/app/src/com/nopalou/CampusActivity.java) 查看当日校车实时班次时默认开启「只看未发车」（自动过滤已发车班次，无需从早班一路下翻），并支持一键切换「全部班次」或全部发车后一键查看今日全部班次。
- **v0.68** 新增常用同行人一键复选：从校方通讯录检索并添加真实同行人后，按当前校园账号在本地记住最近 6 位已核验同行人，下次预约任意研修室时直接在「添加同行人」下方显示 `＋ 姓名` 快捷胶囊一键加入（提交仍经校方实时校验与手动确认，绝不伪造身份）。
- **v0.67** 优化研修室预约填写效率：在预约确认弹窗的「选择时段」下动态生成合法的快捷时长胶囊（`30分钟`/`1小时`/`2小时`/`3小时`，仅展示满足房间时长、间隔且不撞占用的选项），并在「研修内容」下增加常用主题快捷填入胶囊（`小组讨论`/`课程复习`/`论文研讨`），减少手机端输入与滚动。
- **v0.66** 进一步优化研修室查询速度与浏览体验：1）`queryReserve` 在无参首屏请求已拿到房间数据时跳过冗余的多类目逗号拼接探测（减少 1 次往返请求并降低校方频控压力）；2）`StudyRoomActivity` 新增「只看可约 N」快捷筛选胶囊，并加入 60 秒短时房间缓存（切换「看空闲房间 ↔ 我的预约」秒开不重复拉取；手动刷新、重连或提交/取消后自动失效）。
- **v0.65** 优化研修室补查与官方核验体验：1）抽取 `missingCampusItems` 共用过滤逻辑，让 `out.leaves` 叶子类目补查同样跳过已命中的校区（避免友谊校区已加载时重复请求友谊类目，进一步加速并降低频控压力）；2）官方核验弹窗顶部增加「已登录校方平台 · 点底部『预约管理』核验」提示与「返回我的预约」按钮并列展示。
- **v0.64** 在「我的预约」顶部说明卡新增「去西工大官方平台核验预约」按钮，点击后复用已登录会话打开 `ic.nwpu.edu.cn` 官方页面弹窗（「预约管理」可直接核验真实预约记录），关闭后自动返回并刷新「我的预约」。
- **v0.63** 修复真机实测 `v0.62` 时长按/点击可约时段出现的 `校方验证码配置尚未读全 [arr:33]`：扩展 `extractConfigMode` 支持从 33 项键值配置数组条目中提取 `resvCode`（并保留未知值安全拦截），同时丰富数组配置诊断标签。
- **v0.62** 修复真机实测定位的两个关键阻塞：1）`roomMenu?sysKind=1` 已返回两校区类目时不再并发探测 `kind`/`lab`（消除 `权限不足 [lab]` 无效请求），并将 `tryBatch` 降至 2 路限速并发与 `请求频繁` 单次退避重试；2）`loadConfig` 支持在无参 `sysConfig/public` 缺少 `resvCode` 时继续回退并解析数字或嵌套 `resvCode`，同时修复 `refreshRoom` 慢回退分支取 `.res` 的笔误。
- **v0.61** 进一步修复真机实测发现的「`请求参数缺失 [sysInfo]`」与「长安校区返回 0 间研修室」问题：打开预约弹窗时透传当前房间标识并在 `sysInfo` 增加参数线索发现；`kind` 类目补查扩展多维回退，并在仍缺失校区时直接展示类目明细与官方接口片段诊断。
- **v0.60** 修复研修室预约流程报错「请求参数缺失」：为 `sysInfo`、`sysConfig/public`、`account/getMembers` 与 `reserve/resvInfo` 只读请求增加兼容参数自动回退与成功参数缓存；提交写操作（`POST /ic-web/reserve`）沿用房间实际属性并保持单次派发不重试，在报错中附带具体接口路径（如 `[sysInfo]`、`[POST reserve]`）。
- **v0.59** 优化研修室连接与加载速度：隐藏 `WebView` 连接期间屏蔽网络图片加载并监听 `onPageCommitVisible`，缩短连接与查询轮询间隔；`loadMenu` 改用并发请求并按会话令牌缓存目录，读操作复用短时身份校验（写操作仍强制实时核验）。
- **v0.58** 修复西工大图书馆研修室缺少「长安校区」：取消 `queryReserve` 仅拿到单校区结果后的过早返回，按 `roomMenu` / `kind` / `lab` 多维度补查并合并去重两校区房间；原生校区筛选条固定展示「长安校区」「友谊校区」。
- **v0.57** 修复西工大 `ic-web` 实测研修室全部「暂不可约」并支持按校区分类：支持 `openLimit: 1` 与省略 `cls` 的合法结构、紧凑 `HHmm` 放号时间、5 分钟间隔 288 时段与校区筛选；批量聚合扩至 40 类目。
- **v0.56** 修复研修室全部显示「暂不可约（没有读到开放时段；课表占用没读全）」：`timeScopeOpenInfo` 支持开放截止日缺省、`YYYYMMDD`/时间戳日期格式及 `HH:mm` 时钟归一化，并将显式 `"cls": null` 识别为空课表占用。
- **v0.55** 修复研修室查询报错「请求参数错误」：改用 `ic-web` `sysKind=1` 对应的单分类/单楼栋参数及分批聚合查询，并支持 `resvInfo` 毫秒级时间戳转换。
- **v0.54** 研修室稳定性加固：避免 `onPageFinished` 与后台轮询产生并发 `connect` 循环；精简过滤参数提取。
- **v0.53** 修复研修室查询报错「参数不能全为空」：当 `/ic-web/reserve` 要求筛选参数时，自动从 `/ic-web/roomMenu` 或 `/ic-web/lab` 获取并补充。
- **v0.52** 修复研修室提示「没有缓存」：改为从 `https://ic.nwpu.edu.cn/` 首页发起连接与验证，动态生成认证会话。
- **v0.51** 研修室连接诊断与超时优化：放开 `*.nwpu.edu.cn` 校内 SSO 跳转域名白名单，缩短超时至 25 秒，支持连接中随时打开校方页面验证。
- **v0.50** 优化研修室连接速度：页面加载完成立即触发连接检查，将轮询间隔缩短至 200ms / 100-150ms。
- **v0.49** 修复西工大研修室登录与校方验证弹窗：等待 `/authcenter/` 票据交换并写入 `sessionStorage.userInfo.token` 后再校验登录态，保留原 `WebView` 会话。
- **v0.48** 概览卡点击稳定性修复：确保整个「今日概览」卡片区域可点击且隐藏顶部吸顶条时使用 `GONE`。
- **v0.47** 修复回归检查：恢复 `stats.setText(TextUtils.join(...))` 形式。
- **v0.46** 交互与界面打磨：时间选择面板增加「时/分」栏位、实时时刻胶囊与常用时刻高亮态；空闲教室教学楼多选改为底部卡片面板；首页概览卡增加水波纹与「点按查看 ›」。
- **v0.45** 概览卡联动筛选：点击首页顶部「今日概览」卡片时，按当前主提示直接跳转到对应筛选列表。
- **v0.44** 细节微调：未设置截止时间时统一显示为「待设置」。
- **v0.43** 界面与筛选优化：手动任务表单移除「暂不设置时间 / 清除」按钮；空闲教室查询支持多选教学楼。
- **v0.42** 时间选择优化：将卡片截止时间、手动任务及空闲教室查询中的系统时钟圆盘统一替换为底部「滚轮 + 快捷时刻」选择面板（`Ui.pickTime`）。
- **v0.41** 电费与网费：校园页电费按可配置电价（默认 0.5 元/度）折算金额，低于阈值（默认 20 元）时提醒一次，并提供「去交电费 / 去交网费」跳转校方官方页面（不代收不保存卡密）。用例：`node eval/run_test.mjs Step60Test`。详见 [缴费与余额提醒](docs/缴费与余额提醒.md)。

---

## v0.40 — v0.31（AI 复核与研修室起步）

- **v0.40 研修室预约体验改进**：房间卡片直接列出可点时段，支持最近 7 天快选；预约面板实时校验时长、人数、占用以及与已有预约/当天课程的冲突；校方受理后可一键生成开始前 2 小时提醒；不后台抢约、不绕过验证码、不自动重试写请求。用例：`node eval/run_test.mjs Step59Test`、`node eval/test_study_room.cjs`。详见 [研修室预约改进](docs/研修室预约改进.md)。
- **v0.39 研修室与手动任务**：校园页新增研修室入口，首页「＋ 新建任务」支持标题、时间、地点和备注。详见 [研修室与手动任务](docs/v0.39研修室与手动任务.md)。
- **v0.38 首页新增栏**：在「全部」左侧显示「新增」及数量，首次启用建立历史基线。用例：`node eval/run_test.mjs Step57Test`。
- **v0.37 AI 补审**：AI 开启后补审待确认群消息卡；原文时间本身含糊时不编造时间，卡片保持待确认并带「AI 已复核」。用例：`node eval/run_test.mjs Step56Test`。
- **v0.36 首页与导航**：筛选默认「全部」置顶，「待确认」显琥珀色，底部导航改为悬浮胶囊，支持「AI 复核中 / AI 已复核」状态指示。
- **v0.35 AI 复核设置向导与界面统一**：四步清单与向导带用户选本机或云端、准备模型、测试并开启。见 [端侧小模型](docs/端侧小模型.md)。
- **v0.34 可选端侧小模型复核**：`llama.cpp` 推理库编入安装包，支持 App 内下载或导入推荐模型（如 Qwen2.5-1.5B Q4_K_M），消息不出手机。
- **v0.31 情境演示与操作记录**：演示与真实卡片隔离，日常修正、完成/撤销、移除和恢复均保留可持久化操作记录。详见 [情境演示与操作记录](docs/v0.31情境演示与操作记录.md)。

---

## v0.29 — v0.9（早期里程碑）

- **v0.29**：收紧变更/更正目标匹配，遇到多张同样匹配的卡时保留原时间并标为待核对；合成集时间准确率由 92.3% 调整至 89.7%，变更处理 74.5%，避免盲目覆盖。
- **v0.27 校园数据深化与视觉/云端复核框架**：增量比对防误报、教务考试入卡去重、友谊校区夏/冬季作息、桌面小组件「今日课程与待办」、`AiClient` / `AiQueue` / `AiJob` 与截图 OCR 入口。
- **v0.26 校园信息聚焦**：聚焦网费、电费、一卡通、空闲教室、校车与待办截止；校车内置核对过的 50 条官方学生班次（范围见 [校园数据接入](docs/校园数据接入.md)）。
- **v0.22 应用内更新**：渠道化更新检测、SHA-256 摘要/包名/版本/签名校验后再交给系统安装。
- **v0.19 Offloor 课表集成**：固定「待办 / 课表」底部主导航，接入西工大等高校课表导入（见 [多校课表测试](docs/多校课表测试.md)）。
- **v0.16 按群管理**：「重点关注 / 普通 / 不用管」三档策略持久化。
- **v0.15 学习平台通知**：学习通、智慧树/知到、雨课堂模板解析。
- **v0.14 「楼外」与首次引导**：不再爬楼，人在楼外；自适应图标与国产后台保活引导清单。
- **v0.13 界面重做**：确定「纸面 + 墨色」视觉体系与完成撤销动画。
- **v0.12 — v0.9**：冷白探索、行动清单与来源应用图标映射、`NotificationListenerService` 通知自动识别与性能基线优化。
