package com.example.capstone

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.smartwaste.capstone.R

class EmailOtpDialog : DialogFragment() {

    private var timer: CountDownTimer? = null           // resend timer
    private var expiryTimer: CountDownTimer? = null     // otp expiry timer

    private val RESEND_SECONDS = 30
    private val EXPIRES_SECONDS = 5 * 60               // 10 minutes

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        val v = requireActivity().layoutInflater.inflate(R.layout.custom_otp_verification_dialog, null)

        val verify = v.findViewById<Button>(R.id.verifyButton)
        val resend = v.findViewById<TextView>(R.id.tvResend)

        val title  = v.findViewById<TextView>(R.id.dialogTitle)
        val emailTv = v.findViewById<TextView>(R.id.dialogEmail)
        val expiryTv = v.findViewById<TextView?>(R.id.tvExpiry)

        val email = requireArguments().getString(ARG_EMAIL).orEmpty()

        title.text = "OTP Authentication"
        emailTv.text = "Please check ${maskEmail(email)}"

        val boxes = arrayOf(
            v.findViewById<EditText>(R.id.txtOtp1),
            v.findViewById<EditText>(R.id.txtOtp2),
            v.findViewById<EditText>(R.id.txtOtp3),
            v.findViewById<EditText>(R.id.txtOtp4),
            v.findViewById<EditText>(R.id.txtOtp5),
            v.findViewById<EditText>(R.id.txtOtp6)
        )
        setupOtpBoxes(boxes)

        // Start both timers
        startResendCountdown(resend)
        expiryTv?.let { startOtpExpiryCountdown(it, verify, resend) }

        verify.setOnClickListener {
            val code = boxes.joinToString("") { it.text.toString().trim() }
            if (code.length == 6) {
                setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(
                        RESULT_EMAIL to email,
                        RESULT_OTP to code
                    )
                )
                // dismiss() // let host decide; or uncomment to auto-close
            } else {
                Toast.makeText(requireContext(), "Enter 6 digits", Toast.LENGTH_SHORT).show()
            }
        }

        resend.setOnClickListener {
            if (resend.isClickable) {
                // Ask host to resend and restart timers
                setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(
                        RESULT_EMAIL to email,
                        RESULT_RESEND to true
                    )
                )
                clearOtpBoxes()
                startResendCountdown(resend)
                expiryTv?.let { startOtpExpiryCountdown(it, verify, resend) }
            }
        }

        // Build dialog once (avoid re-creating inside click listener)
        val d = AlertDialog.Builder(requireContext()).setView(v).create()
        d.setCanceledOnTouchOutside(false)
        return d
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onDestroyView() {
        timer?.cancel()
        timer = null
        expiryTimer?.cancel()
        expiryTimer = null
        super.onDestroyView()
    }

    fun clearOtpBoxes() {
        val boxes = arrayOf<EditText>(
            dialog?.findViewById(R.id.txtOtp1)!!,
            dialog?.findViewById(R.id.txtOtp2)!!,
            dialog?.findViewById(R.id.txtOtp3)!!,
            dialog?.findViewById(R.id.txtOtp4)!!,
            dialog?.findViewById(R.id.txtOtp5)!!,
            dialog?.findViewById(R.id.txtOtp6)!!
        )
        boxes.forEach { it.text.clear() }
        boxes.first().requestFocus()
    }

    private fun setupOtpBoxes(boxes: Array<EditText>) {
        boxes.forEachIndexed { i, et ->
            et.filters = arrayOf(InputFilter.LengthFilter(1))
            et.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {}
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (s?.length == 1 && i < boxes.lastIndex) boxes[i + 1].requestFocus()
                }
            })
            et.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    if (et.text.isEmpty() && i > 0) {
                        boxes[i - 1].apply { requestFocus(); setSelection(text.length) }
                    }
                }
                false
            }
        }
        boxes.first().requestFocus()
    }

    private fun startResendCountdown(resend: TextView) {
        resend.isClickable = false
        resend.setTextColor(Color.parseColor("#6E6E6E")) // muted during countdown

        timer?.cancel()
        timer = object : CountDownTimer(RESEND_SECONDS * 1000L, 1000L) {
            override fun onTick(msUntilFinished: Long) {
                val s = (msUntilFinished / 1000).toInt()
                resend.text = "Resend in ${s}s"
            }
            override fun onFinish() {
                resend.text = "Resend"
                resend.isClickable = true
                resend.setTextColor(Color.parseColor("#2962FF"))
            }
        }.start()
    }

    /** Shows “Code expires in mm:ss”, disables Verify when expired, and nudges user to Resend. */
    private fun startOtpExpiryCountdown(expiryTv: TextView, verifyBtn: Button, resend: TextView) {
        verifyBtn.isEnabled = true
        expiryTimer?.cancel()

        expiryTimer = object : CountDownTimer(EXPIRES_SECONDS * 1000L, 1000L) {
            override fun onTick(ms: Long) {
                val totalSec = (ms / 1000).toInt()
                val mm = totalSec / 60
                val ss = totalSec % 60
                expiryTv.text = String.format("Code expires in %02d:%02d", mm, ss)
                expiryTv.setTextColor(Color.parseColor("#6E6E6E"))
            }
            override fun onFinish() {
                expiryTv.text = "Code expired. Please resend."
                expiryTv.setTextColor(Color.parseColor("#D32F2F")) // red
                verifyBtn.isEnabled = false

                // Optional: automatically enable/highlight the resend action
                resend.text = "Resend"
                resend.isClickable = true
                resend.setTextColor(Color.parseColor("#2962FF"))

                // Optional: notify host that it expired (if you want to react)
                // setFragmentResult(REQUEST_KEY, bundleOf(RESULT_EXPIRED to true))
            }
        }.start()
    }

    private fun maskEmail(email: String): String {
        val parts = email.trim().split("@")
        if (parts.size != 2) return email
        val name = parts[0]
        val domain = parts[1]
        val masked = when {
            name.length <= 1 -> "${name}*"
            name.length == 2 -> "${name.first()}*"
            else -> "${name.first()}${"*".repeat(name.length - 2)}${name.last()}"
        }
        return "$masked@$domain"
    }

    companion object {
        private const val ARG_EMAIL = "arg_email"
        const val REQUEST_KEY   = "EmailOtpDialogRequest"
        const val RESULT_EMAIL  = "result_email"
        const val RESULT_OTP    = "result_otp"
        const val RESULT_RESEND = "result_resend"
        // const val RESULT_EXPIRED = "result_expired"

        fun newInstance(email: String) = EmailOtpDialog().apply {
            arguments = Bundle().apply { putString(ARG_EMAIL, email) }
        }
    }
}
