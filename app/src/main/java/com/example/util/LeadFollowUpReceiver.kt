package com.example.util

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.model.CustomerLead
import com.example.ui.FollowUpAlarmActivity

class LeadFollowUpReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val leadId = intent.getStringExtra(EXTRA_LEAD_ID) ?: return
    val customerName = intent.getStringExtra(EXTRA_CUSTOMER_NAME) ?: "Customer Lead"
    val mobile = intent.getStringExtra(EXTRA_MOBILE) ?: ""
    val productName = intent.getStringExtra(EXTRA_PRODUCT_NAME) ?: "Loan Facility"
    val notes = intent.getStringExtra(EXTRA_NOTES) ?: ""
    val loanAmount = intent.getLongExtra(FollowUpAlarmActivity.EXTRA_LOAN_AMOUNT, 0L)
    val monthlyEmi = intent.getLongExtra(FollowUpAlarmActivity.EXTRA_MONTHLY_EMI, 0L)
    val tenureMonths = intent.getIntExtra(FollowUpAlarmActivity.EXTRA_TENURE, 0)

    // 1. Immediately launch full-screen dedicated FollowUpAlarmActivity window
    val fullScreenIntent = Intent(context, com.example.ui.FollowUpAlarmActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_CLEAR_TOP or
        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
      putExtra(EXTRA_LEAD_ID, leadId)
      putExtra(EXTRA_CUSTOMER_NAME, customerName)
      putExtra(EXTRA_MOBILE, mobile)
      putExtra(EXTRA_PRODUCT_NAME, productName)
      putExtra(EXTRA_NOTES, notes)
      putExtra(com.example.ui.FollowUpAlarmActivity.EXTRA_LOAN_AMOUNT, loanAmount)
      putExtra(com.example.ui.FollowUpAlarmActivity.EXTRA_MONTHLY_EMI, monthlyEmi)
      putExtra(com.example.ui.FollowUpAlarmActivity.EXTRA_TENURE, tenureMonths)
    }

    try {
      context.startActivity(fullScreenIntent)
    } catch (e: Exception) {
      Log.e("LeadFollowUpReceiver", "Could not launch FollowUpAlarmActivity directly", e)
    }

    // 2. Also build heads-up notification with FullScreenIntent
    showFollowUpAlarmNotification(
      context = context,
      leadId = leadId,
      customerName = customerName,
      mobile = mobile,
      productName = productName,
      notes = notes,
      fullScreenIntent = fullScreenIntent
    )
  }

  private fun showFollowUpAlarmNotification(
    context: Context,
    leadId: String,
    customerName: String,
    mobile: String,
    productName: String,
    notes: String,
    fullScreenIntent: Intent
  ) {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channelId = "kb_lead_followup_alarm_channel_v2"
    val alarmSound: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
      ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    val vibrationPattern = longArrayOf(0, 700, 300, 700, 300, 900)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val audioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_ALARM)
        .build()

      val channel = NotificationChannel(
        channelId,
        "Lead Follow-Up Alarms",
        NotificationManager.IMPORTANCE_HIGH
      ).apply {
        description = "High-priority alarms and reminders for scheduled bank customer follow-ups."
        enableVibration(true)
        this.vibrationPattern = vibrationPattern
        setSound(alarmSound, audioAttributes)
        lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        setShowBadge(true)
      }
      notificationManager.createNotificationChannel(channel)
    }

    val fullScreenPendingIntent = PendingIntent.getActivity(
      context,
      (leadId + "_fullscreen").hashCode(),
      fullScreenIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // Main tap intent to open the app & navigate to lead
    val openAppIntent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      putExtra("open_lead_id", leadId)
    }
    val contentPendingIntent = PendingIntent.getActivity(
      context,
      leadId.hashCode(),
      openAppIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val builder = NotificationCompat.Builder(context, channelId)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("⏰ Follow-Up Alarm: $customerName")
      .setContentText("$productName • ${notes.ifBlank { "Scheduled follow-up due now" }}")
      .setStyle(
        NotificationCompat.BigTextStyle()
          .bigText("Customer: $customerName\nScheme: $productName\nContact: ${mobile.ifBlank { "No phone recorded" }}\nNotes: ${notes.ifBlank { "No special notes" }}")
          .setSummaryText("Scheduled Follow-Up Due")
      )
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setSound(alarmSound)
      .setVibrate(vibrationPattern)
      .setAutoCancel(true)
      .setContentIntent(contentPendingIntent)
      .setFullScreenIntent(fullScreenPendingIntent, true)

    // Call Now Action button
    if (mobile.isNotBlank()) {
      val callIntent = Intent(Intent.ACTION_DIAL).apply {
        data = Uri.parse("tel:$mobile")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
      }
      val callPendingIntent = PendingIntent.getActivity(
        context,
        (leadId + "_call").hashCode(),
        callIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
      builder.addAction(
        android.R.drawable.ic_menu_call,
        "📞 Call Customer",
        callPendingIntent
      )
    }

    // Open Lead Action button
    builder.addAction(
      android.R.drawable.ic_menu_view,
      "📋 Open Lead",
      contentPendingIntent
    )

    try {
      notificationManager.notify(leadId.hashCode(), builder.build())
    } catch (e: Exception) {
      Log.e("LeadFollowUpReceiver", "Failed to display alarm notification", e)
    }
  }

  companion object {
    const val ACTION_LEAD_FOLLOW_UP = "com.example.ACTION_LEAD_FOLLOW_UP"
    const val EXTRA_LEAD_ID = "extra_lead_id"
    const val EXTRA_CUSTOMER_NAME = "extra_customer_name"
    const val EXTRA_MOBILE = "extra_mobile"
    const val EXTRA_PRODUCT_NAME = "extra_product_name"
    const val EXTRA_NOTES = "extra_notes"
  }
}

object LeadAlarmScheduler {

  fun scheduleFollowUpAlarm(
    context: Context,
    lead: CustomerLead,
    triggerTimeMillis: Long
  ) {
    if (triggerTimeMillis <= System.currentTimeMillis()) return
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

    val intent = Intent(context, LeadFollowUpReceiver::class.java).apply {
      action = LeadFollowUpReceiver.ACTION_LEAD_FOLLOW_UP
      putExtra(LeadFollowUpReceiver.EXTRA_LEAD_ID, lead.id)
      putExtra(LeadFollowUpReceiver.EXTRA_CUSTOMER_NAME, lead.customerName)
      putExtra(LeadFollowUpReceiver.EXTRA_MOBILE, lead.mobile)
      putExtra(LeadFollowUpReceiver.EXTRA_PRODUCT_NAME, lead.productName)
      putExtra(LeadFollowUpReceiver.EXTRA_NOTES, lead.notes)
      putExtra(com.example.ui.FollowUpAlarmActivity.EXTRA_LOAN_AMOUNT, lead.loanAmount)
      putExtra(com.example.ui.FollowUpAlarmActivity.EXTRA_MONTHLY_EMI, lead.monthlyEmi)
      putExtra(com.example.ui.FollowUpAlarmActivity.EXTRA_TENURE, lead.tenureMonths)
    }

    val pendingIntent = PendingIntent.getBroadcast(
      context,
      lead.id.hashCode(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        alarmManager.setExactAndAllowWhileIdle(
          AlarmManager.RTC_WAKEUP,
          triggerTimeMillis,
          pendingIntent
        )
      } else {
        alarmManager.setExact(
          AlarmManager.RTC_WAKEUP,
          triggerTimeMillis,
          pendingIntent
        )
      }
      Log.d("LeadAlarmScheduler", "Alarm scheduled for lead ${lead.id} at $triggerTimeMillis")
    } catch (e: SecurityException) {
      // If exact alarm permission is disabled by OS, fallback to standard set
      try {
        alarmManager.set(
          AlarmManager.RTC_WAKEUP,
          triggerTimeMillis,
          pendingIntent
        )
      } catch (ex: Exception) {
        Log.e("LeadAlarmScheduler", "Failed to schedule alarm", ex)
      }
    } catch (e: Exception) {
      Log.e("LeadAlarmScheduler", "Error scheduling alarm", e)
    }
  }

  fun cancelFollowUpAlarm(context: Context, leadId: String) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    val intent = Intent(context, LeadFollowUpReceiver::class.java).apply {
      action = LeadFollowUpReceiver.ACTION_LEAD_FOLLOW_UP
    }
    val pendingIntent = PendingIntent.getBroadcast(
      context,
      leadId.hashCode(),
      intent,
      PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )
    if (pendingIntent != null) {
      alarmManager.cancel(pendingIntent)
      pendingIntent.cancel()
      Log.d("LeadAlarmScheduler", "Alarm cancelled for lead $leadId")
    }
  }
}
