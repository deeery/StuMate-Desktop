package com.example.classreminder.data

/**
 * 「今天」页便签摘要的挑选规则。
 *
 * 抽成纯函数（不依赖 Android）是为了能单测 —— 这里有两条容易踩边界的规则：
 * **排序**（Deadline 优先，但同组内要保持用户的拖拽顺序）和
 * **截断**（超过上限时最后一条要换成省略栏，而不是被默默砍掉）。
 *
 * ## 规则
 *
 * 1. 最多显示 [TODAY_NOTE_LIMIT] 条
 * 2. **Deadline 类优先** —— 它们有时效，漏看了代价最大
 * 3. 当便签总数超过上限时，最后一条**不显示便签**，改用一条灰色的「…」省略栏占位，
 *    表示「下面还有」
 *
 * ## 为什么用「占位」而不是「显示满 5 条」
 *
 * 上限 5 条时，如果库里正好有 5 条，用户看不出「到底还有没有」。留一条省略栏，
 * 「还有多少」这件事就不再靠数数，而是靠有没有那个省略栏来判断 —— 一眼可辨。
 * 代价是超量时只能看到 4 条正文，但摘要区本来就不是用来看全的（旁边有「全部」入口）。
 *
 * ## 为什么 Deadline 优先排序后仍要稳定
 *
 * 用户是手动拖拽排过序的（[NoteEntity.position]），那份顺序是用户的意图。
 * Deadline 优先只是「把它们提到前面」，**组内不能乱**，否则每次进今天页
 * 顺序都可能不一样 —— 所以这里用 `sortedBy`（Kotlin 的 sortedBy 是稳定排序），
 * 保证同组内维持传入顺序。
 */
object TodayNotePicker {

    /** 今天页最多显示几条（含省略栏占位） */
    const val TODAY_NOTE_LIMIT = 5

    /**
     * 挑出今天页要展示的便签。
     *
     * @param notes 全部便签，**顺序即用户排定的顺序**（DAO 已按 position 排好）
     * @return 要显示的前若干条；调用方还需要用 [needsMoreRow] 判断末尾是否补省略栏
     */
    fun pick(notes: List<NoteEntity>): List<NoteEntity> {
        if (notes.isEmpty()) return emptyList()
        val ordered = notes.sortedBy { if (it.hasDeadline) 0 else 1 }
        // 超量时留一格给省略栏，所以正文只有 LIMIT - 1 条
        val bodySlots = if (ordered.size > TODAY_NOTE_LIMIT) TODAY_NOTE_LIMIT - 1 else ordered.size
        return ordered.take(bodySlots)
    }

    /**
     * 末尾要不要补一条省略栏。
     *
     * 条件是「总数超过上限」—— 注意不是「挑出来的是否少于总数」：
     * 上限是 5 时，库里正好 5 条不该出现省略栏（全都在下面列着了，没有更多）。
     */
    fun needsMoreRow(notes: List<NoteEntity>): Boolean = notes.size > TODAY_NOTE_LIMIT
}
