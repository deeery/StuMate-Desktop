#!/usr/bin/env sh
# ---------------------------------------------------------------
# StuMate-Desktop 的 gradlew 转发脚本（Git Bash / WSL）
#
# 本机无法访问 services.gradle.org 的重定向目标（GitHub release
# assets 返回 502），因此不使用官方 gradle-wrapper.jar，而是直接
# 转发到本机已缓存的 Gradle 8.4 发行版。
# ---------------------------------------------------------------

for CANDIDATE in \
    "$HOME/.gradle/wrapper/dists/gradle-8.4-bin/gradle-8.4/bin/gradle" \
    "$USERPROFILE/.gradle/wrapper/dists/gradle-8.4-bin/gradle-8.4/bin/gradle"
do
    if [ -f "$CANDIDATE" ]; then
        exec sh "$CANDIDATE" "$@"
    fi
done

if [ -n "$GRADLE_HOME" ] && [ -f "$GRADLE_HOME/bin/gradle" ]; then
    exec sh "$GRADLE_HOME/bin/gradle" "$@"
fi

if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
fi

echo "[gradlew] 找不到可用的 Gradle 8.4，请设置 GRADLE_HOME 或把 gradle 加入 PATH。" >&2
exit 1
