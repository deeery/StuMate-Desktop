package com.example.classreminder.data

import java.util.UUID

/**
 * 生成同步用的全局唯一标识（UUIDv4）。
 *
 * 为什么需要它：两端的本地主键都是应用自己分配的 `id: Int`（没有 autoGenerate），
 * 各自新建记录必然撞号，不能拿来跨设备对齐。同步层只认 [uid]，
 * 本地 DAO / UI 继续用 `id`，所以现有查询逻辑一行都不用改（设计方案 v1.3 §5.2）。
 *
 * 只在**落库那一刻**生成：DAO 写入时若 `uid` 为空就补一个，之后原样保留。
 * 绝不能每次启动重新生成——那样同一台设备每次开机都会换一批 uid，
 * 同步层会把它们当成一批新记录，历史数据就重复了。
 */
internal fun newUid(): String = UUID.randomUUID().toString()
