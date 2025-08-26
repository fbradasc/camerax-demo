package com.fbradasc.exposurefinder.fragments

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import androidx.navigation.Navigation
import com.fbradasc.exposurefinder.R
import com.fbradasc.exposurefinder.adapter.MediaAdapter
import com.fbradasc.exposurefinder.databinding.FragmentPreviewBinding
import com.fbradasc.exposurefinder.fragments.CameraFragment.Companion.TAG
import com.fbradasc.exposurefinder.fragments.CameraFragment.Companion.calculateLv
import com.fbradasc.exposurefinder.fragments.CameraFragment.Companion.fitEvInRange
import com.fbradasc.exposurefinder.fragments.CameraFragment.Companion.getEvFromLv
import com.fbradasc.exposurefinder.utils.*

class PreviewFragment : BaseFragment<FragmentPreviewBinding>(R.layout.fragment_preview) {
    private val mediaAdapter = MediaAdapter(
        onItemClick = { uri ->
            val visibility = if (binding.groupPreviewActions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            binding.groupPreviewActions.visibility = visibility

            if (visibility == View.GONE) try {
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val exif = ExifInterface(inputStream!!);
                val tv = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                val av = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                val sv = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                val cv = exif.getAttribute(ExifInterface.TAG_CONTRAST)

                if ((tv != null) && (sv != null) && (av != null)) {
                    var dcv = 1.0
                    if (cv != null) {
                        dcv = cv.toDouble() / 10.0
                    }
                    val rcv = CameraFragment.roundVal(dcv, 10.0)
                    val dtv = ( 1.0 / tv.toDouble() ).toDouble()
                    val dav = av.toDouble()
                    val dsv = sv.toDouble()
                    val dlv = CameraFragment.calculateLv(dav, dtv, dsv) // EV @ 100 ASA
                    val rlv = CameraFragment.roundVal(dlv, 10.0)

                    var dev = CameraFragment.getEvFromLv(dlv, CameraFragment.getFilmSpeed()) // LV @ film_speed ASA
                    val result: Pair<Double, Double>? = CameraFragment.fitEvInRange(dev)

                    var stv="---"
                    var sav="---"

                    if (result != null) {
                        stv = if (result.second > 1.0) {
                            val itv = result.second.toInt()
                            "1/${itv}"
                        } else {
                            val itv = (1.0 / result.second).toInt()
                            "${itv}"
                        }
                        val rav = CameraFragment.roundVal(result.first.toDouble(), 10.0)
                        sav = "${rav}"
                    }

                    val rev = CameraFragment.roundVal(dev, 10.0)
                    val isv = CameraFragment.getFilmSpeed().toInt()

                    val msg = "LV: ${rlv} -> EV: ${rev} @ ${isv} ASA\n\n" +
                              " - TV: ${stv}\n" +
                              " - AV: ${sav}\n\n" +
                              "Contrast: ${rcv}"

                    CameraFragment.showSimpleDialog(requireContext(), msg)
                }
            } catch (e: Exception) {
              Log.e(CameraFragment.TAG, "Failed to read EXIF data", e)
            }
        },
        onDeleteClick = { isEmpty, uri ->
            if (isEmpty) onBackPressed()

            val resolver = requireContext().applicationContext.contentResolver
            resolver.delete(uri, null, null)
        },
    )
    private var currentPage = 0
    override val binding: FragmentPreviewBinding by lazy { FragmentPreviewBinding.inflate(layoutInflater) }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        showSystemUI()
        adjustInsets()

        // Check for the permissions and show files
        if (allPermissionsGranted()) {
            binding.pagerPhotos.apply {
                adapter = mediaAdapter.apply { submitList(getMedia()) }
                onPageSelected { page -> currentPage = page }
            }
        }

        binding.btnBack.setOnClickListener { onBackPressed() }
        binding.btnShare.setOnClickListener { shareImage() }
        binding.btnDelete.setOnClickListener { deleteImage() }
    }

    /**
     * This methods adds all necessary margins to some views based on window insets and screen orientation
     * */
    private fun adjustInsets() {
        activity?.window?.fitSystemWindows()
        binding.btnBack.onWindowInsets { view, windowInsets ->
            view.topMargin = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).top
        }
        binding.btnShare.onWindowInsets { view, windowInsets ->
            view.bottomMargin = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
        }
    }

    private fun shareImage() {
        mediaAdapter.shareImage(currentPage) { share(it) }
    }

    private fun deleteImage() {
        mediaAdapter.deleteImage(currentPage)
    }

    override fun onBackPressed() {
        view?.let { Navigation.findNavController(it).popBackStack() }
    }

}
