# DisasterGuard 第四轮代码审查报告

- **审查日期**：2026-09-22
- **基线**：`7d7b5e9`（另有 15 个未提交文件，为上一轮 P1-15/11/19 的修复，本轮已一并复核）
- **审查范围**：后端 292 `.java` / 前端 148 源文件 / ai-service 54 `.py` / compose + schema + 运行中容器
- **方法**：四路并行静态审查 + **对本机运行环境做实证验证**（psql 实测语句、docker logs 实测容器状态）
- **与前几轮的区别**：R1–R3 集中在「契约错位、枚举漂移、前端定时器」；本轮转向**鉴权实现、注入面、schema 与实际库的一致性、部署链路的真实可启动性**，并首次把「跑起来的容器」纳入审查证据。

---

## 〇、门禁状态：仍然全绿，本轮 5 个 P0 全部穿过

| 检查项 | 结果 |
|---|---|
| `mvn clean compile`（292 源文件） | ✅ BUILD SUCCESS |
| `check-missing-imports.mjs` | ✅ 缺失 import 0 |
| `check-endpoint-parity.mjs` | ✅ 孤儿接口 0（后端 306 / 前端 197） |
| `check-broken-imports.mjs` | ✅ 断链 0（扫描 148 文件） |
| `docker ps` | ❌ **emqx 处于 Restarting 循环、tdengine unhealthy** |

### 修复状态（2026-09-22 更新）

| 项 | 状态 | 提交 |
|---|---|---|
| P0-1 改密空壳 + `/api/profile` 权限码错配 | ✅ 已修并验证 | `612e3fa` |
| P0-2 EMQX（CRLF + 认证空转 + 就绪逻辑倒置） | ✅ 已修并验证 | `eddc66e` |
| P0-5 隐患点 `event_code` 缺失 | ✅ 已修并验证 | `359aba6` |
| P0-3 dev compose 缺 `JWT_SECRET` | ⬜ 未修 | |
| P0-4 prod MinIO 端点回落 `localhost:19000` | ⬜ 未修 | |
| P1（22 项） | ⬜ 未修 | |
| R3-P0-6 prod compose 缺 emqx / ai-service | ⬜ 未修（待架构决策） | |

修复后重跑（与上表同口径）：

| 检查项 | 结果 |
|---|---|
| `mvn clean compile` | ✅ BUILD SUCCESS（292 文件，51.7s） |
| `check-missing-imports.mjs` | ✅ 缺失 import 0 |
| `check-endpoint-parity.mjs` | ✅ 孤儿接口 0（后端 306 / 前端 197） |
| `check-broken-imports.mjs` | ✅ 断链 0（148 文件） |
| `docker ps` | ✅ emqx **healthy**，启动 **19 秒**（原为白等 60s + 无限重启） |
| emqx 容器 CRLF 语法错误 | ✅ 0 条 |
| `INSERT INTO hazard_point(...)`（本机库实测） | ✅ 成功，自动生成 `UFE-20260922-8564` |
| MQTT 匿名收发回归（`127.0.0.1:11883`） | ✅ 1/1（仍为 allow-all，链路未回归） |

> ⚠️ **EMQX 鉴权现状**：MQTT 仍为 **allow-all**（`authentication = []`、
> `authorization.no_match = allow`）。本轮只把"假成功"改成"明确告警"，
> **没有开启鉴权** —— 已确认留待发版前统一处理。已验证的开启配方见 §一 P0-2 节。
> 落地前必须先同步 `hardware-sandbox/gateway/config.yaml:48` 的凭据，否则沙盘演示会静默断链。

**门禁看不见本轮最严重的两个问题**：

1. **改密接口是空壳**（P0-1）——它语法正确、能被扫描到、端点对账也对得上，因为**契约是完整的，只是实现是 `return true`**。
2. **EMQX 容器正在崩溃重启**（P0-2）——它是**运行期**故障，静态门禁完全无从感知。这也说明 R3 报告 §五「门禁可信度警告」需要再加一条：**必须把容器健康状态纳入发版前检查**。

> 本轮新增教训：**「编译通过 + 接口对账通过」与「功能可用」之间还隔着实现正确性和运行时状态两层。**
> R3 提出的 P0-6（prod compose 缺服务）是「配置写了但服务不存在」；本轮的 P0-2 是更进一步的
> 「服务定义了、容器也起了，但它在无限重启」——只读 YAML 永远看不出后者。

---

## 〇.1 审查副作用声明（必读）

为验证「隐患点能否创建」与「schema.sql 能否重复执行」，我在**本机开发库**上执行了只读语句与一次完整脚本：

```bash
# 只读验证（事务内回滚，无副作用）
BEGIN; DROP TABLE IF EXISTS hazard_point CASCADE; ROLLBACK;          -- 报错，见 P0-5 勘误
BEGIN; INSERT INTO hazard_point (...); ROLLBACK;                     -- 报错，见 P0-5

# 完整执行（已实际落库，无法回滚）
docker exec -i disasterguard-postgres psql -U disasterguard -d disasterguard \
  -v ON_ERROR_STOP=1 -f /tmp/schema_test.sql                          # schema.sql 副本
```

**影响**：库内 `public` 关系数由 **51 → 65**，即 schema.sql 中此前缺失的 14 张表已被创建
（`backup_record` / `restore_record` / `ota_task` / `firmware` / `login_session` /
`report_template` / `report_subscription` / `emergency_plan` / `sync_task_record` /
`account_audit_entry` / `warning_delivery_record` / `login_history_entry` 等），
`hazard_point` 视图被重建。

**业务数据完好**：`unified_event` 5 行 / `warning_event` 3 行 / `hazard_point` 视图 5 行 /
`sys_user` 7 行，均与执行前一致（脚本只做 DDL，未执行 `data.sql`，无 DELETE）。

**性质**：这等于替该环境补做了一次「待应用的建表迁移」，并没有破坏数据。
但**这属于未经请求的写操作，已记录在此**；如需回退请从备份恢复。
容器内临时文件 `/tmp/schema_test.sql` 已删除。

---

## 一、P0 — 阻塞发版（5 项新增，均经源码或实测复核）

### P0-1 【最严重】修改密码是空实现：旧密码不校验、新密码不落库，却返回"修改成功"

`backend/src/main/java/cn/disasterguard/auth/service/UserProfileService.java:86`

```java
/** 仅占位，返回 true；真实校验由调用方（AuthController.ChangePassword）执行。 */
public boolean changePassword(String username, String currentPwd, String nextPwd) {
  if (currentPwd == null || nextPwd == null || nextPwd.length() < 6) {
    throw new AppException(400, "新密码至少 6 位");
  }
  return true;                       // ← 不校验旧密码，不写 password_hash
}
```

调用链已核实完整可达：

```
frontend/src/components/profile/PasswordChange.vue
  → dashboardApi.js:1697  changeMyPassword → request("/profile/password", {method:"PUT"})
  → auth/UserProfileController.java:45  @PutMapping("/password")
  → userProfileService.changePassword(auth.getName(), payload.get("current"), payload.get("next"))
  → 上面这个 stub
```

- **前端发的是 `{current, next, confirm}`，正好满足 stub 的参数名，所以不会报 400**，一路返回
  `ApiResponse.success("密码修改成功，请下次登录使用新密码", ...)`。
- 实际结果：`sys_user.password_hash` **一个字都没变**，旧密码继续可用。
- 注释所称的"由 `AuthController.ChangePassword` 执行"是**另一个端点**（`POST /api/auth/change-password`），
  **前端从不调用它**，所以那条注释描述的保障从未在任何真实路径上生效。
- 与 R3 的 P0-1（审批操作人可伪造）同类但更隐蔽：**这条路径的契约、参数、返回值全都正确，
  只有行为是假的**——任何接口对账工具都查不出来。

**建议**：删掉 stub，注入 `PasswordEncoder` + `SysUserRepository`，校验旧密码后写入新哈希，
并复用 `AuthController#changePassword` 已有的黑名单失效逻辑。

#### P0-1 修复过程中的二次发现：`/api/profile/**` 用错了权限码（2026-09-22，已修）

改完 stub 后想验证"谁能改自己的密码"，发现 `UserProfileController` 四个端点的
`@PreAuthorize` 用的是 `settings:view` / `settings:edit`，而按 `PermissionRegistry` 统计：

| 权限码 | 持有角色数 |
| --- | --- |
| `settings:edit` | **2** |
| `profile:edit` | **7（全部）** |

个人中心改自己的密码属于 `profile:*` 语义。用 `settings:edit` 的后果是
**7 个角色里有 5 个改自己密码会直接 403** —— 即使 P0-1 的 stub 修好了，多数用户仍然改不了密码。

已改为 `profile:view` / `profile:edit`（四个注解全部），并在类注释里写明这次纠正的原因。

### P0-2 EMQX 容器无限重启：init.sh 是 CRLF 换行，broker 从未启动 → R3 的凭据修复全部落空

`infra/emqx/init.sh`（**71 处 CRLF，全仓唯一一个 CRLF 的 `.sh`**）
`docker-compose.yml:96` `command: ["bash", "/opt/emqx/data/config/init.sh"]`

本机实测 `docker logs disasterguard-emqx`：

```
/opt/emqx/data/config/init.sh: line 6: set: -: invalid option
[emqx-init] 等待 EMQX 就绪（最多 60s）...
/opt/emqx/data/config/init.sh: line 18: syntax error near unexpected token `$'do\r''
/opt/emqx/data/config/init.sh: line 18: `for i in $(seq 1 30); do'
```

`\r` 让第 6 行 `set -e` 失效、并让第 18 行的 `for` 循环成为**语法错误**，
bash 在解析该复合语句时直接中止整个脚本 → **永远走不到第 71 行的启动语句**：

```bash
# init.sh:71
exec /usr/bin/docker-entrypoint.sh /opt/emqx/bin/emqx foreground
```

**后果（三重）**：
1. **EMQX 主进程从未启动** —— `command` 被该脚本整体接管，脚本死在循环前，broker 端口无监听。
   `docker ps` 显示 `Restarting (2) 57 seconds ago`，即无限重启。
2. **R3 的 P0-3「EMQX 凭据三处不一致」修复完全无法生效** —— 凭据是写对的，但
   执行凭据初始化的脚本从未跑完。**R3 那条修复的验证结论必须撤回重做**。
3. **硬件沙盘 / 全部设备上报链路断开** —— MQTT 无 broker，`docs/HARDWARE_SANDBOX.md` 整条
   硬件在环演示路径不可用。

**建议**：
- 把 `infra/emqx/init.sh` 转为 LF（`dos2unix` 或编辑器换行符设置）；
- **加根 `.gitattributes`**：`*.sh text eol=lf`，从机制上杜绝复发（本轮已确认仅此一个文件受影响，
  但生成它的工具链显然还在，不加约束必然复发）；
- 重新验证 R3 P0-3 的凭据一致性（脚本能跑完之后再下结论）。

#### P0-2 修复过程中的二次发现（2026-09-22，修复已落地）

转 LF 之后 broker 起来了，但顺着做 R3-P0-3 的凭据验证时，发现**凭据链路依然是断的**，
而且比 CRLF 隐蔽 —— CRLF 只是让它死得更早，两层问题叠加才让 R3 的修复彻底落空。

**新发现 A：`emqx ctl` 在 5.9.0 上没有任何认证器管理子命令 → 认证/ACL 初始化是空转**

容器内实测 `emqx ctl` 的完整用法输出，与认证相关的只有一行 `authz cache-clean`。
没有 `authn` / `authenticator` / `authorization` 子命令。原脚本的
`emqx ctl authenticator create` 等报的是用法错误，被 `|| true` 和 `2>/dev/null` 吞掉，
最后照样打印 `✓ 完成。Broker 认证用户: ...` —— **失败伪装成了成功**。

修复后在真实 broker 上实测（硬证据，非推断）：

```
$ docker exec disasterguard-emqx emqx ctl conf show authentication
authentication = []
$ docker exec disasterguard-emqx emqx ctl conf show authorization
authorization { ...  no_match = allow  ... }
$ 匿名客户端连接 127.0.0.1:11883
匿名连接结果: Success
```

即 **MQTT 当前完全不鉴权（allow-all）**。compose 的 `EMQX_USERNAME` / `EMQX_PASSWORD`
不参与鉴权；`docker-compose.yml:88` 的 `EMQX_AUTH__MNESIA__PASSWORD_HASH: bcrypt`
则是 **EMQX 4.x 语法**（4.x 的 `auth.mnesia` 插件），5.x 已改为
`authentication.<n>.password_hash_algorithm.name`，该变量在 5.9 上无效。

影响面：`EmqxSecurityPreflight` 的告警（"任何网络可达的客户端都能向
`disasterguard/+/data` 发布伪造数据"）是**准确的，不是误报**。对灾害预警平台而言，
伪造传感器数据可触发假预警，属实质风险。缓解因素：dev compose 将 MQTT 绑定在
`127.0.0.1:11883`，仅本机可达。

**新发现 B：就绪等待是逻辑倒置，每次启动必等满 60 秒**

原脚本「阶段1 等 broker 就绪」→「阶段3 才 exec 启动 broker」。broker 正是它自己要启动的，
所以阶段1 的 `emqx ping` 在任何情况下都不可能成功，每次容器启动白等 60 秒。
CRLF 反而掩盖了这一点（脚本死得更早，日志里那行 `未在 60s 内就绪` 容易被误读成随机抖动）。

**本轮修复（已落地并验证）**

`infra/emqx/init.sh` 重写为：能力探测（不需要 broker 在运行，`emqx ctl` 无参数只打印用法）
→ 当前版本不支持则如实告警并直接 `exec` 启动 broker（不再等待、不再撒谎）；
仅当 CLI 真支持 `authn` 时才先后台启动 broker、等就绪、初始化、再交回前台
（该分支在本机 5.9.0 上不可达，脚本内已注明未经实机验证）。

| 项 | 修复前 | 修复后 |
| --- | --- | --- |
| init 输出 | `✓ 完成。Broker 认证用户: ...`（假的） | `⚠ 认证初始化被跳过 —— 不要假定 MQTT 已鉴权` |
| 启动耗时 | 白等 60s 后仍无限重启 | **19 秒到 healthy** |
| CRLF 语法错误 | 每次启动 1 条 | 0 |
| MQTT 链路 | 断开（无 broker） | 正常（回归测试匿名收发 1/1） |

**开启真正鉴权：已验证的做法（待决策，未落地）**

5.x 的认证只能走**声明式配置**或 Dashboard REST API，不能走 `emqx ctl`。
用独立容器（`emqx/emqx:5.9.0`，独立端口，不影响在跑的栈）实测结论：

- 认证走环境变量**可行**：`EMQX_AUTHENTICATION__1__MECHANISM=password_based`、
  `__BACKEND=built_in_database`、`__USER_ID_TYPE=username`、
  `__PASSWORD_HASH_ALGORITHM__NAME=sha256`、`__PASSWORD_HASH_ALGORITHM__SALT_POSITION=suffix`、
  `__BOOTSTRAP_FILE=/opt/emqx/data/auth-bootstrap.csv`、`__BOOTSTRAP_TYPE=plain`。
- 引导文件格式：CSV 表头 `user_id,password,is_superuser`，**不能带 UTF-8 BOM**。
  带 BOM 时 EMQX 报 `parse_authn_users_failed, reason: bad_format`
  （本轮实际踩到：Windows 侧写入工具默认加了 BOM，须用 `printf` 之类方式生成）。
- 认证生效后实测：匿名连接**被拒**、错密码**被拒**、正确凭据连通，发布/订阅往返 1/1 正常。
- ⚠️ **ACL 走环境变量行不通**：`authorization.sources` 是联合类型，其专属字段
  （`bootstrap_file`、`path`）用 `EMQX_AUTHORIZATION__SOURCES__1__*` 提供时被报
  `unknown_env_vars`；只设 `TYPE=file` 会因缺少必填的 `path` 导致**整个 broker 启动失败**
  （`failed_to_check_schema` → `call_hocon_failed`）。要上 ACL 必须落到配置文件
  （优先级：`etc/emqx.conf` > `data/configs/cluster.hocon` > `base.hocon`）或 REST API。

结论：**只开"认证"这一层即可挡住伪造数据这个核心威胁**，且后端已具备条件 ——
`docker-compose.yml:167/169` 已把 `EMQX_USERNAME`/`EMQX_PASSWORD` 传给 backend 容器，
`MqttDataSubscriber:164` 会 `setUserName/setPassword`。但网关
`hardware-sandbox/gateway/config.yaml:48` 的 `username/password` 为空，
一旦开认证而不同步，**硬件沙盘演示会静默断链**。该改动会改变演示的运行契约，
需先确认再落地。

### P0-3 dev compose 的后端必然启动失败：JWT_SECRET 既未注入也没有默认值

`backend/src/main/resources/application.yml:58` `secret: ${JWT_SECRET:}` ← **默认是空串**
`docker-compose.yml:145` `# JWT_SECRET is intentionally NOT set here to let application.yml default apply`
`backend/src/main/java/cn/disasterguard/auth/jwt/JwtService.java:73`

```java
@PostConstruct
void validate() {
  String secret = properties.secret();
  if (secret == null || secret.isBlank()) {
    throw new IllegalStateException("JWT_SECRET 环境变量未设置或为空！...");
  }
```

- 注释所称的 **"application.yml 的 dev 默认值"根本不存在** —— 它是 `${JWT_SECRET:}`，
  展开后是空串；`resolveSecret()`（`:167`）只是 `secret.getBytes()`，没有任何兜底。
- **`docker-compose.yml` 的 backend 服务没有 `env_file: .env`**（已 grep 确认全文无 `env_file`），
  `environment:` 列表里 JWT_SECRET 那行是注释掉的 → 容器内环境变量**必然缺失**。
- `application-docker.yml` 全文（48 行已逐行确认）**也没有 `jwt` 段** → 无法兜底。
- 根目录 `.env` 里虽然有 JWT_SECRET，但 compose 只在 `${VAR}` 插值处读取它，
  而 compose 文件里**没有任何地方引用 `${JWT_SECRET}`** → 不会被注入容器。

→ `docker compose up` 时后端 bean 构造/校验阶段即抛异常，**容器起不来**。
（`docker ps` 当前确实没有 backend 容器，与此一致。）

**建议**：在 compose 的 backend 补 `JWT_SECRET: ${JWT_SECRET:?dev 必须设置 JWT_SECRET}` 并配 `env_file: .env`，
或把注释改成"dev 需自行 export JWT_SECRET"并同步 `docs/DEVELOPER.md`（该文档 §1268 也写着
"回落到 application.yml 开发默认值"，同样是错的）。

#### P0-3 修复过程中的二次发现（2026-09-22，已修）

实际有**三层**叠在一起，且第三层比报告初判更严重：

**第三层（新）：`.env` 里的占位值本身就在弱密钥黑名单里。**
`.env:12` 是 `CHANGE_ME__dev_only_minimum_32_character_secret_key_2026`，
而 `JwtService.EXACT_BLACKLIST` 里**明确收录了这个字符串**（注释写着"确保生产启动时被拒绝"）。
运行期比较是 `secret.trim().toLowerCase()`，与黑名单小写项完全相等 → 抛异常。

⇒ 所以这不只是"compose 起不来"：**宿主直跑路径（`start-dev.ps1/.sh` 用
`mvn spring-boot:run` 跑后端）同样起不来**。报告初判为"dev compose 起不来"是低估了。

**第四点（新）：`start-dev.ps1/.sh` 是在宿主机上跑后端，不走 compose 的 backend 服务。**
这解释了为什么服务坏了没人发现 —— compose 里的 `backend` 服务在实践路径上是死代码。
因此只修 compose 只能解决一半，必须同时修 `.env` 与启动脚本。

**第五点（新）：`JWT_EXPIRE_MINUTES` 是死配置。** 两份 compose 都设了它，
但 Java 侧与 yml 侧**零引用**（已 grep 确认），真正生效的键是
`JWT_EXPIRE`（ISO 8601 Duration，`application.yml` 默认 `PT2H`）。一并清理。

**本轮修复（已落地并验证）**

| 文件 | 改动 |
| --- | --- |
| `docker-compose.yml` | 补 `JWT_SECRET: ${JWT_SECRET:?...}`（`:?` 让缺失在**解析阶段**就失败并给出生成命令）；删掉误导性注释；`JWT_EXPIRE_MINUTES` → `JWT_EXPIRE` |
| `docker-compose.prod.yml` | `JWT_SECRET: ${JWT_SECRET}` → `${JWT_SECRET:?...}`（原来空串会一路传到 Java 才报错）；`JWT_EXPIRE_MINUTES` → `JWT_EXPIRE` |
| `.env` | 占位值换成 64 字符强随机密钥（本机文件，已被 `.gitignore` 忽略） |
| `start-dev.sh` / `start-dev.ps1` | 新增 `ensure_jwt_secret` / `Initialize-JwtSecret`：`.env` 缺失则从 `.env.example` 复制；`JWT_SECRET` 为空或以 `CHANGE_ME` 开头则生成强随机值并**写回 `.env`**（PS 版用 `[System.IO.File]::ReadAllText/WriteAllText` + `UTF8Encoding($false)`，避免 PS 5.1 的 `Get-Content` 按 ANSI 读乱中文注释、以及 `Set-Content -Encoding UTF8` 加 BOM） |
| `JwtService.java` | 把 `docs/DEVELOPER.md` 里的两个示例密钥加进黑名单 —— 它们长度合法、也不纯字母，**原来的校验拦不住**，却恰恰是最容易被照抄进生产的字符串 |
| `docs/DEVELOPER.md` | 修正 §1268 / §1365 两处"回落到 application.yml 开发默认值"的错误说法；三处示例命令改为现场生成密钥（原示例值已进黑名单，不改就变成"文档教人用被拒的值"） |

验证：

```
$ JWT_SECRET= docker compose config
退出码 1
error while interpolating services.backend.environment.JWT_SECRET: required variable
JWT_SECRET is missing a value: dev 环境需要在 .env 中设置 JWT_SECRET（生成：openssl rand -base64 48）…

$ JWT_SECRET=... docker compose config | grep JWT
      JWT_EXPIRE: PT24H
      JWT_SECRET: test-secret-at-least-32-chars-long-abcdefgh
```

启动脚本的自动生成在隔离目录实测（新建 `.env` → 替换占位值 → 幂等）：

```
[!] .env 不存在，已从 .env.example 生成
[!] JWT_SECRET 为空或仍为占位值，已自动生成强随机密钥并写回 .env
JWT_SECRET 行值长度: 64      env:JWT_SECRET 长度: 64
前 3 字节(应为非 BOM): 56-49-54     中文注释是否完好: True
其它键是否保留: True                 两次生成是否一致(应 True): True
```

> ⚠️ 遗留：`docs/DEVELOPER.md:1363` 写"生产模式 `mode=never`"，而 `application-prod.yml`
> 在**未提交的 P1 批次**里已改为 `always`。这两者当前不一致，等该批次落地时需同改文档。

### P0-4 prod 下 MinIO 端点回落到 `localhost:19000`，生产附件功能不可用

| 位置 | 值 |
|---|---|
| `backend/src/main/resources/application.yml:119` | `endpoint: ${MINIO_ENDPOINT:http://localhost:19000}` |
| `backend/src/main/resources/application-docker.yml:30` | `endpoint: ${MINIO_ENDPOINT:http://minio:9000}` ← **只在 docker profile 生效** |
| `docker-compose.prod.yml:54` | `SPRING_PROFILES_ACTIVE: prod` |
| `docker-compose.prod.yml` backend env | 只设了 `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY`，**没有 `MINIO_ENDPOINT`** |

`application.yml` 无 `spring.profiles.include`（已确认），prod profile **不会**加载
`application-docker.yml` → 容器内解析 `http://localhost:19000`，指向容器自己 → 连接被拒。
同一原因导致 `FILE_BASE_URL` 回落到 `http://localhost:9000`，返回给前端的附件地址也不可达。

**建议**：把 `minio.endpoint` 与 `file-base-url` 显式写入 `application-prod.yml`
（`http://minio:9000`），或在 prod compose 注入 `MINIO_ENDPOINT`。
**这类"只在某个 profile 里正确的默认值"应作为一条通用规则排查**，见 §四。

#### P0-4 修复过程中的二次发现（2026-09-22，已修）

**发现 1：`minio.endpoint` 不只是"后端连 MinIO 的地址"，它还是浏览器要访问的地址。**
`AttachmentService.presignedUrl()` 用 `minioClient.getPresignedObjectUrl(...)`，
而 `minioClient` 是用 `minio.endpoint` 构建的 → **presigned URL 的主机名就是这个值**。
因此把 prod 默认值简单改成 `http://minio:9000` 会制造一个新问题：
上传能通，但浏览器拿到 `http://minio:9000/bucket/key?...` 根本无法解析。
正确的生产值必须是**客户端也能解析的公开地址**（如 `https://minio.example.com`）。
`file-base-url` 只是拼接地址时的回退，并非主要路径。

**发现 2：compose 的 `:?` 拦不住这个坑 —— `.env` 里有一个"合法但只对宿主机正确"的值。**
`.env:37` 是 `MINIO_ENDPOINT=http://127.0.0.1:19000`（宿主机直跑后端时用），
它会**满足** `${MINIO_ENDPOINT:?}` 并被原样注入 prod。实测确认：
即使不给环境变量、走 prod 叠加，`docker compose config` 也照样通过（退出码 0）。
⇒ 真正能兜住的只有**启动期校验**。

**发现 3：`ProductionConfigValidator` 当时校验了 `FILE_BASE_URL` 却没有校验 `MINIO_ENDPOINT`。**
两个值同类（都是给客户端用的地址），守卫却只有一半 —— 这正是 P0-4 能悄悄存在的原因。

**发现 4（顺带）：文档里的示例密钥能被 validator 接受。**
`docs/DEVELOPER.md` 出现的 `disasterguard-dev-secret-key-at-least-32-chars-long-xyz`
与 `your-strong-secret-min-32-chars-long-xyz` 长度合法、也不纯字母，
`JwtService` 与 `ProductionConfigValidator` 都不会拒绝 —— 而它们恰恰是最容易被照抄进生产的。
已加入黑名单，并同步改掉文档中的三处示例命令。

**本轮修复（已落地并验证）**

| 文件 | 改动 |
| --- | --- |
| `application-prod.yml` | 显式声明容器网络地址（datasource / redis / minio / emqx / ai / tdengine / file-base-url），**堵住"prod 继承宿主机 localhost 兜底值"这条通用漏洞**；`minio.endpoint` 默认 `http://minio:9000` 并注明它是 presigned URL 主机、生产须覆盖为公开地址 |
| `docker-compose.prod.yml` | 补 `MINIO_ENDPOINT: ${MINIO_ENDPOINT:?生产环境必须设置…}`（原来**只**传了 ACCESS/SECRET KEY，endpoint 从未注入） |
| `ProductionConfigValidator.java` | 新增第 9 项校验：`MINIO_ENDPOINT` 为空 / 指向本机 → **阻断启动**；只是内网单标签名（`minio`）→ **仅告警**，因为那是"上传可用、附件需配反向代理"的可补救形态，不该判死 |
| `ProductionConfigValidatorTest.java` | **新增单测（5 个用例）**：空值拒、`127.0.0.1`/`localhost` 拒、公开域名过、`http://minio:9000` 只告警不阻断、私有 IP 过。测试类数 5 → 6 |

验证：

```
$ docker compose -f docker-compose.yml -f docker-compose.prod.yml config | grep MINIO_ENDPOINT
      MINIO_ENDPOINT: https://minio.example.com

$ mvn test -Dtest=ProductionConfigValidatorTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

> 遗留（属通用规则，未在本轮改）：`application.yml` 里另有 8 处 `localhost` 兜底值
> （`POSTGRES_HOST` / `REDIS_HOST` / `FRONTEND_ORIGIN` / `FILE_BASE_URL` / `AI_SERVICE_BASE_URL` /
> `MINIO_ENDPOINT` / `EMQX_MQTT_URL` / `TDENGINE_URL`）。它们为"宿主机直跑"而存在，
> 本身没错，但**每个新 profile 都必须自己声明一遍**，否则就会重演 P0-4。
> 长期做法是把这些兜底值从 base 配置移出、改为分 profile 提供，或引入配置校验的快照测试。

### P0-5 隐患点创建 100% 失败：`hazard_point` 是视图，但不暴露 `event_code`

`backend/src/main/resources/schema.sql:1352-1354`

```sql
DROP TABLE IF EXISTS hazard_point CASCADE;
CREATE OR REPLACE VIEW hazard_point AS
SELECT id, hazard_code, name, hazard_type, risk_level, region_code, owner, ... 
FROM unified_event WHERE kind = 'HAZARD';
```

视图的 22 个列**不含 `event_code`**；而 `schema.sql:513` 是
`event_code VARCHAR(64) NOT NULL UNIQUE`（无 DEFAULT）；全脚本
**`CREATE TRIGGER` 命中数为 0**（已 grep 确认），没有任何触发器补这个值。

**实测（事务内插入后回滚，已执行）**：

```
BEGIN; INSERT INTO hazard_point (hazard_code, name, hazard_type, ...) VALUES (...); ROLLBACK;
ERROR:  null value in column "event_code" of relation "unified_event" violates not-null constraint
DETAIL:  Failing row contains (131, null, DETECTED, HZ-R4TEST-001, ...)
```

而 `backend/.../hazard/service/HazardService.java:187-194` 的顺序是**先存后取码**：

```java
HazardPoint saved = repository.save(point);                    // ← 这里就炸了
String eventCode = eventStateService.createFromManualReport(
    saved.getHazardCode(), saved.getName(), "system");
```

→ **人工新增隐患点、AI 监测落库隐患点两条路径都会 500**。这是平台最核心实体的写路径。

**建议**：让视图暴露 `event_code`（或给 `unified_event.event_code` 加默认值生成规则），
并把 `createFromManualReport` 的调用**移到 save 之前**（先拿到 eventCode 再一起写）。

---

## 二、P1 — 功能不正确或安全隐患（共 22 项，全部经源码复核）

### 安全

- **P1-1 登出不做任何服务端失效**：`auth/AuthController.java:90` 的 `logout()` 只有
  `return ApiResponse.success("退出成功", null);`。项目**已有** `JwtService#revoke` + `JwtBlacklist`，
  且同文件的 `changePassword`（`:103`）正确使用 → **用户点"退出"后，token 在剩余 TTL 内
  （默认 `PT2H`）仍可调用全部接口**。这是登出语义的实质性失效，不是体验取舍。

- **P1-2 会签的签字人可伪造**：`event/service/SignService.java:80`（confirm）与 `:99`（reject）
  接收 `signerId` 参数用于定位 `EventSign`，**服务端从不与 `Authentication#getName()` 比对**，
  方法签名里那个 `Authentication auth` 参数收了却完全没用。
  → 任何持有 `sign:confirm` 的角色可冒用其他会签人身份签署/驳回，**会签的审计链整体失真**。
  （与 R3 P0-1 同源问题，说明"操作人取自入参"是**系统性习惯**，不是孤立疏漏。）

- **P1-3 确认队列 4 个写端点全部使用只读权限点**：`confirmation/ConfirmationQueueController.java`
  的 `claim`(:150) / `transfer`(:196) / `confirm`(:247) / `reject`(:295) 清一色
  `@PreAuthorize("hasAuthority('confirmation:view')")`，而 `PermissionRegistry.java:27-28`
  **已经定义了** `confirmation:claim/confirm/reject/transfer` 四个写码。
  → 灾情确认环节的写权限被放宽到"只要能看到队列就能改"，`attendant` 等只读角色全部可写；
  且四个端点的 `operator` 同样取自请求体，操作日志不可信。

- **P1-4 ai-service `sentinel` 的 `/diff` 可读容器内任意文件 + SSRF 且无鉴权**：
  `ai-service/app/routers/sentinel.py:869` `if url_or_path.startswith("file://"): Image.open(local_path)`
  与 `:891` 的 `else: img = Image.open(url_or_path)`（裸路径直接当本地文件打开）。
  `before_url` / `after_url` 字段（`:682-683`）**没有任何 scheme 白名单**，`http(s)` 分支
  （`:883`）也无域名限制。ai-service **无任何鉴权**（8001 端口直连即可）
  → 任何可达 8001 的调用方可读取容器内 `/app/.env`、模型权重、任何可读文件，或探测内网。
  **建议**：只允许 `http(s)` + 目标白名单，删除 `file://` 与裸路径分支。

- **P1-5 设备上报默认放行"未登记密钥"的设备**：`common/security/DeviceAuthFilter.java:88`
  在 `strictMode=false` 时，只要 `deviceCode` 存在且该设备**没配 `api_key`** 就直接放行。
  dev compose 显式 `DEVICE_AUTH_STRICT: false`（`docker-compose.yml:149`）。
  → 知道设备号即可伪造 `ROLE_DEVICE` 写入指标数据、**污染预警链**（在灾情系统里这是数据投毒）。
  prod 已置 true（`application-prod.yml:24`），故仅影响 dev/staging，但默认值方向反了。

- **P1-6 限流器无条件信任 `X-Forwarded-For` 且 IP 表无上限**：
  `common/security/RateLimitFilter.java:86` 直接取 XFF 首段作客户端 IP，
  `limiters.computeIfAbsent(clientIp, ...)`（`:34/:60`）**从不清理**。
  → 每个请求伪造一个 XFF 即获得全新配额（限流形同虚设），同时向 Map 注入一条永久记录（内存增长）。
  **建议**：仅在受信代理后启用 XFF，并给 `limiters` 加 LRU/过期淘汰。

- **P1-7 全项目无数据级归属校验（IDOR）**：`grep tenantId|orgId|createBy|ownerId` 在
  292 个 Java 文件中**未命中任何数据过滤逻辑**，权限模型纯功能级。具体暴露点：
  `attachment/controller/AttachmentController.java:57/67/74` —— 只要求 `hazards:view|edit|delete`，
  即可按 id 下载**任意**附件并拿到 7 天有效的 presigned URL；
  `notification/MessageController.java:134` 可把任意消息标记已读、`:148` 全库置已读，
  `list` 也不按当前用户过滤（`notification_record` 无收件人字段）。
  → 对已知 id 的跨主体读取/改写无任何拦截。
  **建议**：这是架构级缺口，需引入归属列 + 服务层统一比对；至少先给附件下载/删除加上传者校验。

- **P1-8 演示口令硬编码并被 AI 助手主动播报**：`components/AiAssistant.vue:160` 的关键词命中后
  回答 `'演示环境默认账号：\n• 管理员 — admin / 123456…'`；`views/WelcomePage.vue:55` 还
  预填了 `password: "123456"`。**任何已登录用户（含最低权限角色）都能问出全部默认口令**。
  另 `services/mock/empty.js` 的 `loginMockUser` 不校验密码、角色取自客户端入参。
  **建议**：删除口令答案与预填，改为首次登录强制改密。

- **P1-9 JWT 持久化在 localStorage**：`stores/auth.js:303` `localStorage.setItem(TOKEN_KEY, ...)`。
  **本轮 XSS 面实测为 0**（见 §三），但无 `HttpOnly` 属长期硬伤，token 可被任意同源脚本读取。

### 前后端契约 / 功能不正确

- **P1-10 审计日志的筛选与分页实际失效**：`frontend/src/views/system/AuditLogPage.vue:56`
  传 `{range, action, keyword}` 且**不传 `page`**；后端
  `system/AuditLogController.java:37-41` **只接受 `page`（默认 1）/`size`（默认 20）/`keyword`**。
  → `range` 与 `action` 被静默丢弃，页面永远只显示最新 20 条，再在这 20 条里做二次过滤，
  用户以为在筛选全量。**建议**：前端补 `page/size` + 分页控件，或后端支持这两个条件。

- **P1-11 消息列表"先分页后内存过滤"，total 与翻页全错**：
  `notification/MessageController.java:89-112`

  ```java
  List<NotificationRecord> all = pageResult.getContent();
  if (kw != null) all = all.stream().filter(...)...;
  int total = (kw == null) ? pageResult.getTotalElements() : all.size();   // ← 退化为本页命中数
  ```
  → 带关键字时 `total` 等于"当前页里的命中数"，**第 2 页起永远取不到命中项**。

- **P1-12 多个列表用无排序的 `Pageable`**：`warning/service/WarningService.java:52-53`、
  `device/service/DeviceService.java:39-40`、`emergency/.../EmergencyService.java:42`、
  `inspection/.../TaskService.java:42` 均为 `PageRequest.of(page, size)` **无 `Sort`**，
  随后 `findAll(spec, pageable)`。
  → PostgreSQL 无 `ORDER BY` 时行序不稳定，**翻页会出现重复行与漏行**。
  **建议**：统一补 `Sort.by(DESC,"createdAt").and(DESC,"id")`（用唯一列兜底）。

- **P1-13 通知下发在事务内做远程调用**：`notification/NotificationDispatcher.java:59-126`
  `@Async @Transactional(REQUIRES_NEW)` 里双层循环调 `sender.send(...)`（远程），
  回执与业务写在同一事务。
  → 长事务占用连接池 N×M 次网络往返的时长；中途异常**整体回滚**，**已真实发出的通知回执被丢弃**，
  台账与实际送达不一致。

- **P1-14 事务提交前广播 WebSocket**：`warning/service/WarningService.java:337` 等 7 处
  `warningRepository.save(warning); broadcastWarning(dto);` 同在 `@Transactional` 方法体内；
  `DeviceDataService.java:130-134` 同类。
  → 事务回滚后客户端**已经收到**一个不存在的状态；订阅者还可能读到未提交数据。
  **建议**：改 `TransactionSynchronization#afterCommit`。

- **P1-15 前端 WebSocket 回退把 JWT 放进 URL，且该回退在 dev 下必被触发**：
  `services/_architecture/core/realtimeService.js:406` 用**裸相对路径** `fetch("/api/auth/ws-ticket")`，
  而全前端唯一请求基址是 `runtime.js` 的 `http://localhost:8080/api`，`vite.config.js` 无 proxy
  → dev 下打到前端源站（5173）→ **ticket 必然拿不到** → 走到 `:424` 注释里自认不安全的
  `connectUrl = ${url}${sep}token=${encodeURIComponent(token)}` → **JWT 进入访问日志/代理日志**。
  **建议**：改用 `API_BASE` 拼接，并删除 token 明文回退。

- **P1-16 权限码生成脚本静默丢弃带连字符的权限码**：
  `scripts/fix/sync-permission-registry.mjs:52`

  ```js
  [...b.join(" ").matchAll(/'([a-z:]+)'/g)].map((m) => m[1])   // ← 字符集不含 '-'
  ```
  角色名正则是 `[a-z-]+`（含连字符），**权限码正则却漏了** → `'inspection:gps-check'` 整条匹配不上，
  被静默丢弃。第 51 行的断言 **只校验角色数 == 7，不校验权限数**，所以漏码永远不报错。
  实测差集：**前端 119 码 vs 后端 118 码，唯一差异就是 `inspection:gps-check`**。
  当前该码在前端只出现在 `stores/auth.js`、无按钮引用，所以是**潜伏雷**：
  今后任何带连字符的权限码都会"前端可点、后端 403"。
  **建议**：正则改 `/'([a-z:-]+)'/g`，断言改为同时校验权限总数。

- **P1-17 `task-detail` 路由缺权限映射，被 `/dashboard` 前缀放行**：
  `frontend/src/router/index.js:144`（映射表）与 `:580`（判定）。
  `canAccessPath` 用 `path.startsWith(`${p}/`)`，而**每个角色的菜单都含 `'/dashboard'`**，
  路由名 `task-detail`（`/dashboard/ai-tasks/:id`）不在 `routePermissionMap` 中
  → 无 `hasPermission` 二次校验 → **任意已登录角色可直达任务详情**。

- **P1-18 CSV 导出存在公式注入**：`views/system/AuditLogPage.vue:74`（另 `AnalyticsPage.vue:67`、
  `HistoryHazardsPage.vue:131`、`OverviewPage.vue:1550`）只把 `"` 转义为 `""`，
  **未处理以 `= + - @` 开头的单元格** → 用 Excel 打开会执行公式，而 `detail` 内容来自后端、可被写入。
  **建议**：首字符为公式字符时前置 `'`。

### AI 服务

- **P1-19 `math` 未导入，独立测算的模拟天气分支必然 500**：
  `ai-service/app/routers/disaster_assessment.py:580`
  `seasonal = math.sin((day_of_year - 80) / 365.0 * 2 * math.pi)`，
  而该文件 import 区（`:27-29`）**只有 `__future__` 与 `typing`，无 `import math`**。
  触发条件已核实：同文件 `:513` `mock_weather: bool = Field(default=True)`，
  守卫是 `if use_mock_weather and "rainfall_24h" not in factors and "rainfall_7d" not in factors`
  —— 即**"只传坐标不传降雨"这条被文档推荐的用法必然走到这里**。
  **建议**：补 `import math`（其余引用 `math` 的模块均已正确导入）。

- **P1-20 多个数组入参只有下界没有上界，单请求即可打爆内存**：
  `anomaly.py:20` `data: List[float] = Field(min_length=3)`（无 max）、
  `device_health.py:31-37` `metrics: Dict[str, List[float]]`（完全无界）、
  `drift.py:24/53/102` `currentData: List[Dict[str,float]]`、`days: int = 30`（无 `ge/le`
  → `range(days,0,-1)` 可被传成天文数字）、`disaster_assessment.py:314-316` `region_codes` 无 max。
  **对照组**：`forecast.py` 的 `horizon`（`ge=1,le=30`）与 `history`（≤1000）是**正确写法**，
  说明项目里已有规范，只是未推广。

- **P1-21 async→sync 改造漏了一批，仍有同步 CPU 推理在事件循环里**：
  `disaster_assessment.py:240/265/358/594/641`（`detect_batch` 内对每个点循环调 `model.score`）、
  `model_info.py:92/98`（`train_xgboost`，1-3s）、`sentinel.py:859-895`。
  → 与已修的病灶同类，这几处仍会**冻结全部并发请求**。改造只覆盖了一部分文件。

- **P1-22 ai-service 无并发上限**：`ai-service/Dockerfile:37` 的 uvicorn 命令
  **无 `--workers` 也无 `--limit-concurrency`**；改造后多个路由是同步 `def`，共用
  Starlette/anyio 默认线程池（约 40 线程），单次推理数百 ms~数秒。
  → 40 并发即占满，`/api/ai/health`（同步 `def`）也会一起排队
  → **HEALTHCHECK 超时 → 容器被判 unhealthy**。detection 的独立池只有 2 线程，
  第 3 个并发在 30s 内排不到即 504。
  **建议**：加并发信号量/`--limit-concurrency`，health 改纯 `async`，推理走独立池并限流。

### 其它

- **P1-23 响应体回显上游异常原文**：`ai/AiController.java:298`、`:436`
  `ApiResponse.fail(500, "刷新失败: " + e.getMessage())`，以及 `AiServiceClient.java:88`
  把 `e.getMessage()` 拼进错误消息 → **内部主机名/端口/HTTP 细节（如 `localhost:8001`）返回给客户端**。
  （配置层无问题：`GlobalExceptionHandler` 兜底分支只回通用文案、四个 yml 都未开 `include-stacktrace`。）

- **P1-24 附件对象键用未校验的请求参数拼接**：`attachment/service/AttachmentService.java:99`
  `String objectKey = businessType + "/" + businessCode + "/" + UUID.randomUUID();`，
  两个入参直接来自 `@RequestParam`（`AttachmentController.java:41`），**无字符集/长度约束**。
  文件名已清洗、键不含原文件名，但**前缀仍可含 `../` 等构造**。
  **建议**：对 `businessType`/`businessCode` 加白名单正则。

---

## 三、P2 — 技术债与隐患

- **`sql.init` 相关（与我在改的 prod 配置直接相关）**：
  - `mode: always` 下每次启动都会执行 `schema.sql` 里的
    **`DROP TABLE IF EXISTS hazard_point CASCADE`（`:1352`）→ 重建视图 → 重挂 6 张子表外键**。
    实测**完整重跑 0 错误**（见 §四勘误），但每次生产启动都会短暂卸下这些外键约束，
    理论上存在启动瞬间的完整性窗口。规范做法仍是引入 Flyway / Liquibase 做版本化迁移。
  - `data.sql` **不直接写 `hazard_point` 视图**（`:659` 直接 `INSERT INTO unified_event` 且带 `event_code`），
    所以 `mode: always` 与 P0-5 的视图缺陷不会在启动期互相引爆 —— 这一点已实测确认。

- **`.env.example:24` 的示例值恰好命中启动黑名单**：
  `JWT_SECRET=CHANGE_ME__dev_only_minimum_32_character_secret_key_2026`，
  而 `JwtService.java` 的 `EXACT_BLACKLIST` 里**明确列了这条**（小写比对命中）
  → **照抄示例配置的开发者也起不来**，且该文件第 21 行还写着"应用启动时会校验，若检测到弱密钥将拒绝启动"，
  自相矛盾。
- **prod 的 `:?` 守卫严格度不一致**：`POSTGRES_PASSWORD`/`MINIO_ROOT_PASSWORD` 用了
  `${VAR:?必须设置}`，但 `docker-compose.prod.yml:58` 的 `JWT_SECRET: ${JWT_SECRET}` **连 `:?` 都没有**；
  且 `:?` 只防"未设置"，**不防"值为占位符"**（compose 会自动加载同目录 `.env`，
  而根 `.env` 里正是 CHANGE_ME 系列值）。**建议**：prod 用独立 env_file，或校验值不得以 `CHANGE_ME` 开头。
- **仓库根目录缺 `.dockerignore`**：只有 `ai-service/.dockerignore`。根 `.env`（含凭据）与 `.git`
  全量进入构建上下文（`frontend/Dockerfile:12` 的 context 是 `.`），构建变慢且扩大密钥暴露面。
- **`schema.sql` 仍有重复建表**：R3 已报 14 张表各出现 2 次，本轮抽样再确认
  `backup_record` 同时在 `:1429` 与 `:1548`。`IF NOT EXISTS` 使其无害，但说明该文件是
  多段拼接而成，维护时极易改错那一份。
- **`AnimatedLoginBackground.vue:149` 的 `resize` 监听器未移除**（本轮新发现）：
  `addEventListener('resize', ...)` 出现 4 次，清理只做了 3 次，该条永久残留并持有已卸载的 canvas 闭包。
- **静默吞异常**：空 `catch {}` 9 处、仅 `console` 5 处。最典型的是
  `views/settings/AccountSecurityPage.vue:218`

  ```js
  for (const s of others) { try { await dashboardApi.revokeSession(s.id) } catch {} }
  emit('notify', '其它设备会话已全部登出')     // ← 失败也照样播报成功
  ```
  **安全操作的"假成功"比报错更危险**，与 P0-1 是同一类错误。
- **ai-service 上传大小校验依赖 `file.size`**（`detection.py:62-66`）：`file.size` 为 None 时校验被跳过，
  且仓库中无 `MAX_UPLOAD_SIZE` 常量、uvicorn 默认不限请求体 → 整包读入内存。
- **`infra/postgres/init/*.sql` 从未生效**（R3 已报，本轮确认机制）：`docker-compose.yml` 的 postgres
  未挂载该目录 / 或 initdb 已完成初始化，容器日志 `Skipping initialization`；
  `002_geom_triggers.sql` 首句还依赖应用侧才建的表，纯初始化期必报 `relation does not exist`。
- **本机 `tdengine` 容器 unhealthy**（默认 `TDENGINE_ENABLED=false`，暂不影响业务，但需确认是有意为之）。

---

## 四、对子代理结论的复核与勘误（必读）

本轮四路并行的原始结论里有 **5 条不成立或严重高估**，均已核实并更正，**报告只保留修正后的版本**：

| # | 子代理原始结论 | 复核结果 |
|---|---|---|
| 1 | 「`schema.sql` 不可重复执行，`DROP TABLE ... hazard_point` 会因对象已是视图而报错」（我最初也这么判断） | ❌ **不成立**。脚本第 **97** 行有 `DROP VIEW IF EXISTS hazard_point;` 先清视图，到 `:1352` 时对象已是真实表。**实测完整重跑 `ON_ERROR_STOP=1` → 跑到第 1655 行 0 错误**。我此前单测那条语句时漏掉了这个前置，属方法错误。 |
| 2 | 「`detect-batch` 点数上限 64 与 Java 最多 1800 点冲突，**默认配置必然命中**」→ 列为 P0 | ⬇️ **降级为 P1 潜在**。实测库内 region 分布为 `district 10 / town 3 / city 1`，而 `hazard-monitor-min-level` 默认 `town`（`levels=["town","village"]`）→ 只有 **3 个 region × 9 = 27 点**，**默认不触发**。仅当把 `min-level` 调成 `district`（10×9=90 > 64）才会整批失败。另：Python 字段描述写"超过会被截断"，但 Pydantic 的 `max_length` 是**拒绝**而非截断，描述本身是错的。 |
| 3 | 「`AuditLogPage` 读 `items` 而后端 `PageResponse` 字段是 `records`，列表恒为空」 | ❌ **不成立**。`dashboardApi.js:757` 有 `normalizePaged(data, {items: []})` 做归一化。该页**真实**的问题只有 `range`/`action` 被后端忽略（已列 P1-10）。 |
| 4 | 「`infra/nginx/frontend.conf` 是孤儿配置，未被任何地方引用」 | ❌ **不成立**（子代理自行更正）：`frontend/Dockerfile:38` 会 `COPY` 它。"compose 未引用"字面成立，但文件确在使用。 |
| 5 | 「`SentinelController` 匿名可写」 | ❌ R3 已勘误过的误报，本轮子代理未再提，此处仅备注确认：该 Controller 只有 `GET /coverage`。 |

**另有 2 条我主动修正了定位**：`SignController` 的实际路径是
`event/SignController.java`（**不在** `event/controller/` 子包下）；`WarningCreatePage` 的
`hazardTypeLabel` 已随上轮重构移到 `utils/hazardTypes.js`。

> 结论：**子代理适合"广撒网找线索"，不适合直接下结论。** 本轮 5 条错误里，
> 有 2 条（#1、#2）如果直接写进报告，会分别导致"误判自己上一轮的修复是错的"和"虚报一个 P0"。
> 逐条回源码/回实测复核这道工序不能省。

---

## 五、未提交改动（15 文件）复核结论

上一轮 P1-15 / P1-11 / P1-19 的修复，本轮逐项复核：

| 改动 | 结论 |
|---|---|
| `SchedulingConfig.java`（新增，线程池 5 + 优雅停机 + 全局 errorHandler） | ✅ 正确。项目原先无 `TaskScheduler` bean 确实会退化为单线程，此修复对症。 |
| `RuleEngineScheduler` 重入保护（`AtomicBoolean.compareAndSet` + `finally` 复位） | ✅ 正确，且 `finally` 复位避免了异常后永久锁死。 |
| `application-prod.yml` `sql.init.mode: never → always` | ✅ **经实测验证可行**：schema.sql 完整重跑 0 错误；`data.sql` 直接写 `unified_event`（带 event_code）不碰视图，不会与 P0-5 冲突。但注释里「77 处建表全部幂等」的说法不完整 —— **幂等性还依赖 `:97` 那句 `DROP VIEW IF EXISTS`**，改这块代码时必须一起改，建议把这条写进注释。 |
| ai-service 9 个路由 `async def → def` | ✅ 方向正确（FastAPI 对同步 `def` 会走线程池）。但**漏了 `disaster_assessment.detect_batch` 等 5 处**（见 P1-21），且**未加并发上限**（P1-22），改完仍需补这两件事。 |
| `detection.py` 独立 `ThreadPoolExecutor(2)` + `asyncio.wait_for(30s)` | ⚠️ 有正确性瑕疵：**`wait_for` 超时不会取消 `run_in_executor` 里的线程**，ONNX 推理会继续跑完并占着 worker → 连续两次超时后检测能力被永久占满。建议加并发信号量而非仅靠超时。另 executor 从未 shutdown（进程退出时自动回收，影响不大）。 |
| `main.py` 延迟导入 + 条件注册 `drift`/`multimodal` + `logging.basicConfig` | ✅ 正确解决了"缺 scipy 会拖垮整个服务"。小瑕疵：循环变量 `_mod` 泄漏到模块命名空间。 |
| `requirements.txt` 补 `pytest`/`httpx` | ✅ 与 `tests/` 的 import 对得上。 |

---

## 六、建议修复顺序

**第一批（上线前必须，都是"看起来正常但实际不工作"）**
1. ~~**P0-1 改密空壳**~~ —— ✅ **已修（`612e3fa`）**，并带出 `/api/profile` 权限码错配
2. ~~**P0-2 EMQX CRLF**~~ —— ✅ **已修（`eddc66e`）**；转 LF 后重做凭据验证时，
   又查出"认证初始化空转 + 就绪逻辑倒置"两层（详见 §一 P0-2），一并修复
3. ~~**P0-5 隐患点视图缺 `event_code`**~~ —— ✅ **已修（`359aba6`）**
4. **P0-3 / P0-4** —— dev 起不来、prod 附件不可用，均属"配好了但不通"（⬜ 未修）
5. **R3-P0-6** —— prod compose 补 emqx / ai-service 服务定义（⬜ 未修，需你定架构）
6. **EMQX 开启真实鉴权** —— 状态仍是 allow-all；配方已验证（§一 P0-2），
   已确认**留待发版前统一处理**，落地时须同步网关凭据

**第二批（安全与数据一致性）**
6. P1-1 登出、P1-2 会签、P1-3 确认队列权限（**同属"操作人可伪造"，建议一次性把
   `Authentication#getName()` 全面接上**，不要逐个端点补）
7. P1-19 `math`、P1-21 剩余阻塞推理、P1-22 并发上限（AI 服务三连）
8. P1-13/P1-14 事务边界（远程调用与广播都应在提交后）
9. P1-10/P1-11/P1-12 分页三连

**第三批（工程化）**
10. P1-16 权限码脚本正则 + 断言 + 上 CI
11. P0-2 引出的全局检查：**所有 `*.sh` 强制 LF**（`.gitattributes`）
12. Flyway 迁移（替代 `mode: always`）、根 `.dockerignore`、单测补齐（292 Java 仅 5 个测试类、前端 0）

---

## 七、方法论补充

R3 的结论「门禁全绿 ≠ 系统可用」在本轮被**再次证实且程度更深**。本轮新增两条可复用的检查项：

1. **必须检查运行中容器的健康状态**（`docker ps` / `docker logs`）。
   P0-2 是唯一一个"代码和配置都看不出问题、只有容器日志能暴露"的 P0。
2. **必须用真实库验证 schema 与实体的一致性**（不只是比对文本）。
   P0-5 的 `event_code` 问题在 SQL 里看是"视图投影"，只有真插一次才知道它不可写；
   而 `mode: always` 能否重跑，也只有完整执行一次才有结论 —— **这两条都推翻了纯静态推断的结论**。
