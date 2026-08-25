package com.vcodec.smartencoder.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.vcodec.smartencoder.ui.theme.DarkSurface
import com.vcodec.smartencoder.ui.theme.PrimaryCyan
import com.vcodec.smartencoder.ui.theme.TextGray

/**
 * Small "?" badge that shows an explanation popup when tapped.
 */
@Composable
fun InfoDot(infoText: String) {
    var showInfo by remember { mutableStateOf(false) }
    Box {
        Text(
            "?",
            color = PrimaryCyan,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .clip(CircleShape)
                .background(PrimaryCyan.copy(alpha = 0.15f))
                .clickable { showInfo = !showInfo }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        if (showInfo) {
            Popup(
                alignment = Alignment.TopStart,
                onDismissRequest = { showInfo = false }
            ) {
                Card(
                    modifier = Modifier.padding(8.dp).widthIn(max = 280.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, PrimaryCyan.copy(alpha = 0.35f))
                ) {
                    Text(
                        infoText,
                        color = TextGray,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}
