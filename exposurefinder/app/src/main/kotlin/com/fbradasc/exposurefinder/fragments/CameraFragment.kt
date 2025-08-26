package com.fbradasc.exposurefinder.fragments

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.View
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import coil.load
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation
import com.fbradasc.exposurefinder.R
import com.fbradasc.exposurefinder.databinding.FragmentCameraBinding
import com.fbradasc.exposurefinder.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutionException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ln
import kotlin.math.exp

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Looper
import android.util.Size
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import kotlin.math.pow

import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.preference.PreferenceManager
import kotlin.math.roundToInt

class CameraFragment : BaseFragment<FragmentCameraBinding>(R.layout.fragment_camera) {
    // An instance for display manager to get display change callbacks
    private val displayManager by lazy { requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }

    // An instance of a helper function to work with Shared Preferences
    private val prefs by lazy { SharedPrefsManager.newInstance(requireContext()) }

    private var preview: Preview? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null

    // A lazy instance of the current fragment's view binding
    override val binding: FragmentCameraBinding by lazy { FragmentCameraBinding.inflate(layoutInflater) }

    private var displayId = -1

    // Selector showing which camera is selected (front or back)
    private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA

    // Selector showing is grid enabled or not
    private var hasGrid = false

    /**
     * A display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        @SuppressLint("UnsafeExperimentalUsageError", "UnsafeOptInUsageError")
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                preview?.targetRotation = view.display.rotation
                imageCapture?.targetRotation = view.display.rotation
                imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hasGrid = prefs.getBoolean(KEY_GRID, false)
        initViews()

        displayManager.registerDisplayListener(displayListener, null)

        binding.run {
            cameraViewFinder.addOnAttachStateChangeListener(object :
                View.OnAttachStateChangeListener {
                override fun onViewDetachedFromWindow(v: View) =
                    displayManager.registerDisplayListener(displayListener, null)

                override fun onViewAttachedToWindow(v: View) =
                    displayManager.unregisterDisplayListener(displayListener)
            })

            btnTakePicture.setOnClickListener { takePicture() }
            bpExposure.setOnClickListener { openSettings() }
            btnGallery.setOnClickListener { openPreview() }
            btnGrid.setOnClickListener { toggleGrid() }
            btnExposure.setOnClickListener { flExposure.visibility = View.VISIBLE }
            flExposure.setOnClickListener { flExposure.visibility = View.GONE }
            btnContrast.setOnClickListener { flContrast.visibility = View.VISIBLE }
            flContrast.setOnClickListener { flContrast.visibility = View.GONE }
            sliderContrast.run {
                valueFrom = 0.2f
                valueTo = 2.0f
                stepSize = 0.2f
                value = 1.0f

                addOnChangeListener { _, value, _ ->
                    contrast = value.toFloat()
                }
            }

            // This swipe gesture adds a fun gesture to switch between video and photo
            val swipeGestures = SwipeGestureDetector().apply {
                setSwipeCallback(
                    right = { openSettings() },
                    left = { openPreview() }
                )
            }
            val gestureDetectorCompat = GestureDetector(requireContext(), swipeGestures)
            cameraViewFinder.setOnTouchListener { _, motionEvent ->
                return@setOnTouchListener !gestureDetectorCompat.onTouchEvent(motionEvent)
            }
        }
    }

    /**
     * Create some initial states
     * */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun initViews() {
        hideSystemUI()
        binding.btnGrid.setImageResource(if (hasGrid) R.drawable.ic_grid_on else R.drawable.ic_grid_off)
        binding.groupGridLines.visibility = if (hasGrid) View.VISIBLE else View.GONE
        adjustInsets()
    }

    /**
     * This methods adds all necessary margins to some views based on window insets and screen orientation
     * */
    private fun adjustInsets() {
        activity?.window?.fitSystemWindows()
        binding.btnTakePicture.onWindowInsets { view, windowInsets ->
            val inset = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                view.bottomMargin = inset.bottom
            } else {
                view.endMargin = inset.right
            }
        }
        binding.btnGrid.onWindowInsets { view, windowInsets ->
            val inset = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                view.topMargin = inset.top
            } else {
                view.startMargin = inset.left
            }
        }
    }

    /**
     * Navigate to SettingsFragment
     * */
    private fun openSettings() {
        view?.let { Navigation.findNavController(it).navigate(R.id.action_camera_to_settings) }
    }

    /**
     * Navigate to PreviewFragment
     * */
    private fun openPreview() {
        if (getMedia().isEmpty()) return
        view?.let { Navigation.findNavController(it).navigate(R.id.action_camera_to_preview) }
    }

    /**
     * Turns on or off the grid on the screen
     * */
    private fun toggleGrid() {
        binding.btnGrid.toggleButton(
            flag = hasGrid,
            rotationAngle = 180f,
            firstIcon = R.drawable.ic_grid_off,
            secondIcon = R.drawable.ic_grid_on,
        ) { flag ->
            hasGrid = flag
            prefs.putBoolean(KEY_GRID, flag)
            binding.groupGridLines.visibility = if (flag) View.VISIBLE else View.GONE
        }
    }

    override fun onPermissionGranted() {
        // Each time apps is coming to foreground the need permission check is being processed
        binding.cameraViewFinder.let { vf ->
            vf.post {
                // Setting current display ID
                displayId = vf.display.displayId
                startCamera()
                lifecycleScope.launch(Dispatchers.IO) {
                    // Do on IO Dispatcher
                    setLastPictureThumbnail()
                }
            }
        }
    }

    private fun setLastPictureThumbnail() = binding.btnGallery.post {
        getMedia().firstOrNull() // check if there are any photos or videos in the app directory
            ?.let { setGalleryThumbnail(it.uri) } // preview the last one
            ?: binding.btnGallery.setImageResource(R.drawable.ic_no_picture) // or the default placeholder
    }

    /**
     * Unbinds all the lifecycles from CameraX, then creates new with new parameters
     * */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        // This is the CameraX PreviewView where the camera will be rendered
        val cameraViewFinder = binding.cameraViewFinder

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
            } catch (e: InterruptedException) {
                Toast.makeText(requireContext(), "Error starting camera", Toast.LENGTH_SHORT).show()
                return@addListener
            } catch (e: ExecutionException) {
                Toast.makeText(requireContext(), "Error starting camera", Toast.LENGTH_SHORT).show()
                return@addListener
            }

            // The display information
            val metrics = DisplayMetrics().also { cameraViewFinder.display.getRealMetrics(it) }
            // The ratio for the output image and preview
            val aspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
            // The display rotation
            val rotation = cameraViewFinder.display.rotation

            val localCameraProvider = cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")

            val screenSize = Size(cameraViewFinder.width, cameraViewFinder.height)

            // The Configuration of camera preview
            preview = Preview.Builder()
                // .setTargetAspectRatio(aspectRatio) // set the camera aspect ratio
                .setTargetResolution(screenSize)
                .setTargetRotation(rotation) // set the camera rotation
                .build()

            // The Configuration of image capture
            imageCapture = Builder()
                .setCaptureMode(CAPTURE_MODE_MAXIMIZE_QUALITY) // setting to have pictures with highest quality possible (may be slow)
                .setFlashMode(FLASH_MODE_OFF) // set capture flash
                .setTargetAspectRatio(aspectRatio) // set the capture aspect ratio
                .setTargetRotation(rotation) // set the capture rotation
                .build()

            val imageAnalyzerBuilder = ImageAnalysis.Builder()

            Camera2Interop.Extender(imageAnalyzerBuilder).setSessionCaptureCallback(
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    result: TotalCaptureResult) {
                        var tv = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) // nanoseconds
                        var sv = result.get(CaptureResult.SENSOR_SENSITIVITY)
                        var av = result.get(CaptureResult.LENS_APERTURE)

                        if ((tv != null) && (sv != null) && (av != null)) {
                            val dtv = ( 1000000000 / tv.toDouble() ).toDouble()
                            val dav = av.toDouble()
                            val dsv = sv.toDouble()
                            val dlv = calculateLv(dav, dtv, dsv) // EV @ 100 ASA
                            val rlv = roundVal(dlv, 10.0)

                            if (exposure != rlv) {
                                fetchNewFilmSpeed(requireContext())
                                fetchNewFilter(requireContext())
                                fetchNewShutterSpeeds(requireContext())
                                fetchNewShutterApertures(requireContext())

                                exposure = rlv

                                var dev = getEvFromLv(dlv + filter_stops, film_speed) // LV @ iso ASA + filter compensation

                                // val result: Pair<Double, Double> = fitEvInRange(dev)
                                val result: Pair<Double, Double> = findAvTvPair(allowed_avs, allowed_tvs, dev)

                                val stv = if (result.second <= -(Double.MAX_VALUE-1.0)) {
                                    "<<<"
                                } else if (result.second >= (Double.MAX_VALUE-1.0)) {
                                    ">>>"
                                } else if (result.second > 1.0) {
                                    val itv = result.second.toInt()
                                    "1/${itv}"
                                } else {
                                    val itv = (1.0 / result.second).toInt()
                                    "${itv}"
                                }

                                val sav = if (result.first <= -(Double.MAX_VALUE-1.0)) {
                                    "<<<"
                                } else if (result.first >= (Double.MAX_VALUE-1.0)) {
                                    ">>>"
                                } else {
                                    val rav = roundVal(result.first.toDouble(), 10.0)
                                    "${rav}"
                                }

                                val rev = roundVal(dev, 10.0)
                                val isv = film_speed.toInt()

                                var flt = ""

                                if (filter_stops != 0.0) {
                                    flt = "${filter_stops}"
                                }

                                binding.textExposure.text =
                                    "${exposure}${flt}\n${rev}\n${isv}\n${stv}\n${sav}"
                            }
                        }
                    }
                }
            )

            // The Configuration of image analyzing
            imageAnalyzer = imageAnalyzerBuilder
                // .setTargetAspectRatio(aspectRatio) // set the analyzer aspect ratio
                .setTargetResolution(screenSize)
                .setTargetRotation(rotation) // set the analyzer rotation
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // in our analysis, we care about the latest image
                .build()
                .also { setFiltersActuator(it) }

            // Unbind the use-cases before rebinding them
            localCameraProvider.unbindAll()
            // Bind all use cases to the camera with lifecycle
            bindToLifecycle(localCameraProvider, cameraViewFinder)
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun rotateBitmap(src: Bitmap, rotationDegrees: Float): Bitmap {
        val matrix = Matrix().apply {
            postRotate(rotationDegrees, src.width / 2f, src.height / 2f)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun yuvToBitmap(image: ImageProxy): Bitmap {
        // Conversion algorithm from https://stackoverflow.com/a/45926852 - Start
        //
        val crop = image.cropRect
        val format = image.format
        val width = crop.width()
        val height = crop.height()
        val planes = image.planes
        val n21Data = ByteArray(width*height*ImageFormat.getBitsPerPixel(format) / 8)
        val rowData = ByteArray(planes[0].rowStride)

        var cnlOffset = 0
        var outStride = 1
        for (i in planes.indices) {
            when(i) {
                0 -> {
                    cnlOffset = 0
                    outStride = 1
                }
                1 -> {
                    cnlOffset = width * height + 1
                    outStride = 2
                }
                2 -> {
                    cnlOffset = width*height
                    outStride = 2
                }
            }

            val buffer = image.planes[i].buffer
            val rowStride = image.planes[i].rowStride
            val pxlStride = image.planes[i].pixelStride

            val shift = if (i == 0) 0 else 1

            val w = width.shr(shift)
            val h = height.shr(shift)

            buffer.position(rowStride*crop.top.shr(shift)+pxlStride*crop.left.shr(shift))

            for (row in 0..<h) {
                var length = 0
                if (pxlStride==1 && outStride==1) {
                    length = w
                    buffer.get(n21Data, cnlOffset, length)
                    cnlOffset += length
                } else {
                    length = (w - 1)*pxlStride + 1
                    buffer.get(rowData, 0, length)
                    for (col in 0..<w) {
                        n21Data[cnlOffset] = rowData[col * pxlStride]
                        cnlOffset += outStride
                    }
                }
                if (row < h - 1) {
                    buffer.position(buffer.position() + rowStride - length)
                }
            }
        }
        //
        // Conversion algorithm from https://stackoverflow.com/a/45926852 - END

        val yuvImage = android.graphics.YuvImage(
            n21Data,
            ImageFormat.NV21,
            image.width, image.height,
            null
        )

        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 100, out)
        val yuv = out.toByteArray()

        return BitmapFactory.decodeByteArray(yuv, 0, yuv.size)
    }

    fun applyFilters(src: Bitmap): Bitmap{
        val bmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(0f) // Grayscale
        if (contrast != 1.0f) {
            val scale = contrast
            val translate = (-0.5f * scale + 0.5f) * 255f
            val contrastMatrix = ColorMatrix(
                floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            colorMatrix.postConcat(contrastMatrix)
        }
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return bmp
    }

    fun copyAllExifData(sourceExif: ExifInterface, targetExif: ExifInterface) {
        val tags = arrayOf(
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_BITS_PER_SAMPLE,
            ExifInterface.TAG_BRIGHTNESS_VALUE,
            ExifInterface.TAG_CFA_PATTERN,
            ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_COMPONENTS_CONFIGURATION,
            ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL,
            ExifInterface.TAG_COMPRESSION,
            ExifInterface.TAG_CONTRAST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_CUSTOM_RENDERED,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DEFAULT_CROP_SIZE,
            ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
            ExifInterface.TAG_DNG_VERSION,
            ExifInterface.TAG_EXIF_VERSION,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_EXPOSURE_INDEX,
            ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_FILE_SOURCE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_FLASH_ENERGY,
            ExifInterface.TAG_FLASHPIX_VERSION,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT,
            ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION,
            ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION,
            ExifInterface.TAG_GAIN_CONTROL,
            ExifInterface.TAG_GAMMA,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_AREA_INFORMATION,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_DEST_BEARING,
            ExifInterface.TAG_GPS_DEST_BEARING_REF,
            ExifInterface.TAG_GPS_DEST_DISTANCE,
            ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
            ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LONGITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
            ExifInterface.TAG_GPS_DIFFERENTIAL,
            ExifInterface.TAG_GPS_DOP,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_MAP_DATUM,
            ExifInterface.TAG_GPS_MEASURE_MODE,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_SATELLITES,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_STATUS,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_TRACK,
            ExifInterface.TAG_GPS_TRACK_REF,
            ExifInterface.TAG_GPS_VERSION_ID,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_IMAGE_UNIQUE_ID,
            ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_INTEROPERABILITY_INDEX,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT,
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
            ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MAKER_NOTE,
            ExifInterface.TAG_MAX_APERTURE_VALUE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_NEW_SUBFILE_TYPE,
            ExifInterface.TAG_OECF,
            ExifInterface.TAG_ORF_ASPECT_FRAME,
            ExifInterface.TAG_ORF_PREVIEW_IMAGE_LENGTH,
            ExifInterface.TAG_ORF_PREVIEW_IMAGE_START,
            ExifInterface.TAG_ORF_THUMBNAIL_IMAGE,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION,
            ExifInterface.TAG_PIXEL_X_DIMENSION,
            ExifInterface.TAG_PIXEL_Y_DIMENSION,
            ExifInterface.TAG_PLANAR_CONFIGURATION,
            ExifInterface.TAG_PRIMARY_CHROMATICITIES,
            ExifInterface.TAG_REFERENCE_BLACK_WHITE,
            ExifInterface.TAG_RELATED_SOUND_FILE,
            ExifInterface.TAG_RESOLUTION_UNIT,
            ExifInterface.TAG_ROWS_PER_STRIP,
            ExifInterface.TAG_RW2_ISO,
            ExifInterface.TAG_RW2_JPG_FROM_RAW,
            ExifInterface.TAG_SAMPLES_PER_PIXEL,
            ExifInterface.TAG_SATURATION,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_SCENE_TYPE,
            ExifInterface.TAG_SENSING_METHOD,
            ExifInterface.TAG_SHARPNESS,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_SPATIAL_FREQUENCY_RESPONSE,
            ExifInterface.TAG_SPECTRAL_SENSITIVITY,
            ExifInterface.TAG_STRIP_BYTE_COUNTS,
            ExifInterface.TAG_STRIP_OFFSETS,
            ExifInterface.TAG_SUBFILE_TYPE,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH,
            ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
            ExifInterface.TAG_TRANSFER_FUNCTION,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_WHITE_POINT,
            ExifInterface.TAG_X_RESOLUTION,
            ExifInterface.TAG_Y_CB_CR_COEFFICIENTS,
            ExifInterface.TAG_Y_CB_CR_POSITIONING,
            ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING,
            ExifInterface.TAG_Y_RESOLUTION
        )
        for (tag in tags) {
            val value = sourceExif.getAttribute(tag)
            if (value != null) {
                Log.d(TAG, "${tag}: ${value}")
                targetExif.setAttribute(tag, value)
            }
        }

        val icv = (contrast * 10.0f).roundToInt()

        targetExif.setAttribute(ExifInterface.TAG_CONTRAST, icv.toString())

        targetExif.saveAttributes()
    }

    private fun processAndSaveImageWithExif(
        contentResolver: ContentResolver,
        imageUri: Uri,
    ) {
        // 1. Decode bitmap from Uri
        val inputStream = contentResolver.openInputStream(imageUri) ?: return
        val filename = System.currentTimeMillis()
        val tempFile = createTemporaryFile("exif_temp_image_${filename}", ".jpg")

        if (tempFile == null) {
            Log.e(TAG, "Failed to create temporary file for image capture")
            return
        }

        FileOutputStream(tempFile).use { out ->
            inputStream.copyTo(out)
        }
        inputStream.close()

        val originalExif = ExifInterface(tempFile.absolutePath)

        val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath)

        val filtered = applyFilters(bitmap)

        val outputStream = contentResolver.openOutputStream(imageUri) ?: return
        filtered.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
        outputStream.close()

        val newExif = ExifInterface(contentResolver.openFileDescriptor(imageUri, "rw")!!.fileDescriptor)

        copyAllExifData(originalExif, newExif)

        tempFile.delete()
    }

    private fun setFiltersActuator(imageAnalysis: ImageAnalysis) {
        // Use a worker thread for image analysis to prevent glitches
        val analyzerThread = HandlerThread("LuminosityAnalysis").apply { start() }
        imageAnalysis.setAnalyzer(
            ThreadExecutor(Handler(analyzerThread.looper)),
            { imageProxy ->
                val bitmap = yuvToBitmap(imageProxy)
                val filtered = applyFilters(bitmap)
                val filteredAndRotated = rotateBitmap(filtered,
                    imageProxy.imageInfo.rotationDegrees.toFloat())

                imageProxy.close()
                binding.filterViewFinder.post {
                    binding.filterViewFinder.setImageBitmap(filteredAndRotated)
                }
            })
        }

    private fun bindToLifecycle(localCameraProvider: ProcessCameraProvider, cameraViewFinder: PreviewView) {
        try {
            localCameraProvider.bindToLifecycle(
                viewLifecycleOwner, // current lifecycle owner
                lensFacing, // either front or back facing
                preview, // camera preview use case
                imageCapture, // image capture use case
                imageAnalyzer, // image analyzer use case
            ).run {
                // Init camera exposure control
                cameraInfo.exposureState.run {
                    val lower = exposureCompensationRange.lower
                    val upper = exposureCompensationRange.upper

                    binding.sliderExposure.run {
                        valueFrom = lower.toFloat()
                        valueTo = upper.toFloat()
                        stepSize = 1f
                        value = exposureCompensationIndex.toFloat()

                        addOnChangeListener { _, value, _ ->
                            cameraControl.setExposureCompensationIndex(value.toInt())
                        }
                    }
                }
            }

            // Attach the cameraViewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(cameraViewFinder.surfaceProvider)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind use cases", e)
        }
    }

    /**
     *  Detecting the most suitable aspect ratio for current dimensions
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    private fun createTemporaryFile(prefix: String, suffix: String): File? {
        return try {
            // Create a temporary file in the app's cache directory
            File.createTempFile(prefix, suffix).apply {
                deleteOnExit() // Ensure the file is deleted when the app exits
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating temporary file: ${e.message}", e)
            null
        }
    }

    @Suppress("NON_EXHAUSTIVE_WHEN")
    private fun takePicture() = lifecycleScope.launch(Dispatchers.Main) {
        val localImageCapture = imageCapture ?: throw IllegalStateException("Camera initialization failed.")

        // Setup image capture metadata
        val metadata = Metadata().apply {
            // Mirror image when using the front camera
            isReversedHorizontal = lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA
        }
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val filename = System.currentTimeMillis()

        // Options fot the output image file
        val outputOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${filename}")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, outputDirectory)
            }

            val contentResolver = requireContext().contentResolver

            // Create the output uri
            val contentUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            OutputFileOptions.Builder(contentResolver, contentUri, contentValues)
        } else {
            File(outputDirectory).mkdirs()
            val file = File(outputDirectory, "${filename}.jpg")

            OutputFileOptions.Builder(file)
        }.setMetadata(metadata).build()

        localImageCapture.takePicture(
            outputOptions, // the options needed for the final image
            requireContext().mainExecutor(), // the executor, on which the task will run
            object : OnImageSavedCallback { // the callback, about the result of capture process
                override fun onImageSaved(outputFileResults: OutputFileResults) {
                    // This function is called if capture is successfully completed
                    outputFileResults.savedUri
                        ?.let { uri ->
                            val contentResolver = requireContext().contentResolver
                            processAndSaveImageWithExif(contentResolver, uri)
                            setGalleryThumbnail(uri)
                            Log.d(TAG, "Photo saved in ${uri.path}")
                        }
                        ?: setLastPictureThumbnail()
                }

                override fun onError(exception: ImageCaptureException) {
                    // This function is called if there is an errors during capture process
                    val msg = "Photo capture failed: ${exception.message}"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    Log.e(TAG, msg)
                    exception.printStackTrace()
                }
            }
        )
    }

    private fun setGalleryThumbnail(savedUri: Uri?) = binding.btnGallery.load(savedUri) {
        placeholder(R.drawable.ic_no_picture)
        transformations(CircleCropTransformation())
        listener(object : ImageRequest.Listener {
            override fun onError(request: ImageRequest, result: ErrorResult) {
                super.onError(request, result)
                binding.btnGallery.load(savedUri) {
                    placeholder(R.drawable.ic_no_picture)
                    transformations(CircleCropTransformation())
//                    fetcher(VideoFrameUriFetcher(requireContext()))
                }
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onBackPressed() {
        requireActivity().finish()
    }

    companion object {
        public const val TAG = "CameraXDemo"

        const val KEY_GRID = "sPrefGridCamera"

        private const val RATIO_4_3_VALUE = 4.0 / 3.0 // aspect ratio 4x3
        private const val RATIO_16_9_VALUE = 16.0 / 9.0 // aspect ratio 16x9

        private var contrast: Float = 1.0f

        private var exposure: Double = 0.0

        private var film_speed: Double = 400.0

        private var filter_stops: Double = 0.0

        private var filter_color: String = "#000000"

        private var allowed_avs = mutableListOf<Double>(3.5, 4.0, 5.6, 8.0, 11.0, 16.0, 22.0)

        private var allowed_tvs = mutableListOf<Double>(500.0, 250.0, 125.0, 60.0, 30.0, 15.0, 8.0, 4.0, 2.0, 1.0)

        private var shutter_allowed_tv: Set<String> = mutableSetOf<String>()

        private var shutter_allowed_av: Set<String> = mutableSetOf<String>()

        public fun getFilmSpeed() = film_speed

        public fun invalidateExposure() {
            exposure = 0.0
        }

        private fun fetchNewFilmSpeed(context: Context) {
            val newFilmSpeed = PreferenceManager.getDefaultSharedPreferences(context)
                                                .getString("film_speed",
                                                           context.getString(R.string.default_film_speed_value))!!
                                                .toDouble()

            if (newFilmSpeed != film_speed) {
                film_speed = newFilmSpeed
            }
        }

        public fun fetchNewFilter(context: Context) {
            val tokens = PreferenceManager.getDefaultSharedPreferences(context)
                                          .getString("filter",
                                                     context.getString(R.string.default_filter_value))!!
                                          .split(":").toTypedArray()

            if (tokens.size == 2)
            {
                filter_color = tokens[0]
                val newFilterStops = tokens[1].toDouble()

                if (newFilterStops != filter_stops) {
                    filter_stops = roundVal(newFilterStops, 10.0)
                }
            }
        }

        private fun fetchNewShutterSpeeds(context: Context) {
            val newVal = PreferenceManager.getDefaultSharedPreferences(context)
                .getStringSet("shutter_allowed_tv",
                              HashSet<String>());

            if (newVal != null && newVal != shutter_allowed_tv) {
                shutter_allowed_tv = newVal

                var a: Array<String> = shutter_allowed_tv.toTypedArray<String>()

                allowed_tvs.clear()

                for (tv in a) {
                    allowed_tvs.add(tv.toDouble())
                }

                allowed_tvs.sortDescending()
            }
        }

        private fun fetchNewShutterApertures(context: Context) {
            val newVal = PreferenceManager.getDefaultSharedPreferences(context)
                .getStringSet("shutter_allowed_av",
                    HashSet<String>());

            if (newVal != null && newVal != shutter_allowed_av) {
                shutter_allowed_av = newVal

                var a: Array<String> = shutter_allowed_av.toTypedArray<String>()

                allowed_avs.clear()

                for (av in a) {
                    allowed_avs.add(av.toDouble())
                }

                allowed_avs.sort()
            }
        }

        public fun showSimpleDialog(context: Context, message: String) {
            AlertDialog.Builder(context, R.style.AppTheme_Dialog)
                       .setMessage(message)
                       .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                       .show()
        }

        public fun showTimedDialog(context: Context, message: String, durationMs: Long = 2000) {
            val dialog = AlertDialog.Builder(context, R.style.AppTheme_Dialog)
                                    .setMessage(message)
                                    .setCancelable(false)
                                    .create()
            dialog.show()
            Handler(Looper.getMainLooper()).postDelayed({
                dialog.dismiss()
            }, durationMs)
        }

        private fun findAvTvPair(
            allowedAvs: List<Double>,
            allowedTvs: List<Double>,
            targetEv  : Double,
        ): Pair<Double, Double> {
            var closestPair: Pair<Double, Double> = Pair(Double.MAX_VALUE, Double.MIN_VALUE)
            var minDiff = Double.MAX_VALUE
            for (fitAv in allowedAvs) {
                for (fitTv in allowedTvs) {
                    val ev = calculateEv(fitAv, fitTv)
                    val diff = kotlin.math.abs(ev - targetEv)
                    if (diff < minDiff) {
                        minDiff = diff
                        closestPair = Pair(fitAv, fitTv)
                    }
                }
            }

            if (minDiff > 1.0) {
                //
                // The best fit is more than a stop far from the scene EV
                //
                if (closestPair.first == allowedAvs.first() && closestPair.second == allowedTvs.last()) {
                    //
                    // The best fit is obtained with the widest aperture and longest exposure time
                    // we are in the range of some seconds of exposure, i.e. in bulb mode
                    //
                    // Let's calculate how many seconds we need for a best fit exposure
                    //
                    minDiff = Double.MAX_VALUE
                    for (fitAv in allowedAvs) {
                        val tv = 1.0 / roundVal(1.0 / calculateTv(fitAv, targetEv), 1.0)
                        val ev = calculateEv(fitAv, tv)
                        val diff = kotlin.math.abs(ev - targetEv)
                        val itv = 1.0 / tv
                        Log.d(TAG, "    EV Diff: ${minDiff} <-> ${diff} - AV: ${fitAv} - TV: ${itv}")
                        if (diff < minDiff) {
                            minDiff = diff
                            closestPair = Pair(fitAv, tv)
                        }
                    }
                }
                else
                if (closestPair.first == allowedAvs.last() && closestPair.second == allowedTvs.first()) {
                    closestPair = Pair(Double.MIN_VALUE, Double.MIN_VALUE)
                }
            }

            Log.d(TAG, "Min EV Diff: ${minDiff} -> ${closestPair} - AV[${allowedAvs.first()}..${allowedAvs.last()}] - TV[${allowedTvs.first()}..${allowedTvs.last()}]")

            return closestPair
        }

        public fun fitEvInRange(ev: Double): Pair<Double, Double> {
            // val allowedAvs = listOf<Double>(/*1.4, 2.8,*/ 3.5, 4.0, 5.6, 8.0, 11.0, 16.0, 22.0)
            // val allowedTvs = listOf<Double>(/*1000.0,*/ 500.0, 250.0, 125.0, 60.0, 30.0, 15.0, 8.0, 4.0, 2.0, 1.0, 0.5, 0.25)
            return findAvTvPair(allowed_avs, allowed_tvs, ev)
        }

        private fun convertAvTv(av1: Double, tv1: Double, iso1: Double, iso2: Double): Pair<Double, Double> {
            val delta = ln(iso2 / iso1) / ln(2.0)
            val av2 = av1
            val tv2 = 2.0.pow( ( ln(tv1) / ln(2.0) ) + delta )
            return Pair(av2, tv2)
        }

        private fun getLvFromEv(ev: Double, iso: Double): Double {
            return ev - ( ln( iso / 100.0 ) / ln( 2.0 ) )
        }

        public fun getEvFromLv(lv: Double, iso: Double): Double {
            return lv + ( ln( iso / 100.0 ) / ln( 2.0 ) )
        }

        private fun calculateEv(av: Double, tv: Double): Double {
            return ( ln( ( av * av ) * tv ) / ln( 2.0 ) )
        }

        private fun calculateTv(av: Double, ev: Double): Double {
            return (  exp( ev * ln( 2.0 ) ) / ( av * av ) )
        }

        public fun calculateLv(av: Double, tv: Double, sv: Double): Double {
            return getLvFromEv(calculateEv(av,tv), sv)
        }

        public fun roundVal(v: Double, r: Double): Double {
            return ( v * r.toInt() ).roundToInt() / r
        }
    }
}
