# 把 Docker 装到 E:\docker 并完成一键部署

> ✅ **本文档记录的任务已经全部完成。**
> Docker Desktop 4.94.0 已装到 `E:\docker`，5 个容器全部运行，容器版系统实测 **51 项验收全部通过**，
> 浏览器打开 <http://localhost:8000> 即可使用（`admin / admin123`）。
>
> 下面保留的是**完整过程记录**，方便你换机器重装、或排查部署问题。
> 如果你只是想用系统，直接跑 `powershell -ExecutionPolicy Bypass -File deploy\up.ps1` 就够了。

> 这份指引是针对**你这台机器实测出来的情况**写的，不是通用文档。
> 安装软件到 `E:\docker` 这个位置按你的要求已经写进命令里了。

---

## 〇、最终结果速览

| 环节 | 结果 |
| --- | --- |
| 装 Docker Desktop 到 `E:\docker` | ✅ 4.94.0，退出码 0，约 1 分钟 |
| 启用虚拟化（VirtualMachinePlatform + WSL） | ✅ `exitcode=3010`（成功待重启） |
| 配置国内镜像加速 | ✅ 实测 `docker.1ms.run` 64 ms；Docker Hub 直连超时 |
| 5 个容器 | ✅ 全部 Up，backend 与 mysql 均 healthy |
| 容器内数据 | ✅ 33 条知识 / 69 个向量块 / 3 个初始账号 |
| 容器版全量验收 | ✅ **51 项全部通过** |
| 首字耗时（容器版） | ✅ 首次 1477 ms、热态 87 ms（修复 keep_alive 前是 10285 ms） |

---

## 一、原先的阻塞点：为什么最初必须你亲手执行

> （已解决，保留作为记录）

我在这台机器上实测了 部署的全部前置条件，结果如下：

| 检查项 | 实测结果 | 结论 |
| --- | --- | --- |
| 系统版本 | Windows 10 Pro Build 19045 | ✅ 满足 Docker Desktop 要求 |
| E 盘空间 | 可用 399.0 GB | ✅ 完全够用 |
| C 盘空间 | 可用 80.8 GB | ⚠️ 所以镜像存储必须挪到 E 盘 |
| Docker | 未安装 | ❌ 需要安装 |
| `Microsoft-Hyper-V` / `VirtualMachinePlatform` | 相关服务 `vmcompute` 不存在 | ❌ **虚拟化平台未启用** |
| WSL | `LxssManager`、`WslService` 都不存在，`wsl -l -v` 直接打印帮助 | ❌ **WSL 实际并没有装**（容易误以为「WSL 已安装」） |

也就是说：**需要先启用虚拟化功能并重启电脑，再安装 Docker**。而这两件事：

- 启用 Windows 功能需要**管理员权限**（会弹 UAC）
- 启用后**必须重启电脑** —— 重启会结束我们当前的会话
- 安装 Docker Desktop 同样需要管理员权限

所以我只能帮你把**安装包下载好、命令写好**，最后那几下点击和重启必须你来。

---

## 二、我已经帮你做好的部分

| 项目 | 状态 |
| --- | --- |
| Docker Desktop 安装包 | ✅ 已下载并校验（606.1 MB，Docker Inc. 数字签名有效，v4.94.0） |
| `docker-compose.yml`（5 个服务） | ✅ 已写好，见 `opsai\docker-compose.yml` |
| 后端 Dockerfile | ✅ 已写好，见 `opsai\opsai-backend\Dockerfile` |
| 前端 Dockerfile + nginx.conf | ✅ 已写好 |
| 容器内 MySQL 初始化 SQL | ✅ 已写好，见 `opsai\deploy\mysql\init.sql` |
| 容器内 Maven 镜像加速配置 | ✅ 已写好，见 `opsai\deploy\maven-settings.xml` |
| `.dockerignore` | ✅ 已写好（避免把 node_modules、.m2repo 打进构建上下文） |
| **一键启动脚本（带体检）** | ✅ `opsai\deploy\up.ps1` |
| **交付物静态校验** | ✅ 已通过 50 项检查（含 compose-spec 官方 schema 校验） |
| **Docker 官方解析器校验** | ✅ `docker compose config --quiet` 退出码 0（Compose v5.5.1） |

### 静态校验做了什么

在没装 Docker 的前提下，我把能查的都查了（脚本见 `.verify\check-compose.mjs`）：

- `docker-compose.yml` 能被 YAML 解析，且**符合 compose-spec 官方 JSON Schema**
- 所有被引用的文件真实存在（两个 Dockerfile、pom.xml、package.json、nginx.conf、init.sql、maven-settings.xml）
- `depends_on` 指向的服务都存在，且声明 `condition: service_healthy` 的一方确实配了 healthcheck
- `volumes` 引用的命名卷都已声明、绑定的宿主机路径都存在
- 端口映射逐个和本机实际监听端口比对（**就是这一步查出了 8080 冲突**）
- nginx.conf 的 listen / 反代目标 / SPA fallback / 反代超时都对
- 后端 Dockerfile 确实没有把 `.mvn` 带进镜像、确实 COPY 了容器专用 settings

没验证的部分（必须装了 Docker 才能验）：镜像能不能真正构建出来、5 个容器能不能真正起来、容器里能不能连上宿主机 Ollama。

### Docker 装好之后，又用 Docker 自己的解析器验了一遍

Docker Desktop 装到 `E:\docker` 之后，虽然守护进程还没起来（要等重启），但 **`docker compose config` 不需要守护进程**，所以可以拿 Docker 官方的解析器做最终校验——这比我自己写的 JS 校验权威得多：

```
docker compose -f docker-compose.yml config --quiet   ->  exit 0
```

解析出来的完整结果（节选）：

| 服务 | 端口映射 | 镜像 |
| --- | --- | --- |
| backend | 8080:8080 | opsai-backend:1.0.0（本地构建） |
| web | 8000:80 | opsai-web:1.0.0（本地构建） |
| mysql | 3307:3306 | mysql:8.0 |
| redis | 6379:6379 | redis:7-alpine |
| chroma | 8001:8000 | chromadb/chroma:0.5.5 |

命名卷 `mysql-data`、`chroma-data` 均已声明；`deploy/mysql/init.sql` 的 bind 挂载被正确解析成宿主机绝对路径且 `read_only: true`；`depends_on: mysql condition: service_healthy` 也正确保留。

另外确认了 Docker 安装程序已把 `E:\docker\resources\bin` 写进**系统 PATH**，重启后 `docker` 命令可直接使用；
`up.ps1` 里还加了兜底——万一 PATH 没刷新，会去 `E:\docker\resources\bin\docker.exe` 找，实测能在当前未重启、
PATH 尚未生效的情况下正确定位到 docker 并报告"守护进程未运行"。

### 还把容器的启动路径"拆开"逐段验证过了

既然装不了 Docker，我把它启动时会执行的每一步**在本机单独跑了一遍**，这样等真装好 Docker 时基本不会翻车：

| 容器里会执行的步骤 | 我的验证方式 | 结果 |
| --- | --- | --- |
| `RUN mvn package -DskipTests` | 在本机跑同一条命令 | ✅ 构建成功 |
| `COPY --from=build /app/target/*.jar app.jar` | 检查 target 下到底有几个 jar | ✅ 只有 1 个（`opsai-backend-1.0.0.jar` 65.6 MB，`.jar.original` 不匹配 `*.jar`），不会 COPY 到多个文件而报错 |
| `ENTRYPOINT ["java","-jar","/app/app.jar"]` | **真的用 `java -jar` 把它跑起来** | ✅ 4.465 秒启动，`/api/health` 返回 ok，登录、统计、知识库全部正常 |
| 容器首次启动执行 `init.sql` | 在本机建临时库 `opsai_ct` 跑同一份 SQL，验完删掉 | ✅ 6 张表全部建出，`NOT NULL` / 默认值 / 字段类型逐项比对一致 |
| web 镜像里的 `npm run build` | 本机跑同一条命令 | ✅ 6.67 秒构建成功，2252 个模块 |
| `nginx.conf` | 静态检查 listen / 反代目标 / SPA fallback / 超时 | ✅ 全部正确 |

也就是说，除了"Docker 引擎本身能不能把这些装进容器"这一件事，**其余每一段都已经用真实运行验证过了**。

---

## 三、你要执行的步骤

### 第 1 步：启用虚拟化功能（管理员 PowerShell）

在开始菜单搜「PowerShell」→ 右键 → **以管理员身份运行**，然后执行：

```powershell
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart
dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
```

两条都出现「操作成功完成」即可。

> **如果上面第二条报错**（有些精简版系统没有 WSL 组件），改用 Hyper-V 方案：
> ```powershell
> dism.exe /online /enable-feature /featurename:Microsoft-Hyper-V-All /all /norestart
> ```
> 后面第 3 步安装时把 `--backend=wsl-2` 换成 `--backend=hyper-v`。

### 第 2 步：重启电脑

**必须重启**，否则第 3 步会失败。重启后重新打开我，告诉我「重启好了」，我继续帮你验证。

### 第 3 步：安装 WSL2 内核（仅 WSL2 方案需要）

重启后，**再次以管理员身份**运行 PowerShell：

```powershell
wsl --set-default-version 2
```

如果提示需要更新内核，下载并安装（双击即可）：
<https://wslstorestorage.blob.core.windows.net/wslblob/wsl_update_x64.msi>

> 如果你打算用 Hyper-V 方案，这一步跳过。

### 第 4 步：安装 Docker Desktop 到 E:\docker

**以管理员身份**运行 PowerShell，执行（这条命令已经把安装目录设成 `E:\docker`）：

```powershell
$exe = "C:\opsai\docker-installer\DockerDesktopInstaller.exe"

Start-Process -FilePath $exe -Verb RunAs -Wait -ArgumentList @(
  'install',
  '--quiet',
  '--accept-license',
  '--backend=wsl-2',
  '--installation-dir=E:\docker'
)
```

- `--installation-dir=E:\docker` ← 就是你要的安装位置
- `--quiet` 表示静默安装，不会弹界面，**大概 3～8 分钟**，请耐心等命令返回
- 会弹一次 UAC，点「是」
- 安装完成后建议**再重启一次**

### 第 5 步：把镜像存储也挪到 E 盘（重要！）

Docker 默认把镜像和容器数据放在 `C:\Users\<你的用户名>\AppData\Local\Docker\wsl`。
你的 C 盘只剩 80.8 GB，而这个项目要拉 mysql + redis + chroma 加构建层，大概会吃掉 **8～12 GB**，所以一定要挪走。

启动 Docker Desktop（开始菜单里找），等右下角鲸鱼图标变绿，然后：

**Settings → Resources → Advanced → Disk image location** 改成 `E:\docker\data` → **Apply & restart**

### 第 6 步：配置国内镜像加速（可选但强烈建议）

**Settings → Docker Engine**，在 JSON 里加一段 `registry-mirrors`，然后 Apply & restart：

```json
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://dockerproxy.com"
  ]
}
```

### 第 7 步：让容器能访问宿主机的 Ollama

Ollama 跑在 Windows 上，容器默认访问不到，必须让它监听所有网卡：

```powershell
setx OLLAMA_HOST 0.0.0.0
```

然后**右下角托盘图标右键 → Quit 退出 Ollama，再重新打开**（这一步不能省，环境变量只在启动时读一次）。

验证：

```powershell
curl http://localhost:11434/api/tags
```

### 第 8 步：一键启动整套系统

**推荐用我写好的一键脚本**，它会先做启动前体检（Docker 是否就绪、端口有没有被占、Ollama 通不通、文件齐不齐），有问题当场告诉你怎么处理：

```powershell
cd C:\opsai
powershell -ExecutionPolicy Bypass -File deploy\up.ps1
```

体检通过后它会自动执行 `docker compose up -d --build` 并等后端就绪。

<details>
<summary>如果你想手动执行（脚本做的事情就是这些）</summary>

```powershell
cd C:\opsai
docker compose up -d --build
docker compose ps
```

</details>

#### ⚠️ 启动前必须先停掉本机直接跑的后端（我实测发现的冲突）

我用脚本在你机器上实测了端口占用，结果是：

```
[占用] 端口 8080 <- java (PID 7292)   backend 容器
[空闲] 端口 3307  mysql 容器
[空闲] 端口 6379  redis 容器
[空闲] 端口 8000  web 容器
[空闲] 端口 8001  chroma 容器
```

**8080 被本机那个 `mvn spring-boot:run` 启动的后端占着**，而 compose 里 backend 也映射到 8080，两个同时开的话容器会起不来（报 `port is already allocated`）。

处理办法二选一：

```powershell
# 方式一：把本机直接跑的后端停掉（推荐，容器化部署和本机直跑二选一）
Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force

# 方式二：只停容器、用本机的方式跑（那就别用 compose 起 backend）
```

`deploy\up.ps1` 会自动检测到这一点并问你要不要结束占用进程，你不用手动记。

> 注意 3307 不是冲突源——本机 MySQL80 占的是 3306，compose 已经特意把容器 MySQL 映射到 3307 来避开它。

第一次构建要下载 maven / node 基础镜像并编译，**大概 10～20 分钟**。`docker compose ps` 应该看到 5 个容器：

| 容器 | 端口 | 说明 |
| --- | --- | --- |
| opsai-web | 8000 → 80 | 前端，**系统入口** |
| opsai-backend | 8080 → 8080 | 后端接口（注意别和本机后端抢） |
| opsai-mysql | 3307 → 3306 | 数据库（注意是 3307） |
| opsai-redis | 6379 | 按需求引入的组件 |
| opsai-chroma | 8001 → 8000 | 向量库（预留） |

然后浏览器打开 **<http://localhost:8000>**，用 `admin / admin123` 登录。

> ⚠️ 容器里的数据库是全新的空库，**知识库也是空的**。启动后需要跑一次导入：
> ```powershell
> powershell -ExecutionPolicy Bypass -File C:\opsai\scripts\seed-kb.ps1
> ```

### 第 9 步：验证

```powershell
docker compose ps                          # 5 个都是 running / healthy
curl http://localhost:8000/api/health      # status=ok
docker compose logs -f backend             # 看后端日志排错
```

常用命令：

```powershell
docker compose restart backend    # 重启后端
docker compose down               # 停止全部
docker compose down -v            # 停止并删数据卷（会清空数据，谨慎）
```

---

## 四、我把两个常见的坑提前修掉了

### 坑 1：常见写法的 Dockerfile 会直接构建失败

常见写法是让你在 `opsai-backend` 目录下写 Dockerfile 并 `COPY . .`。

但我为了让 `mvn` 命令不用每次加 `-s` 参数，在 `opsai-backend\.mvn\maven.config` 里写了一行**Windows 绝对路径**：

```
-s
C:\opsai\maven-settings.xml
```

这个文件一旦被 COPY 进 Linux 容器，`mvn` 会直接报
`The specified user settings file does not exist` 然后构建失败。

**我的做法**：Dockerfile 里**故意不 COPY `.mvn` 目录**，只精确 COPY `pom.xml` 和 `src`，
另外单独放了一份容器专用的 `deploy/maven-settings.xml`（里面不写 `localRepository`，因为容器路径和 Windows 不一样）。

### 坑 2：健康检查工具不存在

后端基础镜像是 `eclipse-temurin:17-jre`，里面**不一定有 wget 或 curl**。
如果直接用 `wget` 做 healthcheck，容器会被一直标成 `unhealthy`。
我改成了 bash 自带的 `/dev/tcp` 探测端口，不依赖任何额外工具。

---

## 五、如果装不上怎么办

容器化只是「把它容器化」这一步。**你现在的系统已经完全能跑、能演示**：

```powershell
# 后端
cd C:\opsai\opsai-backend
mvn spring-boot:run

# 前端（另开一个窗口）
cd C:\opsai\opsai-web
npm run dev
```

浏览器打开 <http://localhost:5173> 就是完整系统。

演示时如果 Docker 出问题，用这个方式演示完全不影响效果——而且核心流程跑通就已经能演示了。
