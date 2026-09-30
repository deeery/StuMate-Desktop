package com.example.classreminder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实教务系统导出的课表 PDF（src/test/resources/example.pdf，2 页 15 个课程格）验证解析器。
 */
class TimetablePdfParserTest {

    private val pdf: ByteArray =
        javaClass.getResourceAsStream("/example.pdf")!!.use { it.readBytes() }

    @Test
    fun parsesEveryCourseCell() {
        val courses = TimetablePdfParser.parse(pdf)

        assertEquals(15, courses.size)
        // 每门课都要有名字、星期、时间和教室
        assertTrue(courses.all { it.title.isNotBlank() })
        assertTrue(courses.all { it.dayOfWeek.isNotBlank() })
        assertTrue(courses.all { Regex("^\\d{2}:\\d{2}$").matches(it.startTime) })
        assertTrue(courses.all { it.room.isNotBlank() })
    }

    @Test
    fun readsFieldsAndWrappedTitle() {
        val courses = TimetablePdfParser.parse(pdf)

        val probability = courses.single { it.title == "概率论与数理统计II" }
        assertEquals("Monday", probability.dayOfWeek)
        assertEquals("09:50", probability.startTime)   // 3-5 节
        assertEquals("12:15", probability.endTime)
        assertEquals("A420", probability.room)
        assertEquals("孙利荣", probability.teacher)
        assertEquals("1-16周", probability.weeks)

        // 标题被折成两行「计算思维与人工智能应」+「用◆」，要拼回完整名字并去掉标记
        val wrapped = courses.single { it.title == "计算思维与人工智能应用" }
        assertEquals("Monday", wrapped.dayOfWeek)
        assertEquals("A124", wrapped.room)
    }

    @Test
    fun keepsCoursesThatOnlyDifferByWeeks() {
        val courses = TimetablePdfParser.parse(pdf)

        // 同一门课在星期三 10-12 节有两个周次段（第 6 周 / 1-5 周），两条都要保留
        val deepSeek = courses.filter { it.title == "解密DeepSeek：从基础到创意实践" }
        assertEquals(2, deepSeek.size)
        assertEquals(setOf("第6周", "1-5周"), deepSeek.map { it.weeks }.toSet())
    }

    @Test
    fun mapsCourseToDatabaseRow() {
        val course = TimetablePdfParser.parse(pdf).single { it.title == "概率论与数理统计II" }

        val entity = course.toEntity(42)

        assertEquals(42, entity.id)
        assertEquals("概率论与数理统计II", entity.title)
        assertEquals("Monday", entity.dayOfWeek)
        assertEquals("09:50", entity.startTime)
        assertEquals("12:15", entity.endTime)
        assertEquals("A420", entity.room)
        // 教师和周次是独立字段（可编辑，也用于按周次过滤课表和提醒）
        assertEquals("孙利荣", entity.teacher)
        assertEquals("1-16周", entity.weeks)
    }

    @Test
    fun ignoresNonTimetableText() {
        val courses = TimetablePdfParser.parse(pdf)

        // 页眉、节次数字、页脚「其他课程 / 打印时间」都不该变成课程
        assertTrue(courses.none { it.title.contains("课表") || it.title.contains("学号") })
        assertTrue(courses.none { it.title.contains("打印时间") || it.title.startsWith("其他课程") })
        assertTrue(courses.none { it.title.matches(Regex("\\d+")) })
    }
}
