# 小说式 PDF 阅读器 · v2（PdfRenderer + Room + 自动生成目录）

把 PDF 当小说读的 Android 阅读器：横向整页翻页、断点续读、可折叠的层级目录抽屉，
核心能力是**自动生成目录**——包括那些没有书签、整页都是扫描图的 PDF。

- **只跑 Android 14 / 15**（`minSdk 34` / `targetSdk 35`，不做向下兼容）
- **渲染只用系统 PdfRenderer**：零第三方 PDF 库、零 native so
- **数据全部在本地**：Room 三张表，无网络请求；OCR 走 ML Kit 设备端模型
- 四种目录来源与优先级见 §5，两本真实书籍的实测数据见 §2 与 §9
- 也支持导入自备的词书（CSV / TSV / JSON）做查词，见 §14

> v1 的说明见文末「附录：v1 设计回顾」。v2 是针对两类真实 PDF（纯扫描件、带书签的合集）重构的版本。

---

## 1. 这一版改了什么

| 项 | v1 | v2 |
| --- | --- | --- |
| 渲染内核 | `android-pdf-viewer 2.8.2`（pdfium，带 native so） | **只用系统 `PdfRenderer` + ViewPager2**，零第三方 PDF 库、零 native so |
| 构建配置 | AGP 8.5.2 / compileSdk 34 / minSdk 21 / 必须开 Jetifier | AGP 8.6.1 / compileSdk 35 / **minSdk 34 / targetSdk 35** / Jetifier 已关闭 |
| 持久化 | Preferences DataStore + 手写 JSON | **Room 3 张表 + KSP**，章节缓存在库里 |
| 章节模型 | 扁平 | **层级化（GROUP/CHAPTER/FRONT/BACK + parentIndex）**，抽屉可折叠 |
| 目录来源 | pdfium 读书签（反射） + JSON | **自研 PDF 大纲解析器** + **ML Kit OCR 重锚定** + JSON + assets |
| 大书支持 | 未验证 | 按 2278 页的合集设计（二分索引、任务进度、可取消、结果入库） |

---

## 2. 两类目标 PDF 的实测结论（这决定了架构）

> 这一节以及后面反复提到两本具体的书。**本仓库不包含任何书籍正文**，只有程序生成的目录条目；
> 相关版权说明见 [§12 免责声明与版权说明](#12-免责声明与版权说明)。

### 2.1 剑桥《真爱无界》（`剑桥小说馆2级08`）

| 指标 | 实测值 |
| --- | --- |
| 体积 / 页数 | 45.3 MB / **92 页** |
| PDF 版本 / 生成者 | 1.3 / `DuXiu/SuperStar`（读秀扫描），`AnnasArchive` 二次封装 |
| 内置书签 | **0 条** |
| 文字层 | **完全没有**：每页就是一张整页位图（`1472×2305` 左右），字体数 0、字符数 0 |
| 实际结构 | p1 封面 → p2 版权/级别表 → p3 扉页 → p4 版权页 → p5 **Contents 目录** → p7 版权页 → p8 前言 → p9 目录 → p11 人物 → p12 地点 → p13 读前思考 → **p14 `Chapter 1 A hand on a guitar`** → … → Chapter 11（印刷页码 9~53）→ 参考译文 |
| 末页 | p92 是 Anna's Archive 加的元数据页（里面写着原名 `Different Worlds`、`pdg_main_pages 86`、`total_pages 91`） |

**结论：这本书没有书签、没有文字层，唯一可行的自动目录方案是 OCR。**
它属于"原生排版 PDF 被整页栅格化"，字迹清晰（逐页核对过缩略图），
`Chapter N ...` 大标题就在正文页顶部，ML Kit 识别毫无压力。

### 2.2 书虫 4 级合集（`书虫·牛津英汉双语读物(第4级全)(套装共16本)`）

| 指标 | 实测值 |
| --- | --- |
| 体积 / 页数 | 24.1 MB / **2278 页** |
| PDF 版本 / 生成者 | 1.4 / `calibre 8.5.0`（**从 EPUB 转出来的**） |
| 内置书签 | **506 条，3 层**（`总目录` / `第4级上` / 16 本书 / 每本书的 封面·扉页·版权页·简介·章节·习题·封底`） |
| 文字层 | 有（这些页都是文本页，只有封面/扉页少数页是图） |
| 层级实测 | `第4级上` p3 起含 9 本：巴斯克维尔猎犬 p14、不平静的坟墓 p181、三怪客泛舟记 p300、三十九级台阶 p461、小妇人 p603、克兰福德 p750、华盛顿广场 p892、织工马南 p1044、化身博士 p1172；`第4级下` p1292 起含 7 本：双城记 p1302、格列佛游记 p1439、金银岛 p1564、黑骏马 p1705、红字 p1834、吉姆老爷 p1977、洛娜·杜恩 p2132 —— **正好 16 本** |

**这本书有目录，但它的页码是坏的**，这是本次改造最重要的发现：

```
大纲说 "1 The Case Begins" 在第 15 页   →  实际在第 19 页   （偏移 +4）
大纲说 "18 Looking Back"   在第 109 页  →  实际在第 168 页  （偏移 +59）
506 条里 396 条页码逆序；偏移量从 +4 一路涨到 +61，**不是常数**
```

原因很清楚：calibre 从 EPUB 转 PDF 时，书签锚点保留了 **EPUB 逻辑页**，而 PDF 重排后每页字数完全不同，
于是偏移量随页数线性漂移。**所以"整体加个偏移"这种修法必然失败。**
但有一项关键性质：**标题文字和排列顺序完全正确**，而且 136 条里 109 条能按标题文本在页面里重新定位。
→ 这就是 v2 里 `TocMode.OCR_REANCHOR`（按标题重锚定页码）存在的原因。

另外注意一个大纲里的坑：`版权页`(p12) 排在 `扉页`(p15) 之后——**大纲顺序与实际页码顺序不一致**。
v1 的"按页码二分找当前章节"在这种数据上会算错，v2 改成"叶子按页码排序的副本 + 二分"，显示顺序仍按大纲遍历顺序。

---

## 3. 架构与数据流

```
ShelfActivity ──► BookRepository（SAF 导入 → bookId(MD5) → 应用私有副本）
      │                    │
      │                    ▼
      │            LibraryRepository ──► Room（books / chapters / settings）
      ▼                    ▲
ReaderActivity ──► ReaderViewModel ──┬── 进度：翻页 snapshot → debounce(800ms) → Room
   │  PdfRenderer        │            └── 生命周期：onPause/onStop/onCleared → saveProgressNow()
   │  + ViewPager2        │                 （投递到 Application 作用域，不受 Activity 取消影响）
   │                      └── 章节：observeChapters(Room) → resolveRanges + PageIndex(二分)
   ▼                                        ▲
TapZoneLayout / 顶栏 / 底栏 / 章节抽屉       │
      │                                     │
      └──「生成目录」──► TocRepository ──► TocGenerator ──► PdfOutlineParser（自研）
                              │                     └────► PdfRasterSession + OcrEngine(ML Kit)
                              └── 结果落 Room，进度经 StateFlow 回传抽屉
```

要点：**Room 是唯一真相源**。目录生成跑在 Application 作用域，退出阅读器也会跑完，
结果直接入库，UI 通过 `observeChapters` 自动刷新——不需要任何回传/回调。

---

## 4. Room 表结构

```kotlin
books(bookId PK, uri, title, local_path, total_pages, page_index, chapter_index,
      added_at, last_read_at, toc_source, toc_generated_at)

chapters(book_id, chapter_index, parent_index, level, kind, title, title_en,
         start_page, end_page, confidence,
         PRIMARY KEY(book_id, chapter_index), INDEX(book_id, start_page))

settings(key PK, value)
```

- 进度与书籍元信息**合并进 books 一张表**：断点页码本质就是"这本书的一条状态"，
  拆两张表只会让每次翻页都跨表事务。
- `chapters` 缓存的是**生成结果**：2278 页的书每打开一次都重新解析 506 条大纲纯属浪费，
  OCR 生成的目录更必须持久化（跑一次要一两分钟）。
- `fallbackToDestructiveMigration()`：个人自用单设备，目录随时可重跑，不背 Migration 包袱。

---

## 5. 目录生成：三条路径

| 模式 | 适用 | 代价 | 产物置信度 |
| --- | --- | --- | --- |
| `OUTLINE_ONLY` | 正规电子书（书签页码可信） | 秒级 | 100 |
| `OCR_REANCHOR` | **有书签但页码漂移**（书虫合集） | 每页渲染+识别页眉，约 30~60ms/页 | 命中 90 / 未命中 30 |
| `OCR_SCAN` | **无书签的扫描件**（剑桥《真爱无界》） | 同上 | 60 |
| `AUTO` | 默认：先 JSON → 再书签（不可信就转重锚定）→ 再全页眉扫描 | 视情况 | — |

优先级：`LOCAL_JSON`（人工校正，永远第一） ＞ `PDF_OUTLINE`/`OCR` ＞ `ASSET_JSON`。

### 5.1 自研 PDF 大纲解析器（`PdfOutlineParser`）

`PdfRenderer` 只有"画页面"能力，没有大纲 API；pdfium 又被移除了。所以大纲由本工程自行解析，只用 `java.util.zip.Inflater`：

- 交叉引用**表**与交叉引用**流**（`/Type /XRef`，含 PNG Predictor 反预测），支持 `/Prev` 链与 `/XRefStm`；
- **对象流 `/Type /ObjStm`**（PDF 1.5+ 把对象塞进流里）；
- xref 损坏时回退**全文暴力扫描** `N G obj`，再靠 `/Type /Catalog` 找根；
- 大纲树 `/First` `/Next` `/FirstChild` + 环检测；
- 目标页：`/Dest` 数组、**命名目标**（`/Root/Dests` 与 `/Names → /Dests` 名字树）、`/A → /D`；
- 标题编码：UTF-16BE BOM / UTF-8 BOM / PDFDocStandard 兜底。

### 5.2 OCR 重锚定算法（`TocGenerator.reanchorByOcr`）

```
大纲顺序可信、页码不可信  ⇒  按大纲顺序扫章节，逐页 OCR 顶部 20% 条带
  · 某页页眉命中"指针附近 3 章之内"的标题 → 把该章锚定到这一页，指针前移
  · 只允许在指针附近命中：否则目录页会一次性匹配掉几十章
  · 未命中的章节保持原位，最后统一夹在相邻已锚定页之间，保证整体单调
```

### 5.3 全页眉扫描（`OcrChapterBuilder`）

逐页把顶部 20% 条带（720px 宽）交给 OCR，按行喂进状态机。**剑桥小说馆这一套书里同时存在三种章节页排版**，
下面的规则全是被它们逼出来的（每条都有真实数据撑着，见 §9.11）：

| 版式 | 长什么样 | 出现在 |
| --- | --- | --- |
| 一、编号与标题同一行 | `Chapter 1 A hand on a guitar` | 2级08《真爱无界》 |
| 二、编号独占一行、标题在下一行 | `Chapter 1` ⏎ `It's not fair!` | 2级16 / 17 / 19 |
| 三、**根本没有编号**，页眉就是标题 | `Quick Man Tan` | 2级07 / 2级18 |

`HeadingDetector` 的判定规则：

1. **锚定行首 + 逐词编辑距离 ≤1**：`Chapter` 会被认成 `hapter`、`After` 认成 `Afier`、
   `Translation` 认成 `TransIation`（t→i、l→I 是这套字体的高频混淆）。
   但**长度 ≤3 的词要求精确匹配**，否则 `the`/`for` 会到处乱配；
2. **编号一律用序号，不采信 OCR 数字**：实测 `Chapter 2` 被认成 `Chapter 乥`、`Chapter 3` 认成 `Chapter 5`，
   但页码与顺序永远正确；
3. **版式二**：`chapterBody()` 在"编号后面没内容"时返回**空串**（而不是 null，早期返回 null 等于把整章丢掉），
   状态机再去下一行取标题；续行只接"小写开头的短尾巴"——`to know` 接上，`、 VVIC` 这类噪声扔掉；
4. **版式三**：条带**只有一行**、且这一行像标题（含 ASCII 字母、≤6 词、≤40 字符、非全大写、
   不含中文标点、不以句末标点结尾）→ 判为章节。正文页在这个高度必然有 3~6 行，"只有一行"因此是很强的判据；
5. **只在"终止性"附文处停止收章节**：`Translation` / `Learning guide` / `Words & expressions` 永远在书末，
   见到就停；而 `ACTIVITIES` / `After reading` 在短篇合集里会夹在两篇之间（2级18 是三个短篇，每篇后面都有活动页），
   在那里停会把后两个故事整段丢掉；
6. **同名去重**：书里有两个"目录"页，阅读器只需要一个跳转目标，重复的只保留第一次。

> 一个反面教训：**判定顺序不能反**。先判"只有一行 → 章节"的话，`Contents 目 录` 也满足这个条件，
> 两个目录页就变成 `Chapter 1 Contents 目 录`。必须先判附文标题，再判版式三。

---

## 6. 章节层级与「当前章节」的正确算法

```kotlin
// Chapter: index(显示顺序=大纲遍历顺序) + parentIndex + level + kind + startPage/endPage + confidence
ChapterResolver.resolveRanges(chapters, totalPages)   // 先按页推进叶子，再回推分组范围
ChapterResolver.PageIndex(chapters).indexAtPage(page) // 叶子按页排序的副本 + 二分
```

- `resolveRanges` **不假设列表有序**：只在叶子上按 `startPage` 升序推进 `endPage`，
  再自底向上给分组节点算区间；
- 抽屉默认**只展开当前章节所在路径**，其余分组折叠（16 本书铺开没法用），
  顶栏显示面包屑：`书虫·第4级上 › 巴斯克维尔猎犬 › 1 The Case Begins`。

---

## 7. 怎么用

1. 书架 → 右下角「导入 PDF」→ 选文件（会自动复制一份到应用私有目录，生成 bookId）。
2. 打开书：直接恢复上次页码；底栏「目录」打开章节抽屉。
3. **书虫合集**：点「生成目录」→ 选 `自动`。正常情况下它会先读到 506 条书签，
   发现页码漂移后自动转 `OCR 重锚定`。2278 页大约需要 1~2 分钟，期间可以继续读，也能取消；
   任务在 Application 作用域跑，退出阅读器也会继续，结果入库。
4. **剑桥《真爱无界》**：点「生成目录」→ 选 `自动` 或 `OCR 全页眉扫描`。
   92 页大概十几秒，会识别出 Chapter 1~11 及其它小节。
5. 想手工修正：抽屉里「导出」→ 得到 `…/files/chapters/<bookId>.json`（含 `pageNumberBase: 1`、
   层级 `level`、`kind`），改完放回原处，重开阅读器即生效（LOCAL_JSON 优先级最高）；
   也可以用 adb：`adb shell run-as com.novelreader.pdf cat files/chapters/<bookId>.json`。

### 7.1 批量导入（多本一起）

右上角 **⋮ → 两个批量入口**：

| 菜单项 | 干什么 | 用在哪 |
| --- | --- | --- |
| **批量导入 PDF 并生成目录** | 一次多选 PDF → 逐本导入 → 对每本**自动跑「OCR 全页眉扫描」**生成目录 | 一次加一批同系列的书（比如一堆剑桥小说馆） |
| **批量导入目录 JSON** | 一次多选 JSON → 按书名匹配书架上的书 → **整表替换**目录 | 已经有现成目录（比如本仓库 `docs/` 里那些），不想在手机上等 OCR |

几个实现上的取舍：

- **逐本串行**，不并行：OCR 是重活，并行只会互相抢 CPU，还把进度显示搞乱；
- **导入后立刻用 `PdfRenderer` 读一次页数**再登记：目录生成需要总页数，而数据库里的
  `totalPages` 要等书被打开过才有值，批量流程里没有"打开"这一步；
- **一本失败不影响下一本**：任何一本出错都只记进结果列表，批量继续；
- **结果逐条摊开**：成功 / 未匹配 / 失败三类都列出来，不做"静默成功"；
- **替换前自动备份**：旧目录会写成 `<bookId>.json.bak`，导错了还能退回去；
- JSON 匹配走三级降级（`bookId` → 书名精确 → 归一化后互相包含），因为导出的 JSON 里书名是干净的
  `剑桥小说馆2级17 魔幻手机`，而书架上的书名带来源后缀 `… (未知) (z-library.sk, …)`，两者并不相等。

---

### 7.2 放大看小字

阅读时：

- **双指捏合**缩放（1× ~ 6×，最小就是"适应屏幕"，缩不回比整页更小）；
- **双击**在「适应屏幕 ↔ 2.5 倍」之间切换；
- 放大后**单指拖动**平移页面；缩回适应状态后横向滑动恢复翻页。

两个实现要点：

1. **放大到位后会按更高分辨率重新渲染这一页**（最多 2600px 宽）。
   低清页是按 ViewPager2 宽度渲染的，放大 2.5 倍等于把每个像素拉大 2.5 倍，扫描件的小字立刻糊成一团
   —— 而"看清小字"正是这个功能唯一的理由。缓存键因此从「页码」改成 **`页码@渲染宽度`**，
   低清与高清两份各自命中；换图时只重算基准倍数、保留当前缩放，所以画面不会跳。
2. **和 ViewPager2 的分工**是整个功能最容易做错的地方：
   没放大时**不**禁止父容器拦截，横向滑动照常翻页；
   一旦落指捏合就 `requestDisallowInterceptTouchEvent(true)`，并把 `isUserInputEnabled` 关掉，
   横向手势变成平移。翻页回调里再把滑动恢复，免得上一页的放大状态把翻页锁死。

缩放/平移的边界运算抽在 `ZoomMath` 里（纯函数，8 条单元测试）：
内容比视口小时居中、比视口大时不允许拖出空白，倍数夹在 1×~6×。

## 8. 构建与运行

```bash
# Android Studio（Ladybug+）/ JDK 17
# AGP 8.6.1 + Gradle 8.7 + Kotlin 1.9.24 + KSP 1.9.24-1.0.20
# compileSdk 35 / minSdk 34 / targetSdk 35  → 只装 Android 14/15 设备
./gradlew :app:assembleDebug
```

依赖：AndroidX(core/appcompat/material/recyclerview/viewpager2/documentfile)、
lifecycle、coroutines、**Room 2.6.1(KSP)**、kotlinx-serialization、**ML Kit text-recognition-chinese 16.0.1**。
没有 JitPack 仓库、没有 native so、Jetifier 已关闭。

> 若 `text-recognition-chinese:16.0.1` 解析不到，改成 `16.0.0` 即可。

---

## 9. 验证证据（本机实跑数据）

`PdfOutlineParser.kt` 与 `Chapter.kt` 只依赖 `java.util.zip.Inflater` 和标准库，没有任何 Android API。
因此这些模块被编成**普通 JVM 程序**，直接对两本真实 PDF 运行过一遍。
这一步抓出 6 个真实缺陷——其中 3 个会让 Android 构建直接失败，另外 3 个会让目录生成出错：

| # | 缺陷 | 后果 | 怎么发现的 |
| --- | --- | --- | --- |
| 1 | `readLongAt` 只推进内部游标，外层 `p` 不动 | 经典 xref 表**死循环** | 代码自审 |
| 2 | 对象号从条目第 18 字节取（那里是 `\r\n`） | 一个对象都读不出来 | 代码自审 |
| 3 | xref 偏移指向 `N G obj` 这段头，却直接当对象体解析 | trailer 有 `/Root` 但解析不出对象 | JVM 实跑 |
| 4 | 大纲子节点键写成 `/FirstChild`（规范是 `/First`） | 书虫只读出 3 条书签，而非 506 条 | JVM 实跑 |
| 5 | 4 处编译错误：private 跨类访问 / 可空集合类型 / `when` 推断成 `Any?` / `isEmpty` 当属性用 | Android 构建直接失败 | JVM 编译 |
| 6 | 重锚定用"指针 + 固定窗口"，开头几章匹配不上就把指针卡死；未命中章节按"上一页+1"夹取 | 命中率 **0%**，488 章被摊成每章一页的假目录 | 真实数据回放 |
| 7 | 对象流条目判断写成 `if (f2 > 0)`，而第三个字段是"在对象流里的下标"、**可以为 0** | 对象流里第一个对象永远读不到；现代 PDF 的第一个对象常常正是 Catalog，导致整本读不出目录 | 合成 PDF 实跑 |
| 8 | `TocRepository` 调用 `ChapterResolver.encodeJson(...)`，而该方法实际叫 `encodeConfig` | Android 构建直接失败 | JVM 编译导出器时暴露 |
| 9 | 「一页出现 ≥3 个章节编号就判为目录页」——只 OCR 顶部条带时永远不成立 | 目录页反而被当成章节写进目录 | 真实扫描件 OCR 回放 |
| 10 | 去掉章节编号时把 ASCII 单字母也当成"被误识别的数字" | `Chapter 1 A hand on a guitar` 变成 `Chapter 1 hand on a guitar` | 真实 OCR 回归测试 |
| 11 | `Entities.kt` 声明成 `package …data.local`，而文件在 `entity/` 子目录下，所有引用方都按 `…local.entity` 导入 | **Android 构建直接失败**（一次报 40 个"找不到包"，全是连带） | 整个 Android 层真编译 |
| 12 | `libs.versions.toml` 被写入了 **UTF-8 BOM** | Gradle 的 TOML 解析器直接拒绝：`Unexpected '\ufeff'`，**构建第一步就挂** | 真跑 Gradle 构建 |
| 13 | `<style name="Widget.NovelReader.PanelIcon">` 没写 `parent` | AAPT 把名字里的点当隐式父样式去解析 `Widget.NovelReader`，报 `resource style/Widget.NovelReader not found`，**资源链接失败** | 真跑 `processDebugResources` |
| 14 | `findLastAscii(...) ?: return` 用在返回 `Int` 的函数上 | 找不到 `startxref` 时返回 -1，`-1 + 9` 会被当成偏移量继续解析（编译器也报了"Elvis 是死代码"） | 编译警告 |
| 15 | `chapterBody()` 在"编号后面没内容"时返回 `null` | `Chapter 1` 独占一行的整章被丢弃——2级16/17/19 三本书全废（共 27 章只认出 8 章） | 真实扫描件回放 |
| 16 | "遇到第一个后置附文就停止收章节" | 2级18 是三个短篇的合集，夹在中间的 `ACTIVITIES` 页把后两个故事整段截掉 | 真实扫描件回放 |
| 17 | "只有一行 → 章节"曾被判在附文判定之前 | `Contents 目 录` 同样满足"只有一行"，两个目录页变成 `Chapter 1 Contents 目 录` | 新夹具跑出的第一条结果 |
| 18 | 附文的中文标题无条件采信页眉下一行 | 2级16 的《参考译文》页眉被识别成 `第章空房子`（糊上了上一章的标题），目录里就写成"第章空房子" | 真实扫描件回放 |
| 19 | 把「多本装订本」当成一本书处理 | 剑桥《赃款》实际是 368 页装了 5 本小说：第二本的 `Chapter 1` 被接着编号成 `Chapter 11`，而且第一本的《参考译文》直接封口，后面四本的章节整段丢失，还吐出一条跨 300 页的「读前思考」垃圾条目 | 真实扫描件回放（368 页 / 5 本） |

### 9.1 目录解析：与 pypdf 全量逐条一致

| 文件 | 页数 | 内置书签 | 耗时 | 结论 |
| --- | --- | --- | --- | --- |
| 剑桥《真爱无界》 | pypdf 92 / 自研 **92** ✓ | pypdf 0 / 自研 **0** ✓ | 101 ms | 一致 |
| 书虫 4 级合集 | pypdf 2278 / 自研 **2278** ✓ | pypdf 506 / 自研 **506** ✓ | 63 ms | **level / 页码 / 标题，506 条全等** |

（包括那条众所周知的乱序 `版权页`：大纲里它排在 `扉页` 之后，两边输出完全一样）

### 9.2 重锚定算法：真实数据回放

用书虫完整的 2278 页文字层模拟 OCR 页眉结果：

- 命中 **353/488 章（72%）**，最终逆序 **0**；
- 与独立文本检索交叉验证一致：`10 The Letter` 大纲 p63 → 真实 **p98**（+35）；`1．Jim's early life` 大纲 p1317 → 真实 **p1983**（+666）；
- 39 个目录页被正确识别并排除——不排除的话，整本目录会被锚成某一章。

### 9.3 章节层级与乱序区间：不变式全部通过

真实数据（488 叶子 + 18 分组）加上人工构造的极端乱序用例：

- 区间无重叠、无空洞、父节点完整包含子节点；
- 976 次逐章定位 + 4 次边界 + 325 次全书抽样，全部命中正确章节；
- 同页并列 23 组（漂移后 封面/扉页/简介 会挤到同一页），规约为「取最后一个」。

### 9.4 Room：3 张表的 SQL 拿本机 SQLite 实跑

建表、复合主键、`(book_id, start_page)` 索引、`INSERT OR REPLACE`、11 条 DAO SQL
（含反引号 `` `key` ``）全部执行通过。

### 9.5 现代 PDF 格式：合成件覆盖 xref 流 / 对象流 / PNG Predictor

两本测试用书都是**经典 xref 表**，覆盖不到 PDF 1.5+ 的主流写法，因此另外手工构造了一个 651 字节的合成 PDF：
9 个对象全部塞进 `/Type /ObjStm` 对象流，用 `/Type /XRef` 交叉引用流 + `/Predictor 12`（PNG Up）压缩，
并挂了三层书签。结果：

| 项 | pypdf | 自研 |
| --- | --- | --- |
| 页数 | 3 | **3** ✓ |
| 书签层级 | 0 / 0 / 1 | **0 / 0 / 1** ✓ |
| Chapter 1 → 目标页 | p1 | **p1** ✓ |
| Section 2.1（二级）→ 目标页 | p3 | **p3** ✓ |
| 无 `/Dest` 的条目 | 报错/None | 返回 -1（不伪造页码）✓ |

这一项**一次就抓出上面第 7 个缺陷**：修之前它只登记到 8 个对象，Catalog 直接丢失。

### 9.8 扫描件 OCR 路径：用真实识别结果回归

剑桥《真爱无界》是 92 页纯扫描件、零书签，只能靠 OCR。验证时把每页**顶部 20% 条带、缩到 720px 宽**
（与 App 里 `TocGenerator` 的参数完全一致）切出来，用系统 OCR（zh-Hans-CN）识别了全部 91 页，
再把 `HeadingDetector` + `OcrChapterBuilder` 的真实输出与印刷目录逐条对照：

| 结果 | 数值 |
| --- | --- |
| 检出条目 | **19 条**（5 前置 + 11 章 + 3 后置） |
| 页码命中 | **19 / 19**，零误报 |
| 720px 与原始分辨率对比 | 结果完全一致（说明降采样没有损失） |

真实识别错误（这些都是 OCR 实际吐出来的，不是构造的）：

| 原文应为 | OCR 实际输出 | 现在的规则怎么救回来 |
| --- | --- | --- |
| `Chapter 2 Shopping` | `Chapter 乥 Shopping` | 章节号不采信 OCR 数字，改用**序号**（第 N 个章节标题就是 Chapter N） |
| `Chapter 3 My best friend` | `Chapter 5 My friend` | 同上，序号纠正为 3 |
| `Chapter 11 Happy ever after` | `Chapter 1 1 Happy ever`＋`after` | 连吃两个数字段 + 跨行拼接 |
| `After reading` | `Afier reading` | 逐词编辑距离 ≤ 1 的模糊匹配 |
| `Translation` | `TransIation` | 同上（l→I 是这套字体的高频混淆） |
| `People in the story` | `PeopIe in the story` | 同上 |
| `前言` | `刖口` | 只有 ≥3 个汉字的中文行才当标题，2 个汉字时用关键词表里的规范名 |

**这三条规则是被真实数据逼出来的，不是设计出来的**：第一版规则在真书上的成绩是
「11 章漏 1、前后附文全丢、目录页被当成章节」。

真实 OCR 文本已经收进工程做回归夹具（`app/src/test/resources/cambridge_ocr_strips.tsv`），
`OcrChapterBuilderTest` 每次构建都会重放这 91 页。

### 9.6 顺带产出：两本书可直接导入的目录

| 文件 | 内容 | 怎么来的 |
| --- | --- | --- |
| [`docs/chapters-书虫4级合集.json`](docs/chapters-书虫4级合集.json) | 506 条（18 分组 + 488 章） | 内置书签 + 按标题重锚定页码（§9.2） |
| [`docs/chapters-剑桥真爱无界.json`](docs/chapters-剑桥真爱无界.json) | 19 条（5 前置 + 11 章 + 3 后置） | OCR 顶部页眉识别（§9.8），页码 19/19 全对 |
| [`docs/chapters-剑桥2级07圆圈故事集.json`](docs/chapters-剑桥2级07圆圈故事集.json) | 10 条（3 + 5 故事 + 2） | OCR 页眉（版式三），5/5 与印刷目录一致（§9.11） |
| [`docs/chapters-剑桥2级16阿姆斯特丹风波.json`](docs/chapters-剑桥2级16阿姆斯特丹风波.json) | 13 条（3 + 8 章 + 2） | OCR 页眉（版式二），8/8 一致 |
| [`docs/chapters-剑桥2级17魔幻手机.json`](docs/chapters-剑桥2级17魔幻手机.json) | 16 条（4 + 10 章 + 2） | OCR 页眉（版式二），10/10 一致 |
| [`docs/chapters-剑桥2级18派对与礼物.json`](docs/chapters-剑桥2级18派对与礼物.json) | 9 条（3 + 3 故事 + 3） | OCR 页眉（版式三），3/3 一致 |
| [`docs/chapters-剑桥2级19杀人蜂.json`](docs/chapters-剑桥2级19杀人蜂.json) | 14 条（3 + 9 章 + 2） | OCR 页眉（版式二），9/9 含尾声一致 |

用法：在 App 里打开对应书籍 → 目录抽屉「导出」拿到正确文件名后，用本文件覆盖其内容即可，
省掉在手机上重跑一遍 OCR。

---

### 9.7 工程内单元测试：21 条，全绿

上面的验证逻辑已经固化进工程（`app/src/test/`），在 Android Studio 里执行 `gradlew :app:test` 即可复跑：

| 测试类 | 条数 | 覆盖内容 |
| --- | --- | --- |
| `OcrChapterBuilderTest` | 10 | **用四本真书的 OCR 文本回放**（2级08/07/17/18，共 387 页）：三种章节页版式、折行拼接、噪声行过滤、短篇合集不被活动页截断、成品 JSON 可导入 |
| `ChapterResolverTest` | 7 | 乱序大纲区间、分组区间回推与包含、同页并列规约、全书页码定位与边界安全、面包屑路径、JSON 往返（含 `pageNumberBase` 进制约定）、`level` → 父子关系 |
| `PdfOutlineParserTest` | 4 | 现场合成的「xref 流 + 对象流 + Predictor 12」PDF、无大纲 PDF、文件不存在、以及**开发机上放着那两本书时的自动回归**（断言 92 页/0 书签 与 2278 页/506 书签） |

`Chapter.kt` 与 `PdfOutlineParser.kt` 都是纯 JVM 代码（只用 `Inflater` 与标准库），所以这些测试**不需要 Robolectric**，秒级跑完。

---

### 9.9 整个 Android 层：真编译 0 错误

"能不能编过"是这次改造最后一个大未知。本机没有 Android SDK，所以搭了一个**假 Android 构建环境**：

1. **框架类**：用 Robolectric 的 `android-all`（API 34，131 MB）当 `android.jar`——
   它包含 `PdfRenderer`、`ViewPager2` 依赖的 `View`、`Bitmap` 等全部框架类；
2. **三方库**：从 Google Maven 直接下载 52 个制品（androidx core/appcompat/lifecycle/activity/fragment/
   room/sqlite/recyclerview/viewpager2/constraintlayout/coordinatorlayout…、Material 1.12、
   ML Kit text-recognition 全家桶），逐个解出 AAR 里的 `classes.jar`；
3. **生成代码**：按布局 XML 自动生成 `R` 类与 6 个 viewBinding 桩类
   （字段名与控件类型都从 XML 推出，必须与源码中的用法完全对得上，否则编不过）；
4. 用 Kotlin 编译器把 **34 个主源文件**全部编一遍。

结果：

| 项 | 结果 |
| --- | --- |
| 主源文件编译 | **0 错误**，产出 234 个 class |
| 测试源文件（编到同一套产物上） | **0 错误** |
| 17 条单元测试 | **全绿** |

第一次编译报了 40 个错误，其中 39 个是同一个根因引起的连带——也就是上表第 11 条：
`Entities.kt` 的包名少了一级。**这个错误在 Android Studio 里同样会让构建失败，
而且报错位置全在"别人家"的文件里，很难一眼看出根因。**

仍未验证的部分（都需要真机/AGP）：AAPT 资源编译、KSP 的 Room 代码生成、viewBinding 真实生成、
真机运行、ML Kit 在设备上的识别率。其中 Room 的 SQL 已用本机 SQLite 单独验过（§9.4）。

### 9.10 真机构建链路：debug + release APK 都产出了

前一轮的"假 Android 构建"证明了 Kotlin 代码能编过；这一轮把**真正的 AGP 构建**跑通了。
（本机没有 Android SDK，所以在工作区里自备了一套：JDK 17 + Gradle 8.7 + Android SDK platform 35 + build-tools 35）

```
> Task :app:checkDebugAarMetadata / mergeDebugResources / processDebugResources
> Task :app:dataBindingGenBaseClassesDebug        ← viewBinding 生成
> Task :app:kspDebugKotlin                        ← Room 代码生成
> Task :app:compileDebugKotlin
> Task :app:dexBuilderDebug / mergeProjectDexDebug
> Task :app:validateSigningDebug / packageDebug
BUILD SUCCESSFUL
```

| 产物 | 大小 | 关键验证 |
| --- | --- | --- |
| `app-debug.apk` | 56.8 MB | `minSdk 34 / targetSdk 35 / compileSdk 35`、12 个 dex、888 个资源、4 个 ABI 的 ML Kit OCR `.so` |
| `app-release-unsigned.apk` | 44.5 MB | R8 把 dex 从 14.1 MB 压到 2.3 MB；**3 个 kotlinx 序列化器、Room `AppDatabase_Impl`、ML Kit 的 dynamite `ModuleDescriptor` 全部保留** |

KSP 的产物也逐项核过：
`BookDao_Impl.kt` / `ChapterDao_Impl.kt` / `SettingDao_Impl.kt` / `AppDatabase_Impl.kt` 都已生成，
schema 导出到 `app/schemas/…AppDatabase/1.json`，生成的 SQL 与 §9.4 里手写 SQLite 测试用的表结构一致。

**17 条单元测试这次是在真实 Gradle 构建里跑的**（`:app:testDebugUnitTest`），全绿。

复现这套构建时踩到的三个环境坑（都与工程本身无关，但会让人以为代码有问题）：

1. **本机 user 级环境变量 `ANDROID_SDK_HOME=C:\Android`** 会让 AGP 抛
   `AndroidLocationsException: Several environment variables … contain different paths`——
   注意 AGP **只要发现设置了多于一个**这类变量就报错，并不比较取值，所以必须只留一个；
2. **Windows MAX_PATH(260)**：Gradle 主目录放得太深时，`material-1.12.0` 的 transform（546 个文件、
   最长路径 278 字符）会以 `AccessDeniedException` 伪装成"权限不足"，实际是路径太长；
3. Gradle 需要 `--no-watch-fs`（沙箱不让 native 文件监视）与 `--no-parallel`。

### 9.11 五本剑桥小说馆：三种版式全中，页码偏移恒定

同系列的另外五本（2级07/16/17/18/19）一起给了过来，而这批书的章节页排版**各不相同**，
正好把"只按一本单调规则"的窟窿全暴露了出来。

标准答案来自**印刷目录页**：把目录页整页渲染出来单独 OCR（不是顶部条带），
拿到每本书的章节清单与印刷页码，再和程序检出的页码对照：

| 书 | 印刷目录 | 程序检出 | 页码偏移（PDF 页 − 印刷页） |
| --- | --- | --- | --- |
| 2级07 圆圈故事集 | 5 个故事 | **5 / 5** | 恒定 +4 |
| 2级16 阿姆斯特丹风波 | 8 章 | **8 / 8** | 恒定 +5 |
| 2级17 魔幻手机 | 10 章 | **10 / 10** | 恒定 +9 |
| 2级18 派对与礼物 | 3 个故事 | **3 / 3** | 恒定 +9 |
| 2级19 杀人蜂 | 8 章 + 尾声 | **9 / 9** | 恒定 +11 |
| 2级08 真爱无界（回归） | 11 章 | **11 / 11** | 与改规则前完全一致 |

**"偏移恒定"本身就是一条很强的校验**：几十个条目里只要有一章落错页，偏移就不可能是常数了。

三本新夹具（2级07 / 2级17 / 2级18 的真实 OCR 文本，共 296 页）已经收进
`app/src/test/resources/`，成为常驻回归测试；连同原有的 2级08 夹具，单元测试现在用
**四本真书、387 页真实识别结果**回归。

### 9.12 一本装了五本小说的合集：切分与编号

剑桥《赃款》（`Dirty Money`）这个 PDF 是 **368 页装了 5 本小说**的装订本，且没有书签、没有文字层，
只能靠版面标记切分。每本书的开头都是同一套：`作者简介` → `故事导读` → `Contents 目录` →
`人物` → `地点` → `读前思考` → `Chapter 1`，于是用它作为切书依据。

| 项 | 结果 |
| --- | --- |
| 切出分组 | **5 个**，页码区间 P12-65 / P66-120 / P121-188 / P189-282 / P283-367 |
| 章节总数 | 62 条目录条目（10 + 7 + 9 + 10 + 11 章） |
| 每本章节编号 | **各自从 `Chapter 1` 重新开始** |
| 书名 | 第 1 本从扉页读出 `Dirty Money`；其余四本的名字来自该书第 6 页的「总目录」页 |

判定「新的一本书开始」时用的是**整行恰好等于** `作者简介` / `故事导读`，
不能用"包含"——封底推荐语里就出现过「…作者简介…」字样，用包含会把推荐语误判成新书
（现有夹具里就有这种行，写测试时踩到过）。

书末的 `New words 生词` / `Reading exercise 阅读练习` 也被拆成独立的附文条目，
否则它们的页眉（形如 `7 New words 生词`）会被当成章节。

## 10. 已知限制与待验证项

1. **完整的 AGP 构建已经跑通**（§9.10，debug + release 都出了 APK），KSP/viewBinding/AAPT/打包/R8 全部验证过。
   release APK 是**未签名**的（没有配 release signingConfig），要装机得先 `apksigner` 签名或用 debug 包。
2. **ML Kit OCR 路径没有端到端验证过**（这里跑不了 Android）：输入输出契约、目录页排除、
   标题判定规则都在 Python 侧用真实文字层验证过（§9.2），但真机上的识别率与耗时仍待实测。
3. **OCR 耗时是估算**（按 720px 宽、顶部 20% 条带、约 30~60ms/页推算）。
   2278 页全扫大约 1~2 分钟；实际以设备为准。
4. 加密 PDF 不支持（解析器会明确返回"已加密"）；PDF 大纲页码整体缺失时只能走 OCR。
5. 目录抽屉用 `notifyDataSetChanged` 全量刷新：506 行完全够用，上千行才会有感知。
6. `Auto` 模式默认**不自动跑 OCR**；首次打开只跑"便宜路径"（本地 JSON/预置/内置书签），
   需要 OCR 时由使用者显式点「生成目录」。这是刻意的：不能在后台静默跑一两分钟的任务。

---

## 11. 下一步

- 用原型验证结果校正 `PdfOutlineParser`（尤其命名目标与对象流分支）；
- OCR 断点续跑：目前取消后重来要重头扫，可以按页把中间结果也缓存进 Room；
- 双页横屏模式（书虫的英汉对照排版横屏双页会很舒服）；
- 目录抽屉的搜索框（506 条里找一章还是有点费劲）。

---

## 12. 免责声明与版权说明

### 12.1 本仓库不包含书籍内容

仓库里没有 PDF、没有正文、没有插图，也没有任何能读全文的文件。跟那两本书有关的，
只有下面这些**由程序生成的派生数据**：

| 文件 | 内容 | 相当于 |
| --- | --- | --- |
| `docs/chapters-书虫4级合集.json` | 506 条目录条目（章节标题 + 页码 + 层级） | 一本书的目录页信息 |
| `docs/chapters-剑桥真爱无界.json` | 19 条目录条目（同上） | 同上 |
| `app/src/test/resources/chapters-cambridge.json` | 同上，供单元测试做导入回归 | 同上 |
| `app/src/test/resources/cambridge_ocr_strips.tsv` | 91 页**页面顶部 20% 条带**的 OCR 文本 | 只有页眉/章节标题，用作算法测试夹具 |

这些数据的产生方式是「在本机打开使用者自己的 PDF，读出目录、识别页眉」——本质上是**给一本书做书签索引**，
目的属于互操作性：让**使用者已经拥有的**电子书在自建阅读器里更好用。它们不含正文段落，
也无法据此还原书籍内容。

### 12.2 使用条件

- 本项目仅供**个人学习与自用**；不提供、不托管、不分发任何书籍文件；
- 使用者应确认**合法拥有**相应书籍；建议通过正规渠道购买正版（这两本书国内都有正规引进版）；
- 请勿将本仓库、其代码或其派生数据用于**商业用途或再分发**；
- 若 PDF 来自网络下载，其来源与本项目无关；
- **版权方**如认为上述派生数据（目录条目、页眉片段）不妥，请在仓库提 issue，
  相关文件会立即移除，无需额外说明。

### 12.3 软件本身

代码按「现状」提供，不附带任何担保。这是个自用工具：OCR 结果会有识别错误、
页码可能个别不准，重要数据（如手工修正过的目录）建议自行导出留底。

---

## 13. 安装与更新：数据会发生什么

### 13.1 覆盖安装（推荐）

同一个包名 `com.novelreader.pdf` + 同一把签名密钥 → Android 原地替换，**不需要卸载**：

- 书架、阅读进度、已生成的目录（Room 数据库）**全部保留**；
- App 私有目录里的 PDF 副本**保留**；
- **原始 PDF 文件任何时候都不会被改动**（依据见 13.3）。

```bash
adb install -r PDFRead-release.apk     # 或者手机上直接点 APK 安装
```

前提是 `versionCode` 比已装的大（v2.0.0 = 2，v2.0.1 = 3）。
release 的签名密钥在 `keystore/pdfread-release.jks`（已 gitignore）——**这把文件丢了，以后就只能卸载重装**。

### 13.2 什么时候才需要卸载

- 从 **debug 版**换到 **release 版**：签名不同，必须先卸载（卸载会清空书架与私有副本，原始 PDF 不受影响）；
- 换一台电脑重新构建 debug 版：AGP 会生成新的调试密钥，同样要卸载一次。

### 13.3 原始 PDF 为什么绝对安全

- 导入只走 SAF（`ACTION_OPEN_DOCUMENT`），App 拿到的是一个 `content://` URI，不是文件路径；
- 整个工程里读文件只有一处：`contentResolver.openInputStream(uri)`（`BookRepository.ensureLocalCopy`），
  **没有 outputStream、没有 `DocumentsContract.deleteDocument`**；
- 持久化授权只申请 **READ**（`MainActivity` 传的就是 `FLAG_GRANT_READ_URI_PERMISSION`），连写权限都没有；
- 从书架删除一本书，删的是**私有副本**（`BookRepository.deleteLocalCopy`）。

换句话说：即使 App 存在缺陷，也不具备改写或删除原始文件的能力。

### 13.4 「私有副本」是什么、为什么要复制

导入时默认会把 PDF 复制一份到 `filesDir/books/<bookId>.pdf`，原因有三：

1. `PdfRenderer` 需要**可 seek** 的 fd，而部分网盘/文档提供者的 URI 给不了；
2. SAF 的持久化授权可能在用户清理「最近文件」或卸载网盘 App 后失效，副本是最后的兜底；
3. 有了确定的本地路径，`bookId` 可以直接由文件路径与元数据算出，不必每次打开都全文件哈希。

代价是**每本书多占一份磁盘**（《真爱无界》45 MB，就多 45 MB）。
`BookRepository.resolve(copyToLocal = false)` 可以关掉复制，退回「直接用 URI」。

### 13.5 一个需要知道的坑：改表结构会清库

`AppDatabase` 用了 `fallbackToDestructiveMigration()`。如果将来某个版本改了 Room 表结构
却没有写 Migration，**升级时会重建整个数据库**——书架、阅读进度、目录缓存都会没
（**原始 PDF 仍然安全**，重新导入即可）。

目前 schema 版本是 1。真要改结构时应该先写 Migration；
升级前也可以用「导出」把目录留一份底。

## 14. 词书与查词（词典数据完全自备）

**App 不附带任何词典数据。** 词书来自使用者自备的文件（CSV / TSV / JSON），导入后解析进本地数据库；
查询时完全不依赖原文件（原文件删掉也照样查），并且**不会复制、不会修改源文件**。

入口：书架右上角 **⋮ → 词书与查词**。

### 14.1 支持的格式

| 格式 | 说明 |
| --- | --- |
| **ECDICT 风格 CSV** | 表头 `word,phonetic,definition,translation,pos,collins,oxford,tag,bnc,frq,exchange,…`。列顺序**按表头识别**，不写死位置 |
| **通用两列 TSV / CSV** | `单词<TAB>释义`（自行整理的词表通常是这个形状）。首行不像表头就**当数据**——早期版本会把第一个词当表头吃掉 |
| **JSON** | `[{"word":"…","translation":"…"}]`，字段名同上 |

解析是**流式按批**的：全量 77 万条的 CSV 也不会把内存打爆，而且批与批之间可取消、可报进度。

### 14.2 查询是三级降级

1. **精确**：归一化后（小写、去标点）直接命中；
2. **词形还原**：`running` / `ran` / `runs` → `run`。靠词书自带的 `exchange` 列建映射，不需要词法分析器；
3. **模糊兜底**：编辑距离 ≤ 1 的近似词（专治 OCR 误识别，也是将来「点读」取词的后备）。

### 14.3 词卡显示什么

音标 · 词性 · 中文释义 · 英文释义 · 词形变化 · 柯林斯星级 · 牛津3000 · BNC/当代语料词频 · 考试标签。

### 14.4 词书管理

列表显示每本的条目数与格式，可以**单独启用/停用**（同时装了英汉和英英两本时只走一本），
也可以删除 —— 删掉的只是本地解析结果，**不动源文件**。同名文件重新导入 = 覆盖更新，不会留两份。

### 14.5 上哪找词书

[ECDICT](https://github.com/skywind3000/ECDICT)（**MIT 许可**，可自由使用）最省事：
它有词头、音标、中文释义、词形变化、柯林斯星级、词频与考试标签，转成 CSV 就能导入。

仓库里**不附带这份数据**（全量 60+ MB，不适合进 git），可自行下载转换。
本机验证时用的是：全量 770,611 条 / 62.9 MB，抽出的"有词频或星级或考试标签"子集 59,137 条 / 9.9 MB。

### 14.6 数据库升级

词书用了三张新表（`dictionaries` / `dict_entries` / `dict_forms`），数据库版本 1 → 2
**写了正经 Migration**（不是靠破坏性升级兜底），所以升级不会清空书架、进度与已生成的目录。
迁移语句与 KSP 生成的建表语句做过逐字比对。

## 附录：v1 设计回顾（已被 v2 取代）

v1 用 `android-pdf-viewer 2.8.2` + DataStore + 扁平章节模型，实现了：
断点续读（DataStore + 800ms 防抖 + Application 作用域落盘）、横向 `swipeHorizontal + pageSnap` 翻页、
`TapZoneLayout` 左/中/右点击热区、章节 BottomSheet、中英标题拆分、三级目录来源。
其中仍然有效的设计（v2 保留）：页码全 0 基、`awaitingInitialSync` 首帧守卫、
`flush()` 乱序写回保护、`distinctBy` 去重、`pageNumberBase` 显式声明进制。
被 v2 淘汰的：pdfium 依赖、反射读书签、DataStore、扁平章节二分。
