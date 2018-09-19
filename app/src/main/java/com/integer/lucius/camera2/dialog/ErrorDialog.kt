package com.integer.lucius.camera2.dialog

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.support.v4.app.DialogFragment

/**
 * @detail
 *
 * @author luciuszhang
 * @date 2018/9/19 15:42
 */
class ErrorDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
            AlertDialog.Builder(activity)
                    .setMessage(arguments?.getString(ARG_MESSAGE)?:"Camera Error")
                    .setPositiveButton(android.R.string.ok) { _, _ -> activity?.finish() }
                    .create()

    companion object {

        @JvmStatic private val ARG_MESSAGE = "message"

        @JvmStatic fun newInstance(message: String): ErrorDialog = ErrorDialog().apply {
            arguments = Bundle().apply { putString(ARG_MESSAGE, message) }
        }
    }

}