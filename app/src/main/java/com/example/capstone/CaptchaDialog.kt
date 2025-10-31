package com.example.capstone

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.smartwaste.capstone.R
import kotlin.random.Random

// ---- Helper: Float range random (fixes "(0.8f..1.2f).random()" compile error) ----
fun ClosedFloatingPointRange<Float>.random(): Float =
    start + Random.nextFloat() * (endInclusive - start)

class CaptchaDialog : DialogFragment() {

    companion object {
        const val REQUEST_KEY = "CaptchaDialogResult"
        const val RESULT = "result"         // "ok" | "fail" | "cancel"
        fun newInstance() = CaptchaDialog()
    }

    private lateinit var answer: String
    private var resultSent = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val v = layoutInflater.inflate(R.layout.dialog_captcha, null)

        val tv = v.findViewById<TextView>(R.id.captchaText)
        val input = v.findViewById<EditText>(R.id.captchaInput)
        val refresh = v.findViewById<ImageView>(R.id.btnRefresh)
        val btnVerify = v.findViewById<MaterialButton>(R.id.btnVerify)
        val btnCancel = v.findViewById<MaterialButton>(R.id.btnCancel)

        fun generate() {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz123456789"
            val answerBuilder = StringBuilder()
            val styled = android.text.SpannableStringBuilder()

            repeat(5) {
                val ch = chars.random().toString()
                answerBuilder.append(ch)

                val start = styled.length
                styled.append(ch)
                val end = styled.length

                // Random color palette
                val colors = listOf(
                    Color.parseColor("#1E88E5"), // blue
                    Color.parseColor("#43A047"), // green
                    Color.parseColor("#E53935"), // red
                    Color.parseColor("#FB8C00"), // orange
                    Color.parseColor("#8E24AA")  // purple
                )
                val color = colors.random()

                // Slightly random text scale (80%..120%)
                val scale = (0.8f..1.2f).random()

                styled.setSpan(ForegroundColorSpan(color), start, end, 0)
                styled.setSpan(RelativeSizeSpan(scale), start, end, 0)
            }

            answer = answerBuilder.toString()

            tv.text = styled
            tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
            tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD) // safe default; replace with custom fonts if desired
            tv.alpha = 0f
            tv.animate().alpha(1f).setDuration(250).start()
            input.text?.clear()
        }

        refresh.setOnClickListener { generate() }
        generate()

        val dlg = AlertDialog.Builder(requireContext())
            .setView(v)   // only custom view; no default POS/NEG buttons
            .create()

        dlg.setOnShowListener {
            // make card corners look clean if your layout has rounded MaterialCardView
            dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        btnVerify.setOnClickListener {
            val user = input.text?.toString()?.trim().orEmpty()
            if (user.equals(answer, ignoreCase = true)) {
                parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT to "ok"))
                resultSent = true
                dismissAllowingStateLoss()
            } else {
                parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT to "fail"))
                // stay open; refresh code so they can try again
                generate()
            }
        }

        btnCancel.setOnClickListener {
            parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT to "cancel"))
            resultSent = true
            dismissAllowingStateLoss()
        }

        // Optional: outside-tap should behave like cancel; onCancel/onDismiss handle result
        dlg.setCanceledOnTouchOutside(true)

        return dlg
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        if (!resultSent) {
            parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT to "cancel"))
            resultSent = true
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!resultSent) {
            parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT to "cancel"))
            resultSent = true
        }
    }
}
