package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.JarvisModelOption
import com.example.data.configureAppCheck
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FirebaseAiDiagnosticTest {

    @Test
    fun runFirebaseAiLogicConnectionDiagnostic() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Firebase Initialization Check
        configureAppCheck(context, null)
        val app = FirebaseApp.getInstance()
        val options = app.options
        println("DIAG_FIREBASE_INIT: SUCCESS")
        println("DIAG_PROJECT_ID: ${options.projectId}")
        println("DIAG_MOBILE_SDK_APP_ID: ${options.applicationId}")
        println("DIAG_PACKAGE_NAME: ${context.packageName}")

        assertNotNull("FirebaseApp should be initialized", app)
        assertEquals("gen-lang-client-0098227654", options.projectId)

        // 2. App Check Token Verification Check
        var appCheckSuccess = false
        try {
            val tokenResult = withContext(Dispatchers.IO) {
                Tasks.await(
                    FirebaseAppCheck.getInstance().getAppCheckToken(false),
                    20,
                    TimeUnit.SECONDS
                )
            }
            val tokenPreview = tokenResult.token.take(16)
            println("DIAG_APP_CHECK_TOKEN: SUCCESS (len=${tokenResult.token.length}, prefix=$tokenPreview..., exp=${tokenResult.expireTimeMillis})")
            appCheckSuccess = tokenResult.token.isNotBlank()
        } catch (e: Exception) {
            val rootCause = e.cause ?: e
            println("DIAG_APP_CHECK_TOKEN: FAILED -> ${rootCause::class.java.simpleName}: ${rootCause.message}")
        }

        // 3. Gemini Response Connectivity Check
        val modelId = JarvisModelOption.default.modelId
        println("DIAG_GEMINI_MODEL: $modelId")
        try {
            val response = withTimeout(30_000L) {
                Firebase.ai.generativeModel(modelId)
                    .generateContent("Diagnostic check: reply with 'ONLINE'.")
            }
            println("DIAG_GEMINI_RESPONSE: SUCCESS -> ${response.text}")
        } catch (e: Exception) {
            println("DIAG_GEMINI_RESPONSE: FAILED -> ${e::class.java.simpleName}: ${e.message}")
            e.cause?.let { cause ->
                println("DIAG_GEMINI_CAUSE: ${cause::class.java.simpleName}: ${cause.message}")
            }
        }

        println("DIAG_SUMMARY: init=true, appCheckToken=$appCheckSuccess")
    }
}
