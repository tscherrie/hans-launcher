package ai.hans.standard.phone.publicapi

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Public Android API implementation. Every operation rechecks its live grant before access. */
class AndroidPublicPhonePlatform(
    context: Context,
    private val notificationReplies: ActiveNotificationReplyRegistry =
        ActiveNotificationReplyRegistry.processWide,
) : PublicPhonePlatform {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val packageManager = appContext.packageManager

    override fun probeCapabilities(): List<PublicPhoneCapabilityProbe> = listOf(
        permissionProbe("contacts.read", Manifest.permission.READ_CONTACTS),
        permissionProbe("calendar.read", Manifest.permission.READ_CALENDAR),
        permissionProbe("calendar.create", Manifest.permission.WRITE_CALENDAR),
        locationProbe(),
        sensorProbe(),
        mediaProbe(MediaCatalogKind.IMAGE),
        mediaProbe(MediaCatalogKind.VIDEO),
        mediaProbe(MediaCatalogKind.AUDIO),
        PublicPhoneCapabilityProbe(
            capability = "camera.open_capture_ui",
            state = if (cameraSupported()) {
                PublicPhoneCapabilityState.AVAILABLE
            } else {
                PublicPhoneCapabilityState.UNSUPPORTED
            },
            limitationCode = "capture_completion_requires_activity_result_ui_integration",
        ),
        PublicPhoneCapabilityProbe(
            capability = "notifications.reply",
            state = if (notificationReplies.isAvailable()) {
                PublicPhoneCapabilityState.AVAILABLE
            } else {
                PublicPhoneCapabilityState.SPECIAL_ACCESS_REQUIRED
            },
            limitationCode = "notification_listener_must_be_connected",
        ),
    )

    override fun searchContacts(
        query: String,
        limit: Int,
    ): PublicPhonePlatformResult<List<ContactSummary>> {
        permissionFailure(Manifest.permission.READ_CONTACTS)?.let { return it }
        val cleanQuery = PublicPhoneBounds.cleanUntrusted(query, 512)
        if (cleanQuery.isBlank()) return PublicPhonePlatformResult.Failure("invalid_contact_query")
        val boundedLimit = limit.coerceIn(1, PublicPhoneBounds.MAX_CONTACT_RESULTS)
        return guarded("contacts_query_failed") {
            val uri = ContactsContract.Contacts.CONTENT_FILTER_URI.buildUpon()
                .appendPath(cleanQuery)
                .build()
            resolver.query(
                uri,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.STARRED,
                ),
                null,
                null,
                "${ContactsContract.Contacts.STARRED} DESC, " +
                    "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
            ).useCursor { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < boundedLimit) {
                        add(
                            ContactSummary(
                                contactId = cursor.long(ContactsContract.Contacts._ID).toString(),
                                displayName = cursor.clean(
                                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                                    512,
                                ),
                                starred = cursor.int(ContactsContract.Contacts.STARRED) != 0,
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun lookupContact(contactId: Long): PublicPhonePlatformResult<ContactDetail> {
        permissionFailure(Manifest.permission.READ_CONTACTS)?.let { return it }
        if (contactId <= 0) return PublicPhonePlatformResult.Failure("invalid_contact_id")
        return guarded("contact_lookup_failed") {
            val contact = resolver.query(
                ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.STARRED,
                ),
                null,
                null,
                null,
            ).useCursor { cursor ->
                if (!cursor.moveToFirst()) return@useCursor null
                Triple(
                    cursor.long(ContactsContract.Contacts._ID).toString(),
                    cursor.clean(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, 512),
                    cursor.int(ContactsContract.Contacts.STARRED) != 0,
                )
            } ?: fail("contact_not_found")
            ContactDetail(
                contactId = contact.first,
                displayName = contact.second,
                starred = contact.third,
                phoneNumbers = contactValues(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                    contactId,
                ),
                emailAddresses = contactValues(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                    ContactsContract.CommonDataKinds.Email.LABEL,
                    contactId,
                ),
            )
        }
    }

    override fun readCalendar(
        startEpochMillis: Long,
        endEpochMillis: Long,
        limit: Int,
    ): PublicPhonePlatformResult<List<CalendarInstance>> {
        permissionFailure(Manifest.permission.READ_CALENDAR)?.let { return it }
        if (
            startEpochMillis < 0 || endEpochMillis <= startEpochMillis ||
            endEpochMillis - startEpochMillis > PublicPhoneBounds.MAX_CALENDAR_WINDOW_MILLIS
        ) {
            return PublicPhonePlatformResult.Failure("invalid_calendar_window")
        }
        val boundedLimit = limit.coerceIn(1, PublicPhoneBounds.MAX_CALENDAR_RESULTS)
        return guarded("calendar_query_failed") {
            val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(uriBuilder, startEpochMillis)
            ContentUris.appendId(uriBuilder, endEpochMillis)
            resolver.query(
                uriBuilder.build(),
                arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.CALENDAR_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.ORGANIZER,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.STATUS,
                ),
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            ).useCursor { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < boundedLimit) {
                        add(
                            CalendarInstance(
                                eventId = cursor.long(CalendarContract.Instances.EVENT_ID).toString(),
                                calendarId = cursor.long(
                                    CalendarContract.Instances.CALENDAR_ID,
                                ).toString(),
                                title = cursor.clean(CalendarContract.Instances.TITLE, 1_024),
                                location = cursor.clean(
                                    CalendarContract.Instances.EVENT_LOCATION,
                                    1_024,
                                ),
                                organizer = cursor.clean(
                                    CalendarContract.Instances.ORGANIZER,
                                    512,
                                ),
                                beginEpochMillis = cursor.long(CalendarContract.Instances.BEGIN),
                                endEpochMillis = cursor.long(CalendarContract.Instances.END),
                                allDay = cursor.int(CalendarContract.Instances.ALL_DAY) != 0,
                                status = cursor.int(CalendarContract.Instances.STATUS),
                            ),
                        )
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun readLocation(
        mode: LocationReadMode,
        timeoutMillis: Long,
    ): PublicPhonePlatformResult<PhoneLocation> {
        if (!hasAnyLocationPermission()) {
            return PublicPhonePlatformResult.Failure("permission_required_location")
        }
        val manager = appContext.getSystemService(LocationManager::class.java)
            ?: return PublicPhonePlatformResult.Failure("location_service_unavailable")
        val providers = guardedValue { manager.getProviders(true) }.orEmpty()
        if (providers.isEmpty()) return PublicPhonePlatformResult.Failure("location_disabled")
        return when (mode) {
            LocationReadMode.LAST_KNOWN -> {
                val newest = providers.mapNotNull { provider ->
                    guardedValue { manager.getLastKnownLocation(provider) }
                }.maxByOrNull(Location::getElapsedRealtimeNanos)
                    ?: return PublicPhonePlatformResult.Failure("last_location_unavailable")
                PublicPhonePlatformResult.Success(newest.toPhoneLocation(current = false))
            }
            LocationReadMode.CURRENT -> currentLocation(
                manager,
                providers,
                timeoutMillis.coerceIn(250L, PublicPhoneBounds.MAX_LOCATION_TIMEOUT_MILLIS),
            )
        }
    }

    override fun readSensors(
        types: Set<PublicSensorType>,
        timeoutMillis: Long,
    ): PublicPhonePlatformResult<List<SensorReading>> {
        if (types.isEmpty() || types.size > PublicPhoneBounds.MAX_SENSOR_TYPES) {
            return PublicPhonePlatformResult.Failure("invalid_sensor_types")
        }
        val manager = appContext.getSystemService(SensorManager::class.java)
            ?: return PublicPhonePlatformResult.Failure("sensor_service_unavailable")
        val sensors = types.mapNotNull { type ->
            manager.getDefaultSensor(type.androidType())?.let { sensor -> type to sensor }
        }
        if (sensors.isEmpty()) return PublicPhonePlatformResult.Failure("requested_sensors_unavailable")
        val thread = HandlerThread("hans-sensor-snapshot").apply { start() }
        val readings = mutableMapOf<PublicSensorType, SensorReading>()
        val latch = CountDownLatch(sensors.size)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val type = sensors.firstOrNull { it.second.type == event.sensor.type }?.first ?: return
                synchronized(readings) {
                    if (readings.containsKey(type)) return
                    readings[type] = SensorReading(
                        type = type,
                        values = event.values.take(MAX_SENSOR_VALUES),
                        accuracy = event.accuracy,
                        timestampNanos = event.timestamp,
                    )
                    latch.countDown()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        return try {
            val handler = Handler(thread.looper)
            sensors.forEach { (_, sensor) ->
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
            }
            latch.await(
                timeoutMillis.coerceIn(100L, PublicPhoneBounds.MAX_SENSOR_TIMEOUT_MILLIS),
                TimeUnit.MILLISECONDS,
            )
            val snapshot = synchronized(readings) {
                types.mapNotNull(readings::get)
            }
            if (snapshot.isEmpty()) {
                PublicPhonePlatformResult.Failure("sensor_snapshot_timed_out")
            } else {
                PublicPhonePlatformResult.Success(snapshot)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            PublicPhonePlatformResult.Failure("sensor_snapshot_interrupted")
        } catch (_: SecurityException) {
            PublicPhonePlatformResult.Failure("sensor_security_rejection")
        } catch (_: RuntimeException) {
            PublicPhonePlatformResult.Failure("sensor_snapshot_failed")
        } finally {
            runCatching { manager.unregisterListener(listener) }
            thread.quitSafely()
        }
    }

    override fun listMedia(
        kinds: Set<MediaCatalogKind>,
        afterEpochMillis: Long?,
        limit: Int,
    ): PublicPhonePlatformResult<List<MediaCatalogItem>> {
        if (kinds.isEmpty() || afterEpochMillis?.let { it < 0 } == true) {
            return PublicPhonePlatformResult.Failure("invalid_media_query")
        }
        kinds.forEach { kind ->
            mediaPermissionFailure(kind)?.let { return it }
        }
        val boundedLimit = limit.coerceIn(1, PublicPhoneBounds.MAX_MEDIA_RESULTS)
        return guarded("media_query_failed") {
            kinds.flatMap { kind -> queryMediaKind(kind, afterEpochMillis, boundedLimit) }
                .sortedByDescending { it.dateAddedEpochMillis ?: 0L }
                .take(boundedLimit)
        }
    }

    override fun openCamera(
        mode: CameraCaptureMode,
    ): PublicPhonePlatformResult<UserVisibleDispatch> {
        val action = when (mode) {
            CameraCaptureMode.PHOTO -> MediaStore.ACTION_IMAGE_CAPTURE
            CameraCaptureMode.VIDEO -> MediaStore.ACTION_VIDEO_CAPTURE
        }
        return dispatchUserVisible(
            Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            "camera_ui_opened_capture_result_not_observed",
        )
    }

    override fun prepareCalendarEvent(
        draft: CalendarEventDraft,
    ): PublicPhonePlatformResult<UserVisibleDispatch> {
        validateDraft(draft)?.let { return PublicPhonePlatformResult.Failure(it) }
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, draft.startEpochMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, draft.endEpochMillis)
            .putExtra(CalendarContract.Events.TITLE, draft.title)
            .putExtra(CalendarContract.Events.ALL_DAY, draft.allDay)
        draft.location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        draft.description?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
        return dispatchUserVisible(intent, "calendar_editor_opened_save_not_observed")
    }

    override fun createCalendarEvent(
        draft: CalendarEventDraft,
    ): PublicPhonePlatformResult<CreatedCalendarEvent> {
        permissionFailure(Manifest.permission.WRITE_CALENDAR)?.let { return it }
        validateDraft(draft)?.let { return PublicPhonePlatformResult.Failure(it) }
        val calendarId = draft.calendarId
            ?: return PublicPhonePlatformResult.Failure("calendar_id_required")
        if (calendarId <= 0) return PublicPhonePlatformResult.Failure("invalid_calendar_id")
        if (hasPermission(Manifest.permission.READ_CALENDAR) && !calendarIsWritable(calendarId)) {
            return PublicPhonePlatformResult.Failure("calendar_not_writable")
        }
        return guarded("calendar_create_failed") {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, draft.title)
                put(CalendarContract.Events.DTSTART, draft.startEpochMillis)
                put(CalendarContract.Events.DTEND, draft.endEpochMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, draft.timeZoneId)
                put(CalendarContract.Events.ALL_DAY, if (draft.allDay) 1 else 0)
                draft.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                draft.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            }
            val inserted = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: fail("calendar_insert_rejected")
            val id = runCatching { ContentUris.parseId(inserted) }.getOrNull()
                ?.takeIf { it > 0 }
                ?: fail("calendar_insert_invalid_uri")
            CreatedCalendarEvent(
                eventId = id.toString(),
                verifiedReadable = hasPermission(Manifest.permission.READ_CALENDAR) &&
                    eventExists(id),
            )
        }
    }

    override fun listReplyableNotifications(
        limit: Int,
    ): PublicPhonePlatformResult<List<ReplyableNotification>> = notificationReplies.list(limit)

    override fun replyToNotification(
        replyToken: String,
        actionIndex: Int,
        message: String,
    ): PublicPhonePlatformResult<NotificationReplyDispatch> = notificationReplies.reply(
        appContext,
        replyToken,
        actionIndex,
        message,
    )

    private fun contactValues(
        uri: Uri,
        contactIdColumn: String,
        valueColumn: String,
        labelColumn: String,
        contactId: Long,
    ): List<LabeledValue> = resolver.query(
        uri,
        arrayOf(valueColumn, labelColumn),
        "$contactIdColumn = ?",
        arrayOf(contactId.toString()),
        null,
    ).useCursor { cursor ->
        buildList {
            while (cursor.moveToNext() && size < MAX_CONTACT_VALUES) {
                val value = cursor.clean(valueColumn, 1_024)
                if (value.isNotBlank()) {
                    add(LabeledValue(cursor.clean(labelColumn, 256), value))
                }
            }
        }.distinctBy { it.value }
    }

    @SuppressLint("MissingPermission")
    private fun currentLocation(
        manager: LocationManager,
        providers: List<String>,
        timeoutMillis: Long,
    ): PublicPhonePlatformResult<PhoneLocation> {
        val orderedProviders = providers.sortedBy { provider ->
            when (provider) {
                LocationManager.FUSED_PROVIDER -> 0
                LocationManager.GPS_PROVIDER -> 1
                LocationManager.NETWORK_PROVIDER -> 2
                else -> 3
            }
        }.take(MAX_LOCATION_PROVIDERS)
        val result = AtomicReference<Location?>(null)
        val latch = CountDownLatch(1)
        val cancellation = CancellationSignal()
        val direct = Executor(Runnable::run)
        var requested = 0
        orderedProviders.forEach { provider ->
            try {
                requested += 1
                manager.getCurrentLocation(provider, cancellation, direct) { location ->
                    if (location != null && result.compareAndSet(null, location)) {
                        cancellation.cancel()
                        latch.countDown()
                    }
                }
            } catch (_: IllegalArgumentException) {
                Unit
            } catch (_: SecurityException) {
                return PublicPhonePlatformResult.Failure("location_security_rejection")
            } catch (_: RuntimeException) {
                Unit
            }
        }
        if (requested == 0) return PublicPhonePlatformResult.Failure("current_location_unavailable")
        return try {
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
            cancellation.cancel()
            result.get()?.let { PublicPhonePlatformResult.Success(it.toPhoneLocation(true)) }
                ?: PublicPhonePlatformResult.Failure("current_location_timed_out")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            cancellation.cancel()
            PublicPhonePlatformResult.Failure("current_location_interrupted")
        }
    }

    private fun queryMediaKind(
        kind: MediaCatalogKind,
        afterEpochMillis: Long?,
        limit: Int,
    ): List<MediaCatalogItem> {
        val collection = when (kind) {
            MediaCatalogKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaCatalogKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaCatalogKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (kind != MediaCatalogKind.AUDIO) {
                add(MediaStore.MediaColumns.WIDTH)
                add(MediaStore.MediaColumns.HEIGHT)
            }
            if (kind != MediaCatalogKind.IMAGE) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()
        val queryArgs = Bundle().apply {
            afterEpochMillis?.let { after ->
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DATE_ADDED} >= ?")
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf((after / 1_000L).toString()),
                )
            }
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.MediaColumns.DATE_ADDED),
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        return resolver.query(collection, projection, queryArgs, null).useCursor { cursor ->
            buildList {
                while (cursor.moveToNext() && size < limit) {
                    val id = cursor.long(MediaStore.MediaColumns._ID)
                    add(
                        MediaCatalogItem(
                            kind = kind,
                            contentUri = ContentUris.withAppendedId(collection, id).toString(),
                            displayName = cursor.clean(MediaStore.MediaColumns.DISPLAY_NAME, 512),
                            mimeType = cursor.clean(MediaStore.MediaColumns.MIME_TYPE, 128),
                            sizeBytes = cursor.nullableLong(MediaStore.MediaColumns.SIZE),
                            dateAddedEpochMillis = cursor.nullableLong(
                                MediaStore.MediaColumns.DATE_ADDED,
                            )?.times(1_000L),
                            durationMillis = if (kind == MediaCatalogKind.IMAGE) null else {
                                cursor.nullableLong(MediaStore.MediaColumns.DURATION)
                            },
                            width = if (kind == MediaCatalogKind.AUDIO) null else {
                                cursor.nullableInt(MediaStore.MediaColumns.WIDTH)
                            },
                            height = if (kind == MediaCatalogKind.AUDIO) null else {
                                cursor.nullableInt(MediaStore.MediaColumns.HEIGHT)
                            },
                        ),
                    )
                }
            }
        }
    }

    private fun calendarIsWritable(calendarId: Long): Boolean = runCatching {
        resolver.query(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId),
            arrayOf(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            null,
            null,
            null,
        ).useCursor { cursor ->
            cursor.moveToFirst() && cursor.int(
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            ) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
        }
    }.getOrDefault(false)

    private fun eventExists(eventId: Long): Boolean = runCatching {
        resolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events._ID),
            null,
            null,
            null,
        ).useCursor(Cursor::moveToFirst)
    }.getOrDefault(false)

    private fun validateDraft(draft: CalendarEventDraft): String? {
        if (draft.title.isBlank()) return "invalid_event_title"
        if (draft.startEpochMillis < 0 || draft.endEpochMillis <= draft.startEpochMillis) {
            return "invalid_event_time"
        }
        if (draft.endEpochMillis - draft.startEpochMillis > MAX_EVENT_DURATION_MILLIS) {
            return "event_duration_too_long"
        }
        if (runCatching { ZoneId.of(draft.timeZoneId) }.isFailure) {
            return "invalid_event_timezone"
        }
        return null
    }

    private fun dispatchUserVisible(
        intent: Intent,
        limitationCode: String,
    ): PublicPhonePlatformResult<UserVisibleDispatch> {
        val resolved = runCatching { packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) }
            .getOrNull()
            ?: return PublicPhonePlatformResult.Failure("activity_not_resolved")
        if (resolved.activityInfo?.exported != true) {
            return PublicPhonePlatformResult.Failure("activity_not_exported")
        }
        return try {
            appContext.startActivity(intent)
            PublicPhonePlatformResult.Success(
                UserVisibleDispatch(true, completionObserved = false, limitationCode),
            )
        } catch (_: ActivityNotFoundException) {
            PublicPhonePlatformResult.Failure("activity_not_found")
        } catch (_: SecurityException) {
            PublicPhonePlatformResult.Failure("activity_security_rejection")
        } catch (_: RuntimeException) {
            PublicPhonePlatformResult.Failure("activity_dispatch_failed")
        }
    }

    private fun cameraSupported(): Boolean = runCatching {
        listOf(MediaStore.ACTION_IMAGE_CAPTURE, MediaStore.ACTION_VIDEO_CAPTURE).any { action ->
            packageManager.resolveActivity(Intent(action), PackageManager.MATCH_DEFAULT_ONLY) != null
        }
    }.getOrDefault(false)

    private fun locationProbe(): PublicPhoneCapabilityProbe {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)) {
            return PublicPhoneCapabilityProbe(
                "location.read",
                PublicPhoneCapabilityState.UNSUPPORTED,
                limitationCode = "location_feature_unavailable",
            )
        }
        val missing = setOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).filterNot(::hasPermission).toSet()
        return PublicPhoneCapabilityProbe(
            capability = "location.read",
            state = if (missing.size == 2) {
                PublicPhoneCapabilityState.PERMISSION_REQUIRED
            } else {
                PublicPhoneCapabilityState.AVAILABLE
            },
            missingPermissions = if (missing.size == 2) missing else emptySet(),
            limitationCode = if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                "coarse_location_only"
            } else {
                null
            },
        )
    }

    private fun sensorProbe(): PublicPhoneCapabilityProbe {
        val manager = appContext.getSystemService(SensorManager::class.java)
        return PublicPhoneCapabilityProbe(
            capability = "sensors.snapshot",
            state = if (manager?.getSensorList(Sensor.TYPE_ALL).isNullOrEmpty()) {
                PublicPhoneCapabilityState.UNSUPPORTED
            } else {
                PublicPhoneCapabilityState.AVAILABLE
            },
            limitationCode = "one_shot_public_sensors_only",
        )
    }

    private fun mediaProbe(kind: MediaCatalogKind): PublicPhoneCapabilityProbe {
        val missing = mediaRequiredPermissions(kind).filterNot(::hasPermission).toSet()
        val selectedVisualAccess =
            Build.VERSION.SDK_INT >= 34 &&
                kind != MediaCatalogKind.AUDIO &&
                hasPermission(PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED)
        return PublicPhoneCapabilityProbe(
            capability = "media.${kind.wireName}.catalog",
            state = if (missing.isEmpty() || selectedVisualAccess) {
                PublicPhoneCapabilityState.AVAILABLE
            } else {
                PublicPhoneCapabilityState.PERMISSION_REQUIRED
            },
            missingPermissions = if (selectedVisualAccess) emptySet() else missing,
            limitationCode = if (selectedVisualAccess && !hasPermission(mediaPrimaryPermission(kind))) {
                "user_selected_media_only"
            } else {
                "primary_external_volume_catalog_only"
            },
        )
    }

    private fun permissionProbe(
        capability: String,
        permission: String,
    ): PublicPhoneCapabilityProbe = PublicPhoneCapabilityProbe(
        capability = capability,
        state = if (hasPermission(permission)) {
            PublicPhoneCapabilityState.AVAILABLE
        } else {
            PublicPhoneCapabilityState.PERMISSION_REQUIRED
        },
        missingPermissions = if (hasPermission(permission)) emptySet() else setOf(permission),
    )

    private fun mediaPermissionFailure(
        kind: MediaCatalogKind,
    ): PublicPhonePlatformResult.Failure? {
        if (
            Build.VERSION.SDK_INT >= 34 && kind != MediaCatalogKind.AUDIO &&
            hasPermission(PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED)
        ) {
            return null
        }
        return if (mediaRequiredPermissions(kind).any(::hasPermission)) {
            null
        } else {
            PublicPhonePlatformResult.Failure("permission_required_media_${kind.wireName}")
        }
    }

    private fun mediaRequiredPermissions(kind: MediaCatalogKind): Set<String> = if (
        Build.VERSION.SDK_INT >= 33
    ) {
        setOf(mediaPrimaryPermission(kind))
    } else {
        setOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun mediaPrimaryPermission(kind: MediaCatalogKind): String = when (kind) {
        MediaCatalogKind.IMAGE -> PERMISSION_READ_MEDIA_IMAGES
        MediaCatalogKind.VIDEO -> PERMISSION_READ_MEDIA_VIDEO
        MediaCatalogKind.AUDIO -> PERMISSION_READ_MEDIA_AUDIO
    }

    private fun permissionFailure(permission: String): PublicPhonePlatformResult.Failure? =
        if (hasPermission(permission)) null else {
            PublicPhonePlatformResult.Failure("permission_required_${permission.substringAfterLast('.').lowercase()}")
        }

    private fun hasAnyLocationPermission(): Boolean =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasPermission(permission: String): Boolean =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun Location.toPhoneLocation(current: Boolean): PhoneLocation = PhoneLocation(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy.takeIf { hasAccuracy() && it.isFinite() },
        altitudeMeters = altitude.takeIf { hasAltitude() && it.isFinite() },
        observedAtEpochMillis = time,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        source = PublicPhoneBounds.cleanUntrusted(provider, 64),
        currentFix = current,
        mock = isMock,
    )

    private fun PublicSensorType.androidType(): Int = when (this) {
        PublicSensorType.ACCELEROMETER -> Sensor.TYPE_ACCELEROMETER
        PublicSensorType.GYROSCOPE -> Sensor.TYPE_GYROSCOPE
        PublicSensorType.MAGNETIC_FIELD -> Sensor.TYPE_MAGNETIC_FIELD
        PublicSensorType.LIGHT -> Sensor.TYPE_LIGHT
        PublicSensorType.PROXIMITY -> Sensor.TYPE_PROXIMITY
        PublicSensorType.PRESSURE -> Sensor.TYPE_PRESSURE
        PublicSensorType.AMBIENT_TEMPERATURE -> Sensor.TYPE_AMBIENT_TEMPERATURE
        PublicSensorType.RELATIVE_HUMIDITY -> Sensor.TYPE_RELATIVE_HUMIDITY
        PublicSensorType.ROTATION_VECTOR -> Sensor.TYPE_ROTATION_VECTOR
    }

    private inline fun <T> guarded(
        failureCode: String,
        block: () -> T,
    ): PublicPhonePlatformResult<T> = try {
        PublicPhonePlatformResult.Success(block())
    } catch (failure: PlatformFailure) {
        @Suppress("UNCHECKED_CAST")
        failure.result as PublicPhonePlatformResult<T>
    } catch (_: SecurityException) {
        PublicPhonePlatformResult.Failure("android_security_rejection")
    } catch (_: RuntimeException) {
        PublicPhonePlatformResult.Failure(failureCode)
    }

    private fun <T> guardedValue(block: () -> T): T? = runCatching(block).getOrNull()

    private fun fail(code: String): Nothing =
        throw PlatformFailure(PublicPhonePlatformResult.Failure(code))

    private inline fun <T> Cursor?.useCursor(block: (Cursor) -> T): T {
        val cursor = this ?: throw IllegalStateException("provider returned no cursor")
        return cursor.use(block)
    }

    private fun Cursor.index(column: String): Int = getColumnIndexOrThrow(column)
    private fun Cursor.long(column: String): Long = getLong(index(column))
    private fun Cursor.int(column: String): Int = getInt(index(column))
    private fun Cursor.nullableLong(column: String): Long? = index(column).let { index ->
        if (isNull(index)) null else getLong(index)
    }
    private fun Cursor.nullableInt(column: String): Int? = index(column).let { index ->
        if (isNull(index)) null else getInt(index)
    }
    private fun Cursor.clean(column: String, maxBytes: Int): String = index(column).let { index ->
        PublicPhoneBounds.cleanUntrusted(if (isNull(index)) null else getString(index), maxBytes)
    }

    private class PlatformFailure(val result: PublicPhonePlatformResult.Failure) : RuntimeException()

    private companion object {
        const val PERMISSION_READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
        const val PERMISSION_READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
        const val PERMISSION_READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
        const val PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED =
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        const val MAX_CONTACT_VALUES = 32
        const val MAX_SENSOR_VALUES = 8
        const val MAX_LOCATION_PROVIDERS = 4
        const val MAX_EVENT_DURATION_MILLIS = 366L * 24L * 60L * 60L * 1_000L
    }
}
