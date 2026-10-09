package com.mp.player.ai

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch

data class ChatMsg(val fromUser: Boolean, val text: String)

/** Haelt Assistent und Gespraech ausserhalb der Compose-UI, damit beides Navigation und Drehungen ueberlebt. */
object AssistantSession {
    val messages = mutableStateListOf<ChatMsg>()
    var quickReplies by mutableStateOf<List<String>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set

    private var assistant: Assistant? = null

    private fun assistant(ctx: Context): Assistant =
        assistant ?: Assistant(PlayerToolsImpl(ctx.applicationContext), memoryStore = SqliteMemoryStore(ctx.applicationContext), episodeStore = SqliteEpisodeStore(ctx.applicationContext), snapshots = FileSnapshotStore(java.io.File(ctx.applicationContext.filesDir, "ai_snapshots.txt")), appContext = ctx.applicationContext).also { assistant = it }

    fun ensureWelcome(ctx: Context) {
        if (messages.isNotEmpty()) return
        val w = assistant(ctx).welcome()
        messages.add(ChatMsg(false, w.text))
        quickReplies = w.quickReplies
    }

    suspend fun send(ctx: Context, text: String) {
        if (busy) return
        busy = true
        messages.add(ChatMsg(true, text))
        quickReplies = emptyList()
        try {
            val reply = assistant(ctx).handle(text)
            messages.add(ChatMsg(false, reply.text))
            quickReplies = reply.quickReplies
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            messages.add(ChatMsg(false, "Da ist was schiefgelaufen - deine Musik läuft normal weiter."))
        } finally {
            busy = false
        }
    }
}

@Composable
fun AssistantScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { AssistantSession.ensureWelcome(context) }
    val count = AssistantSession.messages.size
    LaunchedEffect(count) { if (count > 0) listState.animateScrollToItem(count - 1) }

    fun send(raw: String) {
        val t = raw.trim()
        if (t.isEmpty() || AssistantSession.busy) return
        input = ""
        scope.launch { AssistantSession.send(context, t) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, null) }
            Column {
                Text("Secret Player", style = MaterialTheme.typography.headlineSmall)
                Text("läuft komplett offline auf deinem Gerät", style = MaterialTheme.typography.bodySmall)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(AssistantSession.messages) { m -> Bubble(m) }
        }

        val quick = AssistantSession.quickReplies
        if (quick.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(quick) { q -> AssistChip(onClick = { send(q) }, label = { Text(q) }) }
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Schreib mir…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) })
            )
            IconButton(onClick = { send(input) }, enabled = !AssistantSession.busy) {
                Icon(Icons.Filled.Send, "Senden")
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMsg) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (m.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(m.text, modifier = Modifier.widthIn(max = 300.dp).padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}
