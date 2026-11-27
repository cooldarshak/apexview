package app.apexview.scene

import android.graphics.Color
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Synthetic CLEAR frames only. Secure output must be accepted on a real protected stream. */
@RunWith(AndroidJUnit4::class)
class SceneAsyncTextureLatchAndroidTest {
    @Test fun sharedTextureSamplesExactFrameAndLatchesWithoutRenderLoop() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0))
        var context = EGL14.EGL_NO_CONTEXT
        var output = EGL14.EGL_NO_SURFACE
        var owner: SceneAsyncTextureLatch? = null
        var producer: Surface? = null
        var texture = 0
        var program = 0
        try {
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE), 0, configs, 0, 1, count, 0))
            assumeTrue(count[0] > 0)
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT)
            output = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
            check(output != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, output, output, context))
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texture = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            for (param in intArrayOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER))
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, param, GLES20.GL_NEAREST)
            for (param in intArrayOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T))
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, param, GLES20.GL_CLAMP_TO_EDGE)
            owner = SceneAsyncTextureLatch.tryCreate(display, config, context, texture, false,
                Handler(Looper.getMainLooper()))
            assumeTrue("Device does not expose independent shared EGL fence admission", owner != null)
            val latch = requireNotNull(owner)
            val events = LinkedBlockingQueue<SceneAsyncTextureLatch.Event>()
            val failure = AtomicReference<Exception?>()
            latch.frames.setDefaultBufferSize(16, 16)
            latch.start({ events.offer(it) }, {}, { failure.set(it) })
            producer = Surface(latch.frames)
            val input = requireNotNull(producer)
            fun push(color: Int): SceneAsyncTextureLatch.Event {
                val canvas = input.lockCanvas(null)
                try { canvas.drawColor(color) } finally { input.unlockCanvasAndPost(canvas) }
                val event = events.poll(5, TimeUnit.SECONDS)
                assertNull(failure.get())
                return requireNotNull(event) { "Independent latch did not progress" }
            }
            fun shader(type: Int, source: String): Int {
                val id = GLES20.glCreateShader(type)
                GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { GLES20.glGetShaderInfoLog(id) }
                return id
            }
            val vert = shader(GLES20.GL_VERTEX_SHADER,
                "attribute vec2 p; void main(){gl_Position=vec4(p,0.0,1.0);}")
            val frag = shader(GLES20.GL_FRAGMENT_SHADER,
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES tex; void main(){gl_FragColor=texture2D(tex,vec2(0.5,0.5));}")
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vert); GLES20.glAttachShader(program, frag)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vert); GLES20.glDeleteShader(frag)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
            val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vertices.put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f)).position(0)
            fun sample(event: SceneAsyncTextureLatch.Event, red: Boolean) {
                val sampledAt = latch.sample(FloatArray(16)) {
                    GLES20.glViewport(0,0,16,16)
                    GLES20.glUseProgram(program)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"tex"),0)
                    val position = GLES20.glGetAttribLocation(program,"p")
                    GLES20.glEnableVertexAttribArray(position)
                    GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,vertices)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
                    GLES20.glDisableVertexAttribArray(position)
                }
                assertEquals(event.latchedNs,sampledAt)
                val pixel = ByteBuffer.allocateDirect(4)
                GLES20.glReadPixels(8,8,1,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixel)
                val r = pixel.get(0).toInt() and 255
                val g = pixel.get(1).toInt() and 255
                assertTrue("Sampled incorrect shared texture: r=$r g=$g",
                    if(red) r > 200 && g < 40 else g > 200 && r < 40)
                assertEquals(GLES20.GL_NO_ERROR,GLES20.glGetError())
            }
            val first = push(Color.RED)
            sample(first,true)
            // No renderer callback or window swap is needed to receive the next image.
            val second = push(Color.GREEN)
            assertTrue(second.latchedNs > first.latchedNs)
            sample(second,false)
            latch.discard()
            assertEquals(0L,latch.sample(FloatArray(16)) { error("discarded image drawn") })
            assertTrue(latch.takeStats().latches >= 2)
        } finally {
            try { producer?.release() } finally {
                try { owner?.close() } finally {
                    if (program != 0) GLES20.glDeleteProgram(program)
                    if (texture != 0) GLES20.glDeleteTextures(1,intArrayOf(texture),0)
                    EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
                    if (output != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,output)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context)
                    EGL14.eglTerminate(display)
                    EGL14.eglReleaseThread()
                }
            }
        }
    }
}
