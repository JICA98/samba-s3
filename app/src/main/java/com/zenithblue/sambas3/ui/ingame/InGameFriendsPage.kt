package com.zenithblue.sambas3.ui.ingame

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.RPCSXColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class FriendsTab { Friends, Requests, Blocked }

@Composable
fun InGameFriendsPage(onBack: () -> Unit) {
    var data by remember { mutableStateOf<FriendsData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(FriendsTab.Friends) }
    var actionMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        data = withContext(Dispatchers.IO) {
            FriendsData.fromJson(try { RPCSX.instance.getFriends() } catch (_: Exception) { null })
        }
        loading = false
    }

    fun refresh() {
        // Trigger reload
        loading = true
        data = null
        // Use wrapper coroutine via LaunchedEffect key toggle? Simpler: launch again
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.70f)), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = RPCSXColors.surfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(0.96f).fillMaxHeight(0.92f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("FRIENDS", color = RPCSXColors.primary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp)
                    TextButton(onClick = onBack) { Text("Back") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // Tabs: Square changes page per spec, but we provide buttons
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(FriendsTab.Friends, FriendsTab.Requests, FriendsTab.Blocked).forEach { t ->
                        FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.name) })
                    }
                }
                if (loading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = RPCSXColors.primary)
                    }
                } else if (data?.available != true) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("RPCN not configured", color = RPCSXColors.textSecondary)
                    }
                } else {
                    val list = when (tab) {
                        FriendsTab.Friends -> data!!.friends.map { it.username to (if (it.online) "Online ${it.presenceTitle}" else "Offline") }
                        FriendsTab.Requests -> (data!!.requestsReceived.map { it to "Received" } + data!!.requestsSent.map { it to "Sent" })
                        FriendsTab.Blocked -> data!!.blocked.map { it to "Blocked" }
                    }
                    if (list.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No entries", color = RPCSXColors.textSecondary)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                            items(list) { (username, status) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).background(RPCSXColors.surface, shape = RoundedCornerShape(8.dp)).padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(username, color = RPCSXColors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text(status, color = RPCSXColors.textSecondary, fontSize = 11.sp)
                                    }
                                    when (tab) {
                                        FriendsTab.Friends -> {
                                            TextButton(onClick = {
                                                val ok = try { RPCSX.instance.friendAction("remove_friend", username) } catch (_: Exception) { false }
                                                actionMessage = if (ok) "Removed $username" else "Failed"
                                            }) { Text("Remove") }
                                        }
                                        FriendsTab.Requests -> {
                                            val isReceived = data!!.requestsReceived.contains(username)
                                            if (isReceived) {
                                                Row {
                                                    TextButton(onClick = {
                                                        val ok = try { RPCSX.instance.friendAction("accept_request", username) } catch (_: Exception) { false }
                                                        actionMessage = if (ok) "Accepted $username" else "Failed"
                                                    }) { Text("Accept") }
                                                    TextButton(onClick = {
                                                        val ok = try { RPCSX.instance.friendAction("reject_request", username) } catch (_: Exception) { false }
                                                        actionMessage = if (ok) "Rejected $username" else "Failed"
                                                    }) { Text("Reject") }
                                                }
                                            } else {
                                                TextButton(onClick = {
                                                    val ok = try { RPCSX.instance.friendAction("cancel_request", username) } catch (_: Exception) { false }
                                                    actionMessage = if (ok) "Canceled $username" else "Failed"
                                                }) { Text("Cancel") }
                                            }
                                        }
                                        FriendsTab.Blocked -> {
                                            Text("Blocked", color = Color.Gray, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                actionMessage?.let {
                    Snackbar(modifier = Modifier.padding(8.dp)) { Text(it) }
                }
            }
        }
    }
}
