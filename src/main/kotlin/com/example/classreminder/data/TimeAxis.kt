package com.example.classreminder.data

/**
 * 课表「表格视图」的时间轴。
 *
 * 横轴是星期几（列），纵轴是时间：**从本周最早的一门课开始，每 [hoursPerRow] 小时一格**。
 * 课程按真实时长占据纵向空间，所以能跨越好几格；同一格里互相重叠的课左右分栏并排，
 * 免得叠在一起互相遮住。
 *
 * 位置一律用「占整列/整轴的比例」表达，跟像素无关，方便单测。
 */
object TimeAxis {

    /** 本周的时间范围（当天分钟数，左闭右开） */
    data class Span(val startMinute: Int, val endMinute: Int) {
        val minutes: Int get() = (endMinute - startMinute).coerceAtLeast(1)
    }

    /** 一节课排好之后的位置 */
    data class Placed(
        val cls: ClassEntity,
        val topFraction: Float,
        val heightFraction: Float,
        val leftFraction: Float,
        val widthFraction: Float
    )

    /** 每格允许的小时数，从最小的往上试 */
    private val HOUR_STEPS = listOf(1, 2, 3, 4, 6, 8, 12)

    /** 展开时把该时段在纵轴上放大的倍数 */
    const val FOCUS_SCALE = 2.2f

    /** 放大时段最多占整轴的比例，免得把其它时段压得看不见 */
    const val MAX_FOCUS_SHARE = 0.62f

    /**
     * 要在纵轴上放大的时段。**左闭右开**：`[startMinute, endMinute)`。
     *
     * 特意不用 IntRange——`9*60 until 10*60` 拿到的 `last` 是 599 而不是 600，
     * 当成右端点用会少算一分钟，非常容易踩。
     */
    data class Focus(val startMinute: Int, val endMinute: Int)

    /**
     * 时间轴的比例映射。
     *
     * 没有 focus 时是**线性**的：每分钟占 `1 / span.minutes`。
     * 有 focus 时改成**分段线性**：focus 那段放大 [FOCUS_SCALE] 倍，其余分钟等比例压缩，
     * 总高度不变。这样被展开的课明显变大，代价只是其它时段略微变矮——
     * 比"靠挤别的块来腾地方"更直观，也不会把别的课压到看不清。
     */
    class Mapping internal constructor(
        val span: Span,
        private val focus: Focus?,
        private val focusShare: Float,
        private val otherShare: Float
    ) {
        private val focusMinutes: Int = focus?.let { it.endMinute - it.startMinute } ?: 0
        private val otherMinutes: Int = (span.minutes - focusMinutes).coerceAtLeast(0)
        private val focusPerMinute: Float = if (focusMinutes > 0) focusShare / focusMinutes else 0f
        private val otherPerMinute: Float = if (otherMinutes > 0) otherShare / otherMinutes else 0f

        /** 某个时间点在轴上的比例（0..1） */
        fun fractionOf(minute: Int): Float {
            val f = focus ?: return linear(minute)
            val head = (f.startMinute - span.startMinute) * otherPerMinute
            return when {
                minute <= f.startMinute -> linearHead(minute)
                minute <= f.endMinute -> head + (minute - f.startMinute) * focusPerMinute
                else -> head + focusShare + (minute - f.endMinute) * otherPerMinute
            }.coerceIn(0f, 1f)
        }

        private fun linear(minute: Int): Float =
            ((minute - span.startMinute).toFloat() / span.minutes).coerceIn(0f, 1f)

        private fun linearHead(minute: Int): Float =
            ((minute - span.startMinute) * otherPerMinute).coerceIn(0f, 1f)
    }

    /** 本周所有课的 [最早开始, 最晚结束]；没有一门时间可解析的课就返回 null */
    fun spanOf(classes: List<ClassEntity>): Span? {
        var from = Int.MAX_VALUE
        var to = Int.MIN_VALUE
        classes.forEach { cls ->
            val start = minutesOf(cls.startTime) ?: return@forEach
            val end = endMinutesOf(cls) ?: return@forEach
            if (start < from) from = start
            if (end > to) to = end
        }
        return if (from == Int.MAX_VALUE) null else Span(from, to)
    }

    /**
     * 造一个映射。[focus] 是本次要放大的时段。为空、退化、或超出本周范围时一律退化成线性映射。
     *
     * [progress] 是「放大到什么程度」：0 = 完全线性（等于没选中），1 = 放大到位。
     * 之所以做成连续参数而不是布尔开关——`share` 在 progress = 0 时**恰好等于该时段的原始占比**，
     * 此时分段映射会精确退化成线性（focusPerMinute / otherPerMinute 都回到 1/span.minutes）。
     * 所以可以用动画把 progress 从 0 推到 1，让「选中放大」是平滑长大的，而不是跳变。
     */
    fun mappingOf(span: Span, focus: Focus?, progress: Float = 1f): Mapping {
        val valid = focus?.takeIf {
            it.endMinute > it.startMinute &&
                it.startMinute >= span.startMinute &&
                it.endMinute <= span.endMinute
        } ?: return Mapping(span, null, 1f, 1f)
        val base = (valid.endMinute - valid.startMinute).toFloat() / span.minutes
        // 先用上限夹住，免得其余时段被压得看不见；但**不能低于原始占比**，否则反而成了缩小。
        // 注意顺序：不能写成 coerceIn(base, MAX)——当 base 本身超过 MAX 时 min > max 会抛异常。
        val full = (base * FOCUS_SCALE).coerceAtMost(MAX_FOCUS_SHARE).coerceAtLeast(base)
        val share = base + (full - base) * progress.coerceIn(0f, 1f)
        return Mapping(span, valid, share, 1f - share)
    }

    /**
     * 每格几小时：行数不超过 [maxRows]，从最小步长往上试。
     * 跨度本来就小的时候直接用 1 小时，不会硬凑成大格。
     */
    fun hoursPerRow(span: Span, maxRows: Int): Int {
        val limit = maxRows.coerceAtLeast(1)
        return HOUR_STEPS.firstOrNull { ceilDiv(span.minutes, it * 60) <= limit } ?: HOUR_STEPS.last()
    }

    fun rowCount(span: Span, hoursPerRow: Int): Int =
        ceilDiv(span.minutes, hoursPerRow * 60).coerceAtLeast(1)

    /** 分钟数 → "HH:mm" */
    fun labelOf(minute: Int): String {
        val day = 24 * 60
        val normalized = ((minute % day) + day) % day
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", normalized / 60, normalized % 60)
    }

    /**
     * 「斑马纹」时间带：每隔一小时交替的横向色带，返回**要铺底**的那些时段。
     *
     * 网格是按时间轴定位的，没有网页表格那种行；但时间本身就是行，一小时一行。
     * 按奇偶小时交替铺一层极淡的底，眼睛扫行时有了参照物，不用每次回到左边读刻度。
     *
     * **相位锚定在 span 起点所在的那一小时**，而不是绝对偶数小时。原因：span 起点由本周
     * 最早的课决定，常落在奇数小时（比如 07:43 → 第 7 小时）。若按绝对偶数小时取，
     * 短跨度（如 09:10–09:40）可能整段都落进「留白」，网格一条带都没有、底纹参照全丢。
     * 锚定起点后，**第一小时永远有带**，斑马纹在任何跨度下都成立。
     *
     * 返回的每段都是 `[start, end)`，落在 [span] 内，升序，且相邻两段至少隔一小时。
     * [minuteOfDay] 是时段基准（默认 0 点），保留给「跨天」场景。
     */
    fun zebraBands(span: Span, minuteOfDay: Int = 0): List<Focus> {
        // 起点所在的那一小时，作为第一条带；之后每隔一小时一条
        val hourOfStart = ((span.startMinute - minuteOfDay).coerceAtLeast(0)) / 60
        val bands = mutableListOf<Focus>()
        // 上限兜底：span 最多 24 小时，最多 12 条带，循环不会失控
        while (bands.size < 24) {
            val bandStart = minuteOfDay + (hourOfStart + bands.size * 2) * 60
            if (bandStart >= span.endMinute) break
            val bandEnd = bandStart + 60
            // 与 span 求交，只保留真正可见的部分
            val from = maxOf(bandStart, span.startMinute)
            val to = minOf(bandEnd, span.endMinute)
            if (to > from) bands += Focus(from, to)
        }
        return bands
    }

    /**
     * 没有任何一列被高亮时，纵轴该标哪些时刻。
     *
     * 返回 [span] 范围内、对齐到 [stepMinutes] 整数倍的「整点」时刻。之所以要单独抽出来并
     * **对齐到整点**，是因为之前的做法是「span 起点 + n × 步长」——span 起点由本周最早的课决定，
     * 通常是 07:43 这种时间，于是纵轴标出来就是 07:43、09:13…，既不是整点也没法当钟表读。
     *
     * 结果按升序，且落在 `[span.startMinute, span.endMinute]` 闭区间内。
     * 若该范围内一个整点都没有（跨度极短），退回只标起点一个，保证轴不会空着。
     */
    fun roundMarks(span: Span, stepMinutes: Int): List<Int> {
        if (stepMinutes <= 0) return listOf(span.startMinute)
        // 不早于 span 起点的第一个 step 的整数倍
        val first = ceilDiv(span.startMinute, stepMinutes) * stepMinutes
        val marks = generateSequence(first) { it + stepMinutes }
            .takeWhile { it <= span.endMinute }
            .toList()
        return marks.ifEmpty { listOf(span.startMinute) }
    }

    /** 线性映射下某个时间点的比例（0..1）。分段映射请用 [Mapping.fractionOf] */
    fun fractionOf(minute: Int, span: Span): Float =
        ((minute - span.startMinute).toFloat() / span.minutes).coerceIn(0f, 1f)

    /**
     * 把某一天的课排好位置。
     *
     * 纵向按 [mapping] 给出的比例，所以能跨越好几格；互相重叠（直接或间接）的课归为一组，
     * 组内左右等分并排。时间解析不出来的课直接跳过——表格里画不出它的位置。
     */
    fun layout(dayClasses: List<ClassEntity>, mapping: Mapping): List<Placed> {
        val timed = dayClasses.mapNotNull { cls ->
            val start = minutesOf(cls.startTime) ?: return@mapNotNull null
            val end = endMinutesOf(cls) ?: return@mapNotNull null
            Triple(cls, start, end)
        }.sortedWith(compareBy({ it.second }, { it.third }))
        if (timed.isEmpty()) return emptyList()

        val result = mutableListOf<Placed>()
        var groupStart = 0
        // 当前组里最晚的结束分钟；下一个的开始早于它，就说明和组内有交集
        var runningEnd = timed[0].third
        for (i in 1..timed.size) {
            val next = timed.getOrNull(i)
            if (next == null || next.second >= runningEnd) {
                result += placeGroup(timed.subList(groupStart, i), mapping)
                if (next != null) {
                    groupStart = i
                    runningEnd = next.third
                }
            } else if (next.third > runningEnd) {
                runningEnd = next.third
            }
        }
        return result
    }

    private fun placeGroup(group: List<Triple<ClassEntity, Int, Int>>, mapping: Mapping): List<Placed> {
        val share = 1f / group.size
        return group.mapIndexed { index, (cls, start, end) ->
            val top = mapping.fractionOf(start)
            Placed(
                cls = cls,
                topFraction = top,
                heightFraction = mapping.fractionOf(end) - top,
                leftFraction = index * share,
                widthFraction = share
            )
        }
    }

    /** "HH:mm" → 当天分钟数；解析不出来返回 null */
    fun minutesOf(hhmm: String): Int? {
        val parts = hhmm.split(":")
        val hour = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
    }

    /** 结束分钟；结束不晚于开始的（跨零点、脏数据）按开始 + 1 分钟算，免得画成 0 高度 */
    private fun endMinutesOf(cls: ClassEntity): Int? {
        val start = minutesOf(cls.startTime) ?: return null
        val end = minutesOf(cls.endTime) ?: return null
        return if (end > start) end else start + 1
    }

    private fun ceilDiv(a: Int, b: Int): Int = if (a <= 0) 0 else (a + b - 1) / b
}
