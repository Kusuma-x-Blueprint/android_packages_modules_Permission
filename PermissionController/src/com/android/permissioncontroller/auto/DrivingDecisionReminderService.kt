/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.permissioncontroller.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.UserHandle
import android.text.BidiFormatter
import androidx.annotation.VisibleForTesting
import com.android.permissioncontroller.Constants
import com.android.permissioncontroller.DumpableLog
import com.android.permissioncontroller.R
import com.android.permissioncontroller.permission.utils.KotlinUtils.getPackageLabel
import com.android.permissioncontroller.permission.utils.KotlinUtils.getPermGroupLabel
import com.android.permissioncontroller.permission.utils.Utils

/**
 * Service that collects permissions decisions made while driving and when the vehicle is no longer
 * in a UX-restricted state shows a notification reminding the user of their decisions.
 */
class DrivingDecisionReminderService : Service() {

    /**
     * Information needed to show a reminder about a permission decisions.
     */
    data class PermissionReminder(
        val packageName: String,
        val permissionGroup: String,
        val user: UserHandle
    )

    private var scheduled = false
    private var carUxRestrictionsManager: CarUxRestrictionsManager? = null
    private val permissionReminders: MutableSet<PermissionReminder> = mutableSetOf()
    private var car: Car? = null

    companion object {
        private const val LOG_TAG = "DrivingDecisionReminderService"

        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_PERMISSION_GROUP = "permission_group"
        const val EXTRA_USER = "user"

        /**
         * Create an intent to launch [DrivingDecisionReminderService], including information about
         * the permission decision to reminder the user about.
         *
         * @param context application context
         * @param packageName package name of app effected by the permission decision
         * @param permissionGroup permission group for the permission decision
         * @param user user that made the permission decision
         */
        fun createIntent(
            context: Context,
            packageName: String,
            permissionGroup: String,
            user: UserHandle
        ): Intent {
            val intent = Intent(context, DrivingDecisionReminderService::class.java)
            intent.putExtra(EXTRA_PACKAGE_NAME, packageName)
            intent.putExtra(EXTRA_PERMISSION_GROUP, permissionGroup)
            intent.putExtra(EXTRA_USER, user)
            return intent
        }

        /**
         * Starts the [DrivingDecisionReminderService] if the vehicle currently requires distraction
         * optimization.
         */
        fun startServiceIfCurrentlyRestricted(
            context: Context,
            packageName: String,
            permGroupName: String
        ) {
            Car.createCar(
                context,
                /* handler= */ null,
                Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { car: Car, ready: Boolean ->
                // just give up if we can't connect to the car
                if (ready) {
                    val restrictionsManager = car.getCarManager(
                        Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    if (restrictionsManager.currentCarUxRestrictions
                            .isRequiresDistractionOptimization) {
                        context.startService(
                            createIntent(
                                context,
                                packageName,
                                permGroupName,
                                Process.myUserHandle()))
                    }
                }
                car.disconnect()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val decisionReminder = parseStartIntent(intent) ?: return START_NOT_STICKY
        permissionReminders.add(decisionReminder)
        if (scheduled) {
            DumpableLog.d(LOG_TAG, "Start service - reminder notification already scheduled")
            return START_STICKY
        }
        scheduleNotificationForUnrestrictedState()
        scheduled = true
        return START_STICKY
    }

    override fun onDestroy() {
        car?.disconnect()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun scheduleNotificationForUnrestrictedState() {
        Car.createCar(this, null,
            Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT
        ) { createdCar: Car?, ready: Boolean ->
            car = createdCar
            if (ready) {
                onCarReady()
            } else {
                DumpableLog.w(LOG_TAG,
                    "Car service disconnected, no notification will be scheduled")
                stopSelf()
            }
        }
    }

    private fun onCarReady() {
        carUxRestrictionsManager = car?.getCarManager(
            Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
        DumpableLog.d(LOG_TAG, "Registering UX restriction listener")
        carUxRestrictionsManager?.registerListener { restrictions ->
            if (!restrictions.isRequiresDistractionOptimization) {
                DumpableLog.d(LOG_TAG,
                    "UX restrictions no longer required - showing reminder notification")
                showRecentGrantDecisionsPostDriveNotification()
                stopSelf()
            }
        }
    }

    private fun parseStartIntent(intent: Intent?): PermissionReminder? {
        if (intent == null ||
            !intent.hasExtra(EXTRA_PACKAGE_NAME) ||
            !intent.hasExtra(EXTRA_PERMISSION_GROUP) ||
            !intent.hasExtra(EXTRA_USER)) {
            DumpableLog.e(LOG_TAG, "Missing extras from intent $intent")
            return null
        }
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val permissionGroup = intent.getStringExtra(EXTRA_PERMISSION_GROUP)
        val user = intent.getParcelableExtra<UserHandle>(EXTRA_USER)
        return PermissionReminder(packageName!!, permissionGroup!!, user!!)
    }

    @VisibleForTesting
    fun showRecentGrantDecisionsPostDriveNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)!!

        val permissionReminderChannel = NotificationChannel(
            Constants.PERMISSION_REMINDER_CHANNEL_ID, getString(R.string.permission_reminders),
            NotificationManager.IMPORTANCE_HIGH)
        notificationManager.createNotificationChannel(permissionReminderChannel)

        notificationManager.notify(DrivingDecisionReminderService::class.java.simpleName,
            Constants.PERMISSION_DECISION_REMINDER_NOTIFICATION_ID,
            createNotification(createNotificationTitle(), createNotificationContent()))
    }

    private fun createNotificationTitle(): String {
        return applicationContext
            .getString(R.string.post_drive_permission_decision_reminder_title)
    }

    // TODO(b/194240664) - update notification content after UX writing review
    private fun createNotificationContent(): String {
        val packageLabels: MutableSet<String> = mutableSetOf()
        val permissionGroupNames: MutableSet<String> = mutableSetOf()
        for (permissionReminder in permissionReminders) {
            val packageLabel = BidiFormatter.getInstance().unicodeWrap(
                getPackageLabel(application, permissionReminder.packageName,
                    permissionReminder.user))
            val permissionGroupLabel = getPermGroupLabel(applicationContext,
                permissionReminder.permissionGroup).toString()
            packageLabels.add(packageLabel)
            permissionGroupNames.add(permissionGroupLabel)
        }
        return "You granted " + packageLabels.joinToString() +
            " access to " + permissionGroupNames.joinToString()
    }

    private fun createNotification(title: String, body: String): Notification {
        // TODO(b/194240664) - intent out to review permission decisions screen on click. Be sure
        // to include sessionId
        val b = Notification.Builder(this, Constants.PERMISSION_REMINDER_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_settings_24dp)
            .setColor(getColor(android.R.color.system_notification_accent_color))
            .setAutoCancel(true)
        Utils.getSettingsLabelForNotifications(applicationContext.packageManager)?.let { label ->
            val extras = Bundle()
            extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME, label.toString())
            b.addExtras(extras)
        }
        return b.build()
    }
}