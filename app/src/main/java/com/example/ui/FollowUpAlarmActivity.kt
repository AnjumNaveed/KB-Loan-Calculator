package com.example.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainActivity
import com.example.model.CustomerLead
import com.example.util.LeadFollowUpReceiver
import java.net.URLEncoder

class FollowUpAlarmActivity : ComponentActivity() {

  private var mediaPlayer: MediaPlayer? = null
  private var vibrator: Vibrator? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Ensure the activity turns on the screen and displays over keyguard/lock screen
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
      val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
      keyguardManager?.requestDismissKeyguard(this, null)
    } else {
      @Suppress("DEPRECATION")
      window.addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
          WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
          WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
      )
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    val leadId = intent.getStringExtra(LeadFollowUpReceiver.EXTRA_LEAD_ID) ?: ""
    val customerName = intent.getStringExtra(LeadFollowUpReceiver.EXTRA_CUSTOMER_NAME) ?: "Customer Lead"
    val mobile = intent.getStringExtra(LeadFollowUpReceiver.EXTRA_MOBILE) ?: ""
    val productName = intent.getStringExtra(LeadFollowUpReceiver.EXTRA_PRODUCT_NAME) ?: "Loan Facility"
    val notes = intent.getStringExtra(LeadFollowUpReceiver.EXTRA_NOTES) ?: ""
    val loanAmount = intent.getLongExtra(EXTRA_LOAN_AMOUNT, 0L)
    val monthlyEmi = intent.getLongExtra(EXTRA_MONTHLY_EMI, 0L)
    val tenureMonths = intent.getIntExtra(EXTRA_TENURE, 0)

    startAlarmSoundAndVibration()

    setContent {
      FollowUpAlarmScreen(
        customerName = customerName,
        mobile = mobile,
        productName = productName,
        notes = notes,
        loanAmount = loanAmount,
        monthlyEmi = monthlyEmi,
        tenureMonths = tenureMonths,
        onCallCustomer = {
          stopAlarmSoundAndVibration()
          if (mobile.isNotBlank()) {
            val callIntent = Intent(Intent.ACTION_DIAL).apply {
              data = Uri.parse("tel:$mobile")
              flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(callIntent)
          }
          finish()
        },
        onWhatsAppCustomer = {
          stopAlarmSoundAndVibration()
          if (mobile.isNotBlank()) {
            try {
              val digits = mobile.filter { it.isDigit() }
              val formattedPhone = when {
                digits.startsWith("03") -> "92" + digits.substring(1)
                digits.startsWith("92") -> digits
                else -> digits
              }
              val msg = "Assalam-o-Alaikum $customerName, I am contacting you regarding your $productName proposal."
              val url = "https://api.whatsapp.com/send?phone=$formattedPhone&text=${URLEncoder.encode(msg, "UTF-8")}"
              val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
              }
              startActivity(waIntent)
            } catch (_: Exception) {}
          }
          finish()
        },
        onSnooze = {
          stopAlarmSoundAndVibration()
          // Reschedule alarm for 10 minutes later
          if (leadId.isNotBlank()) {
            val dummyLead = CustomerLead(
              id = leadId,
              customerName = customerName,
              mobile = mobile,
              productName = productName,
              notes = notes,
              loanAmount = loanAmount,
              monthlyEmi = monthlyEmi,
              tenureMonths = tenureMonths
            )
            com.example.util.LeadAlarmScheduler.scheduleFollowUpAlarm(
              context = applicationContext,
              lead = dummyLead,
              triggerTimeMillis = System.currentTimeMillis() + (10 * 60 * 1000L)
            )
          }
          finish()
        },
        onDismiss = {
          stopAlarmSoundAndVibration()
          finish()
        },
        onOpenInApp = {
          stopAlarmSoundAndVibration()
          val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_lead_id", leadId)
          }
          startActivity(openIntent)
          finish()
        }
      )
    }
  }

  private fun startAlarmSoundAndVibration() {
    try {
      val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

      mediaPlayer = MediaPlayer().apply {
        setDataSource(applicationContext, alarmUri)
        setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        )
        isLooping = true
        prepare()
        start()
      }
    } catch (_: Exception) {}

    try {
      vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
      }

      val pattern = longArrayOf(0, 800, 400, 800, 400, 1000)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0)) // repeat indefinitely
      } else {
        @Suppress("DEPRECATION")
        vibrator?.vibrate(pattern, 0)
      }
    } catch (_: Exception) {}
  }

  private fun stopAlarmSoundAndVibration() {
    try {
      mediaPlayer?.stop()
      mediaPlayer?.release()
      mediaPlayer = null
    } catch (_: Exception) {}

    try {
      vibrator?.cancel()
      vibrator = null
    } catch (_: Exception) {}
  }

  override fun onDestroy() {
    super.onDestroy()
    stopAlarmSoundAndVibration()
  }

  companion object {
    const val EXTRA_LOAN_AMOUNT = "extra_loan_amount"
    const val EXTRA_MONTHLY_EMI = "extra_monthly_emi"
    const val EXTRA_TENURE = "extra_tenure"
  }
}

@Composable
fun FollowUpAlarmScreen(
  customerName: String,
  mobile: String,
  productName: String,
  notes: String,
  loanAmount: Long,
  monthlyEmi: Long,
  tenureMonths: Int,
  onCallCustomer: () -> Unit,
  onWhatsAppCustomer: () -> Unit,
  onSnooze: () -> Unit,
  onDismiss: () -> Unit,
  onOpenInApp: () -> Unit
) {
  val infiniteTransition = rememberInfiniteTransition(label = "pulse")
  val pulseScale by infiniteTransition.animateFloat(
    initialValue = 0.95f,
    targetValue = 1.05f,
    animationSpec = infiniteRepeatable(
      animation = tween(700, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "pulseScale"
  )

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          colors = listOf(
            Color(0xFF0F172A),
            Color(0xFF1E293B),
            Color(0xFF0B1329)
          )
        )
      )
      .padding(20.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState()),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.SpaceBetween
    ) {
      // Top Pulsating Alarm Header
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 24.dp)
      ) {
        Box(
          modifier = Modifier
            .scale(pulseScale)
            .size(76.dp)
            .clip(CircleShape)
            .background(Color(0xFFEF4444).copy(alpha = 0.2f))
            .border(2.dp, Color(0xFFEF4444), CircleShape),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.Alarm,
            contentDescription = "Alarm Active",
            tint = Color(0xFFEF4444),
            modifier = Modifier.size(38.dp)
          )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Surface(
          shape = RoundedCornerShape(20.dp),
          color = Color(0xFFEF4444).copy(alpha = 0.25f),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444))
        ) {
          Text(
            text = "⏰ FOLLOW-UP REMINDER DUE NOW",
            color = Color(0xFFFCA5A5),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
          )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
          text = customerName.ifBlank { "Valued Customer" },
          color = Color.White,
          fontSize = 24.sp,
          fontWeight = FontWeight.ExtraBold,
          textAlign = TextAlign.Center
        )

        if (mobile.isNotBlank()) {
          Text(
            text = "📱 $mobile",
            color = Color(0xFF38BDF8),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp)
          )
        }
      }

      // Middle Card with Details & Notes
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 18.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text("Scheme / Facility:", color = Color(0xFF94A3B8), fontSize = 12.sp)
            Text(productName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
          }

          if (loanAmount > 0L) {
            HorizontalDivider(color = Color(0xFF334155))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text("Proposal Amount:", color = Color(0xFF94A3B8), fontSize = 12.sp)
              Text("PKR $loanAmount", color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
          }

          if (monthlyEmi > 0L) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text("Monthly Installment:", color = Color(0xFF94A3B8), fontSize = 12.sp)
              Text("PKR $monthlyEmi ($tenureMonths Months)", color = Color(0xFFFACC15), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
          }

          HorizontalDivider(color = Color(0xFF334155))

          Column {
            Text("Follow-Up Notes & Instructions:", color = Color(0xFF94A3B8), fontSize = 11.5.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = Color(0xFF0F172A),
              modifier = Modifier.fillMaxWidth()
            ) {
              Text(
                text = notes.ifBlank { "Scheduled follow-up due for customer loan discussion." },
                color = Color(0xFFE2E8F0),
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(10.dp)
              )
            }
          }
        }
      }

      // Bottom Actions (Call, WhatsApp, Snooze, Dismiss)
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        // Direct Action Buttons: Call & WhatsApp
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Button(
            onClick = onCallCustomer,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .weight(1f)
              .height(52.dp)
              .testTag("alarm_call_button")
          ) {
            Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Call Now", fontSize = 14.sp, fontWeight = FontWeight.Bold)
          }

          Button(
            onClick = onWhatsAppCustomer,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .weight(1f)
              .height(52.dp)
              .testTag("alarm_whatsapp_button")
          ) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
            Spacer(modifier = Modifier.width(6.dp))
            Text("WhatsApp", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
          }
        }

        // Secondary Actions: Snooze 10m & Stop Alarm
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = onSnooze,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF59E0B)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .weight(1f)
              .height(48.dp)
              .testTag("alarm_snooze_button")
          ) {
            Icon(Icons.Default.Snooze, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Snooze 10m", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
          }

          Button(
            onClick = onDismiss,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .weight(1f)
              .height(48.dp)
              .testTag("alarm_dismiss_button")
          ) {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Stop Alarm", fontSize = 13.sp, fontWeight = FontWeight.Bold)
          }
        }

        TextButton(
          onClick = onOpenInApp,
          modifier = Modifier.fillMaxWidth()
        ) {
          Icon(Icons.Default.OpenInNew, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Open Lead in Calculator App", color = Color(0xFF94A3B8), fontSize = 12.5.sp)
        }
      }
    }
  }
}
