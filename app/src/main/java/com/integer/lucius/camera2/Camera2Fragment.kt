package com.integer.lucius.camera2

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.app.ActivityCompat
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.util.Log
import android.view.*
import android.widget.Button
import com.example.android.camera2basic.CompareSizesByArea
import com.integer.lucius.camera2.dialog.ConfirmationDialog
import com.integer.lucius.camera2.dialog.ErrorDialog
import kotlinx.android.synthetic.main.fragment_camera2.*
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * @detail
 *
 * @author luciuszhang
 * @date 2018/9/19 15:30
 */
class Camera2Fragment: Fragment(), ActivityCompat.OnRequestPermissionsResultCallback {


    companion object {
        private const val TAG = "Camera2Fragment"
        @JvmStatic fun newInstance(): Camera2Fragment = Camera2Fragment()
    }

    private var dataing: Boolean = false
    private lateinit var textureView: TextureView
    private lateinit var button: Button

    /**
     * A [Semaphore] to prevent the app from exiting before closing the camera.
     */
    private val cameraOpenCloseLock = Semaphore(1)


    /** A [Handler] for running tasks in the background. */
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    /** 0为前置摄像头， 1为后置摄像头 */
    private var cameraIds: Array<String?> =  arrayOfNulls(2)
    private var currentCameraId: String? = ""

    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var previewSession: CameraCaptureSession? = null
    private var recordSession: CameraCaptureSession? = null
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private lateinit var recordRequestBuilder: CaptureRequest.Builder
    private lateinit var previewRequest: CaptureRequest
    private lateinit var recordRequest: CaptureRequest


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View?  =
            inflater.inflate(R.layout.fragment_camera2, container, false)


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        textureView = capture_preview_view
        button = btn_start_data
        button.setOnClickListener {
            if (dataing) {
                closeRecordSession()
            } else {
                createCameraRecordSession()
            }
        }
    }


    override fun onResume() {
        super.onResume()
        if (!requestPermission()) return
        prepareThread()
        detectionCamera()
        calculateCameraParameters()
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopThread()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.size != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ErrorDialog.newInstance(getString(R.string.request_permission))
                        .show(childFragmentManager, "request permission camera")
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    /** 请求权限 */
    private fun requestPermission(): Boolean {
        val context = activity
        return if (context == null) {
            false
        } else {
            val permission = ContextCompat.checkSelfPermission(context.applicationContext, Manifest.permission.CAMERA)
            if (permission != PackageManager.PERMISSION_GRANTED) {
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    ConfirmationDialog().show(childFragmentManager, "request camera")
                } else {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
                }
                false
            } else {
                true
            }
        }
    }

    /** 准备工作线程 */
    private fun prepareThread() {
        backgroundThread = HandlerThread("CaptureV2").also { it.start() }
        backgroundHandler = Handler(backgroundThread?.looper)
    }

    /** 检测摄像头 */
    private fun detectionCamera() {
        val manager = activity?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for (cameraId in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (cameraDirection == CameraCharacteristics.LENS_FACING_FRONT) {
                cameraIds[0] = cameraId
            } else if (cameraDirection == CameraCharacteristics.LENS_FACING_BACK) {
                cameraIds[1] = cameraId
            }
        }
        currentCameraId = cameraIds[1]
    }

    /** 根据当前摄像头计算所需参数 */
    private fun calculateCameraParameters() {
        val context = activity
        if (context != null) {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics: CameraCharacteristics = manager.getCameraCharacteristics(currentCameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val largest = Collections.max(Arrays.asList(*map.getOutputSizes(ImageFormat.YUV_420_888)), CompareSizesByArea())
            imageReader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.YUV_420_888, /*maxImages*/ 2).apply {
                setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
            }
        } else {
            Log.d(TAG, "context is null")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        val manager = activity?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            // Wait for camera to open - 2.5 seconds is sufficient
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            manager.openCamera(currentCameraId, deviceStateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    /** 创建camera preview会话  */
    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture
            val surface = Surface(texture)
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(surface)
            /** camera数据发送到两个surface上 */
            cameraDevice?.createCaptureSession(Arrays.asList(surface), captureStateCallback, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 创建录制会话 */
    private fun createCameraRecordSession() {
        try {
            closePreviewSession()
            dataing = true
            val texture = textureView.surfaceTexture
            val surface = Surface(texture)
            recordRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            recordRequestBuilder.addTarget(surface)
            recordRequestBuilder.addTarget(imageReader?.surface)
            /** camera数据发送到两个surface上 */
            cameraDevice?.createCaptureSession(Arrays.asList(surface, imageReader?.surface), recordSessionStateCallback, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun closePreviewSession() {
        previewSession?.close()
        previewSession = null
    }

    private fun closeRecordSession() {
        dataing = false
        createCameraPreviewSession()
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            previewSession?.close()
            previewSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun stopThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }

    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture?, width: Int, height: Int) {
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture?) = Unit

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture?): Boolean = true

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture?, width: Int, height: Int) {
            openCamera(width, height)
        }

    }

    /** YUV_420_888数据接收 */
    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        // TODO handle YUV_420_888
        backgroundHandler?.post {
            val threadName = Thread.currentThread().name
            val image = reader?.acquireNextImage()
            if (image != null) {

                /** Y */
                val bufferY = image.planes[0].buffer
                val bufferYSize = bufferY.remaining()
                /** U(Cb) */
                val bufferU = image.planes[1].buffer
                val bufferUSize = bufferU.remaining()
                /** V(Cr) */
                val bufferV = image.planes[2].buffer
                val bufferVSize = bufferV.remaining()

                /** YUV数据集合 */
                val data = ByteArray(bufferYSize + bufferUSize + bufferVSize)
                bufferY.get(data, 0, bufferYSize)
                bufferU.get(data, bufferYSize, bufferUSize)
                bufferV.get(data, bufferYSize + bufferUSize, bufferUSize)

                Log.d(TAG, "data size = " + data.size + "; $threadName")
                image.close()
            } else {
                Log.d(TAG, "image is null")
            }
        }

    }

    private val deviceStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@Camera2Fragment.cameraDevice = cameraDevice
            createCameraPreviewSession()
        }
        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@Camera2Fragment.cameraDevice = null
        }
        override fun onError(cameraDevice: CameraDevice, error: Int) {
            onDisconnected(cameraDevice)
            this@Camera2Fragment.activity?.finish()
        }
    }

    private val captureStateCallback = object : CameraCaptureSession.StateCallback() {

        /** camera设置完成，会话创建成功，在此处开始请求 */
        override fun onConfigured(session: CameraCaptureSession?) {
            if (cameraDevice == null) {
                return
            } else {
            }
            previewSession = session
            try {
                /** 自动对焦模式是continuous */
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                /** 闪光灯自动模式 */
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                /** 创建请求 */
                previewRequest = previewRequestBuilder.build()
                /** 开始请求 */
                previewSession?.setRepeatingRequest(previewRequest, null, backgroundHandler)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onConfigureFailed(session: CameraCaptureSession?) {
            Log.d(TAG, "onConfigureFailed ===========================")
        }
    }

    private val recordSessionStateCallback = object : CameraCaptureSession.StateCallback() {

        /** camera设置完成，会话创建成功，在此处开始请求 */
        override fun onConfigured(session: CameraCaptureSession?) {
            if (cameraDevice == null) {
                return
            } else {
            }
            recordSession = session
            try {
                /** 自动对焦模式是continuous */
                recordRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                /** 闪光灯自动模式 */
                recordRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                /** 创建请求 */
                recordRequest = recordRequestBuilder.build()
                /** 开始请求 */
                recordSession?.setRepeatingRequest(recordRequest, null, backgroundHandler)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onConfigureFailed(session: CameraCaptureSession?) {
            Log.d(TAG, "onConfigureFailed ===========================")
        }
    }

}