package com.zenithblue.sambas3.ui.user

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.EmulatorState
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.User
import com.zenithblue.sambas3.UserRepository
import com.zenithblue.sambas3.dialogs.AlertDialogQueue

@Composable
fun UserItem(
    user: User,
    isActive: Boolean,
    setActive: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clickable(
                enabled = !isActive
            ) {
                setActive()
            }
    ) {
        RadioButton(
            selected = isActive,
            onClick = setActive
        )
        Text(
            text = user.username,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = user.userId,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsersScreen(
    navigateBack: () -> Unit,
    isInSplitPane: Boolean = false
) {
    val users = remember { UserRepository.users }
    val activeUser by remember { UserRepository.activeUser }
    val emulatorState by remember { RPCSX.state }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        UserRepository.load()
    }

    @Composable
    fun UsersContent(modifier: Modifier = Modifier) {
        LazyColumn(
            contentPadding = PaddingValues(8.dp),
            modifier = modifier
        ) {
            items(
                count = users.size,
                key = { index -> users.toList()[index].first }
            ) {
                val user = users.values.elementAt(it)
                UserItem(
                    user = user,
                    isActive = user.userId == activeUser,
                    setActive = {
                        if (emulatorState != EmulatorState.Stopped) {
                            AlertDialogQueue.showDialog(
                                title = context.getString(R.string.ask_if_stop_emu),
                                message = context.getString(R.string.ask_if_stop_emu_description),
                                onConfirm = {
                                    RPCSX.instance.kill()
                                    RPCSX.updateState()
                                    UserRepository.loginUser(user.userId)
                                }
                            )
                        } else {
                            UserRepository.loginUser(user.userId)
                        }
                    },
                )
            }
            item {
                Box(Modifier.height(LocalConfiguration.current.screenHeightDp.dp * 0.4f))
            }
        }
    }

    if (isInSplitPane) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = navigateBack) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_keyboard_arrow_left),
                        contentDescription = null,
                        tint = com.zenithblue.sambas3.RPCSXColors.primary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.users).uppercase(),
                    color = com.zenithblue.sambas3.RPCSXColors.primary,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = 2.sp
                )
            }
            UsersContent(modifier = Modifier.weight(1f))
        }
    } else {
        Scaffold(
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            topBar = {
                TopAppBar(
                    title = {
                        Text(stringResource(R.string.users))
                    },
                    navigationIcon = {
                        IconButton(onClick = navigateBack) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_keyboard_arrow_left),
                                contentDescription = null
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier.padding(innerPadding)
            ) {
                UsersContent()
            }
        }
    }
}
