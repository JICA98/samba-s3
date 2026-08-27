package com.zenithblue.sambas3.ui.ingame

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors

@Composable
fun InGameMenuCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = RPCSXColors.surfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth(0.92f)
            .heightIn(max = 520.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = title,
                color = RPCSXColors.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 2.sp,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
fun InGameMenuRow(
    label: String,
    iconRes: Int,
    selected: Boolean = false,
    enabled: Boolean = true,
    showArrow: Boolean = true,
    onClick: () -> Unit
) {
    val bg = if (selected) RPCSXColors.primary.copy(alpha = 0.15f) else Color.Transparent
    val textColor = if (enabled) RPCSXColors.textPrimary else RPCSXColors.textSecondary.copy(alpha = 0.5f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = if (enabled) RPCSXColors.primary else RPCSXColors.textSecondary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label.uppercase(),
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1f)
        )
        if (showArrow) {
            Icon(
                painter = painterResource(id = R.drawable.ic_keyboard_arrow_right),
                contentDescription = null,
                tint = RPCSXColors.textSecondary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
fun InGameMenuFooterActions(
    showSave: Boolean,
    showDiscard: Boolean,
    onSave: () -> Unit = {},
    onDiscard: () -> Unit = {}
) {
    if (!showSave && !showDiscard) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (showSave) {
                TextButton(onClick = onSave) { Text("□ SAVE") }
            } else Spacer(modifier = Modifier.width(1.dp))
            if (showDiscard) {
                TextButton(onClick = onDiscard) { Text("△ DISCARD") }
            } else Spacer(modifier = Modifier.width(1.dp))
        }
    }
}
