package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.UUID

object FirebaseManager {
    private const val TAG = "FirebaseManager"

    // Configuration from user
    private const val API_KEY = "AIzaSyAS41FhDwmR5YR6B6lMiuby09mPfEY5htA"
    private const val PROJECT_ID = "sma-asisi"
    private const val STORAGE_BUCKET = "sma-asisi.firebasestorage.app"
    private const val MESSAGING_SENDER_ID = "794234430869"
    private const val APP_ID = "1:794234430869:web:1bf8f6ef607c3f91da029c"

    private var isInitialized = false
    private var firestore: FirebaseFirestore? = null
    var sessionId: String = UUID.randomUUID().toString()
        private set

    private var configListener: ListenerRegistration? = null

    /**
     * Initializes Firebase App programmatically with the provided credentials.
     */
    fun initialize(context: Context) {
        if (isInitialized) return

        try {
            val existingApps = FirebaseApp.getApps(context)
            if (existingApps.isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApiKey(API_KEY)
                    .setProjectId(PROJECT_ID)
                    .setStorageBucket(STORAGE_BUCKET)
                    .setGcmSenderId(MESSAGING_SENDER_ID)
                    .setApplicationId(APP_ID)
                    .build()

                FirebaseApp.initializeApp(context, options)
                Log.d(TAG, "FirebaseApp initialized successfully with sma-asisi config.")
            } else {
                Log.d(TAG, "FirebaseApp already initialized.")
            }

            firestore = FirebaseFirestore.getInstance()
            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase: ${e.message}", e)
        }
    }

    /**
     * Records the start of an exam session in Firestore under 'exam_sessions'.
     */
    @SuppressLint("HardwareIds")
    fun recordExamSessionStarted(context: Context, examUrl: String) {
        val db = firestore ?: return

        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
        } catch (e: Exception) {
            "UNKNOWN"
        }

        val sessionData = hashMapOf(
            "sessionId" to sessionId,
            "deviceId" to androidId,
            "deviceBrand" to Build.MANUFACTURER,
            "deviceModel" to Build.MODEL,
            "androidVersion" to Build.VERSION.RELEASE,
            "sdkInt" to Build.VERSION.SDK_INT,
            "examUrl" to examUrl,
            "status" to "ACTIVE",
            "violationCount" to 0,
            "startedAt" to FieldValue.serverTimestamp(),
            "lastActiveAt" to FieldValue.serverTimestamp()
        )

        db.collection("exam_sessions")
            .document(sessionId)
            .set(sessionData)
            .addOnSuccessListener {
                Log.d(TAG, "Exam session logged to Firestore: $sessionId")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Could not log exam session to Firestore: ${e.message}")
            }
    }

    /**
     * Records a violation attempt in Firestore under 'exam_sessions/{sessionId}/violations'
     * and updates the summary violation counter.
     */
    fun recordViolation(violationNumber: Int, reason: String) {
        val db = firestore ?: return

        val violationData = hashMapOf(
            "violationNumber" to violationNumber,
            "reason" to reason,
            "timestamp" to FieldValue.serverTimestamp()
        )

        val sessionRef = db.collection("exam_sessions").document(sessionId)

        // 1. Add violation detail
        sessionRef.collection("violations")
            .add(violationData)
            .addOnSuccessListener {
                Log.d(TAG, "Violation #$violationNumber logged.")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to log violation detail: ${e.message}")
            }

        // 2. Update session status & counter
        sessionRef.update(
            mapOf(
                "violationCount" to violationNumber,
                "lastViolationReason" to reason,
                "lastActiveAt" to FieldValue.serverTimestamp()
            )
        ).addOnFailureListener { e ->
            Log.w(TAG, "Failed to update session violation count: ${e.message}")
        }
    }

    /**
     * Updates exam session status when completed or disqualified.
     */
    fun recordExamFinished(status: String) {
        val db = firestore ?: return

        db.collection("exam_sessions")
            .document(sessionId)
            .update(
                mapOf(
                    "status" to status,
                    "finishedAt" to FieldValue.serverTimestamp()
                )
            )
            .addOnSuccessListener {
                Log.d(TAG, "Exam session finished with status: $status")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to update exam finished status: ${e.message}")
            }
    }

    /**
     * Listens for remote configuration changes (e.g. dynamic exam URL or admin broadcast message).
     */
    fun listenExamConfig(onRemoteUrlUpdate: (String) -> Unit) {
        val db = firestore ?: return

        try {
            configListener = db.collection("exam_config")
                .document("settings")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Listen failed: ${error.message}")
                        return@addSnapshotListener
                    }

                    if (snapshot != null && snapshot.exists()) {
                        val remoteUrl = snapshot.getString("examUrl")
                        if (!remoteUrl.isNullOrBlank()) {
                            onRemoteUrlUpdate(remoteUrl)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Remote config listener error: ${e.message}")
        }
    }

    fun cleanup() {
        configListener?.remove()
        configListener = null
    }
}
