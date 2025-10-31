package com.example.capstone

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.smartwaste.capstone.R
import de.hdodenhof.circleimageview.CircleImageView

class ProfileFragment : Fragment() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var database: DatabaseReference
    private lateinit var storageRoot: StorageReference

    private lateinit var profileImage: CircleImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var emailTextView: TextView
    private lateinit var profileImageTextView: TextView
    private lateinit var nameTextView: TextView
    private lateinit var mobileTextView: TextView
    private lateinit var editProfileButton: Button
    private lateinit var signOutButton: Button

    private val PICK_IMAGE_REQUEST = 1001
    private var rawName: String? = null
    private var rawMobile: String? = null
    private var rawEmail: String? = null


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_profile, container, false)

        // Firebase
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().getReference("users")
        storageRoot = FirebaseStorage.getInstance().reference

        // Google sign-in (kept)
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail().build()
        googleSignInClient = GoogleSignIn.getClient(requireContext(), gso)

        // UI
        profileImage = view.findViewById(R.id.profileImage)
        profileImageTextView = view.findViewById(R.id.editProfileText)
        emailTextView = view.findViewById(R.id.emailTextView)
        nameTextView = view.findViewById(R.id.nameTextView)
        mobileTextView = view.findViewById(R.id.contactTextView)
        editProfileButton = view.findViewById(R.id.editProfileButton)
        signOutButton = view.findViewById(R.id.logoutButton)
        progressBar = view.findViewById(R.id.progressBar)
        val changePasswordButton = view.findViewById<Button>(R.id.changePasswordButton)

        profileImage.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Profile Picture")
                .setItems(arrayOf("Change Profile Picture", "Remove Profile Picture")) { _, which ->
                    if (which == 0) selectProfileImage() else confirmRemoveProfilePicture()
                }.show()
        }

        editProfileButton.setOnClickListener { showEditProfileDialog() }
        signOutButton.setOnClickListener { signOut() }
        changePasswordButton.setOnClickListener { showResetPasswordDialog() }

        loadUserProfile()
        return view
    }

    private fun loadUserProfile() {
        val user = auth.currentUser ?: return
        rawEmail = user.email                 // keep raw
        emailTextView.text = "Email: ${maskEmail(rawEmail ?: "N/A")}"

        database.child(user.uid).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                rawName = snapshot.child("name").getValue(String::class.java) ?: "Not set"
                rawMobile = snapshot.child("mobile").getValue(String::class.java) ?: "Not set"

                nameTextView.text = "Name: $rawName"
                mobileTextView.text = "Mobile: ${maskPhoneNumber(rawMobile ?: "Not set")}"

                val profileImageUrl = snapshot.child("profileImageUrl").getValue(String::class.java)
                if (!profileImageUrl.isNullOrEmpty()) {
                    Glide.with(requireContext()).load(profileImageUrl).into(profileImage)
                    profileImageTextView.text = "Edit Profile Picture"
                } else {
                    profileImage.setImageResource(R.drawable.default_profile)
                    profileImageTextView.text = "Upload Profile Picture"
                }
            }
            override fun onCancelled(error: DatabaseError) { /* toast… */ }
        })
    }

    private fun maskEmail(email: String) = email.takeIf { it.contains("@") }?.let {
        val (local, domain) = it.split("@", limit = 2)
        val maskedLocal = if (local.length > 3) local.take(3) + "****" + local.takeLast(3) else local
        "$maskedLocal@$domain"
    } ?: email

    private fun maskPhoneNumber(p: String) = if (p.length > 4) "*****" + p.takeLast(4) else p

    private fun selectProfileImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            .apply { type = "image/*" }
        startActivityForResult(intent, PICK_IMAGE_REQUEST)
    }

    private fun confirmRemoveProfilePicture() {
        AlertDialog.Builder(requireContext())
            .setTitle("Remove Profile Picture")
            .setMessage("Are you sure you want to remove your profile picture?")
            .setPositiveButton("Yes") { _, _ -> removeProfilePicture() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeProfilePicture() {
        val user = auth.currentUser ?: return
        database.child(user.uid).child("profileImagePath")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    val relPath = (snap.getValue(String::class.java)
                        ?: "profile_pictures/${user.uid}.jpg").removePrefix("/")
                    val fileRef = storageRoot.child(relPath)

                    fileRef.delete()
                        .addOnSuccessListener {
                            database.child(user.uid).updateChildren(
                                mapOf("profileImageUrl" to null, "profileImagePath" to null)
                            ).addOnSuccessListener {
                                if (!isAdded) return@addOnSuccessListener
                                Toast.makeText(requireContext(), "Profile picture removed!", Toast.LENGTH_SHORT).show()
                                profileImage.setImageResource(R.drawable.default_profile)
                            }
                        }
                        .addOnFailureListener { e ->
                            if (!isAdded) return@addOnFailureListener
                            Toast.makeText(requireContext(), "Failed to remove image: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                profileImage.setImageURI(uri)
                uploadProfileImage(uri)
            }
        }
    }

    private fun uploadProfileImage(imageUri: Uri) {
        val user = auth.currentUser ?: return
        progressBar.visibility = View.VISIBLE

        // figure out a safe extension that also passes your Storage rules
        val srcExt = getFileExtension(imageUri)         // e.g. "jpeg", "jpg", "png", "webp"
        val finalExt = when (srcExt) {
            "jpeg" -> "jpg"
            "jpg", "png", "webp" -> srcExt
            else -> "jpg"
        }

        val relPath = "profile_pictures/${user.uid}.$finalExt"
        val fileRef = FirebaseStorage.getInstance().reference.child(relPath)
        val metadata = StorageMetadata.Builder()
            .setContentType("image/$finalExt")
            .build()

        fileRef.putFile(imageUri, metadata)
            .addOnSuccessListener {
                fileRef.downloadUrl.addOnSuccessListener { url ->
                    database.child(user.uid).updateChildren(
                        mapOf("profileImageUrl" to url.toString(), "profileImagePath" to relPath)
                    ).addOnSuccessListener {
                        if (!isAdded) return@addOnSuccessListener
                        Toast.makeText(requireContext(), "Profile Image Updated!", Toast.LENGTH_SHORT).show()
                        loadUserProfile()
                    }.addOnFailureListener { e ->
                        if (!isAdded) return@addOnFailureListener
                        Toast.makeText(requireContext(), "Failed to update database: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .addOnFailureListener { e ->
                if (!isAdded) return@addOnFailureListener
                Toast.makeText(requireContext(), "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            .addOnCompleteListener { progressBar.visibility = View.GONE }
    }

    private fun getFileExtension(uri: Uri): String {
        val mime = requireContext().contentResolver.getType(uri) ?: return "jpg"
        return mime.substringAfterLast("/").lowercase()
    }

    // ---------- EDIT PROFILE ----------
    private fun showEditProfileDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_profile, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.editName)
        val mobileInput = dialogView.findViewById<EditText>(R.id.editMobile)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        // use the cached raw values, not the masked TextViews
        nameInput.setText(if (rawName == "Not set") "" else (rawName ?: ""))
        mobileInput.setText(if (rawMobile == "Not set") "" else (rawMobile ?: ""))

        val dlg = AlertDialog.Builder(requireContext()).setView(dialogView).create()
        dlg.setOnShowListener {
            dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        dlg.show()

        btnSave.setOnClickListener {
            val newName = nameInput.text.toString().trim()
            val newMobile = mobileInput.text.toString().trim()
            updateProfile(newName, newMobile)
            dlg.dismiss()
        }
        btnCancel.setOnClickListener { dlg.dismiss() }
    }



    private fun updateProfile(newName: String, newMobile: String) {
        val user = auth.currentUser ?: return
        val updates = mutableMapOf<String, Any>()
        if (newName.isNotEmpty() && newName != (rawName ?: "")) updates["name"] = newName
        if (newMobile.isNotEmpty() && newMobile != (rawMobile ?: "")) updates["mobile"] = newMobile
        if (updates.isEmpty()) {
            Toast.makeText(requireContext(), "No changes detected", Toast.LENGTH_SHORT).show()
            return
        }
        database.child(user.uid).updateChildren(updates)
            .addOnSuccessListener {
                // Refresh and the cache will be updated in loadUserProfile()
                loadUserProfile()
                Toast.makeText(requireContext(), "Profile Updated!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "Update Failed!", Toast.LENGTH_SHORT).show()
            }
    }


    // ---------- RESET PASSWORD ----------
    private fun showResetPasswordDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_reset_password, null)
        val emailInput = dialogView.findViewById<EditText>(R.id.editEmail)
        val btnReset = dialogView.findViewById<Button>(R.id.btnReset)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val dlg = AlertDialog.Builder(requireContext())
            .setView(dialogView)   // custom view, no title/message here
            .setCancelable(true)
            .create()

        // Make CardView rounded corners actually visible
        dlg.setOnShowListener {
            dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        btnReset.setOnClickListener {
            val email = emailInput.text.toString().trim()
            if (email.isNotEmpty() && android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                sendPasswordResetEmail(email)
                dlg.dismiss()
            } else {
                showErrorDialog("Invalid Email", "Please enter a valid email address.")
            }
        }

        btnCancel.setOnClickListener { dlg.dismiss() }

        dlg.show()
    }


    private fun sendPasswordResetEmail(email: String) {
        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                if (!isAdded) return@addOnCompleteListener
                if (task.isSuccessful) {
                    showSuccessDialog("Password Reset", "A reset link has been sent to your email.")
                } else {
                    showErrorDialog("Error", task.exception?.localizedMessage ?: "Failed to send reset email.")
                }
            }
    }

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title).setMessage(message)
            .setPositiveButton("OK", null).show()
    }

    private fun showSuccessDialog(title: String, message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title).setMessage(message)
            .setPositiveButton("OK", null).show()
    }

    // ---------- SIGN OUT ----------
    private fun signOut() {
        FirebaseAuth.getInstance().signOut()
        startActivity(Intent(requireActivity(), Activity_Login::class.java))
        requireActivity().finishAffinity()
    }

    // ---------- STATUS BAR ----------
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val w = requireActivity().window
            w.statusBarColor = ContextCompat.getColor(requireContext(), R.color.medyowhite)
            WindowInsetsControllerCompat(w, w.decorView).isAppearanceLightStatusBars = true
        }
    }
}
