package com.example.classreminder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格视图的时间轴：时间范围、每格几小时、比例映射、以及课程块的纵向/横向定位。
 * 这些比例直接决定真机上画出来的样子，但没法在单测里截图，所以在这里把边界盯死。
 */
class TimeAxisTest {

    private fun cls(start: String, end: String, day: String = "Monday", title: String = "课") =
        ClassEntity(
            id = title.hashCode(),
            title = title,
            dayOfWeek = day,
            startTime = start,
            endTime = end
        )

    private fun linear(span: TimeAxis.Span) = TimeAxis.mappingOf(span, null)

    @Test
    fun spanCoversEarliestStartToLatestEnd() {
        val span = TimeAxis.spanOf(
            listOf(cls("09:50", "12:15"), cls("08:00", "09:35"), cls("15:10", "16:45"))
        )!!

        assertEquals(8 * 60, span.startMinute)
        assertEquals(16 * 60 + 45, span.endMinute)
    }

    @Test
    fun spanIgnoresUnparsableTimes() {
        val span = TimeAxis.spanOf(listOf(cls("08:00", "09:35"), cls("8点", "9点")))!!

        assertEquals(8 * 60, span.startMinute)
        assertEquals(9 * 60 + 35, span.endMinute)
    }

    @Test
    fun spanIsNullWhenNothingParsable() {
        assertNull(TimeAxis.spanOf(emptyList()))
        assertNull(TimeAxis.spanOf(listOf(cls("8点", "9点"))))
    }

    @Test
    fun hoursPerRowGrowsUntilRowsFit() {
        val span = TimeAxis.Span(8 * 60, 18 * 60)   // 10 小时

        // 只放得下 2 行 → 每格至少 6 小时（10 / 6 上取整 = 2 行）
        assertEquals(6, TimeAxis.hoursPerRow(span, maxRows = 2))
        assertEquals(2, TimeAxis.hoursPerRow(span, maxRows = 5))
        assertEquals(1, TimeAxis.hoursPerRow(span, maxRows = 10))
    }

    @Test
    fun hoursPerRowFallsBackToLargestStepForVeryLongSpan() {
        val span = TimeAxis.Span(0, 24 * 60)

        assertEquals(12, TimeAxis.hoursPerRow(span, maxRows = 1))
    }

    @Test
    fun rowCountRoundsUp() {
        val span = TimeAxis.Span(8 * 60, 11 * 60 + 30)   // 3.5 小时

        assertEquals(2, TimeAxis.rowCount(span, hoursPerRow = 2))
        assertEquals(4, TimeAxis.rowCount(span, hoursPerRow = 1))
    }

    @Test
    fun labelsAreZeroPaddedAndWrapPastMidnight() {
        assertEquals("08:00", TimeAxis.labelOf(8 * 60))
        assertEquals("09:05", TimeAxis.labelOf(9 * 60 + 5))
        // 跨过一天绕回来
        assertEquals("01:00", TimeAxis.labelOf(25 * 60))
    }

    @Test
    fun linearFractionIsRelativeToTheSpan() {
        val span = TimeAxis.Span(8 * 60, 10 * 60)   // 2 小时

        assertEquals(0f, TimeAxis.fractionOf(8 * 60, span), 0.0001f)
        assertEquals(0.5f, TimeAxis.fractionOf(9 * 60, span), 0.0001f)
        assertEquals(1f, TimeAxis.fractionOf(10 * 60, span), 0.0001f)
    }

    // ── 分段线性映射（展开时放大指定时段） ──────────────────────────

    @Test
    fun mappingWithoutFocusIsLinear() {
        val span = TimeAxis.Span(8 * 60, 12 * 60)
        val mapping = linear(span)

        assertEquals(0f, mapping.fractionOf(8 * 60), 0.0001f)
        assertEquals(0.25f, mapping.fractionOf(9 * 60), 0.0001f)
        assertEquals(1f, mapping.fractionOf(12 * 60), 0.0001f)
    }

    @Test
    fun focusedSpanGetsScaledUpShare() {
        val span = TimeAxis.Span(8 * 60, 12 * 60)      // 4 小时
        val mapping = TimeAxis.mappingOf(span, TimeAxis.Focus(9 * 60, 10 * 60))

        // 原本占 1/4，放大 2.2 倍 → 0.55
        val focusHeight = mapping.fractionOf(10 * 60) - mapping.fractionOf(9 * 60)
        assertEquals(0.55f, focusHeight, 0.0001f)
        // 位置整体仍然从 0 走到 1
        assertEquals(0f, mapping.fractionOf(8 * 60), 0.0001f)
        assertEquals(1f, mapping.fractionOf(12 * 60), 0.0001f)
        // 放大段之前的部分被压缩：1 小时原本占 0.25，现在只有 0.15
        assertEquals(0.15f, mapping.fractionOf(9 * 60), 0.0001f)
    }

    @Test
    fun mappingIsMonotonic() {
        val span = TimeAxis.Span(8 * 60, 18 * 60)
        val mapping = TimeAxis.mappingOf(span, TimeAxis.Focus(10 * 60, 12 * 60))

        var previous = -1f
        for (minute in span.startMinute..span.endMinute step 5) {
            val current = mapping.fractionOf(minute)
            assertTrue("第 $minute 分钟的比例倒退了", current >= previous)
            previous = current
        }
    }

    @Test
    fun focusShareIsCappedSoOthersStayVisible() {
        val span = TimeAxis.Span(8 * 60, 12 * 60)   // 4 小时
        // 2 小时原本占一半，放 2.2 倍会到 1.1 —— 必须夹到上限，否则其它时段没地方站
        val mapping = TimeAxis.mappingOf(span, TimeAxis.Focus(8 * 60, 10 * 60))

        val focusHeight = mapping.fractionOf(10 * 60) - mapping.fractionOf(8 * 60)
        assertEquals(TimeAxis.MAX_FOCUS_SHARE, focusHeight, 0.0001f)
        // 剩下的时间仍然分得到高度
        assertTrue(mapping.fractionOf(12 * 60) - mapping.fractionOf(10 * 60) > 0f)
    }

    @Test
    fun focusShareNeverDropsBelowItsOriginalShare() {
        val span = TimeAxis.Span(8 * 60, 12 * 60)
        // 这门课本身已占 3/4、超过上限：不再往上放大，但绝不能低于原始占比，否则就成了缩小
        // （早先这里写成 coerceIn(base, MAX)，base > MAX 时会直接抛 IllegalArgumentException）
        val mapping = TimeAxis.mappingOf(span, TimeAxis.Focus(8 * 60, 11 * 60))

        val focusHeight = mapping.fractionOf(11 * 60) - mapping.fractionOf(8 * 60)
        assertEquals(0.75f, focusHeight, 0.0001f)
    }

    @Test
    fun focusOutsideTheSpanFallsBackToLinear() {
        val span = TimeAxis.Span(8 * 60, 12 * 60)

        // 完全在范围外
        assertEquals(0.25f, TimeAxis.mappingOf(span, TimeAxis.Focus(5 * 60, 6 * 60)).fractionOf(9 * 60), 0.0001f)
        // 退化区间
        assertEquals(0.25f, TimeAxis.mappingOf(span, TimeAxis.Focus(9 * 60, 9 * 60)).fractionOf(9 * 60), 0.0001f)
    }

    // ── progress：把「放大」做成连续量，好让 UI 用动画推它 ────────────────

    @Test
    fun progressZeroIsExactlyLinear() {
        val span = TimeAxis.Span(8 * 60, 12 * 60)
        val focus = TimeAxis.Focus(9 * 60, 10 * 60)
        val zero = TimeAxis.mappingOf(span, focus, progress = 0f)

        // 0 时 share 恰好等于原始占比，分段映射精确退化成线性 —— 所以动画的起点就是「没选中」的样子
        for (minute in span.startMinute..span.endMinute step 10) {
            assertEquals(
                TimeAxis.mappingOf(span, null).fractionOf(minute),
                zero.fractionOf(minute),
                0.0001f
            )
        }
    }

    @Test
    fun progressInterpolatesTheFocusShare() {
        val span = TimeAxis.Span(8 * 60, 12 * 60)
        val focus = TimeAxis.Focus(9 * 60, 10 * 60)   // 原本占 0.25，放大到位是 0.55

        fun focusHeight(progress: Float): Float {
            val m = TimeAxis.mappingOf(span, focus, progress)
            return m.fractionOf(10 * 60) - m.fractionOf(9 * 60)
        }

        assertEquals(0.25f, focusHeight(0f), 0.0001f)
        assertEquals(0.40f, focusHeight(0.5f), 0.0001f)
        assertEquals(0.55f, focusHeight(1f), 0.0001f)
    }

    @Test
    fun progressIsClampedToZeroOne() {
        val span = TimeAxis.Span(8 * 60, 12 * 60)
        val focus = TimeAxis.Focus(9 * 60, 10 * 60)

        assertEquals(0.25f, TimeAxis.mappingOf(span, focus, -1f).fractionOf(9 * 60), 0.0001f)
        assertEquals(0.15f, TimeAxis.mappingOf(span, focus, 2f).fractionOf(9 * 60), 0.0001f)
    }

    @Test
    fun mappingStaysMonotonicAtEveryProgress() {
        val span = TimeAxis.Span(8 * 60, 18 * 60)
        val focus = TimeAxis.Focus(10 * 60, 12 * 60)

        for (step in 0..10) {
            val mapping = TimeAxis.mappingOf(span, focus, step / 10f)
            var previous = -1f
            for (minute in span.startMinute..span.endMinute step 5) {
                val current = mapping.fractionOf(minute)
                assertTrue("progress=${step / 10f} 时第 $minute 分钟的比例倒退了", current >= previous)
                previous = current
            }
        }
    }

    @Test
    fun focusHeightGrowsWithProgress() {
        val span = TimeAxis.Span(8 * 60, 12 * 60)
        val focus = TimeAxis.Focus(9 * 60, 10 * 60)

        var previous = -1f
        for (step in 0..10) {
            val m = TimeAxis.mappingOf(span, focus, step / 10f)
            val height = m.fractionOf(10 * 60) - m.fractionOf(9 * 60)
            assertTrue("放大占比随 progress 不增反降", height >= previous)
            previous = height
        }
    }

    @Test
    fun focusedCourseTakesScaledHeightInLayout() {
        val span = TimeAxis.Span(8 * 60, 12 * 60)
        val focused = cls("09:00", "10:00", title = "A")
        val other = cls("11:00", "11:30", title = "B")

        val plain = TimeAxis.layout(listOf(focused, other), linear(span))
            .associateBy { it.cls.title }
        val warped = TimeAxis.layout(listOf(focused, other), TimeAxis.mappingOf(span, TimeAxis.Focus(9 * 60, 10 * 60)))
            .associateBy { it.cls.title }

        // A 原本占 1/4，放大后占 0.55
        assertEquals(0.25f, plain.getValue("A").heightFraction, 0.0001f)
        assertEquals(0.55f, warped.getValue("A").heightFraction, 0.0001f)
        // B 被相应压缩
        assertTrue(warped.getValue("B").heightFraction < plain.getValue("B").heightFraction)
    }

    @Test
    fun nonOverlappingCoursesTakeTheFullColumnWidth() {
        val span = TimeAxis.Span(8 * 60, 12 * 60)
        val placed = TimeAxis.layout(
            listOf(cls("08:00", "09:00", title = "A"), cls("10:00", "11:00", title = "B")),
            linear(span)
        )

        assertEquals(2, placed.size)
        placed.forEach {
            assertEquals(0f, it.leftFraction, 0.0001f)
            assertEquals(1f, it.widthFraction, 0.0001f)
        }
    }

    @Test
    fun overlappingCoursesShareTheColumnSideBySide() {
        val span = TimeAxis.Span(8 * 60, 12 * 60)
        val placed = TimeAxis.layout(
            listOf(cls("08:00", "10:00", title = "A"), cls("09:00", "11:00", title = "B")),
            linear(span)
        )

        assertEquals(2, placed.size)
        // 并排各占一半，谁也不遮谁
        assertEquals(0f, placed[0].leftFraction, 0.0001f)
        assertEquals(0.5f, placed[0].widthFraction, 0.0001f)
        assertEquals(0.5f, placed[1].leftFraction, 0.0001f)
        assertEquals(0.5f, placed[1].widthFraction, 0.0001f)
    }

    @Test
    fun touchingCoursesAreNotTreatedAsOverlapping() {
        val span = TimeAxis.Span(8 * 60, 12 * 60)
        val placed = TimeAxis.layout(
            listOf(cls("08:00", "09:00", title = "A"), cls("09:00", "10:00", title = "B")),
            linear(span)
        )

        placed.forEach { assertEquals(1f, it.widthFraction, 0.0001f) }
    }

    @Test
    fun chainedOverlapsFormOneGroup() {
        val span = TimeAxis.Span(8 * 60, 12 * 60)
        val placed = TimeAxis.layout(
            listOf(
                cls("08:00", "09:00", title = "A"),
                cls("10:00", "11:00", title = "C"),
                // 与 A、C 都相交，把三者连成一组
                cls("08:30", "10:30", title = "B")
            ),
            linear(span)
        )

        assertEquals(3, placed.size)
        placed.forEach { assertEquals(1f / 3f, it.widthFraction, 0.0001f) }
    }

    @Test
    fun verticalPositionFollowsRealDuration() {
        val span = TimeAxis.Span(8 * 60, 12 * 60)   // 4 小时
        val placed = TimeAxis.layout(listOf(cls("09:00", "11:00", title = "A")), linear(span)).single()

        assertEquals(0.25f, placed.topFraction, 0.0001f)
        assertEquals(0.5f, placed.heightFraction, 0.0001f)
    }

    @Test
    fun skipsCoursesWithUnparsableTimes() {
        val span = TimeAxis.Span(8 * 60, 12 * 60)
        val placed = TimeAxis.layout(
            listOf(cls("08:00", "09:00", title = "A"), cls("8点", "9点", title = "坏的")),
            linear(span)
        )

        assertEquals(1, placed.size)
        assertEquals("A", placed[0].cls.title)
    }

    @Test
    fun zeroLengthCourseStillGetsSomeHeight() {
        val span = TimeAxis.Span(8 * 60, 10 * 60)
        val placed = TimeAxis.layout(listOf(cls("09:00", "09:00", title = "A")), linear(span)).single()

        assertTrue(placed.heightFraction > 0f)
    }

    // ── roundMarks：没有高亮列时纵轴标哪些「整点」 ──
    // 之前这里标的是「span 起点 + n × 步长」，span 起点由最早一节课决定，
    // 于是纵轴会出现 07:43、09:13 这种读不出含义的时刻。

    @Test
    fun roundMarksAlignsToWholeHours() {
        // 07:43 开始的一周，按 1 小时步进，第一个刻度应是 08:00 而不是 07:43
        val span = TimeAxis.Span(7 * 60 + 43, 12 * 60)
        val marks = TimeAxis.roundMarks(span, 60)

        assertEquals(listOf(8 * 60, 9 * 60, 10 * 60, 11 * 60, 12 * 60), marks)
        assertTrue("刻度必须是整点", marks.all { it % 60 == 0 })
    }

    @Test
    fun roundMarksUsesStepMultiple() {
        // 步长 2 小时：刻度应为 08:00 / 10:00 / 12:00，而不是 08:00 / 09:00 …
        val span = TimeAxis.Span(7 * 60 + 43, 12 * 60 + 30)
        val marks = TimeAxis.roundMarks(span, 120)

        assertEquals(listOf(8 * 60, 10 * 60, 12 * 60), marks)
    }

    @Test
    fun roundMarksStaysWithinSpan() {
        val span = TimeAxis.Span(8 * 60, 10 * 60)
        val marks = TimeAxis.roundMarks(span, 60)

        // 端点闭区间，且不越界
        assertEquals(listOf(8 * 60, 9 * 60, 10 * 60), marks)
        assertTrue(marks.all { it in span.startMinute..span.endMinute })
    }

    @Test
    fun roundMarksFallsBackWhenNoWholeHourFits() {
        // 跨度只有 20 分钟，按 1 小时步进一个整点都落不进来 —— 不能返回空轴
        val span = TimeAxis.Span(10 * 60 + 5, 10 * 60 + 25)
        val marks = TimeAxis.roundMarks(span, 60)

        assertEquals(listOf(span.startMinute), marks)
    }

    @Test
    fun roundMarksIsAscending() {
        val span = TimeAxis.Span(8 * 60, 18 * 60)
        val marks = TimeAxis.roundMarks(span, 60)

        assertEquals(marks.sorted(), marks)
        assertEquals(marks.distinct(), marks)
    }

    @Test
    fun zebraBandsAlternateEveryOtherHour() {
        val span = TimeAxis.Span(8 * 60, 12 * 60)
        val bands = TimeAxis.zebraBands(span)

        // 起点在 08:00 → 带落在 08-09、10-11；09-10、11-12 留白
        assertEquals(2, bands.size)
        assertEquals(8 * 60, bands[0].startMinute)
        assertEquals(9 * 60, bands[0].endMinute)
        assertEquals(10 * 60, bands[1].startMinute)
        assertEquals(11 * 60, bands[1].endMinute)
    }

    @Test
    fun zebraBandsClipToSpan() {
        // span 从 08:30 开始、11:30 结束。第一条带从起点所在小时起算，
        // 所以是 08-09（被起点裁掉前半）、10-11（被终点裁掉后半）
        val span = TimeAxis.Span(8 * 60 + 30, 11 * 60 + 30)
        val bands = TimeAxis.zebraBands(span)

        assertEquals(2, bands.size)
        assertEquals(span.startMinute, bands[0].startMinute)
        assertEquals(9 * 60, bands[0].endMinute)
        assertEquals(10 * 60, bands[1].startMinute)
        assertEquals(11 * 60, bands[1].endMinute)
    }

    @Test
    fun zebraBandsStayWithinSpan() {
        val span = TimeAxis.Span(7 * 60 + 43, 21 * 60 + 10)
        val bands = TimeAxis.zebraBands(span)

        assertTrue(bands.all { it.startMinute >= span.startMinute })
        assertTrue(bands.all { it.endMinute <= span.endMinute })
        assertTrue(bands.all { it.endMinute > it.startMinute })
    }

    @Test
    fun zebraBandsAreAscendingAndDisjoint() {
        val span = TimeAxis.Span(6 * 60, 22 * 60)
        val bands = TimeAxis.zebraBands(span)

        // 升序，且相邻两段之间至少隔 1 小时（斑马纹「隔一条空一条」）
        for (i in 1 until bands.size) {
            assertTrue(bands[i].startMinute > bands[i - 1].endMinute)
        }
    }

    @Test
    fun zebraBandsHandlesShortSpan() {
        // 半小时的跨度，起点在 09:10。相位锚定起点，所以第一小时（09-10）就是带，
        // 整段被裁成 [09:10, 09:40)。若按绝对偶数小时取，这里会整段落进留白、一条带都没有
        val span = TimeAxis.Span(9 * 60 + 10, 9 * 60 + 40)
        val bands = TimeAxis.zebraBands(span)

        assertEquals(1, bands.size)
        assertEquals(span.startMinute, bands[0].startMinute)
        assertEquals(span.endMinute, bands[0].endMinute)
    }

    @Test
    fun zebraBandsAlwaysStartAtSpanStartHour() {
        // 相位锚定起点的核心保证：第一段带的起点永远等于 span 起点所在的那一小时
        // （即 band.start == span.start，因为起点就在这一小时内）。
        // 换几种起点小时（奇数 / 偶数）都成立
        listOf(7, 8, 9, 10, 23).forEach { hour ->
            val span = TimeAxis.Span(hour * 60 + 43, hour * 60 + 43 + 90)
            val bands = TimeAxis.zebraBands(span)
            assertEquals("hour=$hour", span.startMinute, bands.first().startMinute)
        }
    }
}
