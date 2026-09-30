@echo off
rem ---------------------------------------------------------------
rem StuMate-Desktop 的 gradlew 转发脚本
rem
rem 本机无法访问 services.gradle.org 的重定向目标（GitHub release
rem assets 返回 502），因此不使用官方 gradle-wrapper.jar，而是直接
rem 转发到本机已缓存的 Gradle 8.4 发行版。
rem
rem 优先顺序：
rem   1. %USERPROFILE%\.gradle\wrapper\dists\gradle-8.4-bin\gradle-8.4\bin\gradle.bat
rem   2. 环境变量 GRADLE_HOME 下的 bin\gradle.bat
rem   3. PATH 上的 gradle
rem ---------------------------------------------------------------

setlocal

set "CACHED=%USERPROFILE%\.gradle\wrapper\dists\gradle-8.4-bin\gradle-8.4\bin\gradle.bat"

if exist "%CACHED%" (
    call "%CACHED%" %*
    exit /b %ERRORLEVEL%
)

if defined GRADLE_HOME (
    if exist "%GRADLE_HOME%\bin\gradle.bat" (
        call "%GRADLE_HOME%\bin\gradle.bat" %*
        exit /b %ERRORLEVEL%
    )
)

where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
    call gradle %*
    exit /b %ERRORLEVEL%
)

echo [gradlew] 找不到可用的 Gradle 8.4，请设置 GRADLE_HOME 或把 gradle 加入 PATH。 1>&2
exit /b 1
