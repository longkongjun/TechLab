# Android Binder 跨进程大数据传输

两个进程各有自己的虚拟地址空间。发送方的普通堆指针只描述它自己地址空间中的位置。接收方要取得字节，需要一份副本，或者对承载这些字节的对象有访问权。Binder 可以把值序列化进事务，让接收方得到独立副本。它也可以交付内核对象的句柄，接收方凭句柄读取已打开的文件、从管道取字节，或映射同一块内存。传递句柄只解决“如何访问”。生成数据、读取字节和等待生产者仍然有成本。[Android Binder 机制](https://source.android.com/docs/core/architecture/ipc/binder-overview) · [Android 文件描述符](https://developer.android.com/reference/android/os/ParcelFileDescriptor)

选型时要明确**初始状态**（已有文件、已有内存、按需生成）、**读取方式**（顺序一次、随机定位、重复或多方读取）和**完成条件**（首块可读、完整接收、校验通过）。再确定谁持有资源、谁发出完成或失败信号，以及取消时谁负责关闭。单次返回完整值、持续生产字节流、共享可变内存，对这几个问题有不同的答案。

可以用长度为 `N`、块长为 `B` 的输入推演耗时。同步分块 RPC 要等待 `⌈N/B⌉` 次正文调用返回。交付文件 FD 只需一次正文调用，后续仍要读实际访问的字节。管道允许消费端读前一块时生产端生成后一块，慢的一端会限制持续吞吐。共享内存允许多次访问同一批字节，但若协议要求整块写完才通知，消费者仍需等到写完才读得到首块。

## 按数据约束选型

| 数据和访问条件 | 优先考虑 | 决定取舍的原因 |
| --- | --- | --- |
| 序列化大小有明确上界，接收方一次需要完整值 | Binder 直传 | 一次调用即可交付独立值。需为同进程其他在途事务留余量 |
| 普通文件已存在，接收方需要顺序读或按偏移读 | 只读文件 FD | 一次交付句柄，后续读取不再逐块调用服务端。若接收方须稍后凭授权打开，考虑 Content URI |
| 字节按顺序生成，允许边生产边消费 | 可靠管道 | 有界缓冲允许生产和读取重叠。要求重读或随机定位时，需要保存副本或改用文件 |
| 大小已知，生产者能直接写内存，多个读者要访问同一版本 | 共享内存 | 多次读取可以复用同一批字节。需明确写完、只读发布以及何时允许覆盖 |
| 现有远端接口只能交付有界小数组，或只需少量离散区间 | 分块 Binder | 每块可独立请求。连续读完整对象时，调用次数随长度增长 |
| 双方在同一长会话里都要收发多条消息 | 本地流式 socket | 一次建立连接后可双向读写。消息分帧、长度上限和断线处理由两端约定 |

## 研究问题与实验约定

Demo 的生产端与消费端属于同一 APK、同一 UID，运行在不同进程。控制请求先取得源数据的标识、长度和参考 CRC32，再发起传输。文件描述符是进程本地的整数。跨进程交付 `ParcelFileDescriptor` 时，系统在接收方建立指向同一内核对象的引用，双方持有的整数可以不同。[AIDL 契约](../../demos/android/demo-binder/src/main/aidl/dev/techlab/android/binder/ITransferSource.aidl) · [ParcelFileDescriptor](https://developer.android.com/reference/android/os/ParcelFileDescriptor)

2026-11-18，S3（Android 11，API 30），单客户端串行，已有文件已预读。时钟从正文请求发起开始，累计收到 32 KiB 记为**首块**，收齐、校验长度和 CRC32 并关闭传输资源记为**完整**。连接服务、查询元数据与接收后的处理在计时外。按需生成组在两条路径使用相同编码器，每次产生相同的 1,705,378 字节，生产耗时计入两组。每个组合每批预热 2 次、采集 20 次，表中 P50 和 P95 为三批共 60 个样本的中位数和第 95 百分位数，单位为毫秒。[计时实现](../../demos/android/demo-binder/src/main/kotlin/dev/techlab/android/binder/TransferMeasurement.kt) · [18 组全量数据和重构回归](evaluation.md)

| 输入 | 方案 | 首块 P50 | 完整 P50 | 完整 P95 |
| --- | --- | ---: | ---: | ---: |
| 已有 8 MiB 文件 | 分块 Binder | 1.27 | 273.13 | 292.89 |
| 已有 8 MiB 文件 | 只读文件 FD | 1.25 | 9.38 | 12.23 |
| 已有 8 MiB 文件 | 可靠管道 | 2.52 | 43.80 | 57.75 |
| 已有 8 MiB 文件 | 共享内存，每次新建 | 30.53 | 56.94 | 72.58 |
| 按需生成同一字节序列 | 编码完再返回文件 FD | 320.02 | 349.18 | 362.81 |
| 按需生成同一字节序列 | 边编码边写可靠管道 | 31.80 | 309.03 | 321.03 |

## 常见方案

下面的 Kotlin 伪代码标出服务端和客户端，`remote` 表示跨进程 AIDL 调用，`consume` 表示客户端消费收到的有效字节。`readExactlyAt`、`drain` 等是为说明步骤而简写的本地操作。前五节对应四个可运行的策略，Binder 直传和分块共用一个实现。Content URI 和 socket 只有机制示意。文末的候选登记给出尚待实现的对照。

### 1. Binder 直传：整个值随一次响应返回

服务端把小对象序列化进回复 Parcel，客户端在同步调用返回时已经拿到完整值。固定宽度的整数可在序列化时直接写入 Parcel。若字段里装的是 `ByteArray`，数组字节也进入事务。Demo 用同一分块接口只请求一次，作为直接返回字节数组的基线。

```kotlin
// 服务端，示意省略 offset 等参数
fun readSmall(id: String): ByteArray = readBoundedBytes(id)

// 客户端，一次返回整份小数据
val bytes = remote.readSmall(id)
consume(bytes)
```

**取舍。** 只有一次调用，无需约定流或关闭句柄，适合有大小上界、调用者需要完整值的消息。代价是两端要组装和还原整份值，请求或响应同时占用有限的事务缓冲。Android 文档给出的缓冲约为每进程 1 MB，在途事务共享。发送方需要按最坏序列化大小和并发量留余量，测通一次的数组大小无法充当安全上限。[Android 对事务大小的说明](https://developer.android.com/reference/android/os/TransactionTooLargeException)

**性能依据。** 对已有的 32 KiB 输入，单次 Binder 完整 P50 为 1.74 ms，文件 FD 为 1.67 ms。就这组数据，为节省 0.07 ms 给小消息引入句柄管理缺乏依据。更大输入使用分块或句柄交付，单次直传的基线仅覆盖 32 KiB。[Binder 客户端与服务端实现](../../demos/android/demo-binder/src/main/kotlin/dev/techlab/android/binder/BinderChunkTransfer.kt)

### 2. 分块 Binder：每要一块，跨一次进程

客户端先拿到总长度，逐次发起 `readChunk(id, offset, length)`。服务端在指定偏移读取，返回一个有界数组，客户端消费完才请求下一块。这样每个回复可控，客户端也能按偏移只取所需片段。

```kotlin
// 服务端，每个请求独立开文件、定位、读取、关闭
fun readChunk(id: String, offset: Int, count: Int) =
    openForRead(id).use { file -> file.readExactlyAt(offset, count) }

// 客户端
var offset = 0
while (offset < totalBytes) {
    val count = minOf(32 * KiB, totalBytes - offset)
    val bytes = remote.readChunk(id, offset, count)
    check(bytes.size == count)
    consume(bytes)
    offset += count
}
```

**取舍。** 现有双方只能交换小数组时，这条路径可保留既有接口形态。范围读取还要求服务端能按偏移定位。一次逻辑读取跨越多次事务，期间源文件必须保持同一版本，否则即使各块长度都对，拼接的也可能来自不同版本。保留服务端文件会话可减少重复开关文件，但需定义会话关闭、取消和服务重启后的处理。

**性能依据。** 8 MiB 按 32 KiB 切为 256 块，需要 256 次同步调用。首块 P50 为 1.27 ms，接近文件 FD 的 1.25 ms，完整 P50 却达到 273.13 ms，约为 FD 的 29 倍。差值同时包含往返、数组处理和本实现逐块开关文件的开销。接收方通常要整份数据时，应优先减少往返次数。[分块读写实现](../../demos/android/demo-binder/src/main/kotlin/dev/techlab/android/binder/BinderChunkTransfer.kt)

### 3. 只读文件 FD：服务端开一次，客户端直接读同一个文件

服务端以只读模式打开文件，AIDL 返回 `ParcelFileDescriptor`。客户端拿到对同一已打开文件的引用，可以自己顺序读，也可以在普通文件上定位。每一次 `read` 都在客户端发起，无需服务端逐块读取后再转发。双方也无需约定彼此可访问的文件路径。

```kotlin
// 服务端，通过 AIDL 返回已打开的描述符
fun openFile(id: String) = ParcelFileDescriptor.open(fileFor(id), MODE_READ_ONLY)

// 客户端，流关闭时同时关闭接收的 FD
val fd = remote.openFile(id)
AutoCloseInputStream(fd).use { input -> drain(input, ::consume) }
```

**取舍。** 输入已经在普通文件中时，顺序遍历、按偏移定位和在同一打开期间重读都可以用文件 FD。服务端应在使用期间保持文件内容稳定，只读限制的是接收方通过此句柄写入的能力。若字节需要现场生成，这条路径要先写完临时文件，再打开读 FD。打开读 FD 后即可解除临时文件名，底层内容会保留到最后一个引用关闭。这里有临时空间和写入等待，本实验没有要求 `fsync` 持久化。[文件 FD 的两条生成路径](../../demos/android/demo-binder/src/main/kotlin/dev/techlab/android/binder/FileFdTransfer.kt)

**性能依据。** 已有 8 MiB 文件完整 P50 为 9.38 ms，P95 为 12.23 ms，四种已测路径里最快。按需生成 1,705,378 字节时，文件 FD 的首块 P50 为 320.02 ms，因为接收方要等生产者写完整个文件。[S3 分输入结果](evaluation.md)

### 4. 可靠管道：先交读端，生产和消费可以重叠

服务端创建单向管道，把写端交给工作线程，把读端立即通过 Binder 返回。生产者每写入一段，客户端就可以读取一段。内核缓冲区有限，读者变慢后，写者可能阻塞。在返回读端前同步写入超出缓冲容量的数据，会让写者等待读者腾出空间，而读者还在等待同步调用返回。

```kotlin
// 服务端，在工作线程写，失败信号要早于写端的普通关闭
fun openStream(): ParcelFileDescriptor {
    val (reader, writer) = ParcelFileDescriptor.createReliablePipe()
    workers.execute {
        AutoCloseOutputStream(writer).use { out ->
            try { generateBytes(out) }
            catch (e: Exception) { writer.closeWithError("生产端写入失败") }
        }
    }
    return reader
}

// 客户端，读完后确认对端状态，再校验收到的字节
val reader = remote.openStream()
AutoCloseInputStream(reader).use { input ->
    drain(input, ::consume)
    reader.checkError()
}
verifyLengthAndCrc()
```

**取舍。** 能按顺序生成的数据可直接写入管道，即使事前不知道最终长度也能传输。Demo 因为有固定样本，能额外核对预期长度和 CRC32。管道无法倒带或随机定位，接收方需要第二次读取时要缓存或重新请求。取消时客户端关闭读端，服务端处理写失败并释放写端。可靠管道还能通过 `closeWithError` 把生产失败传给读端，避免把截断的数据当作完整结果。Demo 每批做 4 轮异常测试。每轮分别提前关闭普通文件和管道的读端，再让另一条管道写入 4 KiB 后报错，最后核对写任务和两端 FD 的数量。[管道实现](../../demos/android/demo-binder/src/main/kotlin/dev/techlab/android/binder/ReliablePipeTransfer.kt) · [Android 可靠管道](https://developer.android.com/reference/android/os/ParcelFileDescriptor#createReliablePipe())

**性能依据。** 同为 1,705,378 字节，数据已在文件中时，管道多了读文件和转写的工作，完整 P50 为 25.12 ms，直接返回文件 FD 为 2.99 ms。改为按需产生相同的字节，管道允许生产与读取重叠。相比先写临时文件，首块 P50 提前 288.22 ms（320.02 → 31.80），完整 P50 提前 40.15 ms（349.18 → 309.03）。接收方必须收齐并校验才能继续时，相关的差值是 40.15 ms。后续计算需要单独计时。[相同输入对照与异常回收记录](evaluation.md)

### 5. 共享内存：两端映射同一区域，何时能读由协议决定

Demo 已知长度，由客户端创建 `SharedMemory`，通过 Binder 把句柄交给服务端。服务端建立可写映射，从已有文件填满区域并解除映射。同步调用返回即代表填充完成，客户端随后限制新映射为只读，映射后分块取数据。通过 Binder 的是句柄，两端映射指向相同的底层字节。

```kotlin
// 客户端，withMapping 在 finally 中 unmap
SharedMemory.create("payload", length).use { region ->
    remote.fillRegion(id, bundleOf("memory" to region))
    check(region.setProtect(PROT_READ))
    withMapping(region.mapReadOnly()) { bytes -> consumeInBlocks(bytes) }
}

// 服务端，关闭自己收到的句柄
fun fillRegion(id: String, args: Bundle) {
    receivedRegion(args).use { region ->
        withMapping(region.mapReadWrite()) { dst -> copyFileInto(id, dst) }
    }
}
```

**取舍。** 如果生产者能直接写映射，或多个读者反复查看同一批字节，就有机会省掉重复复制或重复生产。这是值得实测的工作负载。当前 Demo 仍有文件到区域的填充，以及客户端从映射取到校验缓冲区的复制。`SharedMemory` 需要 API 27+，创建时要给定大小，映射须 `unmap`，句柄须关闭。`setProtect` 只能收紧后续映射权限，已有写映射仍可写。若设计可复用区域，要另约定版本、写完通知和读者归还，确保最后一个读者释放当前版本后，写者才覆盖区域。[共享内存实现](../../demos/android/demo-binder/src/main/kotlin/dev/techlab/android/binder/SharedMemoryTransfer.kt) · [Android 映射与保护语义](https://developer.android.com/reference/android/os/SharedMemory)

**性能依据。** 对已有 8 MiB 文件，当前实现要等服务端填完整个区域才读到首块，首块 P50 为 30.53 ms，完整 P50 为 56.94 ms。同一输入直接返回文件 FD 分别为 1.25 ms 和 9.38 ms。若后续要给多个消费者共享原生内存数据，应比较“一次生产、N 次读取”的总耗时与峰值内存，现有单读者结果无法给出复用多少次才划算。[S3 共享内存测量口径](evaluation.md)

### 6. Content URI / FileProvider：把稍后打开文件的权利交给另一个应用

发送方把已配置目录里的文件转换为 `content://` URI，将 URI 和临时读权限随 Intent 交给目标应用。接收方真正需要内容时，调用 `ContentResolver`，系统路由到提供方检查权限并打开文件。URI 标识内容，打开操作选定正文实际走哪条路径。

```kotlin
// 发送方，READ_ACTION 为双方约定的动作，targetPackage 为接收应用
val uri = FileProvider.getUriForFile(context, authority, file)
val request = Intent(READ_ACTION).setDataAndType(uri, "application/octet-stream")
    .setPackage(targetPackage).addFlags(FLAG_GRANT_READ_URI_PERMISSION)
context.startActivity(request)

// 接收方，在授权有效期内打开
val fd = requireNotNull(contentResolver.openFileDescriptor(intent.data!!, "r"))
AutoCloseInputStream(fd).use { input -> drain(input, ::consume) }
```

**取舍。** 接收应用可按标准 URI 接口打开内容，无需接入自定义 AIDL，发送方还能把授权限定到指定文件。提供方须配置可分享目录和授权，保证接收方打开时文件仍存在。示例通过 Activity 的 Intent 传递临时授权，生命周期与接收方 Activity 栈相关。若需要超出该授权期限再次打开，应另行定义内容保留与授权生命周期。[AndroidX FileProvider 与 URI 授权](https://developer.android.com/reference/androidx/core/content/FileProvider)

**性能边界。** 本次仅调研机制。标准 FileProvider 打开普通文件后，后续读取走文件 FD。打开前还可能有提供方冷启动、URI 解析和权限检查。自定义 ContentProvider 也可以返回管道，此时读取表现还取决于生产速度。按需打开场景应分别测 URI 交付至打开成功、打开至读完的耗时，并区分提供方冷启动与已运行。现有文件 FD 的 2.99 ms 未包含打开提供方的成本。

### 7. 本地 socket / socket pair：一次建连，两端可持续交换多条消息

服务端可以创建一对已连接的本地 socket，自己保留一端，通过 Binder 返回另一端。以下使用流式 socket，每端都能读写，协议自行划分消息边界。例如每次请求携带序号，每个响应帧携带同一序号与正文长度，两端随后可以继续复用连接。

```kotlin
// 服务端，工作线程与客户端分别关闭各自持有的一端
fun openSession(): ParcelFileDescriptor {
    val (server, client) = ParcelFileDescriptor.createSocketPair()
    workers.execute {
        server.use { socket ->
            for (id in readRequests(socket)) {
                writeFrame(socket, id, payloadFor(id))  // 帧头包含 id 和正文长度
            }
        }
    }
    return client
}

// 客户端，按帧头长度循环读，处理短读和中途断连
remote.openSession().use { socket ->
    for (id in requestIds) {
        writeRequest(socket, id)
        consume(readFrameExactly(socket, expectedId = id, maxBytes = limit))
    }
}
```

**取舍。** 一条持续双向通道适合高频多消息、两端均需主动发送的协议，可以在多条消息之间摊薄建立连接的开销。一次读可能只拿到半帧，也可能包含下一帧的字节，两端需约定长度上限、请求标识、取消、超时和断线处理。发送缓冲区满时也会产生背压。只交付一次单向字节流时，这套协议会增加实现和排错成本。[Android socket pair](https://developer.android.com/reference/android/os/ParcelFileDescriptor#createSocketPair()) · [LocalSocket](https://developer.android.com/reference/android/net/LocalSocket)

**性能边界。** 本次仅调研机制。对持续多次收发的会话，应分别测建连后第一帧的耗时、同一连接稳定传输时每帧的 P50/P95，以及慢消费者条件下的排队长度。可用“每次传输开一个管道”作同负载基线，现有单文件管道数据不能代替 socket 的实测。

## 尚未实现的候选方案

以下五项已登记，性能均待测。前两项已在上文给出双端伪代码。后三项分别改变接收方的访问方式、Parcel 传值方式和持续交换数据的同步协议。

| 候选 | 原理与接入方式 | 使用条件与代价 | 要验证的差异 |
| --- | --- | --- | --- |
| Content URI / FileProvider | 发送方交付标识与授权，接收方稍后解析并打开内容 | 适合跨 UID 延迟打开。授权有效期和内容保留期都要覆盖打开时刻 | 对同一个普通文件，与直接交付 FD 比较。分别记录冷、热提供方的打开耗时，以及打开后的读取耗时 |
| 本地流式 socket pair | 建立一对双向端点，Binder 交付一端，后续按自定义帧协议读写 | 适合重复双向交换。需限制帧长，处理短读、背压和半途断连 | 与相同字节数和消息数的管道基线比较，单列建连、稳定期延迟和慢读者积压 |
| 文件 FD + 只读映射 | 发送方仍交付普通文件 FD，接收方用 `channel.map(READ_ONLY, offset, length)` 建立映射，按偏移访问 | 适合可定位、内容稳定的文件，尤其是重复或稀疏读取。首次访问可能触发缺页，关闭 channel 后 Java 映射仍可存活 | 与同一 FD 的普通读取比较，固定访问序列，分开测建立映射、首次触页和再次访问。记录缺页数与驻留内存。[FileChannel.map](https://developer.android.com/reference/java/nio/channels/FileChannel#map(java.nio.channels.FileChannel.MapMode,%20long,%20long)) |
| Parcel Blob | 自定义 Parcelable 中发送方调用 `parcel.writeBlob(bytes)`，接收方调用 `parcel.readBlob()`。小值原位写入，大值可经匿名共享内存交付 | Android 公共 API 33+。发送和接收的接口仍然是整份字节数组，适合需要值语义且已持有数组的调用。协议不要依赖系统选择传输方式的阈值 | 在同一台 API 33+ 设备上，用相同内存输入对照有界数组直传与显式 SharedMemory，按长度测完整延迟和两端峰值内存。数组直传仅在事务可承载的范围内比较。本次 API 30 设备不能测该公共接口。[Parcel.writeBlob](https://developer.android.com/reference/android/os/Parcel#writeBlob(byte%5B%5D)) |
| AIDL Fast Message Queue（FMQ） | 一端创建共享内存队列，经 Binder 传递队列描述符，另一端据此打开。建立后双方直接读写队列 | Android 12+ 的 AIDL NDK 后端，消息布局须跨进程一致，不能含进程内指针。接入需要原生端和相应 FMQ 库，Java 后端可转发描述符 | 以固定大小记录和固定队列容量，对照逐次 Binder 和流式 socket。分开测首次建队、稳态吞吐、队列满时的等待或丢失，并标注所用队列模式。[AOSP AIDL FMQ](https://source.android.com/docs/core/architecture/aidl/fmq) |

## Demo 与资源归属

四个策略实现 `TransferStrategy`，按序向同一回调交付字节。计时逻辑统一核对长度和 CRC32。功能检查还依次请求三个不同样本，验证返回内容与请求标识的对应关系。异常检查覆盖消费者提前退出、生产者中途失败以及两进程的句柄回收。[策略接口](../../demos/android/demo-binder/src/main/kotlin/dev/techlab/android/binder/TransferStrategy.kt) · [独立进程服务](../../demos/android/demo-binder/src/main/kotlin/dev/techlab/android/binder/TransferService.kt) · [运行与扩展](../../demos/android/README.md#阅读和扩展传输方案)

在 FD 协议中，接口至少应说明接收方得到的是可定位文件还是顺序流，以及谁负责关闭各自的引用。AIDL 直接返回 PFD 时，框架写入副本后关闭服务端用于返回的引用，接收方仍需关闭自己的引用。管道还要约定生产失败的信号。未知长度的流可以从第一个字节开始发送，若要核验完整性，可在生产过程中累计长度和校验值，再通过与同一请求关联的完成消息交付。共享区域则要约定写完与归还的时机。不同方案的“完成”只有在这些条件相同时才适合比较。[PFD 返回值语义](https://developer.android.com/reference/android/os/ParcelFileDescriptor#writeToParcel(android.os.Parcel,%20int))
