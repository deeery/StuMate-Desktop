package com.example.classreminder.platform

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局轻提示，替代安卓的 `android.widget.Toast`。
 *
 * 安卓的 Toast 可以随处 `makeText(ctx, ...)`；Compose 桌面端没有这个概念，
 * 所以做成「全局消息总线 + 根节点一个 Host」：任何地方调 [ToastBus.show]，
 * 由 `Main.kt` 里挂着的 [ToastHost] 统一渲染成底部浮条。
 */
object ToastBus {

    data class Message(val id: Long, val text: String)

    private val _message = MutableStateFlow<Message?>(null)
    val message: StateFlow<Message?> = _message.asStateFlow()

    private var counter = 0L

    fun show(text: String) {
        if (text.isBlank()) return
        _message.value = Message(++counter, text)
    }
}

/** 提示停留时长，比安卓 Toast.LENGTH_LONG（3.5s）略短一点，桌面端不挡操作 */
private const val TOAST_VISIBLE_MS = 3_000L

@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    val incoming by ToastBus.message.collectAsState()
    var text by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(incoming) {
        val msg = incoming ?: return@LaunchedEffect
        text = msg.text
        visible = true
        delay(TOAST_VISIBLE_MS)
        visible = false
    }

    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { it / 3 }
        ) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 6.dp
            ) {
                Text(
                    text = text,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}
