    package com.example.smartwaste

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
    import androidx.appcompat.app.AppCompatActivity
    import androidx.core.content.ContextCompat
    import androidx.fragment.app.Fragment
    import com.bumptech.glide.Glide
    import com.google.android.gms.auth.api.signin.GoogleSignIn
    import com.google.android.gms.auth.api.signin.GoogleSignInClient
    import com.google.android.gms.auth.api.signin.GoogleSignInOptions
    import com.google.firebase.auth.FirebaseAuth
    import com.google.firebase.database.*
    import com.google.firebase.storage.FirebaseStorage
    import com.google.firebase.storage.StorageReference
    import de.hdodenhof.circleimageview.CircleImageView

    class ProfileFragment : Fragment() {

        private lateinit var auth: FirebaseAuth
        private lateinit var googleSignInClient: GoogleSignInClient
        private lateinit var database: DatabaseReference
        private lateinit var storageReference: StorageReference
        private lateinit var profileImage: CircleImageView
        private lateinit var progressBar : ProgressBar
        private lateinit var emailTextView: TextView
        private lateinit var profileImageTextView : TextView
        private lateinit var nameTextView: TextView
        private lateinit var mobileTextView: TextView
        private lateinit var editProfileButton: Button
        private lateinit var signOutButton: Button
        private val PICK_IMAGE_REQUEST = 1001

        override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View? {
            val view = inflater.inflate(R.layout.fragment_profile, container, false)

            // Initialize Firebase
            auth = FirebaseAuth.getInstance()
            database = FirebaseDatabase.getInstance().getReference("users")
            storageReference = FirebaseStorage.getInstance().reference.child("profile_pictures")


            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .build()
            googleSignInClient = GoogleSignIn.getClient(requireContext(), gso)


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
                val options = arrayOf("Change Profile Picture", "Remove Profile Picture")
                val builder = AlertDialog.Builder(requireContext())
                builder.setTitle("Profile Picture")
                    .setItems(options) { dialog, which ->
                        when (which) {
                            0 -> selectProfileImage()
                            1 -> confirmRemoveProfilePicture()
                        }
                    }
                builder.show()
            }

            editProfileButton.setOnClickListener { showEditProfileDialog() }
            signOutButton.setOnClickListener { signOut() }
            changePasswordButton.setOnClickListener { showResetPasswordDialog() }

            loadUserProfile()

            return view
        }
        private fun loadUserProfile() {
            val user = auth.currentUser
            if (user != null) {

                val maskedEmail = maskEmail(user.email ?: "N/A")
                emailTextView.text = "Email: $maskedEmail"

                database.child(user.uid).addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (snapshot.exists()) {
                            val name = snapshot.child("name").getValue(String::class.java) ?: "Not set"
                            val mobile = snapshot.child("mobile").getValue(String::class.java) ?: "Not set"
                            val profileImageUrl = snapshot.child("profileImageUrl").getValue(String::class.java)


                            val maskedMobile = maskPhoneNumber(mobile)
                            nameTextView.text = "Name: $name"
                            mobileTextView.text = "Mobile: $maskedMobile"


                            if (!profileImageUrl.isNullOrEmpty()) {

                                Glide.with(requireContext()).load(profileImageUrl).into(profileImage)
                                profileImageTextView.text = "Edit Profile Picture"
                            } else {

                                profileImage.setImageResource(R.drawable.default_profile)
                                profileImageTextView.text = "Upload Profile Picture"
                            }
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Toast.makeText(requireContext(), "Failed to load profile", Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }


        private fun maskEmail(email: String): String {
            return if (email.contains("@")) {
                val parts = email.split("@")
                val localPart = parts[0]
                val domain = parts[1]

                val maskedLocalPart = if (localPart.length > 3) {
                    localPart.take(3) + "****" + localPart.takeLast(3)
                } else {
                    localPart
                }
                "$maskedLocalPart@$domain"
            } else {
                email
            }
        }


        private fun maskPhoneNumber(phoneNumber: String): String {
            return if (phoneNumber.length > 4) {
                "*****" + phoneNumber.takeLast(4)
            } else {
                phoneNumber
            }
        }


        private fun selectProfileImage() {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            intent.type = "image/*"
            startActivityForResult(intent, PICK_IMAGE_REQUEST)
        }

        private fun confirmRemoveProfilePicture() {
            val builder = AlertDialog.Builder(requireContext())
            builder.setTitle("Remove Profile Picture")
                .setMessage("Are you sure you want to remove your profile picture?")
                .setPositiveButton("Yes") { _, _ ->
                    removeProfilePicture()
                }
                .setNegativeButton("Cancel", null)
            builder.show()
        }

        private fun removeProfilePicture() {
            val user = auth.currentUser
            if (user != null) {
                val fileRef = storageReference.child("profile_pictures/${user.uid}.jpg")

                fileRef.metadata
                    .addOnSuccessListener {

                        fileRef.delete()
                            .addOnSuccessListener {

                                database.child(user.uid).child("profileImageUrl").removeValue()
                                    .addOnSuccessListener {
                                        Toast.makeText(requireContext(), "Profile picture removed!", Toast.LENGTH_SHORT).show()
                                        profileImage.setImageResource(R.drawable.default_profile)
                                    }
                                    .addOnFailureListener { e ->
                                        Toast.makeText(requireContext(), "Failed to update database: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(requireContext(), "Failed to remove image: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                    }
                    .addOnFailureListener {

                        database.child(user.uid).child("profileImageUrl").removeValue()
                            .addOnSuccessListener {
                                Toast.makeText(requireContext(), "Profile picture removed!", Toast.LENGTH_SHORT).show()
                                profileImage.setImageResource(R.drawable.default_profile) // Set default avatar
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(requireContext(), "Failed to update database: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                    }
            }
        }




        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)

            if (requestCode == PICK_IMAGE_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
                val imageUri = data.data
                if (imageUri != null) {
                    profileImage.setImageURI(imageUri)
                    uploadProfileImage(imageUri)
                }
            }
        }

        private fun uploadProfileImage(imageUri: Uri) {
            progressBar.visibility = View.VISIBLE

            val user = auth.currentUser
            if (user != null) {
                val fileExtension = getFileExtension(imageUri)
                val fileRef = storageReference.child("profile_pictures/${user.uid}.$fileExtension")

                Log.d("FirebaseStorage", "Uploading to path: ${fileRef.path}")

                fileRef.putFile(imageUri)
                    .addOnSuccessListener { taskSnapshot ->
                        fileRef.downloadUrl.addOnSuccessListener { uri ->

                            database.child(user.uid).addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(snapshot: DataSnapshot) {
                                    if (snapshot.exists()) {
                                        val existingName = snapshot.child("name").getValue(String::class.java) ?: "None"
                                        val existingMobile = snapshot.child("mobile").getValue(String::class.java) ?: "None"


                                        val updates = mapOf(
                                            "profileImageUrl" to uri.toString(),
                                            "name" to existingName,
                                            "mobile" to existingMobile
                                        )

                                        database.child(user.uid).updateChildren(updates)
                                            .addOnSuccessListener {
                                                Toast.makeText(requireContext(), "Profile Image Updated!", Toast.LENGTH_SHORT).show()
                                                loadUserProfile() // Refresh UI
                                            }
                                            .addOnFailureListener { e ->
                                                Log.e("FirebaseStorage", "Database update failed: ${e.message}")
                                                Toast.makeText(requireContext(), "Failed to update database: ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                    }
                                }

                                override fun onCancelled(error: DatabaseError) {
                                    Toast.makeText(requireContext(), "Failed to retrieve profile data", Toast.LENGTH_SHORT).show()
                                }
                            })
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("FirebaseStorage", "Upload Failed: ${e.message}")
                        Toast.makeText(requireContext(), "Upload Failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    .addOnCompleteListener {
                        progressBar.visibility = View.GONE
                    }
            }
        }


        private fun getFileExtension(uri: Uri): String {
            val contentResolver = requireContext().contentResolver
            val mimeType = contentResolver.getType(uri)
            return mimeType?.substringAfterLast("/") ?: "jpg"
        }

        private fun showEditProfileDialog() {
            val builder = AlertDialog.Builder(requireContext())
            val inflater = layoutInflater
            val dialogView = inflater.inflate(R.layout.dialog_edit_profile, null)
            builder.setView(dialogView)

            val nameInput = dialogView.findViewById<EditText>(R.id.editName)
            val mobileInput = dialogView.findViewById<EditText>(R.id.editMobile)
            val saveButton = dialogView.findViewById<Button>(R.id.saveButton)
            val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)

            val currentName = nameTextView.text.toString().replace("Name: ", "").trim()
            val currentMobile = mobileTextView.text.toString().replace("Mobile: ", "").trim()

            nameInput.setText(if (currentName == "Not set") "" else currentName)
            mobileInput.setText(if (currentMobile == "Not set") "" else currentMobile)

            val dialog = builder.create()
            dialog.show()

            saveButton.setOnClickListener {
                val newName = nameInput.text.toString().trim()
                val newMobile = mobileInput.text.toString().trim()
                if (newName.isNotEmpty() && newMobile.isNotEmpty()) {
                    updateProfile(newName, newMobile)
                    dialog.dismiss()
                } else {
                    Toast.makeText(requireContext(), "Fields cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }

            cancelButton.setOnClickListener {
                dialog.dismiss()
            }
        }


        private fun updateProfile(newName: String, newMobile: String) {
            val user = auth.currentUser
            if (user != null) {
                val userRef = database.child(user.uid)

                // Only update if the values have changed
                val updates = mutableMapOf<String, Any>()
                if (newName != nameTextView.text.toString().replace("Name: ", "")) updates["name"] = newName
                if (newMobile != mobileTextView.text.toString().replace("Mobile: ", "")) updates["mobile"] = newMobile

                if (updates.isNotEmpty()) {
                    userRef.updateChildren(updates)
                        .addOnSuccessListener {
                            Toast.makeText(requireContext(), "Profile Updated!", Toast.LENGTH_SHORT).show()
                            loadUserProfile() // Refresh UI
                        }
                        .addOnFailureListener {
                            Toast.makeText(requireContext(), "Update Failed!", Toast.LENGTH_SHORT).show()
                        }
                } else {
                    Toast.makeText(requireContext(), "No changes detected", Toast.LENGTH_SHORT).show()
                }
            }
        }

        private fun showResetPasswordDialog() {
            val builder = AlertDialog.Builder(requireContext())
            builder.setTitle("Reset Password")
                .setMessage("Enter your email to receive a password reset link.")

            val container = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 10)
            }

            val input = EditText(requireContext()).apply {
                hint = "Email Address"
                inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                background = ContextCompat.getDrawable(context, R.drawable.edittext_bg)
                setPadding(20, 20, 20, 20)
                textSize = 16f
            }

            container.addView(input)
            builder.setView(container)

            builder.setPositiveButton("Reset") { _, _ ->
                val email = input.text.toString().trim()
                if (email.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    sendPasswordResetEmail(email)
                } else {
                    showErrorDialog("Invalid Email", "Please enter a valid email address.")
                }
            }

            builder.setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }

            val dialog = builder.create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.darkGreen))
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            }

            dialog.show()
        }

        private fun showErrorDialog(title: String, message: String) {
            val builder = AlertDialog.Builder(requireContext())
            builder.setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            builder.show()
        }

        private fun showSuccessDialog(title: String, message: String) {
            val builder = AlertDialog.Builder(requireContext())
            builder.setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            builder.show()
        }

        private fun sendPasswordResetEmail(email: String) {
            auth.sendPasswordResetEmail(email)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        showSuccessDialog("Password Reset", "A reset link has been sent to your email. Check your inbox.")
                    } else {
                        showErrorDialog("Error", task.exception?.localizedMessage ?: "Failed to send reset email.")
                    }
                }
        }


        private fun signOut() {
            val auth = FirebaseAuth.getInstance()
            auth.signOut()

            val intent = Intent(requireActivity(), Activity_Login::class.java)
            startActivity(intent)
            requireActivity().finishAffinity()
        }



        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)


            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                requireActivity().window.statusBarColor = ContextCompat.getColor(requireContext(), android.R.color.white)
            }


            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requireActivity().window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }
