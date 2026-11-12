package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.GlassMeshBackground
import com.example.ui.theme.GlassTheme
import com.example.ui.theme.press3d

@Composable
fun SignInScreen(
  isLoading: Boolean,
  errorMessage: String?,
  onSignInClick: () -> Unit,
  onContinueOffline: () -> Unit = {}
) {
  GlassMeshBackground {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
      contentAlignment = Alignment.Center
    ) {
      Surface(
        modifier = Modifier
          .fillMaxWidth(0.92f)
          .widthIn(max = 440.dp)
          .shadow(
            elevation = 16.dp,
            shape = RoundedCornerShape(24.dp),
            ambientColor = Color(0x220F172A),
            spotColor = Color(0x330F172A)
          )
          .clip(RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.94f),
        border = BorderStroke(
          1.dp,
          Brush.linearGradient(
            listOf(
              Color.White.copy(alpha = 0.35f),
              Color(0xFF334155).copy(alpha = 0.50f)
            )
          )
        )
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(26.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
          // App Icon 3D Glass Badge
          Box(
            modifier = Modifier
              .size(68.dp)
              .press3d(0.95f)
              .shadow(8.dp, CircleShape, ambientColor = Color(0x33000000), spotColor = Color(0x55000000))
              .background(
                brush = Brush.linearGradient(
                  listOf(
                    Color(0xFF1E3A8A),
                    Color(0xFF0F172A)
                  )
                ),
                shape = CircleShape
              ),
            contentAlignment = Alignment.Center
          ) {
            Text("🏛️", fontSize = 32.sp)
          }

          Text(
            text = "KB Loan Calculator",
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            textAlign = TextAlign.Center
          )

          Text(
            text = "Financial Calculator & Lead Pipeline",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF60A5FA),
            textAlign = TextAlign.Center
          )

          Text(
            text = "Calculate customized loan proposals, generate professional PDF schedules, and sync customer leads in real-time.",
            fontSize = 11.5.sp,
            color = Color(0xFFCBD5E1),
            textAlign = TextAlign.Center,
            lineHeight = 17.sp
          )

          // 3 Feature Pills
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(Icons.Default.Speed, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.height(3.dp))
              Text("100% Offline", fontSize = 9.5.sp, color = Color(0xFFE2E8F0), fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(Icons.Default.Sync, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.height(3.dp))
              Text("Auto Cloud Sync", fontSize = 9.5.sp, color = Color(0xFFE2E8F0), fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.height(3.dp))
              Text("Encrypted", fontSize = 9.5.sp, color = Color(0xFFE2E8F0), fontWeight = FontWeight.Bold)
            }
          }

          Spacer(modifier = Modifier.height(4.dp))

          // Google Sign-In Button with 3D press
          Button(
            onClick = onSignInClick,
            enabled = !isLoading,
            modifier = Modifier
              .fillMaxWidth()
              .height(48.dp)
              .press3d(0.96f)
              .shadow(6.dp, RoundedCornerShape(12.dp)),
            colors = ButtonDefaults.buttonColors(
              containerColor = Color.White,
              contentColor = Color(0xFF0F172A)
            ),
            shape = RoundedCornerShape(12.dp)
          ) {
            if (isLoading) {
              CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color(0xFF0F172A),
                strokeWidth = 2.dp
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text("Connecting securely...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            } else {
              Text("G", fontSize = 17.sp, fontWeight = FontWeight.Black, color = Color(0xFFEA4335))
              Spacer(modifier = Modifier.width(8.dp))
              Text("Sign in with Google", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
          }

          // Offline mode button
          OutlinedButton(
            onClick = onContinueOffline,
            modifier = Modifier
              .fillMaxWidth()
              .height(44.dp)
              .press3d(0.97f),
            colors = ButtonDefaults.outlinedButtonColors(
              contentColor = Color(0xFFFCD34D)
            ),
            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
            shape = RoundedCornerShape(12.dp)
          ) {
            Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Continue in Offline Mode (بغیر نیٹ استعمال کریں)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
          }

          errorMessage?.let { error ->
            Surface(
              color = Color(0xFF7F1D1D).copy(alpha = 0.7f),
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              Text(
                text = "⚠️ $error",
                color = Color(0xFFFCA5A5),
                fontSize = 10.5.sp,
                modifier = Modifier.padding(8.dp),
                textAlign = TextAlign.Center
              )
            }
          }
        }
      }
    }
  }
}
