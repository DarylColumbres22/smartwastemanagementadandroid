package com.example.capstone

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.smartwaste.capstone.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class CreateUserDialog : DialogFragment() {

    private val functions by lazy(LazyThreadSafetyMode.NONE) { Firebase.functions("us-central1") }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_create_user, null)

        val etEmail   = v.findViewById<EditText>(R.id.etEmail)
        val etName    = v.findViewById<EditText>(R.id.etName)
        val cbAdmin   = v.findViewById<CheckBox>(R.id.cbMakeAdmin)
        val btnAdd    = v.findViewById<View>(R.id.btnCreate)
        val progress  = v.findViewById<ProgressBar>(R.id.progress) // <- spinner in your title row

        fun setLoading(loading: Boolean) {
            progress.visibility = if (loading) View.VISIBLE else View.GONE
            btnAdd.isEnabled = !loading
            etEmail.isEnabled = !loading
            etName.isEnabled = !loading
            cbAdmin.isEnabled = !loading
            // Block dismiss while loading (back press / outside touch)
            dialog?.setCancelable(!loading)
            dialog?.setCanceledOnTouchOutside(!loading)
        }

        fun createUser() {
            val email = etEmail.text.toString().trim()
            val name  = etName.text.toString().trim()
            val makeAdmin = cbAdmin.isChecked

            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(requireContext(), "Enter a valid email", Toast.LENGTH_SHORT).show()
                return
            }

            setLoading(true)

            lifecycleScope.launch {
                try {
                    val res = functions.getHttpsCallable("admin_createUser")
                        .call(hashMapOf("email" to email, "name" to name, "makeAdmin" to makeAdmin))
                        .await().data as Map<*, *>

                    val emailed = res["emailed"] as? Boolean ?: false
                    val link    = res["link"] as? String

                    if (emailed) {
                        Toast.makeText(requireContext(), "Invitation email sent!", Toast.LENGTH_SHORT).show()
                    } else if (!link.isNullOrBlank()) {
                        val cm = requireContext()
                            .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Setup link", link))
                        Toast.makeText(requireContext(), "User created. Link copied to clipboard.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(), "User created.", Toast.LENGTH_SHORT).show()
                    }

                    setFragmentResult("CreateUserDialog", Bundle().apply { putBoolean("created", true) })
                    dismiss()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), e.message ?: "Create failed", Toast.LENGTH_LONG).show()
                } finally {
                    // If not dismissed (e.g., failure), restore UI
                    if (dialog?.isShowing == true) setLoading(false)
                }
            }
        }

        btnAdd.setOnClickListener { createUser() }

        val dlg = AlertDialog.Builder(requireContext())
            .setView(v)
            .create()

        dlg.setOnShowListener {
            dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        return dlg
    }

    companion object {
        fun newInstance() = CreateUserDialog()
    }
}
