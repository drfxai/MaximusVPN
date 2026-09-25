package com.example.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chat.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretChatScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val chatManager = remember { SecretChatManager(context) }
    val myAtomicId by chatManager.myAtomicId.collectAsState()
    val conversations by chatManager.conversationsFlow.collectAsState()
    val activeMessages by chatManager.activeMessagesFlow.collectAsState()

    var activeConversation by remember { mutableStateOf<SecretConversation?>(null) }
    var showNewChatDialog by remember { mutableStateOf(false) }
    var showNukeConfirmDialog by remember { mutableStateOf(false) }
    var inputMessage by remember { mutableStateOf("") }
    var peerIdInput by remember { mutableStateOf("") }
    var peerAliasInput by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (activeConversation != null) activeConversation!!.peerAlias.ifBlank { activeConversation!!.peerAtomicId.take(9) + "..." } else "Messaging Preview (Unavailable)",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF9C27B0).copy(alpha = 0.25f)
                            ) {
                                Text(
                                    text = "OFFLINE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFCE93D8),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (activeConversation != null) {
                            Text(
                                text = "Auto-Shred: ${activeConversation!!.ephemeralTimer.label}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (activeConversation != null) {
                            activeConversation = null
                            chatManager.setActiveConversation(null)
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (activeConversation != null) {
                        IconButton(onClick = {
                            val nextTimer = when (activeConversation!!.ephemeralTimer) {
                                EphemeralTimer.ONE_MINUTE -> EphemeralTimer.FIVE_MINUTES
                                EphemeralTimer.FIVE_MINUTES -> EphemeralTimer.ONE_HOUR
                                EphemeralTimer.ONE_HOUR -> EphemeralTimer.TWENTY_FOUR_HOURS
                                EphemeralTimer.TWENTY_FOUR_HOURS -> EphemeralTimer.OFF
                                EphemeralTimer.OFF -> EphemeralTimer.ONE_MINUTE
                            }
                            chatManager.updateEphemeralTimer(activeConversation!!.id, nextTimer)
                            activeConversation = activeConversation!!.copy(ephemeralTimer = nextTimer)
                        }) {
                            Icon(Icons.Default.Timer, contentDescription = "Timer")
                        }
                    }
                    IconButton(onClick = { showNukeConfirmDialog = true }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Nuke", tint = MaterialTheme.colorScheme.error)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (activeConversation == null) {
                // Conversation list & My Atomic ID Card
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    // My Atomic ID Banner Card
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.VpnKey,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "My 20-Digit Atomic ID",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Maximus Atomic ID", myAtomicId))
                                            Toast.makeText(context, "Atomic ID copied to clipboard", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = myAtomicId,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Local identifier only. Authenticated key exchange and peer transport are not implemented.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    item {
                        Button(
                            onClick = { showNewChatDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.AddComment, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save Local Contact")
                        }
                    }

                    item {
                        Text(
                            text = "LOCAL CONTACTS (${conversations.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (conversations.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.Shield,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "No local contacts",
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Messaging is unavailable in this build",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    } else {
                        items(conversations, key = { it.id }) { conv ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        activeConversation = conv
                                        chatManager.setActiveConversation(conv.id)
                                    },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Lock,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = conv.peerAlias.ifBlank { "Peer ${conv.peerAtomicId.take(9)}..." },
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                        Text(
                                            text = conv.lastMessageText ?: "No authenticated session",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(conv.lastMessageTime)),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer
                                        ) {
                                            Text(
                                                text = conv.ephemeralTimer.label.replace(" Shred", ""),
                                                fontSize = 10.sp,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Active chat view
                Column(modifier = Modifier.fillMaxSize()) {
                    // Message Stream
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        items(activeMessages, key = { it.id }) { msg ->
                            val isMe = msg.isOutgoing
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomStart = if (isMe) 16.dp else 4.dp,
                                        bottomEnd = if (isMe) 4.dp else 16.dp
                                    ),
                                    color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.widthIn(max = 280.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = msg.text,
                                            color = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 14.sp
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.End,
                                            modifier = Modifier.align(Alignment.End)
                                        ) {
                                            Icon(
                                                Icons.Default.Lock,
                                                contentDescription = null,
                                                modifier = Modifier.size(10.dp),
                                                tint = if (isMe) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp)),
                                                fontSize = 10.sp,
                                                color = if (isMe) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Input bar
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = inputMessage,
                                onValueChange = { inputMessage = it },
                                placeholder = { Text("Messaging unavailable: peer transport not integrated", fontSize = 12.sp) },
                                enabled = false,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(24.dp),
                                maxLines = 4
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                enabled = false,
                                onClick = {
                                    if (inputMessage.isNotBlank() && activeConversation != null) {
                                        chatManager.sendMessage(activeConversation!!.id, inputMessage.trim())
                                        inputMessage = ""
                                        coroutineScope.launch {
                                            listState.animateScrollToItem((activeMessages.size).coerceAtLeast(0))
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog: New Chat
    if (showNewChatDialog) {
        AlertDialog(
            onDismissRequest = { showNewChatDialog = false },
            title = { Text("Save Local Contact ID") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "This saves a local contact only. It does not establish an encrypted or network session.",
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = peerIdInput,
                        onValueChange = { peerIdInput = it },
                        label = { Text("20-Digit Atomic ID") },
                        placeholder = { Text("XXXX-XXXX-XXXX-XXXX-XXXX") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = peerAliasInput,
                        onValueChange = { peerAliasInput = it },
                        label = { Text("Alias / Contact Name (Optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val formatted = CryptoE2ee.formatAtomicId(peerIdInput)
                        if (CryptoE2ee.isValidAtomicId(formatted) || formatted.replace("-", "").length == 20) {
                            val conv = chatManager.openConversation(formatted, peerAliasInput)
                            activeConversation = conv
                            showNewChatDialog = false
                            peerIdInput = ""
                            peerAliasInput = ""
                        } else {
                            Toast.makeText(context, "Please enter 20 valid digits", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewChatDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog: Nuke All Chats
    if (showNukeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showNukeConfirmDialog = false },
            title = { Text("Delete All Local Contact Data?") },
            text = {
                Text("This removes the app's local contact records. Flash storage may retain system-managed remnants, so this is deletion—not guaranteed forensic erasure.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        chatManager.nukeAllChats()
                        activeConversation = null
                        showNukeConfirmDialog = false
                        Toast.makeText(context, "Local contact data deleted.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNukeConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
