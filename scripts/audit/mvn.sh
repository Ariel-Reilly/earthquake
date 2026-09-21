#!/usr/bin/env bash
# 后端 Maven 包装脚本（离线模式）。
#
# 背景：本机 mvn 启动器不可用——环境变量只有 MAVEN_HOME，没有 M2_HOME，
#       mvn.cmd 依赖 M2_HOME 拼 classworlds 路径，于是报
#       "找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher"。
#       直接调用 classworlds Launcher 可绕过该问题。
#
# 用法（必须在 backend/ 目录下执行，不要带 -f 参数，
#       否则 JVM 参数会被当成 pom 路径解析而启动失败）：
#   cd backend && ../scripts/audit/mvn.sh compile
#   cd backend && ../scripts/audit/mvn.sh -q test
#
# 注意：Maven 增量编译只重编译变更文件，BUILD SUCCESS 不代表整个模块可编译。
#       做全量校验前先：find src/main/java -name "*.java" -exec touch {} +

set -euo pipefail

MAVEN_HOME="${MAVEN_HOME:-E:/Apache/apache-maven-3.9.16}"
LAUNCHER="org.codehaus.plexus.classworlds.launcher.Launcher"
CW_JAR="$(ls "$MAVEN_HOME"/boot/plexus-classworlds-*.jar | head -1)"

exec java \
  -classpath "$CW_JAR" \
  "-Dclassworlds.conf=$MAVEN_HOME/bin/m2.conf" \
  "-Dmaven.home=$MAVEN_HOME" \
  "-Dmaven.multiModuleProjectDirectory=$(pwd)" \
  "$LAUNCHER" \
  -o \
  "$@"
