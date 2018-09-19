package com.integer.lucius.camera2.dialog

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.support.v4.app.DialogFragment
import com.integer.lucius.camera2.R
import com.integer.lucius.camera2.REQUEST_CAMERA_PERMISSION

/**
 * @detail
 *
 * @author luciuszhang
 * @date 2018/9/19 15:34
 */
class ConfirmationDialog: DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
            AlertDialog.Builder(activity)
                    .setMessage(R.string.request_permission)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        parentFragment?.requestPermissions(arrayOf(Manifest.permission.CAMERA),
                                REQUEST_CAMERA_PERMISSION)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        parentFragment?.activity?.finish()
                    }
                    .create()
}