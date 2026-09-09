package com.zenithblue.sambas3.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.ui.common.SambaScreenScaffold

private data class PrivacySection(val title: String, val body: String)

@Composable
fun PrivacyPolicyScreen(navigateBack: () -> Unit) {
    val sections = listOf(
        PrivacySection(
            "INFORMATION STORED ON THIS DEVICE",
            "SambaS3 stores folders and document access you select; emulator settings and controller mappings; local PS3 profile names; game metadata; save states; screenshots; shader and PPU caches; and local compatibility or crash records. Diagnostic captures can include device, Android, GPU, driver, memory, thermal, battery, and emulator performance information."
        ),
        PrivacySection(
            "NETWORK ACCESS & SHARING",
            "The Google Play build includes its emulator runtime and supported GPU-driver packages and does not download executable code. Patch features may retrieve public RPCS3 game-patch metadata from rpcs3.net. Logs or captures leave your device only when you explicitly choose Share or Export and select a destination in Android's system interface."
        ),
        PrivacySection(
            "PERMISSIONS",
            "Android's system file picker grants access only to folders and documents you select. Notification permission supports visible emulation, installation, and compilation progress. Foreground services keep user-started emulation and compilation visible and stoppable. Controller, vibration, and USB access support game input and connected devices."
        ),
        PrivacySection(
            "RETENTION & DELETION",
            "Local information remains until you remove it in SambaS3, clear the app's storage, uninstall the app, or delete exported files. Diagnostic logs use bounded rotation. Eligible app data may be included in Android device backup according to your device settings. SambaS3 has no server-side account data to delete."
        ),
        PrivacySection(
            "CHILDREN & GAME CONTENT",
            "SambaS3 is not directed to children and does not knowingly collect personal information from children. Games and firmware are supplied by the user and may have their own age ratings. SambaS3 does not provide copyrighted game or firmware content."
        ),
        PrivacySection(
            "CHANGES & CONTACT",
            "Policy changes are published with a revised effective date. Privacy questions can be submitted to the SambaS3 / JICA98 project through its public GitHub issue tracker."
        )
    )

    SambaScreenScaffold(
        title = stringResource(R.string.privacy_policy),
        iconRes = R.drawable.ic_lock,
        onBack = navigateBack,
        hints = listOf(R.drawable.circle to "Back")
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 920.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = 20.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xA6121724),
                        border = BorderStroke(1.dp, RPCSXColors.primary.copy(alpha = 0.42f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text(
                                "EFFECTIVE SEPTEMBER 9, 2026",
                                color = RPCSXColors.primary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                "Privacy by default",
                                color = RPCSXColors.textPrimary,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "SambaS3 requires no online account. It contains no ads or analytics SDKs, does not sell personal information, and does not automatically send games, save states, screenshots, profile names, logs, or performance captures to the developer.",
                                color = RPCSXColors.textSecondary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                items(sections) { section ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x8F111722),
                        border = BorderStroke(1.dp, Color(0x28FFFFFF)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                section.title,
                                color = RPCSXColors.primaryDim,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 1.sp
                            )
                            Text(
                                section.body,
                                color = RPCSXColors.textPrimary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
