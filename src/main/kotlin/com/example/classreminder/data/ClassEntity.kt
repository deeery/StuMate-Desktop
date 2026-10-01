package com.example.classreminder.data

/**
 * 一节课。
 *
 * 桌面端去掉了 Room 注解（改用 sqlite-jdbc + 手写 DAO），
 * 字段与顺序**与安卓端 Room v7 的 `classes` 表逐列一致**，保证两端数据可以互换。
 */
data class ClassEntity(
    val id: Int,
    val title: String,
    val dayOfWeek: String,
    // Separate start and end time in HH:mm format
    val startTime: String,
    val endTime: String,
    // Classroom / room number
    val room: String = "",
    val notes: String = "",
    /** 任课教师 */
    val teacher: String = "",
    /** 上课周次，如 "1-16周"、"第6周"、"1-8周,10-12周"；空表示不限 */
    val weeks: String = "",
    /** 临时提醒的具体日期 yyyy-MM-dd；空 = 每周重复的长期课程 */
    val date: String = "",

    // ── 同步元数据（v8 新增，安卓端同名列、同顺序） ──────────────────
    /** 跨设备唯一标识（UUIDv4）。本地新建时由 DAO 生成，一旦落库就保持不变 */
    val uid: String = "",
    /** 本地最后修改时刻（epoch ms）。0 = 老数据、尚未写入过 */
    val updatedAt: Long = 0L,
    /** 软删除标记（epoch ms）。0 = 未删除；非 0 = 已删除，保留以供同步传播 */
    val deletedAt: Long = 0L
)
