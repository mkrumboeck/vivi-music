package at.techtown.sonoscontroller

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JavaAdapterAndroidTest {
    @Test fun closedAdapterReportsTypedErrorOnMainThread() = runBlocking {
        val adapter = SonosControllerJava(InstrumentationRegistry.getInstrumentation().targetContext)
        val closed = CompletableDeferred<Unit>()
        adapter.close(object : SonosControllerJava.Callback<Void?> {
            override fun onSuccess(value: Void?) { closed.complete(Unit) }
            override fun onError(error: SonosException) { closed.completeExceptionally(error) }
        })
        withTimeout(5000) { closed.await() }
        val error = CompletableDeferred<SonosErrorCode>()
        adapter.discover(object : SonosControllerJava.Callback<List<SonosDevice>> {
            override fun onSuccess(value: List<SonosDevice>) { error.completeExceptionally(AssertionError("Already closed")) }
            override fun onError(failure: SonosException) {
                if (Looper.myLooper() != Looper.getMainLooper())
                    error.completeExceptionally(AssertionError("Callback not on main thread"))
                else error.complete(failure.code)
            }
        })
        assertEquals(SonosErrorCode.CLOSED, withTimeout(5000) { error.await() })
    }
}
