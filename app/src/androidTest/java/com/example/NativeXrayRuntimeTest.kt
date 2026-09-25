package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeXrayRuntimeTest {
    @Test fun bundledLibXrayLoadsAndAnswersVersionRequest() {
        val api = Class.forName("libXray.LibXray")
        val invoke = api.methods.single { it.name.equals("invoke", ignoreCase = true) && it.parameterTypes.contentEquals(arrayOf(String::class.java)) }
        val responseText = invoke.invoke(null, """{"apiVersion":3,"method":"xrayVersion","payload":{}}""") as String
        val response = JSONObject(responseText)
        assertTrue(response.optString("error"), response.optBoolean("success"))
        assertFalse(response.optJSONObject("data")?.optString("version").isNullOrBlank())

        val controller = Class.forName("libXray.DialerController")
        assertTrue(controller.isInterface)
        assertTrue(controller.methods.any { it.name.equals("protectFd", ignoreCase = true) && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) })
    }
}
