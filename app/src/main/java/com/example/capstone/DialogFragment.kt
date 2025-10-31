package com.example.capstone

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.smartwaste.capstone.R

class LoginApprovalDialog : DialogFragment() {

    private var anim: AnimatorSet? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialogView = requireActivity()
            .layoutInflater
            .inflate(R.layout.custom_login_request_dialog, null)

        val logo = dialogView.findViewById<ImageView>(R.id.logoLoading)
        val title = dialogView.findViewById<TextView>(R.id.dialog_title)
        val message = dialogView.findViewById<TextView>(R.id.dialog_message)
        val linkUseEmail = dialogView.findViewById<TextView>(R.id.linkUseEmail)

        title.text = "Waiting for login approval…"
        message.text = "Approve this sign-in on your other device to continue."

        // Pull the email from arguments (set via newInstance)
        val userEmail = requireArguments().getString(ARG_EMAIL).orEmpty()

        linkUseEmail.setOnClickListener {
            // Return the email to the host (Activity/Fragment)
            setFragmentResult(REQUEST_KEY, Bundle().apply {
                putString(RESULT_EMAIL, userEmail)
                putBoolean(RESULT_USE_EMAIL, true)
            })
            dismiss()
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        dialog.setCanceledOnTouchOutside(false) // ignore outside taps
        isCancelable = true                      // back button allowed

        dialog.setOnShowListener { anim = startRotateJumpAnimation(logo) }
        dialog.setOnDismissListener { anim?.cancel() }

        return dialog
    }

    override fun onStart() {
        super.onStart()
        // widen the dialog so text wraps nicely
        dialog?.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.9f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
    }

    private fun startRotateJumpAnimation(logo: ImageView): AnimatorSet {
        logo.visibility = View.VISIBLE

        val rotate = ObjectAnimator.ofFloat(logo, "rotation", 0f, 360f).apply {
            duration = 1200
            interpolator = LinearInterpolator()
        }
        val up = ObjectAnimator.ofFloat(logo, "translationY", 0f, -30f).apply { duration = 250 }
        val down = ObjectAnimator.ofFloat(logo, "translationY", -30f, 0f).apply { duration = 250 }

        return AnimatorSet().apply {
            playSequentially(rotate, up, down)
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationEnd(animation: Animator) {
                    if (logo.isShown) start() // loop safely while shown
                }
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
            })
            start()
        }
    }

    companion object {
        private const val ARG_EMAIL = "arg_email"

        // Fragment Result API keys
        const val REQUEST_KEY = "LoginApprovalDialogRequest"
        const val RESULT_EMAIL = "result_email"
        const val RESULT_USE_EMAIL = "result_use_email"

        fun newInstance(userEmail: String): LoginApprovalDialog {
            return LoginApprovalDialog().apply {
                arguments = Bundle().apply { putString(ARG_EMAIL, userEmail) }
            }
        }
    }
}
